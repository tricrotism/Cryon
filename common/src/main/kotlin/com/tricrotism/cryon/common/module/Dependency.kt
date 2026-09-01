package com.tricrotism.cryon.common.module

/**
 * A prerequisite a [Module] declares up front, so the loader can order the lifecycle and refuse a
 * module whose world is missing *before* running a line of its code.
 *
 * Without this a feature discovers its own prerequisites the hard way: `services.get<ShopService>()`
 * throws from inside `onEnable`, the module is marked `FAILED`, and the console shows a wiring
 * exception rather than the one fact an admin needs, which jar to go and install. Declared instead,
 * the same situation is one log line naming the missing thing.
 *
 * Three targets, because a module can need three different kinds of thing:
 *
 * - [OnModule] names another Cryon module by id. Checked when the module loads, and it also **orders**
 *   the lifecycle: a dependency's `onEnable` runs before its dependant's.
 * - [OnService] names a [ServiceRegistry] type by class name. The truest form, since isolated
 *   classloaders mean modules intertwine through service interfaces and never through each other's
 *   classes, and it does not couple a consumer to whichever repo happens to publish the impl. Checked
 *   before `onEnable`, since a provider publishes in `onLoad`.
 * - [OnPlugin] names a third-party plugin/extension on the host platform (WorldGuard, PlaceholderAPI).
 *   Checked when the module loads, against whatever the platform loader can see.
 *
 * [hard] is the whole difference in behaviour. A missing hard dependency marks the module `FAILED`
 * before its lifecycle starts, and a soft one only participates in ordering, which is what lets a
 * feature integrate with a peer it can live without.
 */
sealed interface Dependency {

    // Whether the module cannot run without this. Soft dependencies only affect ordering
    val hard: Boolean

    // Human-readable form for logs and `/cryon info`
    val description: String

    /**
     * Another Cryon module, by [Module.id].
     */
    data class OnModule(val id: String, override val hard: Boolean) : Dependency {
        override val description: String get() = "module $id"
    }

    /**
     * A [ServiceRegistry] entry, by the binary name of its API type.
     */
    data class OnService(val className: String, override val hard: Boolean) : Dependency {
        override val description: String get() = "service ${className.substringAfterLast('.')}"
    }

    /**
     * A third-party plugin (Paper), plugin (Velocity) or extension (Geyser), by name.
     */
    data class OnPlugin(val name: String, override val hard: Boolean) : Dependency {
        override val description: String get() = "plugin $name"
    }

    companion object {

        /**
         * Depend on another Cryon module by id.
         */
        fun module(id: String, hard: Boolean = true): Dependency = OnModule(id, hard)

        /**
         * Depend on a service by the **name** of its API type, e.g. `"com.example.shop.ShopService"`.
         *
         * The form to use for anything optional. Naming the type as a string means this module can
         * declare the dependency without its api jar being on the classpath at all, where the reified
         * [service] below has to load the class to read its name and throws `NoClassDefFoundError`
         * when it is absent, exactly the case a soft dependency exists to survive.
         */
        fun service(className: String, hard: Boolean = true): Dependency = OnService(className, hard)

        /**
         * Depend on a service by type. Only for a type this module's jar can always load (see the string form).
         */
        inline fun <reified T : Any> service(hard: Boolean = true): Dependency = OnService(T::class.java.name, hard)

        /**
         * Depend on a third-party plugin or Geyser extension by the name the platform registers it under.
         */
        fun plugin(name: String, hard: Boolean = true): Dependency = OnPlugin(name, hard)
    }
}

