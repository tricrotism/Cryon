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
     * Register `@Command` [handlers] onto Paper's Brigadier. **Call from [onLoad]** — at boot that's
     * the COMMANDS lifecycle window. The commands are gated on [isEnabled], so while this module is
     * disabled they become unavailable (and reappear on re-enable) without being re-registered.
     *
     * **Runtime caveat:** Paper only permits registering a COMMANDS lifecycle handler during the
     * bootstrap/enable window. A module loaded or reloaded at runtime (hot-swap, `/cryon load`,
     * `reload-api`) therefore *cannot* (re)register its command tree — that's caught here so `onLoad`
     * still succeeds and the module enables; its commands refresh on the next server reload/restart.
     */
    protected fun registerCommands(vararg handlers: Any) {
        try {
            (plugin as JavaPlugin).lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
                // This runs inside Paper's lifecycle dispatch, which rethrows fatally. Guard each handler
                // (Throwable — a mislinked command class throws Errors) so one bad command can't crash boot.
                handlers.forEach { handler ->
                    try {
                        AnnotationCommands.register(event.registrar(), handler, ::isEnabled)
                    } catch (t: Throwable) {
                        logger.error(
                            "Failed to register commands from ${handler.javaClass.name} in module $id — skipping",
                            t
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            logger.warn(
                "Module $id loaded at runtime! But its commands won't register until the next server reload ({})",
                t.message
            )
        }
    }

    override fun onDisable() {
        listeners.forEach(HandlerList::unregisterAll)
        listeners.clear()
    }
}
