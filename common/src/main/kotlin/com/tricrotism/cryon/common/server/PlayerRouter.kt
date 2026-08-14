package com.tricrotism.cryon.common.server

import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Sends a player to a server by serverId or by instance, without the caller needing a proxy handle.
 * Selection is platform-neutral (it only reads the [ServerRegistry]), so the same implementation runs
 * on both Paper and Velocity; the actual connection is performed by whichever proxy owns the player.
 *
 * Registered **only when the transport is shared**. Resolve via `services.find<PlayerRouter>()`
 * and treat null as "there is nowhere to route to", which is the literal truth of a single-server
 * deployment. Unlike [ServerRegistry], routing cannot degrade to this process: the request is carried
 * out by a proxy in another JVM.
 */
interface PlayerRouter {

    /** Route [player] to the best node of [serverId] (matchmaking one if that server is ephemeral). */
    fun route(player: UUID, serverId: String): CompletableFuture<RouteResult>

    /** Route [player] to a specific [nodeId]. */
    fun routeToInstance(player: UUID, nodeId: String): CompletableFuture<RouteResult>
}

/** Outcome of a routing request. */
sealed interface RouteResult {
    data class Sent(val nodeId: String) : RouteResult
    data object NoInstance : RouteResult
    data class Failed(val reason: String) : RouteResult
}
