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

    fun close()
}
