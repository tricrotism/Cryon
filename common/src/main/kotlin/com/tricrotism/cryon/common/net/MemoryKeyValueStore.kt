package com.tricrotism.cryon.common.net

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * [KeyValueStore] held inside this process, what a single-server deployment runs instead of
 * [RedisKeyValueStore]. Same contract, same expiry semantics; the state simply never leaves the JVM
 * and dies with it, which is exactly right when the JVM *is* the whole serverId.
 *
 * Expiry is lazy: an entry past its deadline reads as absent and is dropped when next touched, so a
 * key nothing ever reads again keeps its (dead) slot rather than being swept. That is a deliberate
 * trade — no sweeper thread — and it holds because the keys here are per-player or per-instance and
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

    override fun set(key: String, value: String, ttl: Duration): CompletableFuture<Void> {
        entries[key] = Entry(value, deadline(ttl))
        return DONE
    }

    override fun get(key: String): CompletableFuture<String?> {
        val entry = entries.compute(key) { _, existing -> live(existing) }
        return CompletableFuture.completedFuture(entry?.value)
    }

    override fun delete(key: String): CompletableFuture<Boolean> {
        val previous = entries.remove(key)
        val held = previous != null && !previous.expired(System.currentTimeMillis())
        return CompletableFuture.completedFuture(held)
    }

    override fun keys(pattern: String): CompletableFuture<List<String>> {
        val now = System.currentTimeMillis()
        val regex = globToRegex(pattern)
        val matched = entries.entries
            .filter { !it.value.expired(now) && regex.matches(it.key) }
            .map { it.key }
        return CompletableFuture.completedFuture(matched)
    }

    override fun mget(keys: Collection<String>): CompletableFuture<List<String?>> {
        val now = System.currentTimeMillis()
        val values = keys.map { key -> entries[key]?.takeIf { !it.expired(now) }?.value }
        return CompletableFuture.completedFuture(values)
    }

    /**
     * All three hash operations run inside [ConcurrentHashMap.compute], so a field write cannot lose
     * a concurrent one and [hdel]'s answer is a real claim — the same guarantees Redis gives by
     * being single-threaded.
     */
    override fun hset(key: String, field: String, value: String, ttl: Duration): CompletableFuture<Void> {
        val now = System.currentTimeMillis()
        hashes.compute(key) { _, existing ->
            val fields = existing?.takeIf { !it.expired(now) }?.fields ?: HashMap()
            fields[field] = value
            Hash(fields, now + ttl.toMillis())
        }
        return DONE
    }

    override fun hgetAll(key: String): CompletableFuture<Map<String, String>> {
        val now = System.currentTimeMillis()
        val hash = hashes.compute(key) { _, existing -> existing?.takeIf { !it.expired(now) } }
        return CompletableFuture.completedFuture(hash?.fields?.toMap() ?: emptyMap())
    }

    override fun hdel(key: String, field: String): CompletableFuture<Boolean> {
        val now = System.currentTimeMillis()
        var removed = false
        hashes.compute(key) { _, existing ->
            val live = existing?.takeIf { !it.expired(now) } ?: return@compute null
            removed = live.fields.remove(field) != null
            live.takeIf { it.fields.isNotEmpty() }
        }
        return CompletableFuture.completedFuture(removed)
    }

    override fun tryHold(
        key: String,
        member: String,
        ttl: Duration,
        limit: Int,
        baseline: Int,
    ): CompletableFuture<Boolean> {
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
        return CompletableFuture.completedFuture(granted)
    }

    override fun refresh(key: String, member: String, ttl: Duration): CompletableFuture<Boolean> {
        val now = System.currentTimeMillis()
        var refreshed = false
        holds.compute(key) { _, existing ->
            val current = existing?.filterTo(HashMap()) { it.value > now } ?: return@compute null
            if (member !in current) return@compute current.ifEmpty { null }
            current[member] = now + ttl.toMillis()
            refreshed = true
            current
        }
        return CompletableFuture.completedFuture(refreshed)
    }

    override fun close() {
        entries.clear()
        holds.clear()
        hashes.clear()
    }

    private fun live(entry: Entry?): Entry? = entry?.takeIf { !it.expired(System.currentTimeMillis()) }

    private fun deadline(ttl: Duration): Long = System.currentTimeMillis() + ttl.toMillis()

    private companion object {
        private val DONE: CompletableFuture<Void> get() = CompletableFuture.completedFuture(null)

        /** Translate a Redis key glob (`*`, `?`, literals) into an equivalent regex. */
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
