package com.tricrotism.cryon.common.config

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * A [ConfigSource] over one YAML document.
 *
 * SnakeYAML rather than each platform's own reader, because all three already have it: Paper declares
 * it in `plugin.yml` `libraries:` for [ConfigMigrator], and the two proxies shade it.
 */
class YamlConfigSource(private val root: Map<String, Any?>, override val origin: String) : ConfigSource {

    override fun raw(path: String): Any? {
        var node: Any? = root

        for (part in path.split('.')) {
            node = (node as? Map<*, *>)?.get(part) ?: return null
        }

        return node
    }

    override fun children(path: String): Set<String> {
        val node = raw(path) as? Map<*, *> ?: return emptySet()

        return node.keys.mapNotNullTo(LinkedHashSet()) { it?.toString() }
    }

    override fun maps(path: String): List<Map<String, Any?>> {
        val node = raw(path) as? List<*> ?: return emptyList()

        return node.mapNotNull { entry ->
            (entry as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }
        }
    }

    companion object {

        /**
         * @return [file] parsed, or an empty source when it is absent. A file that will not parse
         *   throws
         */
        fun load(file: Path): YamlConfigSource {
            if (!Files.exists(file)) return YamlConfigSource(emptyMap(), file.toString())

            Files.newInputStream(file).use { input ->
                @Suppress("UNCHECKED_CAST")
                val map = Yaml().load<Any?>(input) as? Map<String, Any?> ?: emptyMap()

                return YamlConfigSource(map, file.toString())
            }
        }

        fun empty(origin: String = "defaults"): YamlConfigSource = YamlConfigSource(emptyMap(), origin)
    }
}
