package com.tricrotism.cryon.common.locale

import java.util.*

/** A static [MessageSource] backed by an in-memory `locale -> (key -> template)` map. */
class MapMessageSource(private val byLocale: Map<Locale, Map<String, String>>) : MessageSource {
    override fun template(locale: Locale, key: String): String? = byLocale[locale]?.get(key)
}
