package com.tricrotism.cryon.papi

import com.tricrotism.cryon.common.server.InstanceIdentity
import com.tricrotism.cryon.paper.api.placeholder.PlaceholderProvider
import org.bukkit.OfflinePlayer

/**
 * The built-in `%cryon_…%` namespace: this instance's network identity, so a scoreboard or tab plugin
 * can show it without a bespoke expansion. Reads only immutable [InstanceIdentity] fields, so it is
 * thread-safe on whatever thread PlaceholderAPI resolves from — it never touches the Bukkit API.
 *
 * `%cryon_family%`, `%cryon_instance%`, `%cryon_mode%`, `%cryon_max_players%`.
 */
class CorePlaceholders(identity: InstanceIdentity) : PlaceholderProvider {

    override val identifier: String = "cryon"

    private val values: Map<String, String> = mapOf(
        "family" to identity.family,
        "instance" to identity.instanceId,
        "mode" to identity.mode.name.lowercase(),
        "max_players" to identity.maxPlayers.toString(),
    )

    override fun onRequest(player: OfflinePlayer?, params: String): String? =
        values[params] ?: values[params.lowercase()]
}
