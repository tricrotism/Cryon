package com.tricrotism.cryon.velocity.config

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal dotted-path reader over a YAML file, keeping key parity with Paper's `config.yml`. Velocity
 * has no built-in config API exposed to plugins, so this loads the file once via (relocated) SnakeYAML.
 */
class VelocityConfig private constructor(private val root: Map<String, Any?>) {

    fun string(path: String, default: String): String = resolve(path) as? String ?: default
    fun boolean(path: String, default: Boolean): Boolean = resolve(path) as? Boolean ?: default
    fun int(path: String, default: Int): Int = (resolve(path) as? Number)?.toInt() ?: default
    fun long(path: String, default: Long): Long = (resolve(path) as? Number)?.toLong() ?: default

    private fun resolve(path: String): Any? {
        var node: Any? = root
        for (part in path.split('.')) {
            node = (node as? Map<*, *>)?.get(part) ?: return null
        }
        return node
    }

    companion object {
        fun load(file: Path): VelocityConfig {
            if (!Files.exists(file)) return VelocityConfig(emptyMap())
            Files.newInputStream(file).use { input ->
                @Suppress("UNCHECKED_CAST")
                val map = Yaml().load<Any?>(input) as? Map<String, Any?> ?: emptyMap()
                return VelocityConfig(map)
            }
        }
    }
}
