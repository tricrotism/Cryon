package com.tricrotism.cryon.velocity.motd

import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyPingEvent

/**
 * Sets the configured [Motd] as the server-list description. Runs at [PostOrder.EARLY] so the
 * maintenance listener (LATE) can override it while maintenance is on; it also skips itself then, so
 * the maintenance MOTD is never briefly built for nothing.
 */
class MotdListener(
    private val motd: Motd,
    private val maintenance: MaintenanceService,
) {
    @Subscribe(order = PostOrder.EARLY)
    fun onPing(event: ProxyPingEvent) {
        if (maintenance.isEnabled()) return
        val description = motd.render() ?: return
        event.ping = event.ping.asBuilder().description(description).build()
    }
}
