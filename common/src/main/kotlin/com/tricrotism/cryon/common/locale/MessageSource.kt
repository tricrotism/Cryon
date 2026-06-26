package com.tricrotism.cryon.common.locale

import java.util.*

/**
 * A provider of MiniMessage templates keyed by `(locale, key)`. A module registers one (or more)
 * with the [MessageService] in `onLoad` to contribute its messages. Implementations must be
 * thread-safe for reads.
 */
interface MessageSource {

    /** The raw MiniMessage template for [key] in [locale], or `null` if this source lacks it. */
    fun template(locale: Locale, key: String): String?

    /** Re-read backing storage (files, config). No-op for static sources. */
    fun reload() {}
}
