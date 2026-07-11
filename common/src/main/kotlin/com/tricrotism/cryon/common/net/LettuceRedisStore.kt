package com.tricrotism.cryon.common.net

import io.lettuce.core.*
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * [RedisStore] over Lettuce. One connection, async commands throughout — every call runs off the
 * caller's thread. Constructed like [RedisMessenger] (its own [RedisClient]); kept independent so
 * enabling/disabling either is isolated.
 */
class LettuceRedisStore(config: RedisConfig) : RedisStore {

    private val client: RedisClient = RedisClient.create(config.uri)
    private val connection: StatefulRedisConnection<String, String> = client.connect()
    private val commands = connection.async()

    override fun set(key: String, value: String, ttl: Duration): CompletableFuture<Void> =
        commands.set(key, value, SetArgs.Builder.ex(ttl.toSeconds())).toCompletableFuture().thenAccept { }

    override fun get(key: String): CompletableFuture<String?> =
        commands.get(key).toCompletableFuture().thenApply { it }

    override fun delete(key: String): CompletableFuture<Void> =
        commands.del(key).toCompletableFuture().thenAccept { }

    override fun keys(pattern: String): CompletableFuture<List<String>> {
        val result = CompletableFuture<List<String>>()
        val found = ArrayList<String>()
        val args = ScanArgs.Builder.matches(pattern).limit(SCAN_BATCH)
        fun step(cursor: ScanCursor) {
            commands.scan(cursor, args).toCompletableFuture().whenComplete { page, error ->
                when {
                    error != null -> result.completeExceptionally(error)
                    else -> {
                        found.addAll(page.keys)
                        if (page.isFinished) result.complete(found) else step(page)
                    }
                }
            }
        }
        step(ScanCursor.INITIAL)
        return result
    }

    override fun mget(keys: Collection<String>): CompletableFuture<List<String?>> {
        if (keys.isEmpty()) return CompletableFuture.completedFuture(emptyList())
        return commands.mget(*keys.toTypedArray()).toCompletableFuture()
            .thenApply { values -> values.map { if (it.hasValue()) it.value else null } }
    }

    override fun evalInt(script: String, keys: List<String>, args: List<String>): CompletableFuture<Long> =
        commands.eval<Long>(script, ScriptOutputType.INTEGER, keys.toTypedArray(), *args.toTypedArray())
            .toCompletableFuture()

    override fun close() {
        connection.close()
        client.shutdown()
    }

    private companion object {
        private const val SCAN_BATCH = 256L
    }
}
