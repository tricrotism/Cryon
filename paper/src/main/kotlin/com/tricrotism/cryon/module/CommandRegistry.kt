package com.tricrotism.cryon.module

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.RootCommandNode
import com.tricrotism.cryon.paper.api.command.AnnotationCommands
import com.tricrotism.cryon.paper.api.command.CommandDescriptor
import com.tricrotism.cryon.paper.api.command.CommandService
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
    private val liveRoots = LinkedHashMap<String, MutableSet<String>>() // owner -> root literal names it owns
    private var booted = false

    override fun register(owner: String, available: () -> Boolean, handlers: List<Any>) {
        handlers.forEach { entries.add(Entry(owner, available, it)) }
        if (!booted) return // the boot flush will pick these up
        var changed = false
        for (handler in handlers) changed = liveRegister(owner, handler, available) || changed
        if (changed) refresh()
    }

    override fun unregister(owner: String) {
        entries.removeIf { it.owner == owner }
        val roots = liveRoots.remove(owner) ?: return
        if (roots.count { removeRoot(it) } > 0) refresh()
    }

    override fun refresh() {
        server.onlinePlayers.forEach { runCatching { it.updateCommands() } }
    }

    override fun describe(owner: String): List<CommandDescriptor> =
        entries.filter { it.owner == owner }.mapNotNull { AnnotationCommands.describe(it.handler) }

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
        booted = true
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
        var removed = false
        for (field in childMapFields) {
            @Suppress("UNCHECKED_CAST")
            val map = field.get(root) as MutableMap<String, *>
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
