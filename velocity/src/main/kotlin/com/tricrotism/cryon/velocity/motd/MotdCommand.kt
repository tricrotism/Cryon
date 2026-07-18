package com.tricrotism.cryon.velocity.motd

import com.tricrotism.cryon.velocity.api.command.Command
import com.tricrotism.cryon.velocity.api.command.Permission
import com.tricrotism.cryon.velocity.api.command.Subcommand
import com.tricrotism.cryon.velocity.sendLocalized
import com.velocitypowered.api.command.CommandSource

/**
 * `/motd reload` — re-reads the MOTD section of the proxy `config.yml` at runtime. Guarded by
 * `cryon.motd`.
 */
@Command("motd", "MOTD control")
@Permission("cryon.motd")
class MotdCommand(private val motd: Motd) {

    @Subcommand
    fun usage(source: CommandSource) = source.sendLocalized("cryon.velocity.motd.usage")

    @Subcommand("reload")
    fun reload(source: CommandSource) {
        motd.reload()
        source.sendLocalized("cryon.velocity.motd.reloaded")
    }
}
