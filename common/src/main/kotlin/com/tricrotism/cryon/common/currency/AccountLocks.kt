package com.tricrotism.cryon.common.currency

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes operations per account, without blocking a thread to do it.
 *
 * The single guarded `UPDATE` behind a withdrawal is already atomic. The database will not let two
 * of them overdraw each other. What it cannot protect is a **sequence**: open-then-add,
 * subtract-then-read-back, or a transfer's debit-then-credit are several statements, and between any
 * two of them another operation on the same account can interleave. That is how a transfer ends up
 * reporting a balance that belongs to a different transfer, and how two concurrent deposits each
 * raise a change event claiming the other's total.
 *
 * A `synchronized` block still cannot be used here. Holding a monitor across a database round trip
 * would park a region or Netty thread until the answer came back, and a monitor cannot be held
 * across a suspension point at all. A coroutine [Mutex] is the right shape instead: a waiter
 * suspends rather than blocking, so the thread goes off and does other work while the queue drains.
 *
 * **In-process only.** These locks order this node's own callers; they say nothing about another
 * node touching the same account. That is deliberate and sufficient, because the operations they
 * guard are each individually atomic *in the database*. The lock removes local interleaving, and
 * the compare-and-set removes cross-node interleaving. Where a genuinely cross-node critical section
 * is needed, that is a `DistributedLock`, not this.
 */
internal class AccountLocks {

    private class Entry {
        val mutex = Mutex()

        /** Callers holding or queued on [mutex]. The entry is removable only at zero. */
        val users = AtomicInteger(0)
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Run [action] with [key]'s lock held. */
    suspend fun <T> withLock(key: String, action: suspend () -> T): T {
        val entry = acquire(key)
        try {
            return entry.mutex.withLock { action() }
        } finally {
            release(key, entry)
        }
    }

    /**
     * Run [action] holding both accounts, for the operations that touch two at once.
     *
     * The two are always taken in a fixed order regardless of the order asked for. Without that,
     * `transfer(a, b)` and `transfer(b, a)` running together would each hold what the other is
     * waiting on and neither would ever proceed, a deadlock no retry escapes, because these locks
     * have no timeout.
     */
    suspend fun <T> withLocks(first: String, second: String, action: suspend () -> T): T {
        if (first == second) return withLock(first, action)
        val (outer, inner) = if (first < second) first to second else second to first
        return withLock(outer) { withLock(inner, action) }
    }

    /**
     * Take a reference to [key]'s entry, creating it if needed.
     *
     * The reference count is what makes removal safe. Dropping the entry the moment a lock is
     * released would let a second caller, already holding the same `Entry` and waiting on its
     * mutex, be joined by a third that finds nothing in the map, creates a *fresh* `Entry`, and
     * proceeds concurrently with the second. Two callers would then be inside the same account's
     * critical section holding different mutexes, which is precisely the interleaving this class
     * exists to prevent. Counting means the map entry survives exactly as long as somebody needs it.
     */
    private fun acquire(key: String): Entry {
        while (true) {
            val entry = entries.computeIfAbsent(key) { Entry() }
            entry.users.incrementAndGet()
            // computeIfAbsent and a concurrent release are not atomic with respect to each other, so
            // re-read: if this entry has since been removed from the map, drop it and take the new one.
            if (entries[key] === entry) return entry
            entry.users.decrementAndGet()
        }
    }

    private fun release(key: String, entry: Entry) {
        if (entry.users.decrementAndGet() == 0) entries.remove(key, entry)
    }
}
