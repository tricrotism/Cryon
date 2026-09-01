package com.tricrotism.cryon.common.server

/**
 * A change to the live topology, delivered to [ServerRegistry.onChange] listeners.
 */
sealed interface ServerRegistryEvent {
    data class Added(val instance: Node) : ServerRegistryEvent
    data class Updated(val instance: Node) : ServerRegistryEvent
    data class Removed(val nodeId: String, val serverId: String) : ServerRegistryEvent
}
