package com.tricrotism.cryon.common.concurrent

import kotlinx.coroutines.future.await
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Collapses concurrent lookups of the same key onto one running request.
 *
 * Twenty players walking into range of one NPC ask for one skin twenty times in the same tick, and
 * the endpoint behind it is usually rate limited. Without this each caller starts its own request, so
 * the burst is worst exactly when the cache is cold and the answer matters.
 *
 * Extracted because the same shape is written three times across the feature repos (the NPC skin
 * cache, and both halves of the player-name cache) and it carries two traps that are invisible until
 * they bite:
 *
 * **The entry is removed with the two-argument [ConcurrentHashMap.remove].** A plain `remove(key)`
 * from a finishing lookup deletes whatever is registered *now*, which after a slow failure is a newer
 * caller's live request. That caller then never gets cleaned up, and the one after it joins a future
 * nothing will complete.
 *
 * **Each caller awaits a [CompletableFuture.copy], never the shared future itself.** `await` cancels
 * the future it is waiting on when its coroutine is cancelled, so a caller that gives up, a player
 * who disconnects mid-lookup, would otherwise fail the request for everyone queued behind it. A copy
 * completes with the original and is cancelled alone.
 *
 * The map holds only what is in flight: an entry lives from the first caller to completion, and this
 * is not a cache. Pair it with one, checking the cache before calling [join].
 */
class SingleFlight<K : Any, V> {

    private val running = ConcurrentHashMap<K, CompletableFuture<V>>()

    /** How many distinct lookups are in flight. For diagnostics; it moves under you. */
    val size: Int get() = running.size

    /**
     * Return the value for [key], starting a lookup with [start] only if one is not already running.
     *
     * [start] runs at most once per concurrent burst and must not block: it returns the handle to
     * work happening elsewhere. It is called inside a [ConcurrentHashMap.computeIfAbsent], so it must
     * not touch this instance.
     */
    suspend fun join(key: K, start: (K) -> CompletableFuture<V>): V {
        val shared = running.computeIfAbsent(key, start)

        // Registered outside computeIfAbsent deliberately. An already-complete future runs the
        // callback inline, and mutating the map from inside its own mapping function is undefined.
        shared.whenComplete { _, _ -> running.remove(key, shared) }

        return shared.copy().await()
    }

    /** Forget in-flight lookups. For teardown; callers already waiting still complete. */
    fun clear() {
        running.clear()
    }
}
