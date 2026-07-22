package com.tricrotism.cryon.paper.api

import com.tricrotism.cryon.common.module.*
import com.tricrotism.cryon.common.server.PlayerHandoff
import com.tricrotism.cryon.paper.api.command.CommandService
import org.bukkit.Server
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Base class for Paper-side feature modules. Captures the [PaperModuleContext] in [onLoad] and
 * exposes the handles a feature needs, plus [listen] which registers a Bukkit listener that is
 * automatically unregistered on disable (no leaked handlers).
 *
 * Override [onLoad] to publish services (call `super.onLoad(context)` first), [onEnable] to wire
 * listeners/tasks and resolve peer services via [services], and [onDisable] for extra teardown
 * (call `super.onDisable()` to keep listener cleanup).
 */
abstract class PaperModule : Module {

    private lateinit var moduleContext: PaperModuleContext
    private val listeners = ArrayList<Listener>()
    private val flushes = ArrayList<AutoCloseable>()

    protected val context: PaperModuleContext get() = moduleContext
    protected val plugin: Plugin get() = moduleContext.plugin
    protected val server: Server get() = moduleContext.server
    protected val services: ServiceRegistry get() = moduleContext.services
    protected val logger: Logger get() = moduleContext.logger

    override fun onLoad(context: ModuleContext) {
        moduleContext = context as PaperModuleContext
    }

    /** Register a Bukkit listener that is automatically unregistered when this module disables. */
    protected fun listen(listener: Listener) {
        server.pluginManager.registerEvents(listener, plugin)
        listeners.add(listener)
    }

    /** Whether this module is currently in the `ENABLED` state, per the [ModuleManager]. */
    protected fun isEnabled(): Boolean =
        services.find(ModuleManager::class)?.state(id) == ModuleState.ENABLED

    /**
     * Register `@Command` [handlers]. **Call from [onLoad].** The handlers are contributed to the
     * core [CommandService], gated on [isEnabled] so while this module is disabled they become
     * unavailable (and reappear on re-enable) without being re-registered.
     *
     * Registration is boot-window-agnostic: at boot the core flushes every contribution through its
     * single COMMANDS lifecycle handler; a module loaded or reloaded at runtime (hot-swap,
     * `/cryon load`, `reload-api`) has its tree spliced straight into the live dispatcher, so its
     * commands appear immediately with no server restart.
     */
    protected fun registerCommands(vararg handlers: Any) {
        val commands = services.find(CommandService::class)
        if (commands == null) {
            logger.error("CommandService unavailable! Commands for module '$id' will not register")
            return
        }
        commands.register(id, ::isEnabled, handlers.toList())
    }

    /**
     * Register `@Command` [handlers] as branches of a **shared** root — see
     * [CommandService.registerBranch]. **Call from [onLoad]**, like [registerCommands].
     *
     * Use this, not [registerCommands], when the root literal is a namespace several modules live
     * under (`/int <module> …`). [registerCommands] would have this module take sole title to the
     * root and evict every other contributor.
     */
    protected fun registerBranchCommands(vararg handlers: Any) {
        val commands = services.find(CommandService::class)
        if (commands == null) {
            logger.error("CommandService unavailable! Branch commands for module '$id' will not register")
            return
        }
        commands.registerBranch(id, ::isEnabled, handlers.toList())
    }

    /**
     * Register how this module writes one player's state down, so the core can flush it before the
     * player is handed to another instance — see [PlayerHandoff] for why saving on quit is too late.
     * Automatically unregistered on disable.
     *
     * [flush] runs off the main thread, must not touch the Bukkit API, and must be safe to call while
     * the player is still online. Register from [onEnable].
     */
    protected fun onFlush(name: String, flush: (UUID) -> CompletableFuture<Void>) {
        val handoff = services.find(PlayerHandoff::class)
        if (handoff == null) {
            logger.error("PlayerHandoff unavailable! '$name' for module $id will never flush")
            return
        }
        flushes += handoff.onFlush("$id/$name", flush) // scoped, so two modules may both flush "balances"
    }

    override fun onDisable() {
        listeners.forEach(HandlerList::unregisterAll)
        listeners.clear()
        flushes.forEach { it.close() }
        flushes.clear()
    }
}
