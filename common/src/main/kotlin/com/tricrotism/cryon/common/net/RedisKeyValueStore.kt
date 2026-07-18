package com.tricrotism.cryon.common.net

import io.lettuce.core.*
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * [KeyValueStore] over Lettuce — the transport that reaches every process in the network. One
 * connection, async commands throughout, so every call runs off the caller's thread. Constructed like
 * [RedisMessenger] (its own [RedisClient]); kept independent so enabling/disabling either is isolated.
 *
 * [tryHold] is the only place Lua lives: the interface exposes the capability, not the scripting
 * engine, so [MemoryKeyValueStore] can honour the same contract in-process.
 */
class RedisKeyValueStore(config: RedisConfig) : KeyValueStore {

    private val client: RedisClient = RedisClient.create(config.uri)
    private val connection: StatefulRedisConnection<String, String> = client.connect()
    private val commands = connection.async()

    override fun set(key: String, value: String, ttl: Duration): CompletableFuture<Void> =
        commands.set(key, value, SetArgs.Builder.px(ttl.toMillis())).toCompletableFuture().thenAccept { }

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

    override fun tryHold(
        key: String,
        member: String,
        ttl: Duration,
        limit: Int,
        baseline: Int,
    ): CompletableFuture<Boolean> {
        val now = System.currentTimeMillis()
        val holdMillis = ttl.toMillis()
        return commands.eval<Long>(
            HOLD_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(key),
            now.toString(),
            (now + holdMillis).toString(),
            limit.toString(),
            baseline.toString(),
            member,
            holdMillis.toString(),
        ).toCompletableFuture().thenApply { it == 1L }
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }

    private companion object {
        private const val SCAN_BATCH = 256L

        // Atomic capacity hold over a sorted set of {member -> expiry}. Prunes expired holds, rejects if
        // the baseline plus live holds would meet the limit, else records the hold (score = expiry) and
        // returns 1. KEYS[1]=hold set; ARGV=now, expiry, limit, baseline, member, ttlMillis.
        private val HOLD_SCRIPT = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
            local held = redis.call('ZCARD', KEYS[1])
            if (tonumber(ARGV[4]) + held) >= tonumber(ARGV[3]) then return 0 end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[5])
            redis.call('PEXPIRE', KEYS[1], ARGV[6])
            return 1
        """.trimIndent()
    }
}
