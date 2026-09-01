package com.tricrotism.cryon.common.config

/**
 * A tree of parsed config values addressed by dotted path.
 *
 * The seam exists so the reader above it is the same on every platform. Paper hands plugins a
 * `FileConfiguration`, Velocity and Geyser hand out nothing, and the two proxies had each grown their
 * own dotted-path reader over SnakeYAML as a result.
 */
interface ConfigSource {

    /**
     * @return the value at [path], or null when nothing is set there
     */
    fun raw(path: String): Any?

    /**
     * @return the child key names directly under [path], for sections an operator names themselves
     */
    fun children(path: String): Set<String>

    /**
     * @return the list of maps at [path], for a repeated block an operator writes as YAML rather than
     *   as keys we could declare
     */
    fun maps(path: String): List<Map<String, Any?>>

    // a file path, or a description, for a failure to name
    val origin: String
}
