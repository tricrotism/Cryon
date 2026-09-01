package com.tricrotism.cryon.module

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import com.mojang.brigadier.tree.RootCommandNode
import com.tricrotism.cryon.paper.api.command.AnnotationCommands
import com.tricrotism.cryon.paper.api.command.CommandDescriptor
import com.tricrotism.cryon.paper.api.command.CommandService
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.Server
import org.bukkit.craftbukkit.CraftServer
import org.slf4j.Logger
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The core's single owner of command registration (see [CommandService]). Every contribution (the
 * core's own commands and each module's) is queued here. At boot the plugin flushes the queue
 * through one `COMMANDS` lifecycle handler ([flushBoot]); once that window has passed, further
 * contributions are spliced straight into the server's live Brigadier dispatcher, so a module loaded
 * at runtime gets its commands with no restart.
 *
 * The live path reaches the dispatcher through the server internals (there is no Paper API to
 * register a command outside the lifecycle event) and removes stale nodes reflectively, since
 * Brigadier exposes no public child removal. Both are best-effort: if the internals shift, runtime
 * (un)registration logs and no-ops, and boot-time registration (the common case) is unaffected.
 *
 * Main-thread only, matching the module loader.
 */
class CommandRegistry(private val server: Server, private val log: Logger) : CommandService {

    private class Entry(val owner: String, val available: () -> Boolean, val handler: Any)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val branches = CopyOnWriteArrayList<Entry>()
    private val liveRoots = LinkedHashMap<String, MutableSet<String>>() // owner -> root literal names it owns
    private val liveBranches = LinkedHashMap<String, MutableSet<Pair<String, String>>>()
    private val sharedRootLabels = LinkedHashMap<String, MutableSet<String>>()
    private var booted = false
    private var refreshScheduled = false

    override fun register(owner: String, available: () -> Boolean, handlers: List<Any>) {
        handlers.forEach { entries.add(Entry(owner, available, it)) }
        if (!booted) return // the boot flush will pick these up
        var changed = false
        for (handler in handlers) changed = liveRegister(owner, handler, available) || changed
        if (changed) refresh()
    }

    override fun registerBranch(owner: String, available: () -> Boolean, handlers: List<Any>) {
        handlers.forEach { branches.add(Entry(owner, available, it)) }
        if (!booted) return // the boot flush will pick these up
        var changed = false
        for (handler in handlers) changed = liveRegisterBranch(owner, handler, available) || changed
        if (changed) refresh()
    }

    override fun unregister(owner: String) {
        entries.removeIf { it.owner == owner }
        branches.removeIf { it.owner == owner }
        var changed = liveRoots.remove(owner)?.count { removeRoot(it) }?.let { it > 0 } ?: false

        val owned = liveBranches.remove(owner)
        val root = dispatcherRoot()
        if (owned != null && root != null) {
            for ((rootName, branchName) in owned) {
                val labels = sharedRootLabels[rootName] ?: linkedSetOf(rootName)
                var emptied = true
                for (label in labels) {
                    val shared = root.getChild(label) ?: continue
                    if (removeChildFrom(shared, branchName)) changed = true
                    if (shared.children.isNotEmpty()) emptied = false
                }
                if (emptied) {
                    labels.forEach { removeRoot(it) }
                    sharedRootLabels.remove(rootName)
                }
            }
        }
        if (changed) refresh()
    }

    /**
     * Resync every online player's command tree, coalescing the bursts a multi-module reload produces
     * into one pass. The snapshot is taken globally but each `updateCommands` is applied on its own
     * player's scheduler: it re-tests every node's access check against that player and writes to their
     * connection, which is their region's work, not ours.
     */
    override fun refresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        Schedulers.global {
            refreshScheduled = false
            server.onlinePlayers.forEach { player ->
                Schedulers.entity(player) { runCatching { player.updateCommands() } }
            }
        }
    }

    override fun describe(owner: String): List<CommandDescriptor> =
        (entries + branches).filter { it.owner == owner }.mapNotNull { AnnotationCommands.describe(it.handler) }

    /**
     * Register everything queued so far onto Paper's registrar, inside the boot COMMANDS window.
     *
     * The registrar's return value is the authoritative label set: it includes the namespaced
     * `cryon:<name>` variants Paper adds on top of the name and aliases we asked for. Recording only
     * what we asked for would leave those nodes in the dispatcher on unload, still dispatching into a
     * closed module classloader, so the answer is taken from Paper rather than re-derived.
     */
    fun flushBoot(registrar: Commands) {
        for (entry in entries) {
            try {
                val built = AnnotationCommands.build(entry.handler, entry.available)
                val labels = registrar.register(built.node, built.description, built.aliases)
                liveRoots.getOrPut(entry.owner) { linkedSetOf() }.addAll(labels)
            } catch (t: Throwable) {
                log.error("Failed to register command {} for {}", entry.handler.javaClass.simpleName, entry.owner, t)
            }
        }
        // Shared roots are merged and registered once per root. Going through the registrar with the
        // same literal once per contributor would lean on Paper's duplicate-name behaviour, which is
        // not part of its API; building the merged node ourselves doesn't.
        for ((rootName, group) in branches.groupBy { rootNameOf(it.handler) }) {
            if (rootName == null) continue
            try {
                val labels = registrar.register(mergeBranches(rootName, group), null, emptyList())
                sharedRootLabels.getOrPut(rootName) { linkedSetOf(rootName) }.addAll(labels)
                group.forEach { trackBranches(it.owner, rootName, it.handler) }
            } catch (t: Throwable) {
                log.error("Failed to register shared command root {} for {}", rootName, group.map { it.owner }, t)
            }
        }
        booted = true
    }

    /**
     * Build one literal node for [rootName] carrying every branch in [group].
     *
     * The root's own access check is the OR of its contributors', so it stays visible while any one
     * of them is usable and disappears when none are, and each branch keeps its own gate underneath.
     */
    private fun mergeBranches(rootName: String, group: List<Entry>): LiteralCommandNode<CommandSourceStack> {
        val built = group.map { AnnotationCommands.build(it.handler, it.available) }
        val requirements = built.map { it.node.requirement }
        val node = Commands.literal(rootName)
            .requires { source -> requirements.any { it.test(source) } }
            .build()
        for (contribution in built) contribution.node.children.forEach(node::addChild)
        return node
    }

    /**
     * Splice one handler's branches into a shared root in the live dispatcher, creating it if absent.
     */
    private fun liveRegisterBranch(owner: String, handler: Any, available: () -> Boolean): Boolean {
        val built = try {
            AnnotationCommands.build(handler, available)
        } catch (t: Throwable) {
            log.error("Failed to build branch command {} for {}", handler.javaClass.simpleName, owner, t)
            return false
        }
        if (built.node.children.isEmpty()) {
            log.warn("Branch command {} for {} has no subcommands, nothing to contribute", built.name, owner)
            return false
        }
        val root = dispatcherRoot() ?: return false

        // The dispatcher is reached as CommandNode<Any> (see dispatcherRoot), so contributions cross
        // the same unchecked boundary the whole-root path already crosses in liveRegister.
        @Suppress("UNCHECKED_CAST")
        val contribution = built.node as CommandNode<Any>

        // A root spliced in live is visible to everyone: unlike the boot path there's no full set of
        // contributors to OR together, and Brigadier still hides the branches a sender can't run.
        val shared = root.getChild(built.name) ?: run {
            @Suppress("UNCHECKED_CAST")
            val created = Commands.literal(built.name).build() as CommandNode<Any>
            root.addChild(created)
            created
        }

        val owned = liveBranches.getOrPut(owner) { linkedSetOf() }
        for (branch in contribution.children) {
            removeChildFrom(shared, branch.name) // drop this owner's previous copy on reload
            shared.addChild(branch)
            owned.add(built.name to branch.name)
        }
        return true
    }

    /**
     * The `@Command` name a branch handler hangs under, or null if the class isn't a command.
     */
    private fun rootNameOf(handler: Any): String? = AnnotationCommands.describe(handler)?.name

    /**
     * Record the (root, branch) pairs a boot-registered branch handler owns, so [unregister] can drop them.
     */
    private fun trackBranches(owner: String, rootName: String, handler: Any) {
        val built = runCatching { AnnotationCommands.build(handler) }.getOrNull() ?: return
        val owned = liveBranches.getOrPut(owner) { linkedSetOf() }
        built.node.children.forEach { owned.add(rootName to it.name) }
    }

    /**
     * Splice one handler's tree into the live dispatcher. Returns true if a node was added.
     */
    private fun liveRegister(owner: String, handler: Any, available: () -> Boolean): Boolean {
        val built = try {
            AnnotationCommands.build(handler, available)
        } catch (t: Throwable) {
            log.error("Failed to build command {} for {}", handler.javaClass.simpleName, owner, t)
            return false
        }
        val root = dispatcherRoot() ?: return false

        @Suppress("UNCHECKED_CAST")
        val node = built.node as CommandNode<Any>
        removeRoot(built.name)
        root.addChild(node)
        val names = liveRoots.getOrPut(owner) { linkedSetOf() }
        names.add(built.name)
        for (alias in built.aliases) {
            removeRoot(alias)
            root.addChild(LiteralArgumentBuilder.literal<Any>(alias).redirect(node).requires(node.requirement).build())
            names.add(alias)
        }
        return true
    }

    private fun removeRoot(name: String): Boolean {
        val root = dispatcherRoot() ?: return false
        return removeChildFrom(root, name)
    }

    /**
     * Drop the child literal [name] from [node]. Brigadier exposes no public child removal.
     */
    private fun removeChildFrom(node: CommandNode<*>, name: String): Boolean {
        var removed = false
        for (field in childMapFields) {
            @Suppress("UNCHECKED_CAST")
            val map = field.get(node) as MutableMap<String, *>
            if (map.remove(name) != null) removed = true
        }
        return removed
    }

    private fun dispatcherRoot(): RootCommandNode<Any>? = try {
        @Suppress("UNCHECKED_CAST")
        (server as CraftServer).server.commands.dispatcher.root as RootCommandNode<Any>
    } catch (t: Throwable) {
        log.error("Cannot reach the command dispatcher. Runtime command (un)registration disabled", t)
        null
    }

    // Brigadier's private child maps (`children`, `literals`, `arguments`). No public removal API
    private val childMapFields: List<Field> by lazy {
        listOf("children", "literals", "arguments").map {
            CommandNode::class.java.getDeclaredField(it).apply { isAccessible = true }
        }
    }
}
