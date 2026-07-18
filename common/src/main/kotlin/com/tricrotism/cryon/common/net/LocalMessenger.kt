package com.tricrotism.cryon.common.net

import org.slf4j.Logger
import java.time.Duration
import java.util.*
import java.util.concurrent.*

/**
 * [Messenger] confined to this process — what a single-server deployment runs instead of
 * [RedisMessenger]. A message reaches this JVM's own subscribers and no further, which is exactly
 * right when the JVM *is* the whole family: the same publish/subscribe code then works unchanged
 * whether one server or ten are listening.
 *
 * Two details make it a faithful stand-in rather than a rough one, and both are load-bearing:
 *
 * 1. **A publisher hears its own message.** Redis pub/sub delivers a publish back to the publisher's
 *    own subscription, and callers lean on it — `SharedServerRegistry` only ever populates its
 *    replica from the echo, so a non-echoing loopback would leave the registry permanently empty.
 * 2. **Delivery happens off the caller's thread**, on one ordered daemon thread, mirroring Lettuce's
 *    ordered per-connection delivery. Dispatching inline would run handlers re-entrantly inside
 *    `publish` (and on the main server thread), so code that worked here would deadlock or reorder
 *    against real Redis — a fidelity gap in the worst direction.
 *
 * [publish]'s future completes once the message is handed to that thread, not once handlers have run
 * — the same promise Redis makes, where a publish completes on server ack rather than on delivery.
 */
class LocalMessenger(private val logger: Logger) : Messenger {

    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()
    private val instanceId = UUID.randomUUID().toString()
    private val replyChannel = "cryon:reply:$instanceId"
    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val delivery: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cryon-local-messenger").apply { isDaemon = true }
    }

    init {
        subscribe(replyChannel) { onReply(it) }
    }

    override fun publish(channel: String, message: String): CompletableFuture<Void> {
        // Rejected once closed; a publish during shutdown is a no-op rather than a failure, matching
        // a Redis connection that has already gone away.
        runCatching { delivery.execute { dispatch(channel, message) } }
        return CompletableFuture.completedFuture(null)
    }

    override fun subscribe(channel: String, handler: (String) -> Unit): MessengerSubscription {
        val list = handlers.computeIfAbsent(channel) { CopyOnWriteArrayList() }
        list.add(handler)
        return MessengerSubscription {
            list.remove(handler)
            if (list.isEmpty()) handlers.remove(channel)
        }
    }

    override fun handle(channel: String, responder: (String) -> CompletableFuture<String>): MessengerSubscription =
        subscribe("$channel:req") { raw ->
            val parts = raw.split(SEP, limit = 3)
            if (parts.size != 3) return@subscribe
            val (correlationId, replyTo, payload) = parts
            responder(payload).thenAccept { reply -> publish(replyTo, "$correlationId$SEP$reply") }
        }

    override fun request(channel: String, message: String, timeout: Duration): CompletableFuture<String> {
        val correlationId = UUID.randomUUID().toString()
        val future = CompletableFuture<String>()
        pending[correlationId] = future
        runCatching {
            delivery.schedule({
                if (pending.remove(correlationId) != null) {
                    future.completeExceptionally(TimeoutException("No reply on '$channel' within $timeout"))
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS)
        }
        publish("$channel:req", "$correlationId$SEP$replyChannel$SEP$message")
        return future
    }

    override fun close() {
        delivery.shutdownNow()
        handlers.clear()
        pending.clear()
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
