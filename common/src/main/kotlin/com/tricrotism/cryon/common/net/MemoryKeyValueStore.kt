package com.tricrotism.cryon.common.net

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * [KeyValueStore] held inside this process, what a single-server deployment runs instead of
 * [RedisKeyValueStore]. Same contract, same expiry semantics; the state simply never leaves the JVM
 * and dies with it, which is exactly right when the JVM *is* the whole serverId.
 *
 * Expiry is lazy: an entry past its deadline reads as absent and is dropped when next touched, so a
 * key nothing ever reads again keeps its (dead) slot rather than being swept. That is a deliberate
 * trade, no sweeper thread, and it holds because the keys here are per-player or per-instance and
 * are bounded by the population of a single server, not by traffic through it.
 *
 * [tryHold] and the hash operations run under [ConcurrentHashMap.compute]'s per-bin lock, giving the
 * same all-or-nothing guarantee Lua gives on Redis.
 */
class MemoryKeyValueStore : KeyValueStore {

    private class Entry(val value: String, val expiresAt: Long) {
        fun expired(now: Long): Boolean = expiresAt <= now
    }

    /**
     * A hash and the deadline for the whole thing, matching Redis' key-level (not field-level) TTL.
     */
    private class Hash(val fields: MutableMap<String, String>, val expiresAt: Long) {
        fun expired(now: Long): Boolean = expiresAt <= now
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    // key -> (member -> expiry), the in-process stand-in for Redis' sorted-set holds.
    private val holds = ConcurrentHashMap<String, MutableMap<String, Long>>()

    private val hashes = ConcurrentHashMap<String, Hash>()

    override suspend fun set(key: String, value: String, ttl: Duration) {
        entries[key] = Entry(value, deadline(ttl))
    }

    override suspend fun get(key: String): String? =
        entries.compute(key) { _, existing -> live(existing) }?.value

    override suspend fun delete(key: String): Boolean {
        val previous = entries.remove(key)
        return previous != null && !previous.expired(System.currentTimeMillis())
    }

    /**
     * The three claim primitives, each inside one [ConcurrentHashMap.compute], which holds the bin
     * for the duration and so gives the same all-or-nothing guarantee Redis gives by being
     * single-threaded.
     */
    override suspend fun setIfAbsent(key: String, value: String, ttl: Duration): Boolean {
        val now = System.currentTimeMillis()
        var claimed = false
        entries.compute(key) { _, existing ->
            if (existing != null && !existing.expired(now)) return@compute existing
            claimed = true
            Entry(value, now + ttl.toMillis())
        }
        return claimed
    }

    override suspend fun compareAndSet(
        key: String,
        expected: String?,
        next: String,
        ttl: Duration,
    ): Boolean {
        val now = System.currentTimeMillis()
        var stored = false
        entries.compute(key) { _, existing ->
            val live = existing?.takeIf { !it.expired(now) }
            val matches = if (expected == null) live == null else live?.value == expected
            if (!matches) return@compute live
            stored = true
            Entry(next, if (ttl.isZero) Long.MAX_VALUE else now + ttl.toMillis())
        }
        return stored
    }

    override suspend fun deleteIfEqual(key: String, value: String): Boolean {
        val now = System.currentTimeMillis()
        var removed = false
        entries.compute(key) { _, existing ->
            val live = existing?.takeIf { !it.expired(now) } ?: return@compute null
            if (live.value != value) return@compute live
            removed = true
            null
        }
        return removed
    }

    override suspend fun refreshIfEqual(key: String, value: String, ttl: Duration): Boolean {
        val now = System.currentTimeMillis()
        var refreshed = false
        entries.compute(key) { _, existing ->
            val live = existing?.takeIf { !it.expired(now) } ?: return@compute null
            if (live.value != value) return@compute live
            refreshed = true
            Entry(live.value, now + ttl.toMillis())
        }
        return refreshed
    }

    override suspend fun keys(pattern: String): List<String> {
        val now = System.currentTimeMillis()
        val regex = globToRegex(pattern)
        return entries.entries
            .filter { !it.value.expired(now) && regex.matches(it.key) }
            .map { it.key }
    }

    override suspend fun mget(keys: Collection<String>): List<String?> {
        val now = System.currentTimeMillis()
        return keys.map { key -> entries[key]?.takeIf { !it.expired(now) }?.value }
    }

    /**
     * Every hash operation runs inside [ConcurrentHashMap.compute], reads included, so a field write
     * cannot lose a concurrent one and [hdel]'s answer is a real claim, the same guarantees Redis
     * gives by being single-threaded.
     */
    override suspend fun hset(key: String, field: String, value: String, ttl: Duration) {
        val now = System.currentTimeMillis()
        hashes.compute(key) { _, existing ->
            val fields = existing?.takeIf { !it.expired(now) }?.fields ?: HashMap()
            fields[field] = value
            Hash(fields, now + ttl.toMillis())
        }
    }

    override suspend fun hsetIfAbsent(key: String, field: String, value: String, ttl: Duration): Boolean {
        val now = System.currentTimeMillis()
        var claimed = false
        hashes.compute(key) { _, existing ->
            val live = existing?.takeIf { !it.expired(now) }
            if (live != null && live.fields.containsKey(field)) return@compute live
            val fields = live?.fields ?: HashMap()
            fields[field] = value
            claimed = true
            Hash(fields, now + ttl.toMillis())
        }
        return claimed
    }

    /**
     * Copied inside the remap, not after it. [Hash.fields] is a plain map the write paths above
     * mutate in place under the same bin lock, so reading it once [compute] has returned races them:
     * a concurrent [hset] can resize the table mid-copy and the caller sees a torn view or a
     * ConcurrentModificationException, which is precisely the atomicity this class claims to match
     * Redis on.
     */
    override suspend fun hgetAll(key: String): Map<String, String> {
        val now = System.currentTimeMillis()
        var snapshot: Map<String, String> = emptyMap()
        hashes.compute(key) { _, existing ->
            val live = existing?.takeIf { !it.expired(now) } ?: return@compute null
            snapshot = HashMap(live.fields)
            live
        }
        return snapshot
    }

    override suspend fun hdel(key: String, field: String): Boolean {
        val now = System.currentTimeMillis()
        var removed = false
        hashes.compute(key) { _, existing ->
            val live = existing?.takeIf { !it.expired(now) } ?: return@compute null
            removed = live.fields.remove(field) != null
            live.takeIf { it.fields.isNotEmpty() }
        }
        return removed
    }

    override suspend fun tryHold(
        key: String,
        member: String,
        ttl: Duration,
        limit: Int,
        baseline: Int,
    ): Boolean {
        val now = System.currentTimeMillis()
        var granted = false
        holds.compute(key) { _, existing ->
            val current = existing?.filterTo(HashMap()) { it.value > now } ?: HashMap()
            // Re-holding an existing member is already counted, exactly as ZCARD counts it once.
            if (baseline + current.size >= limit) return@compute current.ifEmpty { null }
            current[member] = now + ttl.toMillis()
            granted = true
            current
        }
        return granted
    }

    override suspend fun refresh(key: String, member: String, ttl: Duration): Boolean {
        val now = System.currentTimeMillis()
        var refreshed = false
        holds.compute(key) { _, existing ->
            val current = existing?.filterTo(HashMap()) { it.value > now } ?: return@compute null
            if (member !in current) return@compute current.ifEmpty { null }
            current[member] = now + ttl.toMillis()
            refreshed = true
            current
        }
        return refreshed
    }

    override fun close() {
        entries.clear()
        holds.clear()
        hashes.clear()
    }

    private fun live(entry: Entry?): Entry? = entry?.takeIf { !it.expired(System.currentTimeMillis()) }

    private fun deadline(ttl: Duration): Long = System.currentTimeMillis() + ttl.toMillis()

    private companion object {

        /**
         * Translate a Redis key glob (`*`, `?`, literals) into an equivalent regex.
         */
        private fun globToRegex(pattern: String): Regex = buildString {
            for (char in pattern) {
                when (char) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(char.toString()))
                }
            }
        }.toRegex()
    }
}
