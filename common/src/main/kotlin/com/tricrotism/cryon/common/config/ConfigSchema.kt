package com.tricrotism.cryon.common.config

/**
 * A platform's declared config keys, in the order they should appear in its generated `config.yml`.
 *
 * Extend it and declare keys with the factories here rather than through [ConfigKeys] directly, and
 * each one registers itself as it initializes. A Kotlin object initializes its properties in source
 * order, so [keys] comes back in the order they are written, which is what makes the declaration the
 * layout.
 *
 * **The self-registration is the point.** The alternative, a hand-kept `val all = listOf(...)` beside
 * the declarations, is a second place to add a key and therefore a place to forget one, and the
 * failure is silent in the worst way: the key still works everywhere it is read, it is simply absent
 * from the file, so no operator ever discovers the option exists. That is the exact failure the
 * generated template is meant to end.
 */
abstract class ConfigSchema {

    private val declared = mutableListOf<ConfigKey<*>>()

    /** Every key this schema declares, in declaration order. */
    val keys: List<ConfigKey<*>> get() = declared.toList()

    /** Prose for a whole block, keyed by its path (`"currency"`), rendered above it. */
    protected open val sections: Map<String, String> get() = emptyMap()

    /** Prose for the top of the file, above everything. */
    protected open val header: String get() = ""

    /**
     * Keys to ship commented out, mapped to the sample value to show.
     *
     * For a key whose fallback the code computes rather than declares, so it has no default here but
     * is not required either. Leaving one uncommented would write a value the operator never chose
     * over a fallback that was tracking something else.
     */
    protected open val commented: Map<String, Any> get() = emptyMap()

    fun render(): String = ConfigTemplate.render(keys, sections, header, commented)

    private fun <T : Any> register(key: ConfigKey<T>): ConfigKey<T> {
        declared += key
        return key
    }

    protected fun string(
        path: String,
        default: String? = null,
        doc: String = "",
        validate: ((String) -> String?)? = null,
    ): ConfigKey<String> = register(ConfigKeys.string(path, default, doc, validate))

    protected fun nonBlankString(path: String, default: String? = null, doc: String = ""): ConfigKey<String> =
        register(ConfigKeys.nonBlankString(path, default, doc))

    protected fun boolean(path: String, default: Boolean? = null, doc: String = ""): ConfigKey<Boolean> =
        register(ConfigKeys.boolean(path, default, doc))

    protected fun int(path: String, default: Int? = null, range: IntRange? = null, doc: String = ""): ConfigKey<Int> =
        register(ConfigKeys.int(path, default, range, doc))

    protected fun long(path: String, default: Long? = null, range: LongRange? = null, doc: String = ""): ConfigKey<Long> =
        register(ConfigKeys.long(path, default, range, doc))

    protected fun double(path: String, default: Double? = null, doc: String = ""): ConfigKey<Double> =
        register(ConfigKeys.double(path, default, doc))

    protected fun strings(path: String, default: List<String> = emptyList(), doc: String = ""): ConfigKey<List<String>> =
        register(ConfigKeys.strings(path, default, doc))

    protected fun <T : Any> choice(
        path: String,
        values: Collection<T>,
        default: T? = null,
        doc: String = "",
        name: (T) -> String,
    ): ConfigKey<T> = register(ConfigKeys.choice(path, values, default, doc, name))

    /**
     * Withdraw a key from the declared set: one this schema reads but that is not part of its shipped
     * shape, such as `network.mode` behind `network.expect`.
     *
     * Wraps the factory call (`hidden(string("network.mode"))`) and removes what it just registered,
     * rather than duplicating every factory as a second `hiddenX`. A retired name is deliberately not
     * in the template, since writing one into the file an operator edits invites them to set it, and
     * a legacy alias that is *set* wins over the new key. That is how a renamed option quietly keeps
     * resolving to the old one forever.
     */
    protected fun <T : Any> hidden(key: ConfigKey<T>): ConfigKey<T> {
        declared.remove(key)
        return key
    }
}
