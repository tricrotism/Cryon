package com.tricrotism.cryon.common.server

import com.tricrotism.cryon.common.net.Messenger
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * [PlayerRouter] that picks the target from the [ServerRegistry] and asks the proxies to move the
 * player by broadcasting on [TransferRequest.CHANNEL]. Needs only registry + messenger, so it runs
 * unchanged on Paper (feature modules requesting transfers) and Velocity (proxies also being senders).
 * Ephemeral families fall back to the [matchmaker] supplier, which is null until Phase 2.
 */
class RedisPlayerRouter(
    private val registry: ServerRegistry,
    private val messenger: Messenger,
    private val matchmaker: () -> Matchmaker? = { null },
) : PlayerRouter {

    override fun route(player: UUID, family: String): CompletableFuture<RouteResult> {
        // Candidates least-loaded first; reserve a slot atomically so two proxies can't overfill one.
        val candidates = registry.family(family)
            .filter { it.state == InstanceState.READY && it.playerCount < it.maxPlayers }
            .sortedWith(compareBy({ it.playerCount }, { it.instanceId }))
            .map { it.instanceId }
        return reserveFirst(player, candidates, 0).thenCompose { reserved ->
            if (reserved != null) return@thenCompose routeToInstance(player, reserved)
            val matcher = matchmaker()
                ?: return@thenCompose CompletableFuture.completedFuture<RouteResult>(RouteResult.NoInstance)
            matcher.claim(family, setOf(player))
                .thenCompose { routeToInstance(player, it.instanceId) }
                .exceptionally { RouteResult.Failed(it.message ?: "matchmaking failed") }
        }
    }

    /** Try each candidate in order until one accepts the reservation; null if all are full. */
    private fun reserveFirst(player: UUID, ids: List<String>, index: Int): CompletableFuture<String?> {
        if (index >= ids.size) return CompletableFuture.completedFuture(null)
        val id = ids[index]
        return registry.tryReserve(id, player).thenCompose { accepted ->
            if (accepted) CompletableFuture.completedFuture(id) else reserveFirst(player, ids, index + 1)
        }
    }

    override fun routeToInstance(player: UUID, instanceId: String): CompletableFuture<RouteResult> =
        messenger.publish(TransferRequest.CHANNEL, TransferRequest.encode(player, instanceId))
            .thenApply<RouteResult> { RouteResult.Sent(instanceId) }
}
