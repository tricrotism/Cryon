package com.tricrotism.cryon.common.lock

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A mutual exclusion that holds **across processes**, for the critical sections a compare-and-set
 * cannot express.
 *
 * **Read this before reaching for it.** Almost nothing needs a distributed lock, and taking one where
 * a CAS would do is strictly worse: it adds two network round trips to the happy path, it can fail
 * open when the transport is unavailable, and it turns a wait-free operation into one that can queue
 * behind a dead node until a TTL lapses. The rule is about *how many keys the decision spans*:
 *
 *  - **One key, decided by its own value.** A balance, a claim, a slot, a flag. Use the atomic
 *    primitive: `CurrencyService.withdraw`, `KeyValueStore.setIfAbsent`, `delete`'s boolean,
 *    `tryHold`. These are wait-free and cannot be held open by a crash. **Do not use this instead.**
 *  - **One process, several statements.** `AccountLocks`, a coroutine `Mutex`. Nothing crosses a
 *    JVM, so nothing needs Redis.
 *  - **Several keys, or a key plus something outside the store, and the sequence must not interleave
 *    across nodes.** A migration two shards could both start, a payout that reads an inventory then
 *    writes a ledger row, rebuilding a cache from several sources. That is this.
 *
 * **It is not a correctness guarantee, and cannot be.** A lock with a TTL is a lease: if the holder
 * stalls past it, a long GC pause or a network partition, the lease expires and another node may
 * acquire while the first still believes it holds. [withLock] narrows that window as far as a lease
 * can (it renews while the body runs, and **cancels the body** the moment a renewal is refused) but
 * the window is not zero. So use it to stop concurrent work from *duplicating effort*, and keep the
 * final write itself idempotent or guarded. Never let it be the only thing standing between two nodes
 * and a double payout. That is what the compare-and-set is for.
 *
 * Every method suspends and does I/O. Call it from a module's `scope`, never from a packet handler.
 */
interface DistributedLock {

    /**
     * Run [block] holding [key] within [namespace], waiting up to [wait] to acquire it.
     *
     * Throws [LockUnavailableException] if the lock could not be taken inside [wait], a refusal
     * rather than a silent skip, so a caller has to decide what happens instead of quietly doing the
     * work unprotected. Use [tryWithLock] where "somebody else is already on it" is a normal outcome.
     *
     * [ttl] is how long the lease survives *without renewal*, and it only has to outlive a renewal
     * interval, not the whole body: the lock renews itself at half the TTL while [block] runs, so a
     * body that legitimately takes minutes is fine. Size [ttl] for how long you are willing to wait
     * after a holder dies before somebody else may proceed.
     *
     * **If a renewal is refused, [block] is cancelled.** That means the lease was lost, expired, or
     * cleared by an operator, and somebody else may already hold it. Continuing would be exactly the
     * concurrent execution the lock exists to prevent, so the body is cancelled at its next
     * suspension point and the failure surfaces here as [LockLostException].
     */
    suspend fun <T> withLock(
        namespace: String,
        key: String,
        ttl: Duration = DEFAULT_TTL,
        wait: Duration = DEFAULT_WAIT,
        block: suspend () -> T,
    ): T

    /**
     * As [withLock], but answers null instead of throwing when the lock is already held.
     *
     * The right shape for work that is *worth doing once*: a scheduled rebuild several nodes all wake
     * up for, a cleanup sweep. Losing the race means somebody else is doing it, which is a success.
     */
    suspend fun <T> tryWithLock(
        namespace: String,
        key: String,
        ttl: Duration = DEFAULT_TTL,
        wait: Duration = Duration.ZERO,
        block: suspend () -> T,
    ): T?

    companion object {
        // Long enough to survive an ordinary hiccup, short enough that a dead holder frees it soon
        val DEFAULT_TTL: Duration = 30.seconds

        // Long enough for a short section to finish ahead of us, short enough not to hang a command
        val DEFAULT_WAIT: Duration = 10.seconds
    }
}

