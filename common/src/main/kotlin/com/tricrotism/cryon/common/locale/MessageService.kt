package com.tricrotism.cryon.common.locale

import com.tricrotism.cryon.common.text.Mini
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The network's i18n service: resolves `(locale, key)` to a rendered `Component` across all
 * registered [MessageSource]s, with a fallback chain (requested locale → its language → default
 * locale → its language) and basic count-based pluralization. Modules add their own sources via
 * [addSource] in `onLoad`. Created once by the core and shared through the module `ServiceRegistry`.
 *
 * Thread-safe: sources are held in a `CopyOnWriteArrayList`; rendering is stateless.
 */
class MessageService(@Volatile var defaultLocale: Locale = Locale.US) {

    private val sources = CopyOnWriteArrayList<MessageSource>()

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

    private fun localeChain(locale: Locale): List<Locale> {
        val chain = LinkedHashSet<Locale>()
        chain.add(locale)
        if (locale.country.isNotEmpty()) chain.add(Locale.of(locale.language))
        chain.add(defaultLocale)
        if (defaultLocale.country.isNotEmpty()) chain.add(Locale.of(defaultLocale.language))
        return chain.toList()
    }

    private fun missing(key: String): Component = Component.text("⟨$key⟩")
}
