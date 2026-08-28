package com.tricrotism.cryon.common.net

import com.tricrotism.cryon.common.concurrent.CryonIO
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * [Messenger] confined to this process, what a single-server deployment runs instead of
 * [RedisMessenger]. A message reaches this JVM's own subscribers and no further, which is exactly
 * right when the JVM *is* the whole serverId: the same publish/subscribe code then works unchanged
 * whether one server or ten are listening.
 *
 * Two details make it a faithful stand-in rather than a rough one, and both are load-bearing:
 *
 * 1. **A publisher hears its own message.** Redis pub/sub delivers a publish back to the publisher's
 *    own subscription, and callers lean on it: `SharedServerRegistry` only ever populates its
 *    replica from the echo, so a non-echoing loopback would leave the registry permanently empty.
 * 2. **Delivery happens off the caller's thread**, on one ordered daemon thread, mirroring Lettuce's
 *    ordered per-connection delivery. Dispatching inline would run handlers re-entrantly inside
 *    `publish` (and on the main server thread), so code that worked here would deadlock or reorder
 *    against real Redis, a fidelity gap in the worst direction.
 *
 * [publish] returns once the message is handed to that thread, not once handlers have run, the same
 * promise Redis makes, where a publish completes on server ack rather than on delivery.
 */
class LocalMessenger(private val logger: Logger) : Messenger {

    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()
    private val nodeId = UUID.randomUUID().toString()
    private val replyChannel = "cryon:reply:$nodeId"
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val delivery: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cryon-local-messenger").apply { isDaemon = true }
    }

    /**
     * Runs [handle] responders. Canceled by [close], so no responder outlives the transport.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + CryonIO.dispatcher + CoroutineExceptionHandler { _, error ->
            logger.error("Unhandled failure in a coroutine of the in-process messenger", error)
        }
    )

    init {
        subscribe(replyChannel) { onReply(it) }
    }

    override suspend fun publish(channel: String, message: String) {
        // Rejected once closed; a publish during shutdown is a no-op rather than a failure, matching
        // a Redis connection that has already gone away.
        runCatching { delivery.execute { dispatch(channel, message) } }
    }

    override fun subscribe(channel: String, handler: (String) -> Unit): MessengerSubscription {
        val list = handlers.computeIfAbsent(channel) { CopyOnWriteArrayList() }
        list.add(handler)
        return MessengerSubscription {
            list.remove(handler)
            if (list.isEmpty()) handlers.remove(channel, list)
        }
    }

    override fun handle(channel: String, responder: suspend (String) -> String): MessengerSubscription =
        subscribe("$channel:req") { raw ->
            val parts = raw.split(SEP, limit = 3)
            if (parts.size != 3) return@subscribe
            val (correlationId, replyTo, payload) = parts
            scope.launch {
                try {
                    publish(replyTo, "$correlationId$SEP${responder(payload)}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Responder on '{}' failed", channel, e)
                }
            }
        }

    /**
     * The timeout is `withTimeoutOrNull` rather than a scheduled task, so a scheduler thread no
     * longer exists purely to fail requests, and a caller that is cancelled while waiting drops its
     * pending entry in the `finally` instead of leaving it for a timer to reap.
     */
    override suspend fun request(channel: String, message: String, timeout: Duration): String {
        val correlationId = UUID.randomUUID().toString()
        val reply = CompletableDeferred<String>()
        pending[correlationId] = reply
        try {
            publish("$channel:req", "$correlationId$SEP$replyChannel$SEP$message")
            return withTimeoutOrNull(timeout.toMillis().milliseconds) { reply.await() }
                ?: throw TimeoutException("No reply on '$channel' within $timeout")
        } finally {
            pending.remove(correlationId)
        }
    }

    /**
     * Fail what is still waiting rather than dropping it: see `RedisMessenger.close`.
     */
    override fun close() {
        scope.cancel("The messenger was closed")
        delivery.shutdownNow()
        handlers.clear()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            entry.value.completeExceptionally(CancellationException("The messenger was closed"))
        }
    }

    // One failing subscriber must not cost the others their message, nor kill the delivery thread.
    private fun dispatch(channel: String, message: String) {
        handlers[channel]?.forEach { handler ->
            runCatching { handler(message) }
                .onFailure { logger.error("Subscriber on '{}' failed", channel, it) }
        }
    }

    private fun onReply(message: String) {
        val parts = message.split(SEP, limit = 2)
        if (parts.size == 2) pending.remove(parts[0])?.complete(parts[1])
    }

    private companion object {
        /** The same envelope separator [RedisMessenger] uses, so the two encode requests alike. */
        private val SEP = Char(0)
    }
}
