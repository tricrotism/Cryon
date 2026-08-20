package com.tricrotism.cryon.geyser.motd

import com.tricrotism.cryon.geyser.api.command.Command
import com.tricrotism.cryon.geyser.api.command.Permission
import com.tricrotism.cryon.geyser.api.command.Subcommand
import com.tricrotism.cryon.geyser.api.sendLocalized
import org.geysermc.geyser.api.command.CommandSource

/**
 * `/motd reload`. Re-reads the `motd.*` section of the extension `config.yml` at runtime. Guarded by
 * `cryon.motd`.
 */
@Command("motd", "Bedrock MOTD control")
@Permission("cryon.motd")
class MotdCommand(private val motd: BedrockMotd) {

    @Subcommand
    fun usage(source: CommandSource) = source.sendLocalized("cryon.geyser.motd.usage")

    @Subcommand("reload")
    fun reload(source: CommandSource) {
        motd.reload()
        source.sendLocalized("cryon.geyser.motd.reloaded")
    }
}
