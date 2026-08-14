package com.tricrotism.cryon.common.net

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.slf4j.Logger
import java.time.Duration
import java.util.*
import java.util.concurrent.*

/**
 * [Messenger] over Lettuce. One connection publishes, one subscribes. Request/response rides pub/sub:
 * a request carries a correlation id + this instance's private reply channel; [handle]rs publish the
 * response back there, and pending futures are completed by correlation id (or timed out).
 */
class RedisMessenger(config: RedisConfig, private val logger: Logger) : Messenger {

    private val client: RedisClient = RedisClient.create(config.uri)
    private val publishConn: StatefulRedisConnection<String, String> = client.connect()
    private val pubSubConn: StatefulRedisPubSubConnection<String, String> = client.connectPubSub()

    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()
    private val nodeId = UUID.randomUUID().toString()
    private val replyChannel = "cryon:reply:$nodeId"
    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val timeouts = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cryon-redis-timeout").apply { isDaemon = true }
    }

    init {
        pubSubConn.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) = dispatch(channel, message)
        })
        subscribe(replyChannel) { onReply(it) }
    }

    override fun publish(channel: String, message: String): CompletableFuture<Void> =
        publishConn.async().publish(channel, message).toCompletableFuture().thenAccept { }

    override fun subscribe(channel: String, handler: (String) -> Unit): MessengerSubscription {
        var created = false
        val list = handlers.compute(channel) { _, existing ->
            (existing ?: CopyOnWriteArrayList<(String) -> Unit>().also { created = true }).apply { add(handler) }
        }!!
        if (created) pubSubConn.async().subscribe(channel)
        return MessengerSubscription {
            list.remove(handler)
            if (list.isEmpty() && handlers.remove(channel, list)) {
                pubSubConn.async().unsubscribe(channel)
            }
        }
    }

    override fun handle(channel: String, responder: (String) -> CompletableFuture<String>): MessengerSubscription =
        subscribe("$channel:req") { raw ->
            val parts = raw.split(SEP, limit = 3)
            if (parts.size != 3) return@subscribe
            val (correlationId, replyTo, payload) = parts
            responder(payload)
                .thenAccept { reply -> publish(replyTo, "$correlationId$SEP$reply") }
                .exceptionally { logger.error("Responder on '{}' failed", channel, it); null }
        }

    override fun request(channel: String, message: String, timeout: Duration): CompletableFuture<String> {
        val correlationId = UUID.randomUUID().toString()
        val future = CompletableFuture<String>()
        pending[correlationId] = future
        timeouts.schedule({
            if (pending.remove(correlationId) != null) {
                future.completeExceptionally(TimeoutException("No reply on '$channel' within $timeout"))
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS)
        publish("$channel:req", "$correlationId$SEP$replyChannel$SEP$message")
        return future
    }

    /**
     * Fail whatever is still waiting before the transport goes away. The timeout thread is what would
     * otherwise have completed these, so dropping it silently leaves every in-flight request pending
     * forever, and a caller that parked a connection behind one never resumes.
     */
    override fun close() {
        timeouts.shutdownNow()
        failPending()
        pubSubConn.close()
        publishConn.close()
        client.shutdown()
    }

    private fun failPending() {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            entry.value.completeExceptionally(CancellationException("The messenger was closed"))
        }
    }

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
        // Built rather than written as a literal NUL, which git reads as binary and refuses to
        // diff. Same separator the other codecs use.
        private val SEP = Char(0)
    }
}
