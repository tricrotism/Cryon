package com.tricrotism.cryon.paper.api.extension

import com.tricrotism.cryon.common.locale.Locales
import org.bukkit.entity.Player
import java.util.*

/**
 * The locale to render for this player: their **persistent, cross-server override** if set, else
 * their **client locale** (`locale()`). All message helpers resolve through this. Synchronous. The
 * override is served from the in-memory cache the core loads on join (null store ⇒ client locale).
 */
fun Player.resolvedLocale(): Locale = Locales.store?.cached(uniqueId) ?: locale()

/**
 * Set this player's language override (persisted to SQL + synced cross-server). No-op without infra.
 *
 * Suspends until the write lands, so a caller can tell the player it took effect rather than
 * guessing. Call it from your module's `scope`.
 */
suspend fun Player.setLanguage(locale: Locale) {
    Locales.store?.set(uniqueId, locale)
}

/** Clear this player's override so they fall back to their client locale again. */
suspend fun Player.clearLanguage() {
    Locales.store?.clear(uniqueId)
}
