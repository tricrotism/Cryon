package com.tricrotism.cryon.common.server

import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * The network's shared directory of live game-server instances. Each game server owns and heartbeats
 * its own entry; every process (game servers and proxies alike) keeps a local replica of the whole
 * network, updated over Redis pub/sub, so queries are in-memory and non-blocking. Lives in `:common`
 * so both the Paper and Velocity loaders share one implementation.
 *
 * **Always registered**. Resolve via `services.get<ServerRegistry>()`. On a single server it
 * contains exactly one instance (this one), which is the truth rather than a degraded mode, so the
 * same query code works on one server and on ten. The SQL `Database`, when present, holds only the
 * slow-changing server catalog.
 */
interface ServerRegistry {

    /** Publish this process's node and start it in the network. */
    fun register(instance: Node): CompletableFuture<Void>

    /** Refresh an owned instance's player count + state, resetting its liveness TTL. */
    fun heartbeat(nodeId: String, playerCount: Int, state: NodeState): CompletableFuture<Void>

    /** Remove an owned instance immediately (graceful shutdown), rather than waiting for TTL expiry. */
    fun deregister(nodeId: String): CompletableFuture<Void>

    /** The replica entry for [nodeId], or null. */
    fun node(nodeId: String): Node?

    /** Every known live node across the network. */
    fun nodes(): Collection<Node>

    /** Every known live node in [serverId]. */
    fun nodesOf(serverId: String): List<Node>

    /** The least-loaded READY, non-full instance of [serverId], or null if none can take a player. */
    fun bestNode(serverId: String): Node?

    /**
     * Atomically hold a slot on [nodeId] for [player], across every proxy, so two of them can't
     * both send a player to a near-full shard and overfill it. The reservation is short-lived and
     * self-expiring (the player is counted by the next heartbeat). Returns false if the shard is
     * unknown or already at capacity once in-flight reservations are counted.
     */
    fun tryReserve(nodeId: String, player: UUID): CompletableFuture<Boolean>

    /** Observe topology changes (proxies register/unregister backends off these). Close to stop. */
    fun onChange(listener: (ServerRegistryEvent) -> Unit): AutoCloseable

    fun close()
}

/** A change to the live topology, delivered to [ServerRegistry.onChange] listeners. */
sealed interface ServerRegistryEvent {
    data class Added(val instance: Node) : ServerRegistryEvent
    data class Updated(val instance: Node) : ServerRegistryEvent
    data class Removed(val nodeId: String, val serverId: String) : ServerRegistryEvent
}
