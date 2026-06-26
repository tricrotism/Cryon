package com.tricrotism.cryon.common.locale

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * A [MessageSource] backed by `<dir>/<locale>.properties` files on disk — e.g. a
 * `plugins/Cryon/lang/` folder admins drop translations into. Register it ahead of jar-bundled
 * sources so on-disk overrides win. [reload] re-reads the folder; reads see a consistent snapshot.
 */
class DirectoryMessageSource(private val dir: File) : MessageSource {

    @Volatile
    private var cache: Map<Locale, Map<String, String>> = emptyMap()

    init {
        reload()
    }

    override fun template(locale: Locale, key: String): String? = cache[locale]?.get(key)

    override fun reload() {
        val loaded = HashMap<Locale, Map<String, String>>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".properties") }?.forEach { file ->
            val tag = file.name.removeSuffix(".properties")
            val props = Properties()
            file.inputStream().use { InputStreamReader(it, StandardCharsets.UTF_8).use(props::load) }
            loaded[LangScanner.parseLocale(tag)] = props.entries.associate { it.key.toString() to it.value.toString() }
        }
        cache = loaded
    }
}
