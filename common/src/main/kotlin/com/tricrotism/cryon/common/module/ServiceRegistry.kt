package com.tricrotism.cryon.common.module

import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Cross-module service registry, the intertwine seam. Because feature jars are class-loaded in
 * isolation, modules cannot reference each other's concrete classes. Instead a module publishes an
 * implementation of a *shared API interface* here in [Module.onLoad], and peers resolve it by that
 * interface in [Module.onEnable]. The interface must live in an artifact the core exposes to every
 * feature loader (`:common`, `:paper-api`, or a feature's own published api jar) so both sides see
 * the same [KClass].
 */
class ServiceRegistry(private val logger: Logger) {

    private val services = ConcurrentHashMap<KClass<*>, Any>()

    /**
     * Publish [service] under its API [type]. One implementation per type.
     */
    fun <T : Any> register(type: KClass<T>, service: T) {
        require(services.putIfAbsent(type, service) == null) {
            "Service ${type.simpleName} is already registered"
        }
        logger.info("Registered service {}", type.simpleName)
    }

    /**
     * Drop every service whose implementation was loaded by [loader]. The hot-unload/reload cleanup.
     * A module publishes impls defined in its own jar, so closing that jar's loader means removing its
     * services here too; otherwise a reload (`onLoad` runs again) would hit "already registered", and
     * peers could resolve a dead instance from a closed loader. Returns how many were removed.
     */
    fun unregisterByClassLoader(loader: ClassLoader): Int {
        var removed = 0
        val iterator = services.entries.iterator()
        while (iterator.hasNext()) {
            val (type, impl) = iterator.next()
            if (impl.javaClass.classLoader === loader) {
                iterator.remove()
                removed++
                logger.info("Unregistered service {}", type.simpleName)
            }
        }
        return removed
    }

    /**
     * Drop every service. For plugin disable only: [register] refuses a duplicate type, so a registry
     * left populated makes the next `onEnable` fail on its first `register` call rather than come up.
     */
    fun clear() {
        services.clear()
    }

    /**
     * The service for [type], or throw, a missing required service is a wiring bug.
     */
    fun <T : Any> get(type: KClass<T>): T {
        val service = services[type] ?: error("No service registered for ${type.simpleName}")
        @Suppress("UNCHECKED_CAST")
        return service as T
    }

    /**
     * Whether a service is registered under the API type named [className] (its binary name).
     *
     * By name rather than by type because the only caller is [Dependency.OnService] resolution, and a
     * module declaring an optional dependency on a peer's api jar must be able to name that type
     * without being able to load it.
     */
    fun has(className: String): Boolean = services.keys.any { it.java.name == className }

    /**
     * The service for [type], or null, for optional dependencies.
     */
    fun <T : Any> find(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return services[type] as T?
    }

    /**
     * Always spell out the type argument (`register<Api>(impl)`): letting it infer keys the entry by the concrete class, not the API interface.
     */
    inline fun <reified T : Any> register(service: T) = register(T::class, service)
    inline fun <reified T : Any> get(): T = get(T::class)
    inline fun <reified T : Any> find(): T? = find(T::class)
}
