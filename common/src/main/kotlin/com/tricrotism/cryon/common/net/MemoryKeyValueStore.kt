package com.tricrotism.cryon.common.net

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * [KeyValueStore] held inside this process — what a single-server deployment runs instead of
 * [RedisKeyValueStore]. Same contract, same expiry semantics; the state simply never leaves the JVM
 * and dies with it, which is exactly right when the JVM *is* the whole family.
 *
 * Expiry is lazy: an entry past its deadline reads as absent and is dropped when next touched. The
 * live key set is bounded (this instance's registry entry and its capacity holds), so nothing
 * accumulates and no sweeper thread is needed. [tryHold] runs under [ConcurrentHashMap.compute]'s
 * per-bin lock, giving the same all-or-nothing guarantee Lua gives on Redis.
 */
class MemoryKeyValueStore : KeyValueStore {

    private class Entry(val value: String, val expiresAt: Long) {
        fun expired(now: Long): Boolean = expiresAt <= now
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    // key -> (member -> expiry), the in-process stand-in for Redis' sorted-set holds.
    private val holds = ConcurrentHashMap<String, MutableMap<String, Long>>()

    override fun set(key: String, value: String, ttl: Duration): CompletableFuture<Void> {
        entries[key] = Entry(value, deadline(ttl))
        return DONE
    }

    override fun get(key: String): CompletableFuture<String?> {
        val entry = entries.compute(key) { _, existing -> live(existing) }
        return CompletableFuture.completedFuture(entry?.value)
    }

    override fun delete(key: String): CompletableFuture<Void> {
        entries.remove(key)
        return DONE
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

    override fun close() {
        entries.clear()
        holds.clear()
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
