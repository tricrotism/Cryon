package com.tricrotism.cryon.common.net

import java.time.Duration
import java.util.concurrent.CompletableFuture

/** Connection settings for the cross-server transport. [uri] e.g. `redis://:password@host:6379/0`. */
data class RedisConfig(val uri: String)

/**
 * Cross-server messaging over Redis pub/sub: fire-and-forget [publish]/[subscribe] plus
 * request/response ([request]/[handle]). String payloads — encode richer data (JSON, etc.) yourself.
 * Shared via the module `ServiceRegistry` when `redis.enabled` is set.
 */
interface Messenger {

    /** Broadcast [message] on [channel] to every subscribed server. */
    fun publish(channel: String, message: String): CompletableFuture<Void>

    /** Receive every message on [channel]. Returns a handle to stop listening. */
    fun subscribe(channel: String, handler: (String) -> Unit): MessengerSubscription

    /** Answer requests on [channel]; the returned string is sent back to the requester. */
    fun handle(channel: String, responder: (String) -> String): MessengerSubscription

    /** Send a request on [channel] and complete with the first reply, or fail after [timeout]. */
    fun request(channel: String, message: String, timeout: Duration): CompletableFuture<String>

    fun close()
}

/** Handle to cancel a [Messenger.subscribe]/[Messenger.handle] registration. */
fun interface MessengerSubscription {
    fun unsubscribe()
}
