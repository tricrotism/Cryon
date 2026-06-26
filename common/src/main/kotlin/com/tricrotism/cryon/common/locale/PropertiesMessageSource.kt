package com.tricrotism.cryon.common.locale

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * A [MessageSource] that loads `<basePath>/<locale>.properties` (UTF-8) from a class loader — e.g.
 * a feature jar bundling `lang/en_US.properties`. Keys are flat (`economy.not_enough`), values are
 * MiniMessage templates. [reload] re-reads the files; reads see a consistent snapshot via `@Volatile`.
 */
class PropertiesMessageSource(
    private val classLoader: ClassLoader,
    private val basePath: String,
    locales: Collection<Locale>,
) : MessageSource {

    private val locales = locales.toList()

    @Volatile
    private var cache: Map<Locale, Map<String, String>> = emptyMap()

    init {
        reload()
    }

    override fun template(locale: Locale, key: String): String? = cache[locale]?.get(key)

    override fun reload() {
        val loaded = HashMap<Locale, Map<String, String>>()
        for (locale in locales) {
            val resource = "$basePath/$locale.properties"
            classLoader.getResourceAsStream(resource)?.use { stream ->
                val props = Properties()
                InputStreamReader(stream, StandardCharsets.UTF_8).use(props::load)
                loaded[locale] = props.entries.associate { it.key.toString() to it.value.toString() }
            }
        }
        cache = loaded
    }
}
