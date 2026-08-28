package com.tricrotism.cryon.papi

import com.tricrotism.cryon.common.server.NodeIdentity
import com.tricrotism.cryon.paper.api.placeholder.PlaceholderProvider
import org.bukkit.OfflinePlayer

/**
 * The built-in `%cryon_…%` namespace: this instance's network identity, so a scoreboard or tab plugin
 * can show it without a bespoke expansion. Reads only immutable [NodeIdentity] fields, so it is
 * thread-safe on whatever thread PlaceholderAPI resolves from; it never touches the Bukkit API.
 *
 * `%cryon_server%`, `%cryon_node%`, `%cryon_expect%`, `%cryon_max_players%`.
 *
 * Keys are lowercase and looked up lowercased. PlaceholderAPI passes the parameter exactly as the
 * admin typed it, and nobody types camelCase into a config, so a mixed-case key here would resolve
 * for no one and fail silently.
 */
class CorePlaceholders(identity: NodeIdentity) : PlaceholderProvider {

    override val identifier: String = "cryon"

    private val values: Map<String, String> = mapOf(
        "server" to identity.serverId,
        "node" to identity.nodeId,
        "expect" to identity.expectation.name.lowercase(),
        "max_players" to identity.maxPlayers.toString(),
    )

    override val placeholders: Collection<String> = values.keys

    override fun onRequest(player: OfflinePlayer?, params: String): String? = values[params.lowercase()]
}
