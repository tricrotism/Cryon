package com.tricrotism.cryon.common.server

import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * The network's shared directory of live game-server instances. Each game server owns and heartbeats
 * its own entry; every process (game servers and proxies alike) keeps a local replica of the whole
 * network, updated over Redis pub/sub, so queries are in-memory and non-blocking. Lives in `:common`
 * so both the Paper and Velocity loaders share one implementation.
 *
 * **Always registered** — resolve via `services.get(ServerRegistry::class)`. On a single server it
 * contains exactly one instance (this one), which is the truth rather than a degraded mode, so the
 * same query code works on one server and on ten. The SQL `Database`, when present, holds only the
 * slow-changing family catalog.
 */
interface ServerRegistry {

    /** Publish this process's instance and start it in the network. */
    fun register(instance: ServerInstance): CompletableFuture<Void>

    /** Refresh an owned instance's player count + state, resetting its liveness TTL. */
    fun heartbeat(instanceId: String, playerCount: Int, state: InstanceState): CompletableFuture<Void>

    /** Remove an owned instance immediately (graceful shutdown), rather than waiting for TTL expiry. */
    fun deregister(instanceId: String): CompletableFuture<Void>

    /** The replica entry for [instanceId], or null. */
    fun instance(instanceId: String): ServerInstance?

    /** Every known live instance across the network. */
    fun instances(): Collection<ServerInstance>

    /** Every known live instance in [family]. */
    fun family(family: String): List<ServerInstance>

    /** The least-loaded READY, non-full instance of [family], or null if none can take a player. */
    fun bestInstance(family: String): ServerInstance?

    /**
     * Atomically hold a slot on [instanceId] for [player], across every proxy, so two of them can't
     * both send a player to a near-full shard and overfill it. The reservation is short-lived and
     * self-expiring (the player is counted by the next heartbeat). Returns false if the shard is
     * unknown or already at capacity once in-flight reservations are counted.
     */
    fun tryReserve(instanceId: String, player: UUID): CompletableFuture<Boolean>

    /** Observe topology changes (proxies register/unregister backends off these). Close to stop. */
    fun onChange(listener: (ServerRegistryEvent) -> Unit): AutoCloseable

    fun close()
}

/** A change to the live topology, delivered to [ServerRegistry.onChange] listeners. */
sealed interface ServerRegistryEvent {
    data class Added(val instance: ServerInstance) : ServerRegistryEvent
    data class Updated(val instance: ServerInstance) : ServerRegistryEvent
    data class Removed(val instanceId: String, val family: String) : ServerRegistryEvent
}
