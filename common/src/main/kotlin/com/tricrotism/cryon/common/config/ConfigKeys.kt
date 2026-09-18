package com.tricrotism.cryon.common.config

/**
 * Factories for [ConfigKey].
 *
 * Declare keys in an object beside the code that reads them, or in [CoreKeys] when more than one
 * platform reads the same one. Pass no default to make a key required.
 *
 * Every factory takes a `doc`, the operator-facing explanation rendered as the comment above the
 * value in the generated `config.yml`. It is the only place that prose lives. See [ConfigKey].
 */
object ConfigKeys {

    fun string(
        path: String,
        default: String? = null,
        doc: String = "",
        validate: ((String) -> String?)? = null,
    ): ConfigKey<String> = ConfigKey(path, default, "value", { it.toString() }, validate, doc)

    /**
     * A string that must not be blank, which is what almost every string key wants: an empty host or
     * database name reaches JDBC as a URL that fails later and less clearly.
     */
    fun nonBlankString(path: String, default: String? = null, doc: String = ""): ConfigKey<String> =
        string(path, default, doc) { if (it.isBlank()) "it must not be blank" else null }

    fun boolean(path: String, default: Boolean? = null, doc: String = ""): ConfigKey<Boolean> =
        ConfigKey(path, default, "true/false value", { raw ->
            when (raw) {
                is Boolean -> raw
                is String -> when (raw.trim().lowercase()) {
                    "true", "yes", "on", "1" -> true
                    "false", "no", "off", "0" -> false
                    else -> null
                }

                else -> null
            }
        }, null, doc)

    fun int(path: String, default: Int? = null, range: IntRange? = null, doc: String = ""): ConfigKey<Int> =
        ConfigKey(path, default, "whole number", { raw ->
            when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
        }, bounds(range?.first, range?.last), doc)

    fun long(path: String, default: Long? = null, range: LongRange? = null, doc: String = ""): ConfigKey<Long> =
        ConfigKey(path, default, "whole number", { raw ->
            when (raw) {
                is Number -> raw.toLong()
                is String -> raw.trim().toLongOrNull()
                else -> null
            }
        }, bounds(range?.first, range?.last), doc)

    fun double(path: String, default: Double? = null, doc: String = ""): ConfigKey<Double> =
        ConfigKey(path, default, "number", { raw ->
            when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> null
            }
        }, null, doc)

    /**
     * A list of strings. An environment variable carries one comma-separated, since an environment has
     * no other shape for a list.
     */
    fun strings(path: String, default: List<String> = emptyList(), doc: String = ""): ConfigKey<List<String>> =
        ConfigKey(path, default, "list", { raw ->
            when (raw) {
                is List<*> -> raw.mapNotNull { it?.toString() }
                is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> null
            }
        }, null, doc)

    /**
     * One of [values], matched case-insensitively by [name].
     *
     * A file naming a value that no longer exists fails at boot listing what is accepted, rather than
     * falling back to a default the operator did not ask for.
     */
    fun <T : Any> choice(
        path: String,
        values: Collection<T>,
        default: T? = null,
        doc: String = "",
        name: (T) -> String,
    ): ConfigKey<T> = ConfigKey(
        path,
        default,
        "choice of " + values.joinToString(", ") { name(it) },
        { raw -> values.firstOrNull { name(it).equals(raw.toString().trim(), ignoreCase = true) } },
        null,
        doc,
    )

    private fun <N : Comparable<N>> bounds(low: N?, high: N?): ((N) -> String?)? {
        if (low == null || high == null) return null

        return { value -> if (value in low..high) null else "it must be between $low and $high" }
    }
}
