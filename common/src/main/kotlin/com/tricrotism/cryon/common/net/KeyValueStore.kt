package com.tricrotism.cryon.common.net

import java.time.Duration

/**
 * Minimal suspending key/value surface with TTLs, complementing [Messenger]'s pub/sub. Exists because live
 * server-registry state needs expiry-based liveness (a dead node's key must expire on its own), which
 * neither [Messenger] nor the SQL `Database` provides. String values, so encode structure yourself.
 *
 * Always registered in the module `ServiceRegistry`: `redis.enabled` picks [RedisKeyValueStore],
 * otherwise [MemoryKeyValueStore] keeps the same contract inside this process. Only the reach of the
 * state differs, never its shape, so callers never branch on the deployment shape.
 */
interface KeyValueStore {

    /**
     * Set [key] to [value], expiring after [ttl].
     */
    suspend fun set(key: String, value: String, ttl: Duration)

    /**
     * The value at [key], or null if it is absent/expired.
     */
    suspend fun get(key: String): String?

    /**
     * Remove [key], reporting whether it was actually there.
     *
     * The boolean is the point: removal is atomic, so a true return is proof *this* caller was the
     * one that took the key. That makes a delete usable as a claim. Several servers racing to
     * answer the same one-shot request get exactly one true between them. Callers that only want
     * the key gone can ignore it.
     */
    suspend fun delete(key: String): Boolean

    /**
     * Set [key] to [value] expiring after [ttl], **only if it is not already set**. True if this
     * caller created it.
     *
     * The claim primitive: on Redis this is one `SET NX PX`, so of any number of callers racing for
     * the same key exactly one is told true. [set] cannot be used for this. It overwrites, so every
     * racer would "succeed" and they would all believe they hold the same thing.
     */
    suspend fun setIfAbsent(key: String, value: String, ttl: Duration): Boolean

    /**
     * Remove [key], but **only if it still holds [value]**. True if this caller removed it.
     *
     * The release half of the claim, and the reason [delete] is not good enough for one. A holder
     * whose TTL lapsed no longer owns the key, someone else may already have claimed it, and an
     * unconditional delete at that point releases *their* claim, silently letting two callers into a
     * section that is supposed to admit one. Comparing the value first makes a late release a no-op
     * instead, which is the only safe way for it to fail.
     */
    suspend fun deleteIfEqual(key: String, value: String): Boolean

    /**
     * Push [key]'s expiry out by [ttl], but **only if it still holds [value]**. True if it did.
     *
     * False is the signal a holder must not ignore: it means the claim lapsed and may now belong to
     * somebody else, so whatever was being done under it has to stop rather than run on unprotected.
     */
    suspend fun refreshIfEqual(key: String, value: String, ttl: Duration): Boolean

    /**
     * Store [next] only if the current value is exactly [expected], where null means it must be
     * absent.
     *
     * The general compare-and-set the claim primitives around it are each a special case of, for the
     * case they do not cover: a value read, computed on, and written back, where a concurrent writer
     * between the read and the write must lose rather than be overwritten. Same shape and same reason
     * as `CurrencyStore.compareAndSet`, one layer down.
     *
     * A zero [ttl] stores the key with no expiry, which is what anything whose loss would be a loss
     * of data rather than of a cache entry wants, and why this does not reuse [set]'s signature.
     */
    suspend fun compareAndSet(
        key: String,
        expected: String?,
        next: String,
        ttl: Duration = Duration.ZERO,
    ): Boolean

    /**
     * Every key matching [pattern] (glob, e.g. `prefix*`), gathered without ever blocking the store.
     *
     * Cursor-based, so it never stalls Redis, but it still walks the **whole** keyspace, and the
     * pattern only filters what comes back. That makes the cost proportional to the size of the
     * store rather than to the number of matches. For a group of related entries that is read as a
     * unit, prefer a hash ([hset]/[hgetAll]) and pay one O(size-of-group) lookup instead.
     */
    suspend fun keys(pattern: String): List<String>

    /**
     * The values for [keys], in order; a missing key maps to null.
     */
    suspend fun mget(keys: Collection<String>): List<String?>

    /**
     * Set [field] within the hash at [key], and set the whole hash to expire after [ttl].
     *
     * A hash is the answer to "several related entries, read together, written independently".
     * Writing a field is atomic against every other field, so concurrent writers cannot lose each
     * other's entries the way they would racing on one serialized value, while [hgetAll] still
     * reads the group in a single round trip rather than a keyspace walk.
     *
     * **The TTL is the hash's, not the field's.** Every [hset] pushes the expiry of the whole hash
     * out, so a field written early lives as long as the most recently written one. Where each entry
     * needs its own deadline, carry a timestamp in the value and discard stale ones on read; treat
     * this [ttl] as the backstop that stops an abandoned hash living forever.
     */
    suspend fun hset(key: String, field: String, value: String, ttl: Duration)

    /**
     * Set [field] within the hash at [key] **only if that field is not already there**, answering
     * whether this caller created it. The hash's TTL is pushed to [ttl] only on a successful claim.
     *
     * The hash counterpart to [setIfAbsent], and the reason it exists rather than callers doing
     * [hgetAll] then [hset]: that pair is a check-then-act, so two callers racing the same field both
     * read it absent and both believe they claimed it. One round trip instead of two, and the boolean
     * is a real claim rather than a prediction, safe to gate a reward on.
     */
    suspend fun hsetIfAbsent(key: String, field: String, value: String, ttl: Duration): Boolean

    /**
     * Every live field of the hash at [key]; empty when it is absent or expired.
     */
    suspend fun hgetAll(key: String): Map<String, String>

    /**
     * Remove [field] from the hash at [key], reporting whether it was actually there.
     *
     * The boolean carries the same weight as [delete]'s: removal is atomic, so a true return is
     * proof *this* caller took the field, which makes it usable as a claim between racing callers.
     */
    suspend fun hdel(key: String, field: String): Boolean

    /**
     * Atomically hold a slot under [key] for [member] until [ttl] lapses, so concurrent callers across
     * the network can't overshoot a capacity. Expired holds are pruned first; the hold is granted only
     * if [baseline] (occupancy already counted elsewhere) plus the live holds stays below [limit].
     */
    suspend fun tryHold(
        key: String,
        member: String,
        ttl: Duration,
        limit: Int,
        baseline: Int,
    ): Boolean

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
    suspend fun refresh(key: String, member: String, ttl: Duration): Boolean

    fun close()
}
