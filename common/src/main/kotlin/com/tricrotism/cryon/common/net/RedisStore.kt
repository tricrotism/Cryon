package com.tricrotism.cryon.common.net

import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Minimal async Redis key/value surface, complementing [Messenger]'s pub/sub. Exists because live
 * server-registry state needs TTL-based liveness (a dead node's key must expire on its own), which
 * neither [Messenger] nor the SQL `Database` provides. String values — encode structure yourself.
 * Shared via the module `ServiceRegistry` when `redis.enabled` is set.
 */
interface RedisStore {

    /** Set [key] to [value], expiring after [ttl]. */
    fun set(key: String, value: String, ttl: Duration): CompletableFuture<Void>

    /** The value at [key], or null if it is absent/expired. */
    fun get(key: String): CompletableFuture<String?>

    /** Remove [key]. */
    fun delete(key: String): CompletableFuture<Void>

    /** Every key matching [pattern], gathered via a non-blocking SCAN cursor (never `KEYS`). */
    fun keys(pattern: String): CompletableFuture<List<String>>

    /** The values for [keys], in order; a missing key maps to null. */
    fun mget(keys: Collection<String>): CompletableFuture<List<String?>>

    /** Run a Lua [script] atomically, returning its integer reply. For check-and-set primitives. */
    fun evalInt(script: String, keys: List<String>, args: List<String>): CompletableFuture<Long>

    fun close()
}
