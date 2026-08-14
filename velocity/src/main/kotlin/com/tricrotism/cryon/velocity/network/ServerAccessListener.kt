package com.tricrotism.cryon.velocity.network

import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.tricrotism.cryon.common.server.NodeState
import com.tricrotism.cryon.common.server.ServerRegistry
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.velocity.sendLocalized
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder

/**
 * Decides whether a player may enter the server they are being sent to, and denies the switch before
 * anything else acts on it.
 *
 * The proxy is the only place this can live. A transfer can be started from a feature module on a
 * game server, from `/server`, from a forced host, or from Velocity's own fallback after a kick, and
 * none of those callers can see the target's state or the player's permissions at once — `PlayerRouter`
 * runs in `:common` with no player handle at all. `ServerPreConnectEvent` is the one point every path
 * passes through, so the rules are enforced here and the callers stay unaware of them.
 *
 * Denying leaves the player where they are (Velocity keeps the current backend), which is the whole
 * point: a refused destination must not also cost them the server they were on. On the initial connect
 * there is no current backend, so a denial disconnects them, which is what a closed server means.
 *
 * Runs at [PostOrder.FIRST] so a denied move never reaches [HandoffListener] and makes the source node
 * flush a player who is not going anywhere.
 */
class ServerAccessListener(
    private val registry: ServerRegistry?,
    private val maintenance: MaintenanceService,
    private val restricted: Set<String>,
) {

    @Subscribe(order = PostOrder.FIRST)
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        if (!event.result.isAllowed) return
        val target = event.result.server.orElse(null)?.serverInfo?.name ?: return
        val player = event.player

        if (maintenance.isEnabled() &&
            !player.hasPermission(MAINTENANCE_BYPASS) &&
            !maintenance.isAllowed(player.username)
        ) {
            event.result = ServerPreConnectEvent.ServerResult.denied()
            player.sendMessage(Mini.format(maintenance.message()))
            return
        }

        val node = registry?.node(target)
        if (node != null && node.state != NodeState.READY) {
            event.result = ServerPreConnectEvent.ServerResult.denied()
            player.sendLocalized("cryon.velocity.server.unavailable", Placeholder.unparsed("server", node.serverId))
            return
        }

        val serverId = node?.serverId ?: target
        if (serverId.lowercase() in restricted && !player.hasPermission(ACCESS_PERMISSION + serverId.lowercase())) {
            event.result = ServerPreConnectEvent.ServerResult.denied()
            player.sendLocalized("cryon.velocity.server.no_access", Placeholder.unparsed("server", serverId))
        }
    }

    private companion object {
        private const val MAINTENANCE_BYPASS = "cryon.maintenance.bypass"
        private const val ACCESS_PERMISSION = "cryon.server."
    }
}
