package com.tricrotism.cryon.common.concurrent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both properties here are load-dependent and silent when broken, which is why they are worth a test
 * rather than a careful read: the coalescing only shows up as extra requests to somebody else's rate
 * limiter, and the cancellation trap only bites when a caller goes away mid-lookup.
 *
 * Deterministic on purpose. Nothing sleeps: the future is completed by hand, once every caller is
 * provably parked on it.
 *
 * The waiting is [yield], never a spin. `runBlocking` drives its children on the calling thread, so a
 * busy-wait in the body starves the very coroutines it is waiting for and the test hangs rather than
 * fails. Yielding also makes "everyone has joined" exact: a child runs without interruption until it
 * suspends, and the first suspension inside [SingleFlight.join] is after the entry is registered, so
 * a full count means a full map.
 */
class SingleFlightTest {

    @Test
    fun `concurrent callers share one lookup and all receive its value`() = runBlocking {
        val flight = SingleFlight<String, Int>()
        val starts = AtomicInteger()
        val gate = CompletableFuture<Int>()

        val joined = AtomicInteger()
        val callers = List(8) {
            async { joined.incrementAndGet(); flight.join("skin") { starts.incrementAndGet(); gate } }
        }

        // Completing early would release the entry and let a late caller start a second lookup, so
        // the coalescing has to be measured with all eight provably registered.
        while (joined.get() < 8) yield()
        gate.complete(42)

        assertEquals(List(8) { 42 }, withTimeout(5_000) { callers.awaitAll() })
        assertEquals(1, starts.get(), "eight callers must cost one lookup")
    }

    @Test
    fun `a caller giving up does not fail the lookup for everyone else`() = runBlocking {
        val flight = SingleFlight<String, Int>()
        val gate = CompletableFuture<Int>()

        val joined = AtomicInteger()
        val quitter = async { joined.incrementAndGet(); flight.join("skin") { gate } }
        val stayer = async { joined.incrementAndGet(); flight.join("skin") { gate } }

        while (joined.get() < 2) yield()
        quitter.cancel()
        gate.complete(7)

        // Awaiting the shared future directly would have cancelled it out from under this one.
        assertEquals(7, withTimeout(5_000) { stayer.await() })
        assertTrue(gate.isDone && !gate.isCancelled, "the shared lookup must survive a lost caller")
    }

    @Test
    fun `the entry is released so a later caller starts a fresh lookup`() = runBlocking {
        val flight = SingleFlight<String, Int>()
        val starts = AtomicInteger()

        assertEquals(1, flight.join("a") { starts.incrementAndGet(); CompletableFuture.completedFuture(1) })
        assertEquals(1, flight.join("a") { starts.incrementAndGet(); CompletableFuture.completedFuture(1) })

        assertEquals(2, starts.get(), "a completed lookup must not be reused as if in flight")
        assertEquals(0, flight.size, "and must not be left behind in the map")
    }

    @Test
    fun `a failed lookup propagates and still releases its entry`() = runBlocking {
        val flight = SingleFlight<String, Int>()
        val failed = CompletableFuture<Int>().apply { completeExceptionally(IllegalStateException("mojang is down")) }

        runCatching { flight.join("a") { failed } }.also {
            assertTrue(it.isFailure, "the failure reaches the caller rather than hanging it")
        }

        assertEquals(0, flight.size, "a failure must not wedge every later caller onto a dead future")
    }
}
