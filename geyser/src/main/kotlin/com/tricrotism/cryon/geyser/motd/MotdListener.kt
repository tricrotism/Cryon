package com.tricrotism.cryon.geyser.motd

import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.geyser.api.toGeyserString
import org.geysermc.event.subscribe.Subscribe
import org.geysermc.geyser.api.event.connection.GeyserBedrockPingEvent

/**
 * Writes the configured [BedrockMotd] onto the Bedrock ping, and the maintenance message instead
 * while maintenance is on.
 *
 * Both states are decided here rather than in two subscribers ordered against each other, which is
 * what the proxy does: on Velocity the maintenance ping also has to overwrite the protocol number,
 * so it is a distinct piece of work. Here it is the same two strings either way, and one subscriber
 * that reads `isEnabled` first is both shorter and free of any dependence on subscriber ordering.
 */
class MotdListener(
    private val motd: BedrockMotd,
    private val maintenance: MaintenanceService,
) {
    @Subscribe
    fun onPing(event: GeyserBedrockPingEvent) {
        if (maintenance.isEnabled()) {
            event.primaryMotd(Mini.format(maintenance.message()).toGeyserString())
            return
        }
        val (primary, secondary) = motd.render() ?: return
        if (primary.isNotEmpty()) event.primaryMotd(primary)
        if (secondary.isNotEmpty()) event.secondaryMotd(secondary)
    }
}
