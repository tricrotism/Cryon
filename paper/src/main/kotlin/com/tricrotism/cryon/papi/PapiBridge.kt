package com.tricrotism.cryon.papi

import com.tricrotism.cryon.paper.api.placeholder.PlaceholderProvider
import com.tricrotism.cryon.paper.api.placeholder.PlaceholderService
import org.bukkit.plugin.Plugin
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The [PlaceholderService] impl: turns each registered [PlaceholderProvider] into a [CryonExpansion]
 * registered with PlaceholderAPI, one per module namespace, and remembers which owner registered which
 * provider so `/cryon info <id>` can list them. Best-effort. When PlaceholderAPI is absent, [register]
 * still records the provider (so info stays honest) but installs no expansion, and features never
 * branch on its presence.
 *
 * The providers themselves are kept, not just their namespaces, because a namespace is not what an
 * admin needs to paste into a scoreboard config. [placeholders] asks each one what keys it answers and
 * renders them whole.
 *
 * PlaceholderAPI is a `softdepend`, so if present it has enabled before our modules register; its
 * classes are only touched on the [available] path, so this bridge loads fine without it (the same lazy
 * pattern as `SparkSupport`). Registration happens on the main thread (module enable / hot-swap), and
 * the owner map is concurrent so a `/cryon info` read never trips over it.
 */
class PapiBridge(private val plugin: Plugin, private val log: Logger) : PlaceholderService {

    private val available: Boolean = plugin.server.pluginManager.getPlugin("PlaceholderAPI") != null
    private val providers = ConcurrentHashMap<String, MutableSet<PlaceholderProvider>>()

    init {
        if (!available) log.info("PlaceholderAPI not installed; Cryon placeholder providers are inert")
    }

    override fun register(owner: String, provider: PlaceholderProvider): AutoCloseable {
        val identifier = provider.identifier
        providers.computeIfAbsent(owner) { Collections.newSetFromMap(ConcurrentHashMap()) }.add(provider)
        val untrack = AutoCloseable { providers[owner]?.remove(provider) }

        if (!available) return untrack
        val expansion = CryonExpansion(provider, plugin)
        // A throw here would surface in the calling module's onEnable and mark it FAILED. Isolate it.
        val registered = runCatching { expansion.register() }.getOrElse {
            log.warn("Failed to register the '{}' PlaceholderAPI expansion", identifier, it)
            false
        }
        if (!registered) {
            log.warn("PlaceholderAPI rejected the '{}' expansion", identifier)
            return untrack
        }
        log.info("Registered PlaceholderAPI expansion '{}'", identifier)
        return AutoCloseable {
            runCatching { expansion.unregister() }
            untrack.close()
        }
    }

    override fun identifiers(owner: String): Collection<String> =
        providers[owner]?.map { it.identifier }?.distinct()?.sorted() ?: emptyList()

    /**
     * A provider that declares nothing is listed as its bare namespace rather than dropped, since
     * "this module owns `%warps_…%` and will not say what is in it" is the useful answer and silence
     * is not. A provider whose keys throw is treated as declaring none, for the same reason
     * `ModuleManager` treats an unreadable declaration as empty: a broken accessor must not cost an
     * operator the rest of the listing.
     */
    override fun placeholders(owner: String): List<String> =
        providers[owner].orEmpty().flatMap { provider ->
            val keys = runCatching { provider.placeholders }.getOrElse {
                log.warn("Provider '{}' of module '{}' failed to list its keys", provider.identifier, owner, it)
                emptyList()
            }
            if (keys.isEmpty()) listOf("%${provider.identifier}_…%")
            else keys.map { key -> "%${provider.identifier}_$key%" }
        }.distinct().sorted()
}
