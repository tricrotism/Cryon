package com.tricrotism.cryon.common.cooldown

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import com.tricrotism.cryon.common.cooldown.MemoryCooldowns.Companion.MAX_TRACKED
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * In-process [CooldownService] over a Caffeine cache with per-entry expiry.
 *
 * **Caffeine rather than a `ConcurrentHashMap` of deadlines**, because the entries that most need
 * removing are the ones nobody will ever ask about again: a player uses a command once and never
 * returns, and a map only cleaned when the same key is read next would hold their entry for the rest
 * of the server's uptime. Every entry here carries its own TTL — the cooldown's own duration — so it
 * evicts itself whether or not anyone asks again, and [MAX_TRACKED] bounds a join flood on top.
 *
 * **Expiry is the backstop, not the answer.** Caffeine evicts lazily, so an entry can outlive its
 * deadline by a maintenance cycle; every read compares the stored deadline against the clock instead
 * of treating presence as proof. That also keeps the two mechanisms independent — the cache decides
 * when memory is reclaimed, the deadline decides when the cooldown is over, and neither has to be
 * exact for the other to be right.
 *
 * Deadlines are [System.nanoTime] offsets, not wall-clock: an NTP correction or a DST jump must not
 * be able to lengthen a cooldown to hours or end one early.
 */
class MemoryCooldowns : CooldownService {

    /**
     * key -> deadline, as a `nanoTime` reading.
     *
     * `Long` rather than a boxed duration so the common path is one lookup and one comparison.
     */
    private val deadlines = Caffeine.newBuilder()
        .maximumSize(MAX_TRACKED)
        .expireAfter(DeadlineExpiry)
        .build<Key, Long>()

    override fun trigger(subject: UUID, id: String, duration: Duration): Boolean {
        if (duration <= Duration.ZERO) return true

        val now = System.nanoTime()
        var granted = false

        deadlines.asMap().compute(Key(subject, id)) { _, existing ->
            if (existing != null && existing - now > 0) {
                existing
            } else {
                granted = true
                now + duration.inWholeNanoseconds
            }
        }

        return granted
    }

    override fun remaining(subject: UUID, id: String): Duration {
        val deadline = deadlines.getIfPresent(Key(subject, id)) ?: return Duration.ZERO
        val left = deadline - System.nanoTime()
        return if (left > 0) (left / NANOS_PER_MILLI).milliseconds else Duration.ZERO
    }

    override fun clear(subject: UUID, id: String) {
        deadlines.invalidate(Key(subject, id))
    }

    override fun clearAll(subject: UUID) {
        // No secondary index by subject: this runs on logout and on admin resets, not per action, and
        // an index would have to be maintained on every trigger to serve it. Walk the keys instead.
        deadlines.asMap().keys.removeIf { it.subject == subject }
    }

    /**
     * Composite key. A `data class` rather than a concatenated string so a check allocates one small
     * short-lived object instead of building and hashing a fresh `String` every time.
     */
    private data class Key(val subject: UUID, val id: String)

    /**
     * Each entry lives exactly as long as the cooldown it represents.
     *
     * Caffeine asks in nanoseconds and the value already *is* a nanosecond deadline, so the TTL is
     * the difference. Clamped at zero because an entry written for an already-past deadline (a
     * cooldown of a millisecond, a scheduling hiccup) must not hand back a negative duration.
     */
    private object DeadlineExpiry : Expiry<Key, Long> {

        override fun expireAfterCreate(key: Key, deadline: Long, currentTime: Long): Long =
            (deadline - System.nanoTime()).coerceAtLeast(0)

        override fun expireAfterUpdate(
            key: Key,
            deadline: Long,
            currentTime: Long,
            currentDuration: Long,
        ): Long = (deadline - System.nanoTime()).coerceAtLeast(0)

        override fun expireAfterRead(
            key: Key,
            deadline: Long,
            currentTime: Long,
            currentDuration: Long,
        ): Long = currentDuration
    }

    private companion object {

        private val NANOS_PER_MILLI = TimeUnit.MILLISECONDS.toNanos(1)

        /** Bounds a join flood. Far above the number of players plausibly mid-cooldown at once. */
        private const val MAX_TRACKED = 50_000L
    }
}
