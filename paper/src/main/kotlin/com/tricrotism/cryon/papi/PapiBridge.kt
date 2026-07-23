package com.tricrotism.cryon.papi

import com.tricrotism.cryon.paper.api.placeholder.PlaceholderProvider
import com.tricrotism.cryon.paper.api.placeholder.PlaceholderService
import org.bukkit.plugin.Plugin
import org.slf4j.Logger

/**
 * The [PlaceholderService] impl: turns each registered [PlaceholderProvider] into a [CryonExpansion]
 * registered with PlaceholderAPI, one per module namespace. Best-effort — when PlaceholderAPI is absent,
 * [register] succeeds but installs nothing, so features never branch on its presence.
 *
 * PlaceholderAPI is a `softdepend`, so if present it has enabled before our modules register; its
 * classes are only touched on the [available] path, so this bridge loads fine without it (the same lazy
 * pattern as `SparkSupport`). Registration happens on the main thread (module enable / hot-swap), so the
 * provider map needs no locking.
 */
class PapiBridge(private val plugin: Plugin, private val log: Logger) : PlaceholderService {

    private val available: Boolean = plugin.server.pluginManager.getPlugin("PlaceholderAPI") != null

    init {
        if (!available) log.info("PlaceholderAPI not installed; Cryon placeholder providers are inert")
    }

    override fun register(provider: PlaceholderProvider): AutoCloseable {
        if (!available) return AutoCloseable {}
        val expansion = CryonExpansion(provider, plugin)

        val registered = runCatching { expansion.register() }.getOrElse {
            log.warn("Failed to register the '{}' PlaceholderAPI expansion", provider.identifier, it)
            false
        }
        if (!registered) {
            log.warn("PlaceholderAPI rejected the '{}' expansion", provider.identifier)
            return AutoCloseable {}
        }
        log.info("Registered PlaceholderAPI expansion '{}'", provider.identifier)
        return AutoCloseable { runCatching { expansion.unregister() } }
    }
}
