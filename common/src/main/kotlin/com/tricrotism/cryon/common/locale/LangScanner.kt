package com.tricrotism.cryon.common.locale

import com.tricrotism.cryon.common.locale.LangScanner.fromJar
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.jar.JarFile

/**
 * Discovers `lang/<locale>.properties` bundles automatically, so features don't register sources by
 * hand — they just bundle the files, and the loader scans them in. [fromJar] reads the entries
 * **directly from the jar** (never via a classloader), so a feature's bundle can't be shadowed by a
 * same-named resource in the core or another jar. A `plugins/Cryon/lang/` folder is covered by
 * [DirectoryMessageSource].
 */
object LangScanner {

    private val PATTERN = Regex("""^lang/([A-Za-z0-9_]+)\.properties$""")

    /** A [MessageSource] from every `lang/<locale>.properties` in [jar], or `null` if there are none. */
    fun fromJar(jar: File): MessageSource? {
        if (!jar.isFile) return null
        val byLocale = HashMap<Locale, Map<String, String>>()
        JarFile(jar).use { jf ->
            val entries = jf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val match = PATTERN.matchEntire(entry.name) ?: continue
                val props = Properties()
                jf.getInputStream(entry).use { InputStreamReader(it, StandardCharsets.UTF_8).use(props::load) }
                byLocale[parseLocale(match.groupValues[1])] =
                    props.entries.associate { it.key.toString() to it.value.toString() }
            }
        }
        return if (byLocale.isEmpty()) null else MapMessageSource(byLocale)
    }

    /** Parse a `lang` filename tag (`en`, `en_US`, `en_US_POSIX`) into a [Locale]. */
    fun parseLocale(tag: String): Locale {
        val parts = tag.split('_')
        return when (parts.size) {
            1 -> Locale.of(parts[0])
            2 -> Locale.of(parts[0], parts[1])
            else -> Locale.of(parts[0], parts[1], parts.drop(2).joinToString("_"))
        }
    }
}
