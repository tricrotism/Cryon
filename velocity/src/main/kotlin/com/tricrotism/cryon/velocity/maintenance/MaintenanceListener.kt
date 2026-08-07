package com.tricrotism.cryon.velocity.maintenance

import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.tricrotism.cryon.common.text.Mini
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.proxy.server.ServerPing
import net.kyori.adventure.text.Component

/**
 * Enforces [MaintenanceService] at the proxy edge. While maintenance is on, the server-list ping
 * reports a [pingProtocol] no client matches (so the client shows it can't join) plus the maintenance
 * MOTD, and every login is denied unless the player holds the bypass permission or is on the
 * allowlist. The ping can't identify a player, so it always shows maintenance; bypass only applies at
 * login.
 *
 * The ping runs at [PostOrder.LATE] so it wins over the MOTD listener (EARLY): under maintenance the
 * maintenance description replaces whatever MOTD was set. The message is a MiniMessage template, so
 * admins can colour/format it.
 */
class MaintenanceListener(
    private val maintenance: MaintenanceService,
    private val pingProtocol: Int,
) : AutoCloseable {

    @Volatile
    private var version: ServerPing.Version = ServerPing.Version(pingProtocol, maintenance.message())

    @Volatile
    private var description: Component = Mini.format(maintenance.message())

    private val handle: AutoCloseable = maintenance.onChange {
        version = ServerPing.Version(pingProtocol, maintenance.message())
        description = Mini.format(maintenance.message())
    }

    @Subscribe(order = PostOrder.LATE)
    fun onPing(event: ProxyPingEvent) {
        if (!maintenance.isEnabled()) return
        event.ping = event.ping.asBuilder()
            .version(version)
            .description(description)
            .build()
    }

    @Subscribe
    fun onLogin(event: LoginEvent) {
        if (!maintenance.isEnabled()) return
        val player = event.player
        if (player.hasPermission(BYPASS_PERMISSION) || maintenance.isAllowed(player.username)) return
        event.result = ResultedEvent.ComponentResult.denied(description)
    }

    override fun close() = handle.close()

    private companion object {
        private const val BYPASS_PERMISSION = "cryon.maintenance.bypass"
    }
}
