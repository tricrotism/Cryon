package com.tricrotism.cryon.common.currency

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

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
 * A `synchronized` block cannot be used here: these operations are futures, and holding a monitor
 * across one parks a region or Netty thread until a database answers. Instead every account keeps a
 * **chain**: a new operation is appended to the tail of the previous one and starts when it
 * finishes. Nothing blocks; the work simply queues.
 *
 * Failure does not stall the queue: the next operation runs whether its predecessor completed or
 * threw, so one bad statement cannot wedge an account for the rest of the process's life.
 */
internal class AccountLocks {

    /** account key -> the tail of that account's queue. Entries are dropped as queues drain. */
    private val tails = ConcurrentHashMap<String, CompletableFuture<*>>()

    /** Run [action] once everything already queued for [key] has finished. */
    fun <T> withLock(key: String, action: () -> CompletableFuture<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        val previous = tails.put(key, result)
        val start = previous ?: COMPLETED

        start.whenComplete { _, _ ->
            try {
                action().whenComplete { value, error ->
                    if (error != null) result.completeExceptionally(error) else result.complete(value)
                }
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        result.whenComplete { _, _ -> tails.remove(key, result) }
        return result
    }

    /**
     * Run [action] holding both accounts, for the operations that touch two at once.
     *
     * The two are always taken in a fixed order regardless of the order asked for. Without that,
     * `transfer(a, b)` and `transfer(b, a)` running together would each hold what the other is
     * queued behind and neither would ever run, a deadlock that no amount of retrying escapes,
     * because these queues have no timeout.
     */
    fun <T> withLocks(first: String, second: String, action: () -> CompletableFuture<T>): CompletableFuture<T> {
        if (first == second) return withLock(first, action)
        val (outer, inner) = if (first < second) first to second else second to first
        return withLock(outer) { withLock(inner, action) }
    }

    private companion object {
        val COMPLETED: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    }
}
