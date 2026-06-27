package com.tricrotism.cryon.paper.api

import com.tricrotism.cryon.common.module.*
import com.tricrotism.cryon.paper.api.command.AnnotationCommands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Server
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.Logger

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
     * Register `@Command` [handlers] onto Paper's Brigadier. **Call from [onLoad]** — that's the
     * COMMANDS lifecycle window, and [onLoad] runs exactly once so a `/cryon reload` never
     * double-registers. The commands are gated on [isEnabled], so while this module is disabled they
     * become unavailable (and reappear on re-enable) without being re-registered.
     */
    protected fun registerCommands(vararg handlers: Any) {
        (plugin as JavaPlugin).lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            handlers.forEach { AnnotationCommands.register(event.registrar(), it, ::isEnabled) }
        }
    }

    override fun onDisable() {
        listeners.forEach(HandlerList::unregisterAll)
        listeners.clear()
    }
}
