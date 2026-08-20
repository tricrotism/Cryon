package com.tricrotism.cryon.common.server

import com.tricrotism.cryon.common.net.Messenger
import kotlinx.coroutines.CancellationException
import java.util.*

/**
 * The one [PlayerRouter] implementation: picks the target from the [ServerRegistry] and asks the
 * proxies to move the player by broadcasting on [TransferRequest.CHANNEL]. Needs only registry +
 * messenger, so it runs unchanged on Paper (feature modules requesting transfers) and Velocity
 * (proxies also being senders). Ephemeral servers fall back to the [matchmaker] supplier, which is
 * null until Phase 2.
 *
 * Registered **only when the transport is shared**. Routing is inherently cross-process. The request
 * is consumed by a proxy in another JVM, so over the in-process transport this could only publish
 * into a void and report success. A single server has nowhere to route to, and `find` returning null
 * says so honestly; see the deployment-shape notes in CLAUDE.md.
 */
class DefaultPlayerRouter(
    private val registry: ServerRegistry,
    private val messenger: Messenger,
    private val matchmaker: () -> Matchmaker? = { null },
) : PlayerRouter {

    override suspend fun route(player: UUID, serverId: String): RouteResult {
        // Candidates least-loaded first; reserve a slot atomically so two proxies can't overfill one.
        val candidates = registry.nodesOf(serverId)
            .filter { it.state == NodeState.READY && it.playerCount < it.maxPlayers }
            .sortedWith(compareBy({ it.playerCount }, { it.nodeId }))
            .map { it.nodeId }

        val reserved = reserveFirst(player, candidates)
        if (reserved != null) return routeToInstance(player, reserved)

        val matcher = matchmaker() ?: return RouteResult.NoInstance
        return try {
            routeToInstance(player, matcher.claim(serverId, setOf(player)).nodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RouteResult.Failed(e.message ?: "matchmaking failed")
        }
    }

    /**
     * Try each candidate in order until one accepts the reservation; null if all are full.
     *
     * Sequential on purpose: reserving is a *side effect*, so asking every candidate at once would
     * hold a slot on each of them and drop all but one, briefly making the whole pool look fuller
     * than it is to any other proxy routing at the same moment.
     */
    private suspend fun reserveFirst(player: UUID, ids: List<String>): String? =
        ids.firstOrNull { registry.tryReserve(it, player) }

    override suspend fun routeToInstance(player: UUID, nodeId: String): RouteResult {
        messenger.publish(TransferRequest.CHANNEL, TransferRequest.encode(player, nodeId))
        return RouteResult.Sent(nodeId)
    }
}
