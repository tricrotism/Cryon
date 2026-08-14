package com.tricrotism.cryon.velocity.network

import com.tricrotism.cryon.common.server.Node
import com.tricrotism.cryon.common.server.NodeState
import com.tricrotism.cryon.common.server.ServerRegistry
import com.tricrotism.cryon.common.server.ServerRegistryEvent
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerInfo
import org.slf4j.Logger
import java.net.InetSocketAddress

/**
 * Keeps this proxy's registered backend list in sync with the shared [ServerRegistry]: it seeds from
 * the current replica, then registers/unregisters Velocity servers as nodes come and go, and as
 * they enter and leave [NodeState.READY] (see [ensure], which is what makes a drain hold). Every
 * proxy runs its own synchronizer against the same registry with no proxy-to-proxy coordination,
 * each converges independently. A backend disappears on a graceful deregister or when the registry
 * reaper drops a crashed node (both surface as [ServerRegistryEvent.Removed]).
 */
class BackendSynchronizer(
    private val proxy: ProxyServer,
    private val registry: ServerRegistry,
    private val logger: Logger,
) {
    private var handle: AutoCloseable? = null

    fun start() {
        registry.nodes().forEach(::ensure)
        handle = registry.onChange { event ->
            when (event) {
                is ServerRegistryEvent.Added -> ensure(event.instance)
                is ServerRegistryEvent.Updated -> ensure(event.instance)
                is ServerRegistryEvent.Removed -> remove(event.nodeId)
            }
        }
    }

    fun stop() {
        handle?.close()
        handle = null
    }

    /**
     * Register [instance] as a backend while it is [NodeState.READY], and drop it again the moment
     * it isn't.
     *
     * The state half is what makes a drain hold. `PlayerRouter` already refuses anything but READY, but
     * it is not the only way into a backend: Velocity's own `try` list, forced hosts and fallback-on-kick
     * all pick from the registered servers and know nothing about node state. A DRAINING node
     * left registered therefore keeps receiving exactly the players the drain just moved off it. The
     * same reasoning covers STARTING, which the node reporter publishes separately from READY
     * precisely so a half-loaded server is not served real players.
     */
    private fun ensure(instance: Node) {
        if (instance.state != NodeState.READY) return remove(instance.nodeId)
        if (proxy.getServer(instance.nodeId).isPresent) return
        proxy.registerServer(ServerInfo(instance.nodeId, InetSocketAddress(instance.address, instance.port)))
        logger.info("Registered backend {} at {}:{}", instance.nodeId, instance.address, instance.port)
    }

    private fun remove(nodeId: String) {
        proxy.getServer(nodeId).ifPresent { server ->
            proxy.unregisterServer(server.serverInfo)
            logger.info("Unregistered backend {}", nodeId)
        }
    }
}
