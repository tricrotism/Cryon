package com.tricrotism.cryon.velocity.maintenance

import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.tricrotism.cryon.velocity.api.command.*
import com.tricrotism.cryon.velocity.sendLocalized
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.ProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder

/**
 * `/maintenance`. Toggles network-wide maintenance and manages the bypass allowlist, all synced to
 * every proxy via [MaintenanceService]. Guarded by `cryon.maintenance`.
 *
 * - `on [message]` / `off`: flip maintenance.
 * - `add|remove <player>`: manage names that may join while maintenance is on (independent of the
 *   `cryon.maintenance.bypass` permission).
 * - `list`: show the allowlist.
 */
@Command("maintenance", "Network maintenance")
@Permission("cryon.maintenance")
class MaintenanceCommand(
    private val maintenance: MaintenanceService,
    private val proxy: ProxyServer,
    private val scope: CoroutineScope,
) {

    @Subcommand
    fun usage(source: CommandSource) = source.sendLocalized("cryon.velocity.maintenance.usage")

    @Subcommand("on")
    fun on(source: CommandSource) = flip(source, true, null, "enabled")

    @Subcommand("on")
    fun onWithMessage(source: CommandSource, @Greedy @Arg("message") message: String) =
        flip(source, true, message.ifBlank { null }, "enabled")

    @Subcommand("off")
    fun off(source: CommandSource) = flip(source, false, null, "disabled")

    /**
     * The toggle is acknowledged immediately and the write is launched behind it. `set` already
     * applied the new state to this proxy's own memory before it suspends, so the ack is not a
     * prediction. What is still in flight is the durable row and the broadcast to the other proxies.
     */
    private fun flip(source: CommandSource, enabled: Boolean, message: String?, key: String) {
        scope.launch { maintenance.set(enabled, message) }
        source.sendLocalized("cryon.velocity.maintenance.$key")
    }

    @Subcommand("add")
    fun add(source: CommandSource, @Arg("player", suggests = "onlinePlayers") player: String) {
        val key = if (maintenance.allow(player)) "added" else "already"
        source.sendLocalized("cryon.velocity.maintenance.allow.$key", Placeholder.unparsed("player", player))
    }

    @Subcommand("remove")
    fun remove(source: CommandSource, @Arg("player", suggests = "allowlisted") player: String) {
        val key = if (maintenance.disallow(player)) "removed" else "absent"
        source.sendLocalized("cryon.velocity.maintenance.allow.$key", Placeholder.unparsed("player", player))
    }

    @Subcommand("list")
    fun list(source: CommandSource) {
        val names = maintenance.allowlist().sorted()
        if (names.isEmpty()) {
            source.sendLocalized("cryon.velocity.maintenance.allow.empty")
        } else {
            source.sendLocalized(
                "cryon.velocity.maintenance.allow.list",
                Placeholder.unparsed("count", names.size.toString()),
                Placeholder.unparsed("players", names.joinToString(", ")),
            )
        }
    }

    /**
     * Tab-completion for `add`. Online player names.
     */
    fun onlinePlayers(): Collection<String> = proxy.allPlayers.map { it.username }

    /**
     * Tab-completion for `remove`. The current allowlist.
     */
    fun allowlisted(): Collection<String> = maintenance.allowlist().sorted()
}
