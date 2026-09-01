package com.tricrotism.cryon.common.config

/**
 * Factories for [ConfigKey].
 *
 * Declare keys in an object beside the code that reads them, or in [CoreKeys] when more than one
 * platform reads the same one. Pass no default to make a key required.
 */
object ConfigKeys {

    fun string(
        path: String,
        default: String? = null,
        validate: ((String) -> String?)? = null,
    ): ConfigKey<String> = ConfigKey(path, default, "value", { it.toString() }, validate)

    /**
     * A string that must not be blank, which is what almost every string key wants: an empty host or
     * database name reaches JDBC as a URL that fails later and less clearly.
     */
    fun nonBlankString(path: String, default: String? = null): ConfigKey<String> =
        string(path, default) { if (it.isBlank()) "it must not be blank" else null }

    fun boolean(path: String, default: Boolean? = null): ConfigKey<Boolean> =
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
        }, null)

    fun int(path: String, default: Int? = null, range: IntRange? = null): ConfigKey<Int> =
        ConfigKey(path, default, "whole number", { raw ->
            when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
        }, bounds(range?.first, range?.last))

    fun long(path: String, default: Long? = null, range: LongRange? = null): ConfigKey<Long> =
        ConfigKey(path, default, "whole number", { raw ->
            when (raw) {
                is Number -> raw.toLong()
                is String -> raw.trim().toLongOrNull()
                else -> null
            }
        }, bounds(range?.first, range?.last))

    fun double(path: String, default: Double? = null): ConfigKey<Double> =
        ConfigKey(path, default, "number", { raw ->
            when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> null
            }
        }, null)

    /**
     * A list of strings. An environment variable carries one comma-separated, since an environment has
     * no other shape for a list.
     */
    fun strings(path: String, default: List<String> = emptyList()): ConfigKey<List<String>> =
        ConfigKey(path, default, "list", { raw ->
            when (raw) {
                is List<*> -> raw.mapNotNull { it?.toString() }
                is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> null
            }
        }, null)

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
        name: (T) -> String,
    ): ConfigKey<T> = ConfigKey(
        path,
        default,
        "choice of " + values.joinToString(", ") { name(it) },
        { raw -> values.firstOrNull { name(it).equals(raw.toString().trim(), ignoreCase = true) } },
        null,
    )

    private fun <N : Comparable<N>> bounds(low: N?, high: N?): ((N) -> String?)? {
        if (low == null || high == null) return null

        return { value -> if (value in low..high) null else "it must be between $low and $high" }
    }
}
