package com.tricrotism.cryon.paper.api.extension

import com.tricrotism.cryon.common.locale.Locales
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * The locale to render for this player: their **persistent, cross-server override** if set, else
 * their **client locale** (`locale()`). All message helpers resolve through this. Synchronous — the
 * override is served from the in-memory cache the core loads on join (null store ⇒ client locale).
 */
fun Player.resolvedLocale(): Locale = Locales.store?.cached(uniqueId) ?: locale()

/** Set this player's language override (persisted to SQL + synced cross-server). No-op without infra. */
fun Player.setLanguage(locale: Locale): CompletableFuture<*> =
    Locales.store?.set(uniqueId, locale) ?: CompletableFuture.completedFuture(null)

/** Clear this player's override so they fall back to their client locale again. */
fun Player.clearLanguage(): CompletableFuture<*> =
    Locales.store?.clear(uniqueId) ?: CompletableFuture.completedFuture(null)
