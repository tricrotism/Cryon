package com.tricrotism.cryon.paper.api.placeholder

import org.bukkit.OfflinePlayer

/**
 * A module's PlaceholderAPI namespace. Registered through [PlaceholderService] (usually via
 * `PaperModule.registerPlaceholders`); the core turns each provider into a PlaceholderAPI expansion
 * whose identifier is [identifier], so a provider owning `"warps"` answers `%warps_<params>%`. Modules
 * never touch PlaceholderAPI classes — only the core does — which keeps the single PAPI dependency in
 * `:paper` and sidesteps the isolated module classloaders.
 *
 * [onRequest] is called by PlaceholderAPI, potentially frequently and potentially off the main thread,
 * so keep it cheap, non-blocking, and thread-safe: read state you already hold rather than computing it,
 * and never touch the Bukkit API off the main thread. Return null for an unrecognised [params] so
 * PlaceholderAPI falls through to its default.
 */
interface PlaceholderProvider {

    /**
     * The `%<identifier>_…%` namespace, lowercase with no spaces (PlaceholderAPI lowercases it).
     */
    val identifier: String

    /**
     * Resolve the text after `%<identifier>_` for [player] (may be offline, or null when there is no player).
     */
    fun onRequest(player: OfflinePlayer?, params: String): String?
}
