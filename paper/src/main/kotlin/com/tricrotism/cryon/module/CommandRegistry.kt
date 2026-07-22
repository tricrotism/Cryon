package com.tricrotism.cryon.module

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import com.mojang.brigadier.tree.RootCommandNode
import com.tricrotism.cryon.paper.api.command.AnnotationCommands
import com.tricrotism.cryon.paper.api.command.CommandDescriptor
import com.tricrotism.cryon.paper.api.command.CommandService
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.Server
import org.bukkit.craftbukkit.CraftServer
import org.slf4j.Logger
import java.lang.reflect.Field

/**
 * The core's single owner of command registration (see [CommandService]). Every contribution — the
 * core's own commands and each module's — is queued here. At boot the plugin flushes the queue
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

    private val entries = ArrayList<Entry>()
    private val branches = ArrayList<Entry>()
    private val liveRoots = LinkedHashMap<String, MutableSet<String>>() // owner -> root literal names it owns

    // owner -> the (shared root, branch literal) pairs it contributed. Distinct from liveRoots because
    // these roots are co-owned: dropping this owner must take its branches and leave the root standing.
    private val liveBranches = LinkedHashMap<String, MutableSet<Pair<String, String>>>()
    private var booted = false

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
        if (owned != null) {
            val root = dispatcherRoot()
            for ((rootName, branchName) in owned) {
                val shared = root?.getChild(rootName) ?: continue
                if (removeChildFrom(shared, branchName)) changed = true
                // The root exists only to hold branches — once the last one goes, so does it.
                if (shared.children.isEmpty()) removeRoot(rootName)
            }
        }
        if (changed) refresh()
    }

    override fun refresh() {
        server.onlinePlayers.forEach { runCatching { it.updateCommands() } }
    }

    override fun describe(owner: String): List<CommandDescriptor> =
        (entries + branches).filter { it.owner == owner }.mapNotNull { AnnotationCommands.describe(it.handler) }

    /** Register everything queued so far onto Paper's registrar, inside the boot COMMANDS window. */
    fun flushBoot(registrar: Commands) {
        for (entry in entries) {
            try {
                AnnotationCommands.register(registrar, entry.handler, entry.available)
                trackRoots(entry.owner, entry.handler)
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
                registrar.register(mergeBranches(rootName, group), null, emptyList())
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
     * of them is usable and disappears when none are — each branch keeps its own gate underneath.
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

    /** Splice one handler's branches into a shared root in the live dispatcher, creating it if absent. */
    private fun liveRegisterBranch(owner: String, handler: Any, available: () -> Boolean): Boolean {
        val built = try {
            AnnotationCommands.build(handler, available)
        } catch (t: Throwable) {
            log.error("Failed to build branch command {} for {}", handler.javaClass.simpleName, owner, t)
            return false
        }
        if (built.node.children.isEmpty()) {
            log.warn("Branch command {} for {} has no subcommands — nothing to contribute", built.name, owner)
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

    /** The `@Command` name a branch handler hangs under, or null if the class isn't a command. */
    private fun rootNameOf(handler: Any): String? = AnnotationCommands.describe(handler)?.name

    /** Record the (root, branch) pairs a boot-registered branch handler owns, so [unregister] can drop them. */
    private fun trackBranches(owner: String, rootName: String, handler: Any) {
        val built = runCatching { AnnotationCommands.build(handler) }.getOrNull() ?: return
        val owned = liveBranches.getOrPut(owner) { linkedSetOf() }
        built.node.children.forEach { owned.add(rootName to it.name) }
    }

    /** Splice one handler's tree into the live dispatcher. Returns true if a node was added. */
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

    /** Record the root literal names a boot-registered handler owns, so [unregister] can drop them. */
    private fun trackRoots(owner: String, handler: Any) {
        val descriptor = AnnotationCommands.describe(handler) ?: return
        val names = liveRoots.getOrPut(owner) { linkedSetOf() }
        names.add(descriptor.name)
        names.addAll(descriptor.aliases)
    }

    private fun removeRoot(name: String): Boolean {
        val root = dispatcherRoot() ?: return false
        return removeChildFrom(root, name)
    }

    /** Drop the child literal [name] from [node]. Brigadier exposes no public child removal. */
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
        log.error("Cannot reach the command dispatcher — runtime command (un)registration disabled", t)
        null
    }

    /** Brigadier's private child maps (`children`, `literals`, `arguments`) — no public removal API. */
    private val childMapFields: List<Field> by lazy {
        listOf("children", "literals", "arguments").map {
            CommandNode::class.java.getDeclaredField(it).apply { isAccessible = true }
        }
    }
}
