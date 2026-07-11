package com.tricrotism.cryon.velocity.maintenance

import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.velocitypowered.api.command.SimpleCommand
import net.kyori.adventure.text.Component

/**
 * `/maintenance on|off [message]` — toggles network-wide maintenance (synced to every proxy via
 * [MaintenanceService]). Guarded by the `cryon.maintenance` permission.
 */
class MaintenanceCommand(private val maintenance: MaintenanceService) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()
        when (args.firstOrNull()?.lowercase()) {
            "on" -> {
                val message = args.drop(1).joinToString(" ").ifBlank { null }
                maintenance.set(true, message)
                source.sendMessage(Component.text("Maintenance enabled."))
            }

            "off" -> {
                maintenance.set(false)
                source.sendMessage(Component.text("Maintenance disabled."))
            }

            else -> source.sendMessage(Component.text("Usage: /maintenance <on|off> [message]"))
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> =
        if (invocation.arguments().size <= 1) listOf("on", "off") else emptyList()

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean =
        invocation.source().hasPermission("cryon.maintenance")
}
