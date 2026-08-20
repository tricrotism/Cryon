package com.tricrotism.cryon.velocity.api

import com.tricrotism.cryon.common.module.*
import com.tricrotism.cryon.velocity.api.bedrock.BedrockService
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import org.slf4j.Logger
import java.io.File

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
    private val tasks = ArrayList<ScheduledTask>()
    private val closeables = ArrayList<AutoCloseable>()

    protected val context: VelocityModuleContext get() = moduleContext
    protected val proxy: ProxyServer get() = moduleContext.proxy
    protected val services: ServiceRegistry get() = moduleContext.services
    protected val logger: Logger get() = moduleContext.logger

    /**
     * Bedrock-client identity. Always present, with no Floodgate every player reports as Java, so a
     * feature can ask without branching on whether Geyser is installed.
     */
    protected val bedrock: BedrockService get() = services.get<BedrockService>()

    /**
     * This module's own directory, `plugins/cryon/data/<id>/`, created on first use. The proxy twin
     * of `PaperModule.dataFolder`, and there for the same reason: the loader's own directory is not
     * a namespace, and two modules writing into it share whatever names they both picked.
     */
    protected val dataFolder: File by lazy {
        moduleContext.dataDirectory.resolve("data").resolve(id).toFile().apply { mkdirs() }
    }

    /** Resolve a required peer service: sugar for `services.get<T>()`. */
    protected inline fun <reified T : Any> service(): T = services.get()

    /** Resolve an optional peer service, or null: sugar for `services.find<T>()`. */
    protected inline fun <reified T : Any> serviceOrNull(): T? = services.find()

    override fun onLoad(context: ModuleContext) {
        moduleContext = context as VelocityModuleContext
    }

    /** Register a Velocity event listener that is automatically unregistered when this module disables. */
    protected fun listen(listener: Any) {
        proxy.eventManager.register(moduleContext.plugin, listener)
        listeners.add(listener)
    }

    /**
     * Record a scheduled task so it is cancelled when this module disables.
     *
     * Proxy tasks are registered against the **core** plugin, not your jar, so nothing cancels them
     * for you: one left running keeps firing into a closed classloader for the proxy's uptime.
     *
     * ```
     * track(proxy.scheduler.buildTask(context.plugin) { … }.repeat(30, SECONDS).schedule())
     * ```
     */
    protected fun track(task: ScheduledTask): ScheduledTask = task.also { tasks += it }

    /**
     * Register a resource closed automatically when this module disables: a `ServerRegistry.onChange`
     * or `MaintenanceService.onChange` handle, or anything else parking one of your lambdas inside an
     * object the core owns. Closed in reverse registration order.
     *
     * **For module-lifetime resources only**: nothing removes an entry before disable, so a per-use
     * object tracked here grows the list for the module's whole life.
     */
    protected fun <T : AutoCloseable> track(closeable: T): T = closeable.also { closeables += it }

    /** Whether this module is currently in the `ENABLED` state, per the [ModuleManager]. */
    protected fun isEnabled(): Boolean =
        services.find<ModuleManager>()?.state(id) == ModuleState.ENABLED

    override fun onDisable() {
        tasks.forEach { runCatching { it.cancel() } }
        tasks.clear()
        listeners.forEach { proxy.eventManager.unregisterListener(moduleContext.plugin, it) }
        listeners.clear()
        closeables.asReversed().forEach { runCatching { it.close() } }
        closeables.clear()
    }
}
