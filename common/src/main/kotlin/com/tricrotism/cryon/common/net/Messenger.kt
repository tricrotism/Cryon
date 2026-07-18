package com.tricrotism.cryon.common.net

import java.time.Duration
import java.util.concurrent.CompletableFuture

/** Connection settings for the cross-server transport. [uri] e.g. `redis://:password@host:6379/0`. */
data class RedisConfig(val uri: String)

/**
 * Messaging between processes: fire-and-forget [publish]/[subscribe] plus request/response
 * ([request]/[handle]). String payloads — encode richer data (JSON, etc.) yourself.
 *
 * Always registered in the module `ServiceRegistry`: `redis.enabled` picks [RedisMessenger], which
 * reaches every process in the network, otherwise [LocalMessenger] keeps the same contract inside
 * this process. Only the reach of a message differs — never its semantics — so callers never branch
 * on the deployment mode. A publisher always receives its own message back, on both transports.
 */
interface Messenger {

    /** Broadcast [message] on [channel] to every subscriber, this process included. */
    fun publish(channel: String, message: String): CompletableFuture<Void>

    /** Receive every message on [channel]. Returns a handle to stop listening. */
    fun subscribe(channel: String, handler: (String) -> Unit): MessengerSubscription

    /**
     * Answer requests on [channel]; whatever the returned future completes with is sent back to the
     * requester. The future keeps the responder off the transport's delivery thread, so answering
     * may take as long as it needs (flushing a player to SQL, say) without stalling other channels.
     */
    fun handle(channel: String, responder: (String) -> CompletableFuture<String>): MessengerSubscription

    /** Send a request on [channel] and complete with the first reply, or fail after [timeout]. */
    fun request(channel: String, message: String, timeout: Duration): CompletableFuture<String>

    fun close()
}

/** Handle to cancel a [Messenger.subscribe]/[Messenger.handle] registration. */
fun interface MessengerSubscription {
    fun unsubscribe()
}
