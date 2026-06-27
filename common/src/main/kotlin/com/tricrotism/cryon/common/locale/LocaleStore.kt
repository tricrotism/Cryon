package com.tricrotism.cryon.common.locale

import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Per-player language override storage. The platform resolver (`Player.resolvedLocale()`) reads the
 * override synchronously via [cached]; [set]/[clear] mutate it. Two implementations:
 * [PlayerLocaleStore] (SQL source-of-truth + Redis cross-server sync, persistent) and
 * [MemoryLocaleStore] (process-local, resets on restart) — the core installs whichever the
 * configured infrastructure supports, so overrides always work.
 */
interface LocaleStore {

    /** The cached override for [uuid], or null (no override). Synchronous. */
    fun cached(uuid: UUID): Locale?

    /** Set [uuid]'s override. The returned future completes once it's durably applied. */
    fun set(uuid: UUID, locale: Locale): CompletableFuture<Void>

    /** Clear [uuid]'s override. */
    fun clear(uuid: UUID): CompletableFuture<Void>

    /** Release any resources (subscriptions, pools). No-op for stores that hold none. */
    fun close() {}
}
