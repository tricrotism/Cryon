package com.tricrotism.cryon.common.config

/**
 * One config value: where it lives, what type it is, what it falls back to, and what counts as valid.
 *
 * The default lives with the key rather than at the read site. Before this, `database.type` carried
 * its default at every `getString("database.type", "postgresql")`, so a key read from two places
 * could disagree with itself, and a typo in the path produced the default instead of failing.
 *
 * A key with no default is required, and reading one that is unset throws. That is what credentials
 * and connection details want: a missing password should stop a boot naming the key, not silently
 * connect as somebody else.
 *
 * @param expected what this key accepts, phrased to finish "… is not a valid ", used only in failures
 */
class ConfigKey<T : Any> internal constructor(
    val path: String,
    val default: T?,
    private val expected: String,
    private val decode: (Any) -> T?,
    private val validate: ((T) -> String?)?,
) {

    val environmentVariable: String = "CRYON_" + path.uppercase().replace('.', '_').replace('-', '_')

    val required: Boolean
        get() = default == null

    /**
     * Turn [raw] into this key's type.
     *
     * [raw] arrives either as whatever YAML parsed or as the string an environment variable carries,
     * so every decoder accepts both.
     *
     * @throws ConfigException naming the key, where it came from, and what was wrong with it
     */
    internal fun read(raw: Any, origin: String): T {
        val value = decode(raw)
            ?: throw ConfigException("$path in $origin is not a valid $expected: '$raw'")
        val complaint = validate?.invoke(value)

        if (complaint != null) throw ConfigException("$path in $origin is invalid: $complaint")

        return value
    }
}
