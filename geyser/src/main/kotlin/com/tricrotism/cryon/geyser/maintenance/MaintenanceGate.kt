package com.tricrotism.cryon.geyser.maintenance

import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.geyser.api.toGeyserString
import org.geysermc.event.subscribe.Subscribe
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent

/**
 * Denies Bedrock logins while [MaintenanceService] is on, so a Bedrock player is refused by Geyser
 * with the maintenance message rather than being carried to the proxy and kicked there with a
 * Java-shaped disconnect screen.
 *
 * **The bypass here is the allowlist only, and that is deliberate.** On Velocity the gate is
 * `cryon.maintenance.bypass` *or* the name allowlist. `SessionLoginEvent` fires before the player
 * exists to any permission backend, so `CommandSource.hasPermission` has nothing to answer from and
 * the node cannot be evaluated at this point. The allowlist is name-based and needs no backend, so
 * it is what this checks. The permission case still works: a player Geyser lets through is a player
 * the proxy then runs `MaintenanceListener` on, which does evaluate the node. So this gate is
 * strictly the earlier, friendlier half, and the proxy remains the backstop.
 *
 * The message is a MiniMessage template rendered to a legacy string, which is what a Bedrock
 * disconnect screen understands.
 */
class MaintenanceGate(private val maintenance: MaintenanceService) : AutoCloseable {

    @Volatile
    private var reason: String = render()

    private val handle: AutoCloseable = maintenance.onChange { reason = render() }

    @Subscribe
    fun onLogin(event: SessionLoginEvent) {
        if (!maintenance.isEnabled()) return
        if (maintenance.isAllowed(event.connection().javaUsername())) return
        event.setCancelled(true, reason)
    }

    override fun close() = handle.close()

    private fun render(): String = Mini.format(maintenance.message()).toGeyserString()
}
