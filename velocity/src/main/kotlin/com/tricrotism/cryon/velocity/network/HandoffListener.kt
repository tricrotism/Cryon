package com.tricrotism.cryon.velocity.network

import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.server.HandoffCoordinator
import com.tricrotism.cryon.common.server.ServerRegistry
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import org.slf4j.Logger
import java.time.Duration

/**
 * Holds a backend switch open just long enough for the server the player is leaving to save them.
 *
 * This is the whole reason player state survives a transfer. The proxy connects the player to the new
 * backend *before* dropping the old one, so without this the new server's login — and every feature
 * load behind it — races the old server's quit handler, and the player arrives with whatever was
 * written last time. Pausing here inverts that: the old instance flushes, acknowledges, and only then
 * does the connection proceed, so the new one is guaranteed to read what the old one just wrote.
 *
 * The pause is bounded and never fatal. A timeout, a missing backend, or a server that isn't a Cryon
 * instance all let the connection through — a player who moves is strictly better than a player stuck
 * on a failed transfer, and the flush will still happen on quit.
 */
class HandoffListener(
    private val messenger: Messenger,
    private val registry: ServerRegistry,
    private val timeout: Duration,
    private val logger: Logger,
) {

    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent): EventTask? {
        if (!event.result.isAllowed) return null
        // No previous server means this is the initial connect: nothing has been loaded, so nothing
        // needs saving. Velocity server names are instance ids (BackendSynchronizer registers them).
        val from = event.previousServer?.serverInfo?.name ?: return null
        val target = event.result.server.orElse(null)?.serverInfo?.name
        if (target == null || target == from) return null
        // Only a registered instance runs a Cryon core that could answer. A statically configured
        // backend never would, and asking it anyway would stall every transfer for the full timeout.
        if (registry.instance(from) == null) return null

        val player = event.player.uniqueId
        val request = messenger
            .request(HandoffCoordinator.channel(from), player.toString(), timeout)
            .handle { _, error ->
                // Never strand the player: log the miss and let them move regardless.
                if (error != null) logger.warn("No handoff flush from {} for {}; moving anyway", from, player)
                null
            }
        return EventTask.resumeWhenComplete(request)
    }
}
