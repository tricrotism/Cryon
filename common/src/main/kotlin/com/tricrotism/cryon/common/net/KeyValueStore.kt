package com.tricrotism.cryon.common.net

import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Minimal async key/value surface with TTLs, complementing [Messenger]'s pub/sub. Exists because live
 * server-registry state needs expiry-based liveness (a dead node's key must expire on its own), which
 * neither [Messenger] nor the SQL `Database` provides. String values, so encode structure yourself.
 *
 * Always registered in the module `ServiceRegistry`: `redis.enabled` picks [RedisKeyValueStore],
 * otherwise [MemoryKeyValueStore] keeps the same contract inside this process. Only the reach of the
 * state differs, never its shape, so callers never branch on the deployment shape.
 */
interface KeyValueStore {

    /** Set [key] to [value], expiring after [ttl]. */
    fun set(key: String, value: String, ttl: Duration): CompletableFuture<Void>

    /** The value at [key], or null if it is absent/expired. */
    fun get(key: String): CompletableFuture<String?>

    /**
     * Remove [key], reporting whether it was actually there.
     *
     * The boolean is the point: removal is atomic, so a true return is proof *this* caller was the
     * one that took the key. That makes a delete usable as a claim. Several servers racing to
     * answer the same one-shot request get exactly one true between them. Callers that only want
     * the key gone can ignore it.
     */
    fun delete(key: String): CompletableFuture<Boolean>

    /**
     * Every key matching [pattern] (glob, e.g. `prefix*`), gathered without ever blocking the store.
     *
     * Cursor-based, so it never stalls Redis — but it still walks the **whole** keyspace, and the
     * pattern only filters what comes back. That makes the cost proportional to the size of the
     * store rather than to the number of matches. For a group of related entries that is read as a
     * unit, prefer a hash ([hset]/[hgetAll]) and pay one O(size-of-group) lookup instead.
     */
    fun keys(pattern: String): CompletableFuture<List<String>>

    /** The values for [keys], in order; a missing key maps to null. */
    fun mget(keys: Collection<String>): CompletableFuture<List<String?>>

    /**
     * Set [field] within the hash at [key], and set the whole hash to expire after [ttl].
     *
     * A hash is the answer to "several related entries, read together, written independently".
     * Writing a field is atomic against every other field, so concurrent writers cannot lose each
     * other's entries the way they would racing on one serialized value — while [hgetAll] still
     * reads the group in a single round trip rather than a keyspace walk.
     *
     * **The TTL is the hash's, not the field's.** Every [hset] pushes the expiry of the whole hash
     * out, so a field written early lives as long as the most recently written one. Where each entry
     * needs its own deadline, carry a timestamp in the value and discard stale ones on read; treat
     * this [ttl] as the backstop that stops an abandoned hash living forever.
     */
    fun hset(key: String, field: String, value: String, ttl: Duration): CompletableFuture<Void>

    /** Every live field of the hash at [key]; empty when it is absent or expired. */
    fun hgetAll(key: String): CompletableFuture<Map<String, String>>

    /**
     * Remove [field] from the hash at [key], reporting whether it was actually there.
     *
     * The boolean carries the same weight as [delete]'s: removal is atomic, so a true return is
     * proof *this* caller took the field, which makes it usable as a claim between racing callers.
     */
    fun hdel(key: String, field: String): CompletableFuture<Boolean>

    /**
     * Atomically hold a slot under [key] for [member] until [ttl] lapses, so concurrent callers across
     * the network can't overshoot a capacity. Expired holds are pruned first; the hold is granted only
     * if [baseline] (occupancy already counted elsewhere) plus the live holds stays below [limit].
     */
    fun tryHold(
        key: String,
        member: String,
        ttl: Duration,
        limit: Int,
        baseline: Int,
    ): CompletableFuture<Boolean>

    /**
     * Extend [member]'s existing hold under [key] by [ttl], reporting whether it still had one.
     *
     * Separate from [tryHold] because that one is a *capacity* primitive: it counts the caller's own
     * live hold against `limit`, so re-asking for a slot you already occupy is refused. A holder that
     * wants to stay is not asking for a second slot; it is saying it is still using the first, and
     * routing that through [tryHold] fails on the very first renewal.
     *
     * False means the hold lapsed and somebody else may have taken it, so the caller must stop acting
     * as though it owns the thing.
     */
    fun refresh(key: String, member: String, ttl: Duration): CompletableFuture<Boolean>

    fun close()
}
