package com.tricrotism.cryon.geyser.maintenance

import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.tricrotism.cryon.geyser.api.command.*
import com.tricrotism.cryon.geyser.api.sendLocalized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.geysermc.geyser.api.GeyserApi
import org.geysermc.geyser.api.command.CommandSource

/**
 * `/maintenance` on the Geyser console, the twin of the proxy command. The state it writes is the
 * same [MaintenanceService] row and the same broadcast, so a toggle made here reaches every proxy
 * and every Geyser instance. Guarded by `cryon.maintenance`.
 */
@Command("maintenance", "Network maintenance")
@Permission("cryon.maintenance")
class MaintenanceCommand(
    private val maintenance: MaintenanceService,
    private val geyser: GeyserApi,
    private val scope: CoroutineScope,
) {

    @Subcommand
    fun usage(source: CommandSource) = source.sendLocalized("cryon.geyser.maintenance.usage")

    @Subcommand("on")
    fun on(source: CommandSource) = flip(source, true, null, "enabled")

    @Subcommand("on")
    fun onWithMessage(source: CommandSource, @Greedy @Arg("message") message: String) =
        flip(source, true, message.ifBlank { null }, "enabled")

    @Subcommand("off")
    fun off(source: CommandSource) = flip(source, false, null, "disabled")

    /**
     * The toggle is acknowledged immediately and the write is launched behind it. `set` already
     * applied the new state to this process's memory before it suspends, so the ack is not a
     * prediction: what is still in flight is the durable row and the broadcast.
     */
    private fun flip(source: CommandSource, enabled: Boolean, message: String?, key: String) {
        scope.launch { maintenance.set(enabled, message) }
        source.sendLocalized("cryon.geyser.maintenance.$key")
    }

    @Subcommand("add")
    fun add(source: CommandSource, @Arg("player", suggests = "onlinePlayers") player: String) {
        val key = if (maintenance.allow(player)) "added" else "already"
        source.sendLocalized("cryon.geyser.maintenance.allow.$key", Placeholder.unparsed("player", player))
    }

    @Subcommand("remove")
    fun remove(source: CommandSource, @Arg("player", suggests = "allowlisted") player: String) {
        val key = if (maintenance.disallow(player)) "removed" else "absent"
        source.sendLocalized("cryon.geyser.maintenance.allow.$key", Placeholder.unparsed("player", player))
    }

    @Subcommand("list")
    fun list(source: CommandSource) {
        val names = maintenance.allowlist().sorted()
        if (names.isEmpty()) {
            source.sendLocalized("cryon.geyser.maintenance.allow.empty")
        } else {
            source.sendLocalized(
                "cryon.geyser.maintenance.allow.list",
                Placeholder.unparsed("count", names.size.toString()),
                Placeholder.unparsed("players", names.joinToString(", ")),
            )
        }
    }

    /**
     * Choices for `add`. The Java-side names of the connected Bedrock players.
     */
    fun onlinePlayers(): Collection<String> = geyser.onlineConnections().map { it.javaUsername() }

    /**
     * Choices for `remove`. The current allowlist.
     */
    fun allowlisted(): Collection<String> = maintenance.allowlist().sorted()
}
