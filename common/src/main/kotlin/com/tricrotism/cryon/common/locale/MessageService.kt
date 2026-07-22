package com.tricrotism.cryon.common.locale

import com.tricrotism.cryon.common.text.Mini
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The network's i18n service: resolves `(locale, key)` to a rendered `Component` across all
 * registered [MessageSource]s, with a fallback chain (requested locale → its language → default
 * locale → its language) and basic count-based pluralization. Modules add their own sources via
 * [addSource] in `onLoad`. Created once by the core and shared through the module `ServiceRegistry`.
 *
 * Thread-safe: sources are held in a `CopyOnWriteArrayList`; rendering is stateless.
 */
class MessageService(defaultLocale: Locale = Locale.US) {

    @Volatile
    var defaultLocale: Locale = defaultLocale
        set(value) {
            field = value
            chains.clear()
        }

    private val sources = CopyOnWriteArrayList<MessageSource>()
    private val chains = ConcurrentHashMap<Locale, List<Locale>>()

    fun addSource(source: MessageSource) = sources.add(source)
    fun removeSource(source: MessageSource) = sources.remove(source)
    fun reload() = sources.forEach(MessageSource::reload)

    /** First matching raw template across the [localeChain] and all sources, or `null`. */
    fun template(locale: Locale, key: String): String? {
        for (candidate in localeChain(locale)) {
            for (source in sources) {
                source.template(candidate, key)?.let { return it }
            }
        }
        return null
    }

    /** Every key any source can enumerate for [locale] (exact locale, no fallback), sorted for stable output. */
    fun keys(locale: Locale): Set<String> = sources.flatMapTo(sortedSetOf()) { it.keys(locale) }

    /**
     * Append every enumerable [locale] key not already in [file] — valued by the resolved default
     * template — so admins get a complete, editable bundle without hand-copying it out of the jars.
     * Existing entries are **never** rewritten (a deliberate override must survive), so the file's
     * comments and order stay intact and new keys are appended in sorted order. Read and written UTF-8
     * to match [DirectoryMessageSource]. Returns how many keys were written.
     */
    fun exportMissing(locale: Locale, file: File): Int {
        val existing = Properties()
        if (file.isFile) file.inputStream().use { InputStreamReader(it, StandardCharsets.UTF_8).use(existing::load) }
        val missing = keys(locale).filter { !existing.containsKey(it) }
        if (missing.isEmpty()) return 0

        file.parentFile?.mkdirs()
        OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8).use { out ->
            if (file.length() > 0L) out.write("\n")
            out.write("# Added automatically; edit freely — existing keys are never overwritten.\n")
            for (key in missing) {
                val value = template(locale, key) ?: continue
                out.write("$key=${escapeValue(value)}\n")
            }
        }
        return missing.size
    }

    /** Render [key] for [locale]; missing keys render as `⟨key⟩` so gaps are visible, not silent. */
    fun render(locale: Locale, key: String, vararg resolvers: TagResolver): Component {
        val template = template(locale, key) ?: return missing(key)
        return Mini.format(template, *resolvers)
    }

    /** Pluralized render: tries `key.one` (count == 1) or `key.other`, then bare `key`. */
    fun renderPlural(locale: Locale, key: String, count: Long, vararg resolvers: TagResolver): Component {
        val variant = if (count == 1L) "$key.one" else "$key.other"
        val template = template(locale, variant) ?: template(locale, key) ?: return missing(key)
        return Mini.format(template, *resolvers)
    }

    private fun localeChain(locale: Locale): List<Locale> = chains.computeIfAbsent(locale) { requested ->
        val chain = LinkedHashSet<Locale>()
        chain.add(requested)
        if (requested.country.isNotEmpty()) chain.add(Locale.of(requested.language))
        chain.add(defaultLocale)
        if (defaultLocale.country.isNotEmpty()) chain.add(Locale.of(defaultLocale.language))
        chain.toList()
    }

    private fun missing(key: String): Component = Component.text("⟨$key⟩")

    // Property-file escaping for values. Files are UTF-8 (not latin1), so non-ASCII is written as-is;
    // only backslash, control chars, and a leading space need escaping. Keys here are dotted ids, safe.
    private fun escapeValue(value: String): String = buildString(value.length) {
        value.forEachIndexed { i, c ->
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                ' ' -> append(if (i == 0) "\\ " else " ")
                else -> append(c)
            }
        }
    }
}
