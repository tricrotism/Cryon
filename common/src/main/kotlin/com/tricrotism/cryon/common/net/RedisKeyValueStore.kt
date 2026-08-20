package com.tricrotism.cryon.common.net

import io.lettuce.core.*
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.future.await
import java.time.Duration

/**
 * [KeyValueStore] over Lettuce, the transport that reaches every process in the network. One
 * connection, async commands throughout, awaited rather than blocked on, so a caller is released
 * while the round trip is in flight. Constructed like [RedisMessenger] (its own [RedisClient]); kept
 * independent so enabling/disabling either is isolated.
 *
 * [tryHold] is the only place Lua lives: the interface exposes the capability, not the scripting
 * engine, so [MemoryKeyValueStore] can honour the same contract in-process.
 */
class RedisKeyValueStore(config: RedisConfig) : KeyValueStore {

    private val client: RedisClient = RedisClient.create(config.uri)
    private val connection: StatefulRedisConnection<String, String> = client.connect()
    private val commands = connection.async()

    override suspend fun set(key: String, value: String, ttl: Duration) {
        commands.set(key, value, SetArgs.Builder.px(ttl.toMillis())).toCompletableFuture().await()
    }

    override suspend fun get(key: String): String? =
        commands.get(key).toCompletableFuture().await()

    override suspend fun delete(key: String): Boolean =
        commands.del(key).toCompletableFuture().await() > 0


    override suspend fun setIfAbsent(key: String, value: String, ttl: Duration): Boolean =
        commands.set(key, value, SetArgs.Builder.nx().px(ttl.toMillis())).toCompletableFuture().await() == "OK"

    override suspend fun deleteIfEqual(key: String, value: String): Boolean =
        commands.eval<Long>(
            DELETE_IF_EQUAL,
            ScriptOutputType.INTEGER,
            arrayOf(key),
            value,
        ).toCompletableFuture().await() == 1L

    override suspend fun refreshIfEqual(key: String, value: String, ttl: Duration): Boolean =
        commands.eval<Long>(
            REFRESH_IF_EQUAL,
            ScriptOutputType.INTEGER,
            arrayOf(key),
            value,
            ttl.toMillis().toString(),
        ).toCompletableFuture().await() == 1L

    override suspend fun keys(pattern: String): List<String> {
        val found = ArrayList<String>()
        val args = ScanArgs.Builder.matches(pattern).limit(SCAN_BATCH)
        var cursor: ScanCursor = ScanCursor.INITIAL
        while (true) {
            val page = commands.scan(cursor, args).toCompletableFuture().await()
            found.addAll(page.keys)
            if (page.isFinished) return found
            cursor = page
        }
    }

    override suspend fun mget(keys: Collection<String>): List<String?> {
        if (keys.isEmpty()) return emptyList()
        return commands.mget(*keys.toTypedArray()).toCompletableFuture().await()
            .map { if (it.hasValue()) it.value else null }
    }

    /**
     * HSET plus PEXPIRE. Two commands rather than one, but they are pipelined on the same connection
     * and neither is a read, so nothing here depends on the pair being atomic: the worst interleaving
     * is a competing writer's expiry winning, and both are asking for the same [ttl] from roughly the
     * same instant.
     */
    override suspend fun hset(key: String, field: String, value: String, ttl: Duration) {
        commands.hset(key, field, value).toCompletableFuture().await()
        commands.pexpire(key, ttl.toMillis()).toCompletableFuture().await()
    }

    override suspend fun hsetIfAbsent(key: String, field: String, value: String, ttl: Duration): Boolean =
        commands.eval<Long>(
            HSET_IF_ABSENT,
            ScriptOutputType.INTEGER,
            arrayOf(key),
            field,
            value,
            ttl.toMillis().toString(),
        ).toCompletableFuture().await() == 1L

    override suspend fun hgetAll(key: String): Map<String, String> =
        commands.hgetall(key).toCompletableFuture().await() ?: emptyMap()

    override suspend fun hdel(key: String, field: String): Boolean =
        commands.hdel(key, field).toCompletableFuture().await() > 0

    override suspend fun tryHold(
        key: String,
        member: String,
        ttl: Duration,
        limit: Int,
        baseline: Int,
    ): Boolean {
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
        ).toCompletableFuture().await() == 1L
    }

    override suspend fun refresh(key: String, member: String, ttl: Duration): Boolean {
        val now = System.currentTimeMillis()
        val holdMillis = ttl.toMillis()
        return commands.eval<Long>(
            REFRESH_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(key),
            now.toString(),
            (now + holdMillis).toString(),
            member,
            holdMillis.toString(),
        ).toCompletableFuture().await() == 1L
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }

    private companion object {
        private const val SCAN_BATCH = 256L

        // KEYS[1]=hash, ARGV[1]=field, ARGV[2]=value, ARGV[3]=ttl millis. Claims the field or not.
        private val HSET_IF_ABSENT = """
            if redis.call('HSETNX', KEYS[1], ARGV[1], ARGV[2]) == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[3])
                return 1
            end
            return 0
        """.trimIndent()

        // KEYS[1]=key, ARGV[1]=expected value. Deletes only what this caller still owns.
        private val DELETE_IF_EQUAL = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
        """.trimIndent()

        // KEYS[1]=key, ARGV[1]=expected value, ARGV[2]=new ttl in millis.
        private val REFRESH_IF_EQUAL = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0
        """.trimIndent()


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

        private val REFRESH_SCRIPT = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
            if redis.call('ZSCORE', KEYS[1], ARGV[3]) == false then return 0 end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
        """.trimIndent()
    }
}
