package com.tricrotism.cryon.common.net

import java.time.Duration

/** Connection settings for the cross-server transport. [uri] e.g. `redis://:password@host:6379/0`. */
data class RedisConfig(val uri: String)

/**
 * Messaging between processes: fire-and-forget [publish]/[subscribe] plus request/response
 * ([request]/[handle]). String payloads. Encode richer data (JSON, etc.) yourself.
 *
 * Always registered in the module `ServiceRegistry`: `redis.enabled` picks [RedisMessenger], which
 * reaches every process in the network, otherwise [LocalMessenger] keeps the same contract inside
 * this process. Only the reach of a message differs, never its semantics, so callers never branch
 * on the deployment shape. A publisher always receives its own message back, on both transports.
 */
interface Messenger {

    /** Broadcast [message] on [channel] to every subscriber, this process included. */
    suspend fun publish(channel: String, message: String)

    /**
     * Receive every message on [channel]. Returns a handle to stop listening.
     *
     * **The handler is not suspending, and runs on the transport's delivery thread.** That is the
     * ordering guarantee: messages on a connection arrive in the order they were sent, and one
     * ordered thread is what preserves it. Launching each message into a scope would deliver them
     * concurrently and quietly reorder anything that depended on the sequence. So keep the handler
     * cheap — read the payload, hand it on — and where it must do I/O, `launch` into your own
     * module scope, which is an explicit choice to give up ordering rather than an accidental one.
     */
    fun subscribe(channel: String, handler: (String) -> Unit): MessengerSubscription

    /**
     * Answer requests on [channel]; whatever [responder] returns is sent back to the requester.
     *
     * Suspending, and run in the messenger's own scope rather than on the delivery thread, so
     * answering may take as long as it needs (flushing a player to SQL, say) without stalling other
     * channels. Unlike [subscribe] there is no ordering to preserve — requests carry their own
     * correlation ids — so concurrent responders are correct here.
     */
    fun handle(channel: String, responder: suspend (String) -> String): MessengerSubscription

    /**
     * Send a request on [channel] and return the first reply.
     *
     * Throws [java.util.concurrent.TimeoutException] if no reply arrives within [timeout] — a real
     * failure rather than a cancellation, so a caller's `try`/`catch` sees it and the surrounding
     * coroutine is not torn down by a peer that simply went away.
     */
    suspend fun request(channel: String, message: String, timeout: Duration): String

    fun close()
}

/** Handle to cancel a [Messenger.subscribe]/[Messenger.handle] registration. */
fun interface MessengerSubscription {
    fun unsubscribe()
}
