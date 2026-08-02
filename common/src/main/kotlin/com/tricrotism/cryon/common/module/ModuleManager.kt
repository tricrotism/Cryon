package com.tricrotism.cryon.common.module

import org.slf4j.Logger

/**
 * Owns module lifecycle and tracks each module's [ModuleState]. The loader [register]s every
 * discovered module, then runs [loadAll] (publish services), [enableAll] (consume peers) and
 * [postLoadAll] (peers now live). [disableAll] tears down in reverse order.
 * [enable]/[disable]/[reload] drive a single module at runtime (e.g. from the `/cryon` command) —
 * re-enabling reuses the context captured at load, and callers follow it with [postLoad].
 *
 * Main-thread only: boot and the module command both run there; not safe for concurrent mutation.
 */
class ModuleManager(private val logger: Logger) {

    private val modules = LinkedHashMap<String, Module>()
    private val states = LinkedHashMap<String, ModuleState>()

    /** Track a discovered module. False (and ignored) if its id is already registered. */
    fun register(module: Module): Boolean {
        if (modules.putIfAbsent(module.id, module) != null) {
            logger.warn("Duplicate module id '{}', ignoring the duplicate", module.id)
            return false
        }
        states[module.id] = ModuleState.REGISTERED
        return true
    }

    /**
     * Drop a module from tracking entirely (the hot-remove path). It must already be disabled —
     * returns false while it is still `ENABLED`, so callers disable first. False too if unknown.
     */
    fun unregister(id: String): Boolean {
        if (states[id] == ModuleState.ENABLED) return false
        if (modules.remove(id) == null) return false
        states.remove(id)
        return true
    }

    /**
     * Run `preLoad` for every registered module, in the host's load phase. Deliberately leaves
     * [ModuleState] alone: `preLoad` is orthogonal to the `REGISTERED → LOADED → ENABLED` machine,
     * and [load] still has to see `REGISTERED` afterwards. A module that throws here is marked
     * `FAILED` so it never reaches `onLoad` in a half-initialized state.
     */
    fun preLoadAll(context: ModuleContext) {
        for ((id, module) in modules) {
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
        for ((id, module) in modules) {
            if (states[id] == ModuleState.ENABLED) postLoadInternal(id, module)
        }
    }

    /**
     * Run `postLoad` for a single already-`ENABLED` module — the hot-add path. Kept apart from
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
        for (id in modules.keys.toList()) load(id, context)
    }

    /** Run `onLoad` for a single `REGISTERED` module (the hot-add path). True if it reached `LOADED`. */
    fun load(id: String, context: ModuleContext): Boolean {
        val module = modules[id] ?: return false
        if (states[id] != ModuleState.REGISTERED) return false
        return try {
            module.onLoad(context)
            states[id] = ModuleState.LOADED
            true
        } catch (e: Throwable) {
            // Throwable, not Exception: a stale/mislinked jar throws Errors (NoSuchMethodError,
            // NoClassDefFoundError, ServiceConfigurationError). One bad module must never crash the server.
            states[id] = ModuleState.FAILED
            logger.error("Failed to load module '{}',' left disabled, server continues", id, e)
            false
        }
    }

    fun enableAll() {
        for ((id, module) in modules) {
            if (states[id] == ModuleState.LOADED) enableInternal(id, module)
        }
    }

    fun disableAll() {
        for ((id, module) in modules.entries.reversed()) {
            if (states[id] == ModuleState.ENABLED) disableInternal(id, module)
        }
    }

    /** Enable a single module at runtime. True if it transitioned to [ModuleState.ENABLED]. */
    fun enable(id: String): Boolean {
        val module = modules[id] ?: return false
        if (states[id] == ModuleState.ENABLED) return false
        return enableInternal(id, module)
    }

    /** Disable a single module at runtime. True if it transitioned to [ModuleState.DISABLED]. */
    fun disable(id: String): Boolean {
        val module = modules[id] ?: return false
        if (states[id] != ModuleState.ENABLED) return false
        return disableInternal(id, module)
    }

    /** Disable (if enabled) then re-enable a module, `postLoad` included — it is a full cycle. */
    fun reload(id: String): Boolean {
        if (!modules.containsKey(id)) return false
        if (states[id] == ModuleState.ENABLED && !disable(id)) return false
        if (!enable(id)) return false
        postLoad(id)
        return true
    }

    fun state(id: String): ModuleState? = states[id]
    fun has(id: String): Boolean = modules.containsKey(id)
    fun ids(): List<String> = modules.keys.toList()
    fun states(): Map<String, ModuleState> = LinkedHashMap(states)

    private fun enableInternal(id: String, module: Module): Boolean = try {
        module.onEnable()
        states[id] = ModuleState.ENABLED
        logger.info("Enabled module {}", id)
        true
    } catch (e: Throwable) {
        states[id] = ModuleState.FAILED
        logger.error("Failed to enable module {}! Left it disabled so the server continues.", id, e)
        false
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
