package com.tricrotism.cryon.paper.api

import com.tricrotism.cryon.common.module.Module
import com.tricrotism.cryon.common.module.ModuleContext
import com.tricrotism.cryon.common.module.ServiceRegistry
import org.bukkit.Server
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
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

    override fun onDisable() {
        listeners.forEach(HandlerList::unregisterAll)
        listeners.clear()
    }
}
