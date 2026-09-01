package com.tricrotism.cryon.common.locale

import java.util.*

/**
 * Per-player language override storage. The platform resolver (`Player.resolvedLocale()`) reads the
 * override synchronously via [cached]; [set]/[clear] mutate it. Two implementations:
 * [PlayerLocaleStore] (SQL source-of-truth + Redis cross-server sync, persistent) and
 * [MemoryLocaleStore] (process-local, resets on restart). The core installs whichever the
 * configured infrastructure supports, so overrides always work.
 */
interface LocaleStore {

    /**
     * The cached override for [uuid], or null (no override). Synchronous.
     */
    fun cached(uuid: UUID): Locale?

    /**
     * Set [uuid]'s override. Returns once it is durably applied.
     */
    suspend fun set(uuid: UUID, locale: Locale)

    /**
     * Clear [uuid]'s override.
     */
    suspend fun clear(uuid: UUID)

    /**
     * Release any resources (subscriptions, pools). No-op for stores that hold none.
     */
    fun close() {}
}
