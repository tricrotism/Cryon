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
class CorePlaceholders(private val identity: InstanceIdentity) : PlaceholderProvider {

    override val identifier: String = "cryon"

    override fun onRequest(player: OfflinePlayer?, params: String): String? = when (params.lowercase()) {
        "family" -> identity.family
        "instance" -> identity.instanceId
        "mode" -> identity.mode.name.lowercase()
        "max_players" -> identity.maxPlayers.toString()
        else -> null
    }
}
