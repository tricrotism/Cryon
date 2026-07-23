package com.tricrotism.cryon.papi

import com.tricrotism.cryon.paper.api.placeholder.PlaceholderProvider
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin

/**
 * Adapts one [PlaceholderProvider] to a PlaceholderAPI expansion. Author and version come from the core
 * plugin; [persist] keeps the expansion registered across `/papi reload`. A provider that throws is
 * swallowed (resolves to null) so a feature bug can never break PlaceholderAPI resolution server-wide —
 * the same failure-isolation rule the module framework applies everywhere it invokes feature code.
 *
 * Referenced only from [PapiBridge] on the PlaceholderAPI-present path, so this class (and its
 * PlaceholderAPI superclass) never loads when PlaceholderAPI is absent.
 */
class CryonExpansion(
    private val provider: PlaceholderProvider,
    private val plugin: Plugin,
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = provider.identifier
    override fun getAuthor(): String = plugin.pluginMeta.authors.joinToString(", ").ifEmpty { "Cryon" }
    override fun getVersion(): String = plugin.pluginMeta.version
    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? =
        runCatching { provider.onRequest(player, params) }.getOrNull()
}
