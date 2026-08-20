package com.tricrotism.cryon.common.net

import com.tricrotism.cryon.common.concurrent.CryonIO
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.slf4j.Logger
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException

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
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val scope = CoroutineScope(SupervisorJob() + CryonIO.dispatcher)

    init {
        pubSubConn.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) = dispatch(channel, message)
        })
        subscribe(replyChannel) { onReply(it) }
    }

    override suspend fun publish(channel: String, message: String) {
        publishConn.async().publish(channel, message).toCompletableFuture().await()
    }

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

    override suspend fun request(channel: String, message: String, timeout: Duration): String {
        val correlationId = UUID.randomUUID().toString()
        val reply = CompletableDeferred<String>()
        pending[correlationId] = reply
        try {
            publish("$channel:req", "$correlationId$SEP$replyChannel$SEP$message")
            return withTimeoutOrNull(timeout.toMillis()) { reply.await() }
                ?: throw TimeoutException("No reply on '$channel' within $timeout")
        } finally {
            pending.remove(correlationId)
        }
    }

    /**
     * Fail whatever is still waiting before the transport goes away. Nothing else will ever complete
     * these once the connection is gone, so a caller suspended on one would never resume.
     */
    override fun close() {
        scope.cancel("The messenger was closed")
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
