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
 * namespace so `/cryon info <id>` can list them. Best-effort — when PlaceholderAPI is absent, [register]
 * still records the namespace (so info stays honest) but installs no expansion, and features never
 * branch on its presence.
 *
 * PlaceholderAPI is a `softdepend`, so if present it has enabled before our modules register; its
 * classes are only touched on the [available] path, so this bridge loads fine without it (the same lazy
 * pattern as `SparkSupport`). Registration happens on the main thread (module enable / hot-swap), and
 * the owner map is concurrent so a `/cryon info` read never trips over it.
 */
class PapiBridge(private val plugin: Plugin, private val log: Logger) : PlaceholderService {

    private val available: Boolean = plugin.server.pluginManager.getPlugin("PlaceholderAPI") != null
    private val namespaces = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        if (!available) log.info("PlaceholderAPI not installed; Cryon placeholder providers are inert")
    }

    override fun register(owner: String, provider: PlaceholderProvider): AutoCloseable {
        val identifier = provider.identifier
        namespaces.computeIfAbsent(owner) { Collections.newSetFromMap(ConcurrentHashMap()) }.add(identifier)
        val untrack = AutoCloseable { namespaces[owner]?.remove(identifier) }

        if (!available) return untrack
        val expansion = CryonExpansion(provider, plugin)
        // A throw here would surface in the calling module's onEnable and mark it FAILED — isolate it.
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
        namespaces[owner]?.sorted() ?: emptyList()
}
