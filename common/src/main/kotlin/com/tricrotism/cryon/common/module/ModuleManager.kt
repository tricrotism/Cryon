package com.tricrotism.cryon.common.module

import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns module lifecycle and tracks each module's [ModuleState]. The loader [register]s every
 * discovered module, then runs [loadAll] (publish services), [enableAll] (consume peers) and
 * [postLoadAll] (peers now live). [disableAll] tears down in reverse order.
 * [enable]/[disable]/[reload] drive a single module at runtime (e.g. from the `/cryon` command),
 * re-enabling reuses the context captured at load, and callers follow it with [postLoad].
 *
 * **Registration is a tree.** A module's [Module.children] are registered as first-class modules of
 * their own, right behind their parent, each with its own state and its own lifecycle calls. The
 * parent/child link then constrains the order: a parent loads and enables first, disables last, and
 * a child cannot enable while its parent is not enabled.
 *
 * **Declared [Dependency]s are checked before a module runs, not discovered while it runs.** A
 * missing hard dependency marks the module `FAILED` without calling into it, and both kinds order
 * the lifecycle so a dependency is enabled before whatever depends on it.
 *
 * **Mutated from one thread only**. Boot, and the `/cryon` command, which funnels onto the global
 * region thread for exactly this reason. Read from any thread: Brigadier runs tab-complete suggesters
 * and `PaperModule.isEnabled` on whatever thread dispatches, which on Folia is the player's own region
 * thread. Hence the storage choices below rather than plain maps.
 */
class ModuleManager(
    private val logger: Logger,
    // How to answer a [Dependency.OnPlugin]. Null (the default) means this manager cannot see the
    // host's plugins, so those dependencies are skipped rather than failed: refusing a module over a
    // question we are unable to ask would be worse than letting it fail on its own terms
    private val plugins: PluginPresence? = null,
) {

    @Volatile
    private var modules: Map<String, Module> = LinkedHashMap()

    private val states = ConcurrentHashMap<String, ModuleState>()
    private val parents = ConcurrentHashMap<String, String>()
    private val declared = ConcurrentHashMap<String, List<Dependency>>()

    // Captured at load, so an enable can check service dependencies against the live registry
    @Volatile
    private var context: ModuleContext? = null

    /**
     * Track a discovered module **and its [Module.children]**, depth-first, so a child always sits
     * behind its parent in every ordering derived from this map. Returns every id registered, empty
     * if the root's id was already taken (a clashing child is skipped with a warning instead, since
     * the rest of the tree is still perfectly loadable).
     */
    fun register(module: Module): List<String> {
        if (modules.containsKey(module.id)) {
            logger.warn("Duplicate module id '{}', ignoring the duplicate", module.id)
            return emptyList()
        }
        val next = LinkedHashMap(modules)
        val added = ArrayList<String>()
        registerTree(module, null, next, added)
        modules = next
        return added
    }

    private fun registerTree(
        module: Module,
        parent: Module?,
        into: LinkedHashMap<String, Module>,
        added: MutableList<String>,
    ) {
        if (into.containsKey(module.id)) {
            logger.warn("Duplicate module id '{}', ignoring the duplicate", module.id)
            return
        }
        if (parent != null && !module.id.startsWith(parent.id + "/")) {
            logger.warn(
                "Sub-module '{}' of '{}' should be named '{}/<name>' so listings read as a tree",
                module.id, parent.id, parent.id,
            )
        }
        into[module.id] = module
        states[module.id] = ModuleState.REGISTERED
        parent?.let { parents[module.id] = it.id }
        declared[module.id] = read(module.id) { module.dependencies }
        added.add(module.id)
        for (child in read(module.id) { module.children }) registerTree(child, module, into, added)
    }

    /**
     * Read a declaration off a module that has not run yet. These two properties can throw where
     * nothing else can: they are evaluated at registration, so a `Dependency.service<T>()` naming a
     * type whose api jar is absent raises `NoClassDefFoundError` right here. Treat that as declaring
     * nothing and let the module fail on its own terms rather than losing the whole jar.
     */
    private fun <T> read(id: String, block: () -> List<T>): List<T> = try {
        block()
    } catch (e: Throwable) {
        logger.error("Could not read the declarations of module '{}', treating it as declaring none", id, e)
        emptyList()
    }

    /**
     * Drop a module from tracking entirely (the hot-remove path), **descendants included**. Refuses
     * while it or any descendant is still `ENABLED`, so callers disable first. False too if unknown.
     */
    fun unregister(id: String): Boolean {
        if (!modules.containsKey(id)) return false
        val tree = subtree(id)
        if (tree.any { states[it] == ModuleState.ENABLED }) return false
        modules = LinkedHashMap(modules).apply { tree.forEach { remove(it) } }
        for (member in tree) {
            states.remove(member)
            parents.remove(member)
            declared.remove(member)
        }
        return true
    }

    /**
     * Run `preLoad` for every registered module, in the host's load phase. Deliberately leaves
     * [ModuleState] alone: `preLoad` is orthogonal to the `REGISTERED -> LOADED -> ENABLED` machine,
     * and [load] still has to see `REGISTERED` afterwards. A module that throws here is marked
     * `FAILED` so it never reaches `onLoad` in a half-initialized state.
     */
    fun preLoadAll(context: ModuleContext) {
        for (id in ordered()) {
            val module = modules[id] ?: continue
            try {
                module.preLoad(context)
            } catch (e: Throwable) {
                states[id] = ModuleState.FAILED
                logger.error("Failed to pre-load module '{}', left disabled, server continues", id, e)
            }
        }
    }

    /**
     * Run `postLoad` for every module that reached `ENABLED`. Like [preLoadAll] this leaves
     * [ModuleState] alone, but for the opposite reason: `onEnable` already succeeded and the
     * module's listeners are live, so marking it `FAILED` here would contradict what that state
     * means while the module keeps handling events. A thrower is logged loudly and left enabled.
     */
    fun postLoadAll() {
        for (id in ordered()) {
            val module = modules[id] ?: continue
            if (states[id] == ModuleState.ENABLED) postLoadInternal(id, module)
        }
    }

    /**
     * Run `postLoad` for a single already-`ENABLED` module, the hot-add path, kept apart from
     * [enable] so a caller enabling several modules at once (a multi-module jar, `reloadApi`) can
     * enable them all before any of their `postLoad`s runs, which is the whole guarantee the phase
     * makes. False if the module is unknown or not `ENABLED`.
     */
    fun postLoad(id: String): Boolean {
        val module = modules[id] ?: return false
        if (states[id] != ModuleState.ENABLED) return false
        postLoadInternal(id, module)
        return true
    }

    fun loadAll(context: ModuleContext) {
        for (id in ordered()) load(id, context)
    }

    /**
     * Run `onLoad` for a single `REGISTERED` module (the hot-add path). True if it reached `LOADED`.
     */
    fun load(id: String, context: ModuleContext): Boolean {
        this.context = context
        val module = modules[id] ?: return false
        if (states[id] != ModuleState.REGISTERED) return false

        val missing = unmetAtLoad(id)
        if (missing.isNotEmpty()) {
            fail(id, missing)
            return false
        }

        return try {
            module.onLoad(context)
            states[id] = ModuleState.LOADED
            true
        } catch (e: Throwable) {
            // Throwable, not Exception: a stale/mislinked jar throws Errors (NoSuchMethodError,
            // NoClassDefFoundError, ServiceConfigurationError). One bad module must never crash the server.
            states[id] = ModuleState.FAILED
            logger.error("Failed to load module '{}', left disabled, server continues", id, e)
            false
        }
    }

    fun enableAll() {
        for (id in ordered()) {
            val module = modules[id] ?: continue
            if (states[id] == ModuleState.LOADED) enableInternal(id, module)
        }
    }

    fun disableAll() {
        for (id in ordered().asReversed()) {
            val module = modules[id] ?: continue
            if (states[id] == ModuleState.ENABLED) disableInternal(id, module)
        }
    }

    /**
     * Enable a single module at runtime. True if it transitioned to [ModuleState.ENABLED].
     */
    fun enable(id: String): Boolean {
        val module = modules[id] ?: return false
        if (states[id] == ModuleState.ENABLED) return false
        return enableInternal(id, module)
    }

    /**
     * Disable a single module at runtime, **children first**: a sub-module's listeners are wired
     * against a parent that is about to tear itself down, so leaving them live would be the same bug
     * as leaving a listener registered past `onDisable`. True if [id] itself reached `DISABLED`.
     */
    fun disable(id: String): Boolean {
        val module = modules[id] ?: return false
        for (child in subtree(id).drop(1).asReversed()) {
            val sub = modules[child] ?: continue
            if (states[child] == ModuleState.ENABLED) disableInternal(child, sub)
        }
        if (states[id] != ModuleState.ENABLED) return false
        return disableInternal(id, module)
    }

    /**
     * Disable (if enabled) then re-enable a module, `postLoad` included, it is a full cycle. A parent
     * cycles its whole tree: the children come down with it and the ones that were enabled go back
     * up, because a reload that silently left half a feature switched off is a worse surprise than
     * the restart it replaces.
     */
    fun reload(id: String): Boolean {
        if (!modules.containsKey(id)) return false
        val wasEnabled = subtree(id).drop(1).filter { states[it] == ModuleState.ENABLED }
        if (states[id] == ModuleState.ENABLED && !disable(id)) return false
        if (!enable(id)) return false
        postLoad(id)
        for (child in wasEnabled) if (enable(child)) postLoad(child)
        return true
    }

    fun state(id: String): ModuleState? = states[id]
    fun has(id: String): Boolean = modules.containsKey(id)
    fun ids(): List<String> = modules.keys.toList()

    /**
     * The owning module's id, or null for a top-level module.
     */
    fun parentOf(id: String): String? = parents[id]

    /**
     * Ids of [id]'s direct sub-modules, in declaration order.
     */
    fun childrenOf(id: String): List<String> = modules.keys.filter { parents[it] == id }

    /**
     * [ids] arranged into the manager's own lifecycle order, parents and dependencies first. What a
     * hot-load path uses: a jar's modules arrive in `ServiceLoader` order, which knows nothing about
     * what depends on what, and enabling a dependant before its dependency would fail it over a
     * prerequisite that is about to be there.
     */
    fun order(ids: Collection<String>): List<String> {
        val wanted = ids.toSet()
        return ordered().filter { it in wanted }
    }

    /**
     * What [id] declared it needs, as read at registration.
     */
    fun dependenciesOf(id: String): List<Dependency> = declared[id].orEmpty()

    fun states(): Map<String, ModuleState> {
        val snapshot = modules
        val out = LinkedHashMap<String, ModuleState>(snapshot.size)
        for (id in snapshot.keys) states[id]?.let { out[id] = it }
        return out
    }

    /**
     * Every registered id in an order satisfying parent-before-child and dependency-before-dependant,
     * falling back to registration order wherever the graph does not constrain it, so a module set
     * that declares nothing behaves exactly as it did before dependencies existed.
     *
     * A cycle cannot be ordered, so the modules caught in one are appended in registration order and
     * logged. They still load: mutual dependencies are a declaration mistake, not a reason to take
     * two features down.
     */
    private fun ordered(): List<String> {
        val remaining = LinkedHashSet(modules.keys)
        if (remaining.size < 2) return remaining.toList()
        val out = ArrayList<String>(remaining.size)
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { id -> prerequisites(id).none { it in remaining } }
            if (ready.isEmpty()) {
                logger.warn("Dependency cycle between modules {}, loading them in registration order", remaining)
                out.addAll(remaining)
                break
            }
            out.addAll(ready)
            remaining.removeAll(ready.toSet())
        }
        return out
    }

    /**
     * Ids that must come before [id]: its parent, and every module it names as a dependency.
     */
    private fun prerequisites(id: String): List<String> = buildList {
        parents[id]?.let(::add)
        for (dependency in declared[id].orEmpty()) if (dependency is Dependency.OnModule) add(dependency.id)
    }

    /**
     * Every id in [id]'s subtree, parent first, depth-first.
     */
    private fun subtree(id: String): List<String> = buildList {
        add(id)
        for (child in childrenOf(id)) addAll(subtree(child))
    }

    /**
     * Hard dependencies answerable before `onLoad`: another module has to be *registered* (its jar
     * present) and a plugin has to be installed. A service cannot be checked here because providers
     * publish theirs in `onLoad` too, so it waits for [unmetAtEnable].
     */
    private fun unmetAtLoad(id: String): List<Dependency> = declared[id].orEmpty().filter {
        it.hard && when (it) {
            is Dependency.OnModule -> !modules.containsKey(it.id)
            is Dependency.OnPlugin -> plugins?.isPresent(it.name) == false
            is Dependency.OnService -> false
        }
    }

    /**
     * Hard dependencies that only become answerable once every module has loaded.
     */
    private fun unmetAtEnable(id: String): List<Dependency> = declared[id].orEmpty().filter {
        it.hard && when (it) {
            is Dependency.OnModule -> states[it.id] != ModuleState.ENABLED
            is Dependency.OnService -> context?.services?.has(it.className) != true
            is Dependency.OnPlugin -> false
        }
    }

    private fun fail(id: String, missing: List<Dependency>) {
        states[id] = ModuleState.FAILED
        logger.error(
            "Module '{}' needs {}, which {} not available. Left disabled; run /cryon reload {} once it is.",
            id,
            missing.joinToString(" and ") { it.description },
            if (missing.size == 1) "is" else "are",
            id,
        )
    }

    private fun enableInternal(id: String, module: Module): Boolean {
        val parent = parents[id]
        if (parent != null && states[parent] != ModuleState.ENABLED) {
            logger.warn("Not enabling sub-module {}: its parent {} is not enabled", id, parent)
            return false
        }

        val missing = unmetAtEnable(id)
        if (missing.isNotEmpty()) {
            fail(id, missing)
            return false
        }

        return try {
            module.onEnable()
            states[id] = ModuleState.ENABLED
            logger.info("Enabled module {}", id)
            true
        } catch (e: Throwable) {
            runCatching { module.onDisable() }.onFailure {
                logger.error("Module {} also failed to unwind after a failed enable", id, it)
            }
            states[id] = ModuleState.FAILED
            logger.error("Failed to enable module {}! Left it disabled so the server continues.", id, e)
            false
        }
    }

    private fun postLoadInternal(id: String, module: Module) = try {
        module.postLoad()
    } catch (e: Throwable) {
        logger.error("Failed to post-load module '{}', it stays enabled, so expect it half-wired", id, e)
    }

    private fun disableInternal(id: String, module: Module): Boolean = try {
        module.onDisable()
        states[id] = ModuleState.DISABLED
        logger.info("Disabled module {}", id)
        true
    } catch (e: Throwable) {
        // Still mark DISABLED: a module that threw mid-teardown must not block shutdown or a reload.
        states[id] = ModuleState.DISABLED
        logger.error("Error disabling module {}! Now forcing it disabled.", id, e)
        false
    }
}
