package com.tricrotism.cryon.common.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The platform-neutral dispatcher for blocking work — Redis round trips, HTTP calls, file reads.
 * **No Bukkit or Velocity API.** Paper code reaches it as `CryonDispatchers.Async`; it lives here so
 * the proxy loader and `:common`'s own infrastructure share one pool rather than each growing their
 * own.
 *
 * **Virtual threads, one per task.** Everything routed here is waiting on something external, which
 * is exactly the shape virtual threads exist for: a task parked on a socket costs a continuation on
 * the heap instead of an OS thread, so the ceiling on concurrent Redis and HTTP calls stops being a
 * pool size. Blocking is the expected behaviour on this dispatcher rather than a hazard — which is
 * precisely why it must never be used for CPU-bound work, where unbounded parallelism just thrashes.
 *
 * Worth doing only on JDK 24+, and the toolchain is 25: before JEP 491 a virtual thread that entered
 * a `synchronized` block pinned its carrier thread, and the JDBC and Redis clients do exactly that on
 * their hot paths.
 *
 * SQL is the deliberate exception — [com.tricrotism.cryon.common.data.SqlDatabase] keeps its own
 * pool sized to the connection pool, because there the scarce resource is connections rather than
 * threads, and bounding at the executor turns a burst into a quiet queue rather than into Hikari
 * connection timeouts.
 */
object CryonIO {

    @Volatile
    private var started = false

    private val executor: ExecutorService by lazy {
        started = true
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("cryon-io-", 0).factory())
    }

    val dispatcher: CoroutineDispatcher by lazy { executor.asCoroutineDispatcher() }

    /**
     * Drain and stop the pool. Called by the loader on disable, after the modules are down.
     *
     * Does nothing if nothing ever used it, so a boot that failed before any I/O does not start a
     * thread factory purely in order to shut it down.
     */
    fun shutdown() {
        if (!started) return
        executor.shutdown()
        runCatching {
            if (!executor.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) executor.shutdownNow()
        }.onFailure {
            executor.shutdownNow()
            if (it is InterruptedException) Thread.currentThread().interrupt()
        }
    }

    private const val DRAIN_TIMEOUT_SECONDS = 5L
}
