package com.tricrotism.cryon.velocity.api

import com.tricrotism.cryon.common.module.*
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger

/**
 * Base class for Velocity-side feature modules, mirroring `PaperModule`. Captures the
 * [VelocityModuleContext] in [onLoad] and exposes the handles a proxy feature needs, plus [listen]
 * which registers a Velocity event listener that is automatically unregistered on disable.
 *
 * Override [onLoad] to publish services (call `super.onLoad(context)` first), [onEnable] to wire
 * listeners and resolve peers, and [onDisable] for extra teardown (call `super.onDisable()`).
 */
abstract class VelocityModule : Module {

    private lateinit var moduleContext: VelocityModuleContext
    private val listeners = ArrayList<Any>()

    protected val context: VelocityModuleContext get() = moduleContext
    protected val proxy: ProxyServer get() = moduleContext.proxy
    protected val services: ServiceRegistry get() = moduleContext.services
    protected val logger: Logger get() = moduleContext.logger

    override fun onLoad(context: ModuleContext) {
        moduleContext = context as VelocityModuleContext
    }

    /** Register a Velocity event listener that is automatically unregistered when this module disables. */
    protected fun listen(listener: Any) {
        proxy.eventManager.register(moduleContext.plugin, listener)
        listeners.add(listener)
    }

    /** Whether this module is currently in the `ENABLED` state, per the [ModuleManager]. */
    protected fun isEnabled(): Boolean =
        services.find(ModuleManager::class)?.state(id) == ModuleState.ENABLED

    override fun onDisable() {
        listeners.forEach { proxy.eventManager.unregisterListener(moduleContext.plugin, it) }
        listeners.clear()
    }
}
