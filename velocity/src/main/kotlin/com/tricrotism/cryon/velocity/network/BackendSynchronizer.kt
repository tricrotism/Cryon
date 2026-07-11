package com.tricrotism.cryon.velocity.network

import com.tricrotism.cryon.common.server.ServerInstance
import com.tricrotism.cryon.common.server.ServerRegistry
import com.tricrotism.cryon.common.server.ServerRegistryEvent
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerInfo
import org.slf4j.Logger
import java.net.InetSocketAddress

/**
 * Keeps this proxy's registered backend list in sync with the shared [ServerRegistry]: it seeds from
 * the current replica, then registers/unregisters Velocity servers as instances come and go. Every
 * proxy runs its own synchronizer against the same registry with no proxy-to-proxy coordination —
 * each converges independently. A backend disappears on a graceful deregister or when the registry
 * reaper drops a crashed instance (both surface as [ServerRegistryEvent.Removed]).
 */
class BackendSynchronizer(
    private val proxy: ProxyServer,
    private val registry: ServerRegistry,
    private val logger: Logger,
) {
    private var handle: AutoCloseable? = null

    fun start() {
        registry.instances().forEach(::ensure)
        handle = registry.onChange { event ->
            when (event) {
                is ServerRegistryEvent.Added -> ensure(event.instance)
                is ServerRegistryEvent.Updated -> ensure(event.instance)
                is ServerRegistryEvent.Removed -> remove(event.instanceId)
            }
        }
    }

    fun stop() {
        handle?.close()
        handle = null
    }

    private fun ensure(instance: ServerInstance) {
        if (proxy.getServer(instance.instanceId).isPresent) return
        proxy.registerServer(ServerInfo(instance.instanceId, InetSocketAddress(instance.address, instance.port)))
        logger.info("Registered backend {} at {}:{}", instance.instanceId, instance.address, instance.port)
    }

    private fun remove(instanceId: String) {
        proxy.getServer(instanceId).ifPresent { server ->
            proxy.unregisterServer(server.serverInfo)
            logger.info("Unregistered backend {}", instanceId)
        }
    }
}
