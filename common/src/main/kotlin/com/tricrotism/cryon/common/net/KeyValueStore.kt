package com.tricrotism.cryon.common.net

import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Minimal async key/value surface with TTLs, complementing [Messenger]'s pub/sub. Exists because live
 * server-registry state needs expiry-based liveness (a dead node's key must expire on its own), which
 * neither [Messenger] nor the SQL `Database` provides. String values — encode structure yourself.
 *
 * Always registered in the module `ServiceRegistry`: `redis.enabled` picks [RedisKeyValueStore],
 * otherwise [MemoryKeyValueStore] keeps the same contract inside this process. Only the reach of the
 * state differs — never its shape — so callers never branch on the deployment mode.
 */
interface KeyValueStore {

    /** Set [key] to [value], expiring after [ttl]. */
    fun set(key: String, value: String, ttl: Duration): CompletableFuture<Void>

    /** The value at [key], or null if it is absent/expired. */
    fun get(key: String): CompletableFuture<String?>

    /** Remove [key]. */
    fun delete(key: String): CompletableFuture<Void>

    /** Every key matching [pattern] (glob, e.g. `prefix*`), gathered without ever blocking the store. */
    fun keys(pattern: String): CompletableFuture<List<String>>

    /** The values for [keys], in order; a missing key maps to null. */
    fun mget(keys: Collection<String>): CompletableFuture<List<String?>>

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
     * wants to stay is not asking for a second slot — it is saying it is still using the first, and
     * routing that through [tryHold] fails on the very first renewal.
     *
     * False means the hold lapsed and somebody else may have taken it, so the caller must stop acting
     * as though it owns the thing.
     */
    fun refresh(key: String, member: String, ttl: Duration): CompletableFuture<Boolean>

    fun close()
}
