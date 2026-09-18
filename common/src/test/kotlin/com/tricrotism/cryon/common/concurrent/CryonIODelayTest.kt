package com.tricrotism.cryon.common.concurrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * This is a class
 */
class CryonIODelayTest {

    /**
     * One method rather than three, because [CryonIO] is a process-wide singleton built on `lazy`:
     * a test that shuts it down cannot share a JVM with one that expects it running, and JUnit does
     * not order methods by name.
     */
    @Test
    fun `delays run on the owned timer and stop with it`() {
        assertFalse(defaultExecutorRunning(), "the default executor was already up before the test ran")

        val scope = CoroutineScope(SupervisorJob() + CryonIO.dispatcher)

        assertTrue(ran(scope) { delay(20) }, "the delayed coroutine never resumed")
        assertFalse(defaultExecutorRunning(), "delay fell through to the library's own executor thread")
        assertTrue(timerRunning(), "delay did not go to the owned timer")

        assertTrue(ran(scope) { withTimeout(5_000) { delay(20) } }, "the timed coroutine never resumed")
        assertFalse(defaultExecutorRunning(), "withTimeout fell through to the library's own executor thread")

        CryonIO.shutdown()

        assertFalse(awaitTimerExit(), "the timer thread outlived shutdown, which is the leak this exists to stop")
    }

    private fun ran(scope: CoroutineScope, block: suspend () -> Unit): Boolean {
        val done = CountDownLatch(1)

        scope.launch {
            block()
            done.countDown()
        }

        return done.await(AWAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun awaitTimerExit(): Boolean {
        var waited = 0L
        while (timerRunning() && waited < SHUTDOWN_WAIT_MILLIS) {
            Thread.sleep(POLL_MILLIS)
            waited += POLL_MILLIS
        }

        return timerRunning()
    }

    private fun defaultExecutorRunning(): Boolean = threadRunning("kotlinx.coroutines.DefaultExecutor")

    private fun timerRunning(): Boolean = threadRunning("cryon-io-timer")

    private fun threadRunning(name: String): Boolean =
        Thread.getAllStackTraces().keys.any { it.isAlive && it.name == name }

    private companion object {
        const val AWAIT_SECONDS = 5L
        const val SHUTDOWN_WAIT_MILLIS = 2_000L
        const val POLL_MILLIS = 25L
    }
}
