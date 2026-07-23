package com.tricrotism.cryon.common.locale

import com.tricrotism.cryon.common.locale.Messages.install
import com.tricrotism.cryon.common.text.Mini
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.util.*

/**
 * Static facade over the installed [MessageService], so stateless helpers
 * ([com.tricrotism.cryon.common.text.CommonMessages]) can localize without threading the service
 * through every call. The core calls [install] once on enable. If nothing is installed (or a key is
 * missing), the `…Or` helpers fall back to the supplied default, so callers always work.
 */
object Messages {

    @Volatile
    private var service: MessageService? = null

    fun install(service: MessageService) {
        this.service = service
    }

    fun service(): MessageService? = service

    /** Best raw template for [key] across [locale]'s fallback chain, or `null`. */
    fun raw(locale: Locale, key: String): String? = service?.template(locale, key)

    /** Raw template for [key], or [fallback] if absent / no service installed. */
    fun rawOr(locale: Locale, key: String, fallback: String): String = raw(locale, key) ?: fallback

    /** Localized component for [key]; missing → `⟨key⟩`. */
    fun get(locale: Locale, key: String, vararg resolvers: TagResolver): Component =
        service?.render(locale, key, *resolvers) ?: Mini.format("⟨$key⟩")

    /** Localized component for [key], or [fallback] (a MiniMessage template) if absent. */
    fun getOr(locale: Locale, key: String, fallback: String, vararg resolvers: TagResolver): Component {
        val template = raw(locale, key) ?: fallback
        return if (resolvers.isEmpty()) Mini.format(template) else Mini.format(template, *resolvers)
    }
}
