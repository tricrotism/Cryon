package com.tricrotism.cryon.geyser.api

import com.tricrotism.cryon.common.module.*
import org.geysermc.event.Event
import org.geysermc.event.subscribe.Subscriber
import org.geysermc.geyser.api.GeyserApi
import org.geysermc.geyser.api.event.EventSubscriber
import org.geysermc.geyser.api.extension.Extension
import org.slf4j.Logger
import java.io.File

/**
 * Base class for Geyser-side feature modules, mirroring `PaperModule` and `VelocityModule`. Captures
 * the [GeyserModuleContext] in [onLoad] and exposes the handles a Geyser feature needs, plus
 * [subscribe] which registers an event-bus subscription that is dropped again on disable.
 *
 * Override [onLoad] to publish services (call `super.onLoad(context)` first), [onEnable] to wire
 * subscriptions and resolve peers, and [onDisable] for extra teardown (call `super.onDisable()`).
 */
abstract class GeyserModule : Module {

    private lateinit var moduleContext: GeyserModuleContext
    private val subscriptions = ArrayList<Subscriber<*>>()
    private val closeables = ArrayList<AutoCloseable>()

    protected val context: GeyserModuleContext get() = moduleContext
    protected val geyser: GeyserApi get() = moduleContext.geyser
    protected val extension: Extension get() = moduleContext.extension
    protected val services: ServiceRegistry get() = moduleContext.services
    protected val logger: Logger get() = moduleContext.logger

    // This module's own directory, `extensions/Cryon/data/<id>/`, created on first use. The Geyser
    // twin of `PaperModule.dataFolder`, and therefore the same reason: the loader's own directory is
    // not a namespace, and two modules writing into it share whatever names they both picked
    protected val dataFolder: File by lazy {
        moduleContext.dataDirectory.resolve("data").resolve(id).toFile().apply { mkdirs() }
    }

    /**
     * Resolve a required peer service: sugar for `services.get<T>()`.
     */
    protected inline fun <reified T : Any> service(): T = services.get()

    /**
     * Resolve an optional peer service, or null: sugar for `services.find<T>()`.
     */
    protected inline fun <reified T : Any> serviceOrNull(): T? = services.find()

    override fun onLoad(context: ModuleContext) {
        moduleContext = context as GeyserModuleContext
    }

    /**
     * Subscribe to a Geyser event; the subscription is dropped when this module disables.
     *
     * Deliberately the only listener seam here. The bus's annotation form, `register(listener)`, has
     * no per-listener counterpart: the only way back out is `unregisterAll()`, which is owned by the
     * extension and would take every other module's listeners with it. A subscription can be
     * withdrawn on its own, so it is what a hot-swappable module can safely hold.
     */
    protected fun <T : Event> subscribe(type: Class<T>, handler: (T) -> Unit): Subscriber<T> {
        val subscriber: Subscriber<T> = extension.eventBus().subscribe(type, handler)
        subscriptions += subscriber
        return subscriber
    }

    /**
     * Register a resource closed automatically when this module disables: a `ServerRegistry.onChange`
     * handle, or anything else parking one of your lambdas inside an object the core owns. Closed in
     * reverse registration order.
     *
     * **For module-lifetime resources only**: nothing removes an entry before disable, so a per-use
     * object tracked here grows the list for the module's whole life.
     */
    protected fun <T : AutoCloseable> track(closeable: T): T = closeable.also { closeables += it }

    /**
     * @return whether this module is currently in the `ENABLED` state, per the [ModuleManager].
     */
    protected fun isEnabled(): Boolean =
        services.find<ModuleManager>()?.state(id) == ModuleState.ENABLED

    override fun onDisable() {
        val bus = moduleContext.extension.eventBus()
        subscriptions.forEach { subscriber ->
            @Suppress("UNCHECKED_CAST")
            runCatching { bus.unsubscribe(subscriber as EventSubscriber<Extension, out Event>) }
        }
        subscriptions.clear()
        closeables.asReversed().forEach { runCatching { it.close() } }
        closeables.clear()
    }
}
