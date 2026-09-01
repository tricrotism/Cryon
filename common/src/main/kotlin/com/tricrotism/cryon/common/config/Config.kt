package com.tricrotism.cryon.common.config

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The typed read of a [ConfigSource], with the environment layered over it.
 *
 * Resolution is environment, then file, then the key's default, and a required key that reaches the
 * end of that throws rather than being invented. Environment first is what makes a container
 * deployment work without a config file per pod, and it is the rule `NodeIdentity` and the
 * remote-module credentials already followed by hand.
 *
 * Reloading swaps the source and tells whoever asked to be told. Nothing is re-read behind a
 * consumer's back: a service that can act on a change registers through [onReload] and re-reads what
 * it owns. Values a running process cannot act on, such as a pool that is already built, are simply
 * not re-read by anyone.
 */
class Config(source: ConfigSource, private val environment: (String) -> String? = System::getenv) {

    @Volatile
    private var source: ConfigSource = source
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val origin: String
        get() = source.origin

    /**
     * @return [key]'s value: the environment, else the file, else its default
     * @throws ConfigException when the key is required and set nowhere, or set to something it
     *   refuses
     */
    operator fun <T : Any> get(key: ConfigKey<T>): T =
        find(key) ?: throw ConfigException(
            "${key.path} is required but is not set in ${source.origin} or in ${key.environmentVariable}"
        )

    /**
     * @return as [get], but null instead of throwing when a required key is unset
     */
    fun <T : Any> find(key: ConfigKey<T>): T? {
        environment(key.environmentVariable)?.takeIf { it.isNotEmpty() }?.let { value ->
            return key.read(value, key.environmentVariable)
        }
        source.raw(key.path)?.let { value -> return key.read(value, source.origin) }

        return key.default
    }

    /**
     * @return whether [key] is set anywhere, ignoring its default. Prefer [get]; this is for
     *   migrations
     */
    fun isSet(key: ConfigKey<*>): Boolean =
        environment(key.environmentVariable)?.isNotEmpty() == true || source.raw(key.path) != null

    fun children(path: String): Set<String> = source.children(path)

    fun maps(path: String): List<Map<String, Any?>> = source.maps(path)

    /**
     * Replace the values and tell every [onReload] listener.
     *
     * @return what each failing listener threw, so one bad listener does not stop the rest
     */
    fun reload(replacement: ConfigSource): List<Throwable> {
        source = replacement
        val failures = ArrayList<Throwable>()

        for (listener in listeners) {
            runCatching { listener() }.onFailure { failures += it }
        }

        return failures
    }

    /**
     * Re-read whatever [body] owns whenever the config is replaced.
     *
     * @return a handle a module must close on disable; one left behind holds its classloader open
     */
    fun onReload(body: () -> Unit): AutoCloseable {
        listeners += body

        return AutoCloseable { listeners -= body }
    }
}
