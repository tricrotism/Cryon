package com.tricrotism.cryon.common.lock

import com.tricrotism.cryon.common.net.KeyValueStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/**
 * [DistributedLock] over the [KeyValueStore]'s claim primitives, so it works on Redis across the
 * network and in-process on a single server without the caller knowing which.
 *
 * Three things make it safe, and each is load-bearing:
 *
 * 1. **A per-call owner token.** The lock value is a fresh UUID, and release is
 *    [KeyValueStore.deleteIfEqual] against it, never a bare delete. A holder whose lease has lapsed
 *    therefore releases *nothing* rather than releasing whoever took it next.
 * 2. **A local mutex in front of the store.** Two coroutines on the same node contending for the same
 *    key queue on a [Mutex] instead of both polling Redis, so same-node contention costs no round
 *    trips at all and the store only ever sees one claimant per node. That is also what makes the
 *    lock reentrant-safe to *reason* about: it is not reentrant, and the mutex turns what would be a
 *    silent self-deadlock against Redis into an ordinary local wait.
 * 3. **Renewal that cancels rather than warns.** While the body runs, a watchdog pushes the lease out
 *    at half the TTL. If a renewal is refused the lease is gone, so the body is cancelled and
 *    [LockLostException] is thrown — the caller learns the work did not complete, instead of the work
 *    running on with no lock and nobody noticing.
 *
 * **Fails closed.** A store that throws while acquiring propagates rather than falling through into
 * the body: running the critical section because the coordination layer is down is the one outcome
 * worse than not running it.
 */
class StoreDistributedLock(
    private val store: KeyValueStore,
    private val logger: Logger,
) : DistributedLock {

    private class Local {
        val mutex = Mutex()
        val users = AtomicInteger(0)
    }

    private val locals = ConcurrentHashMap<String, Local>()

    override suspend fun <T> withLock(
        namespace: String,
        key: String,
        ttl: Duration,
        wait: Duration,
        block: suspend () -> T,
    ): T {
        val outcome = attempt(namespace, key, ttl, wait, block)
        if (outcome === Missed) throw LockUnavailableException(namespace, key, wait)
        @Suppress("UNCHECKED_CAST")
        return outcome as T
    }

    override suspend fun <T> tryWithLock(
        namespace: String,
        key: String,
        ttl: Duration,
        wait: Duration,
        block: suspend () -> T,
    ): T? {
        val outcome = attempt(namespace, key, ttl, wait, block)
        if (outcome === Missed) return null
        @Suppress("UNCHECKED_CAST")
        return outcome as T
    }

    /**
     * Returns the body's result, or [Missed] if the lock was never taken.
     *
     * The sentinel rather than a nullable return, because `T` may itself be nullable: a body that
     * legitimately returns null must not be indistinguishable from one that never ran.
     */
    private suspend fun <T> attempt(
        namespace: String,
        key: String,
        ttl: Duration,
        wait: Duration,
        block: suspend () -> T,
    ): Any? {
        require(ttl > Duration.ZERO) { "A lock ttl must be positive, got $ttl" }
        val full = "$namespace:$key"
        val storeKey = LOCK_PREFIX + full
        val token = UUID.randomUUID().toString()
        val local = acquireLocal(full)

        val deadline = System.nanoTime() + wait.coerceAtLeast(Duration.ZERO).inWholeNanoseconds
        try {
            val queued = withTimeoutOrNull(remaining(deadline).milliseconds) { local.mutex.lock() } != null
            if (!queued) return Missed
            try {
                if (!claim(storeKey, token, ttl, deadline)) return Missed
                try {
                    return guarded(namespace, key, storeKey, token, ttl, block)
                } finally {
                    release(storeKey, token, full)
                }
            } finally {
                local.mutex.unlock()
            }
        } finally {
            releaseLocal(full, local)
        }
    }

    /** Poll for the key until it is ours or [deadline] passes. */
    private suspend fun claim(storeKey: String, token: String, ttl: Duration, deadline: Long): Boolean {
        if (store.setIfAbsent(storeKey, token, ttl.toJavaDuration())) return true
        val left = remaining(deadline)
        if (left <= 0) return false
        return withTimeoutOrNull(left.milliseconds) {
            while (true) {
                delay(POLL_INTERVAL)
                if (store.setIfAbsent(storeKey, token, ttl.toJavaDuration())) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }

    /** Milliseconds left before [deadline]; at least 1 so a zero wait still gets one attempt. */
    private fun remaining(deadline: Long): Long =
        ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)

    /**
     * Run [block] with a renewal watchdog beside it, cancelling it if the lease is lost.
     *
     * The watchdog is a *child* of this scope and it **throws** when a renewal is refused. That one
     * choice gives the whole behavior for free: a child failing cancels its siblings, so the body is
     * torn down at its next suspension point, and `coroutineScope` rethrows the failure, so the caller
     * sees [LockLostException] rather than a body that quietly returned while unprotected. If the
     * body finishes first the `finally` cancels the watchdog, so it never outlives the section.
     */
    private suspend fun <T> guarded(
        namespace: String,
        key: String,
        storeKey: String,
        token: String,
        ttl: Duration,
        block: suspend () -> T,
    ): T = coroutineScope {
        val watchdog = launch { renew(namespace, key, storeKey, token, ttl) }
        try {
            block()
        } finally {
            watchdog.cancel()
        }
    }

    /** Push the lease out at half the TTL until canceled, or throw once it is no longer ours. */
    private suspend fun renew(
        namespace: String,
        key: String,
        storeKey: String,
        token: String,
        ttl: Duration,
    ) {
        val interval = (ttl.inWholeMilliseconds / 2).coerceAtLeast(MIN_RENEWAL_MILLIS).milliseconds
        while (true) {
            delay(interval)
            val renewed = try {
                store.refreshIfEqual(storeKey, token, ttl.toJavaDuration())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Could not renew the lease on '{}'", storeKey, e)
                continue
            }
            if (!renewed) throw LockLostException(namespace, key)
        }
    }

    private suspend fun release(storeKey: String, token: String, full: String) {
        try {
            if (!store.deleteIfEqual(storeKey, token)) {
                logger.warn("Released '{}' but the lease was no longer ours", full)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to release the lock on '{}'; it will expire on its own", full, e)
        }
    }

    /** Reference-counted so the map entry cannot be dropped while another caller is queued on it. */
    private fun acquireLocal(key: String): Local {
        while (true) {
            val local = locals.computeIfAbsent(key) { Local() }
            local.users.incrementAndGet()
            if (locals[key] === local) return local
            local.users.decrementAndGet()
        }
    }

    private fun releaseLocal(key: String, local: Local) {
        if (local.users.decrementAndGet() == 0) locals.remove(key, local)
    }

    /** Distinguishes "the block returned null" from "the lock was never taken". */
    private object Missed

    private companion object {
        const val LOCK_PREFIX = "cryon:lock:"
        val POLL_INTERVAL = 50.milliseconds

        /** Floor on the renewal interval, so a very short TTL cannot spin the watchdog. */
        const val MIN_RENEWAL_MILLIS = 100L
    }
}
