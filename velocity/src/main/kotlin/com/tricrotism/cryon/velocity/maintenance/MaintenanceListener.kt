package com.tricrotism.cryon.velocity.maintenance

import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.proxy.server.ServerPing
import net.kyori.adventure.text.Component

/**
 * Enforces [MaintenanceService] at the proxy edge. While maintenance is on, the server-list ping
 * reports a [pingProtocol] no client matches (so the client shows it can't join) plus the maintenance
 * MOTD, and every login without the bypass permission is denied. The ping can't identify a player, so
 * it always shows maintenance; bypass only applies at login.
 */
class MaintenanceListener(
    private val maintenance: MaintenanceService,
    private val pingProtocol: Int,
) {
    @Subscribe
    fun onPing(event: ProxyPingEvent) {
        if (!maintenance.isEnabled()) return
        val text = Component.text(maintenance.message())
        event.ping = event.ping.asBuilder()
            .version(ServerPing.Version(pingProtocol, maintenance.message()))
            .description(text)
            .build()
    }

    @Subscribe
    fun onLogin(event: LoginEvent) {
        if (!maintenance.isEnabled() || event.player.hasPermission(BYPASS_PERMISSION)) return
        event.result = ResultedEvent.ComponentResult.denied(Component.text(maintenance.message()))
    }

    private companion object {
        private const val BYPASS_PERMISSION = "cryon.maintenance.bypass"
    }
}
