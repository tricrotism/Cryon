package com.tricrotism.cryon.common.server

import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Sends a player to a server by family or by instance, without the caller needing a proxy handle.
 * Selection is platform-neutral (it only reads the [ServerRegistry]), so the same implementation runs
 * on both Paper and Velocity; the actual connection is performed by whichever proxy owns the player.
 * Resolve via `services.find(PlayerRouter::class)`.
 */
interface PlayerRouter {

    /** Route [player] to the best instance of [family] (matchmaking one if the family is ephemeral). */
    fun route(player: UUID, family: String): CompletableFuture<RouteResult>

    /** Route [player] to a specific [instanceId]. */
    fun routeToInstance(player: UUID, instanceId: String): CompletableFuture<RouteResult>
}

/** Outcome of a routing request. */
sealed interface RouteResult {
    data class Sent(val instanceId: String) : RouteResult
    data object NoInstance : RouteResult
    data class Failed(val reason: String) : RouteResult
}
