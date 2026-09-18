@file:OptIn(InternalCoroutinesApi::class)

package com.tricrotism.cryon.common.concurrent

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * The platform-neutral dispatcher for blocking work: Redis round trips, HTTP calls, file reads.
 * **No Bukkit or Velocity API.** Paper code reaches it as `CryonDispatchers.Async`; it lives here so
 * the proxy loader and `:common`'s own infrastructure share one pool rather than each growing their
 * own.
 *
 * **Virtual threads, one per task.** Everything routed here is waiting on something external, which
 * is exactly the shape virtual threads exist for: a task parked on a socket costs a continuation on
 * the heap instead of an OS thread, so the ceiling on concurrent Redis and HTTP calls stops being a
 * pool size. Blocking is the expected behaviour on this dispatcher rather than a hazard, which is
 * precisely why it must never be used for CPU-bound work, where unbounded parallelism just thrashes.
 *
 * Worth doing only on JDK 24+, and the toolchain is 25: before JEP 491 a virtual thread that entered
 * a `synchronized` block pinned its carrier thread, and the JDBC and Redis clients do exactly that on
 * their hot paths.
 *
 * SQL is the deliberate exception. [com.tricrotism.cryon.common.data.SqlDatabase] keeps its own
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

    // One platform thread, and it never runs the work itself: a fired timer hands the resume back to
    // the pool. Created with the dispatcher, but a thread only starts once something actually delays
    private val timer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread.ofPlatform().name("cryon-io-timer").daemon(true).unstarted(task)
        }
    }

    val dispatcher: CoroutineDispatcher by lazy { TimedDispatcher(executor.asCoroutineDispatcher(), timer) }

    /**
     * Drain and stop the pool. Called by the loader on disable, after the modules are down.
     *
     * Does nothing if nothing ever used it, so a boot that failed before any I/O does not start a
     * thread factory purely in order to shut it down.
     *
     * The timer goes first, so nothing new is scheduled onto the pool while it drains. Every scope is
     * cancelled before the loader gets here, so a task still queued on it is one nobody is waiting on.
     */
    fun shutdown() {
        if (!started) return
        timer.shutdownNow()
        executor.shutdown()
        runCatching {
            if (!executor.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) executor.shutdownNow()
        }.onFailure {
            executor.shutdownNow()
            if (it is InterruptedException) Thread.currentThread().interrupt()
        }
    }

    /**
     * The pool's dispatcher, plus the `delay` and `withTimeout` support it cannot give on its own.
     *
     * `asCoroutineDispatcher()` schedules a delay on its executor only when that executor is a
     * [ScheduledExecutorService], and a thread-per-task one is not. Everything else falls through to
     * `kotlinx.coroutines.DefaultExecutor`: a thread the library owns, that nothing here starts and
     * nothing here can stop.
     *
     * On a server that reloads plugins that is a leak with teeth. The thread outlives the unload still
     * holding the plugin's classloader, and each queued resume afterwards fails trying to load a class
     * out of a jar that has already been closed, forever, on a thread with no owner to log against.
     *
     * A resume goes back through this dispatcher rather than running where the timer fired it, so the
     * blocking work this pool exists for never lands on the single timer thread.
     */
    private class TimedDispatcher(
        private val delegate: CoroutineDispatcher,
        private val timer: ScheduledExecutorService,
    ) : CoroutineDispatcher(), Delay {

        override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, block)

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val scheduled = timer.schedule(
                Runnable { continuation.resume(Unit) },
                timeMillis,
                TimeUnit.MILLISECONDS,
            )

            continuation.invokeOnCancellation { scheduled.cancel(false) }
        }

        override fun invokeOnTimeout(
            timeMillis: Long,
            block: Runnable,
            context: CoroutineContext,
        ): DisposableHandle {
            val scheduled = timer.schedule(block, timeMillis, TimeUnit.MILLISECONDS)

            return DisposableHandle { scheduled.cancel(false) }
        }

        override fun toString(): String = "CryonIO"
    }

    private const val DRAIN_TIMEOUT_SECONDS = 5L
}
