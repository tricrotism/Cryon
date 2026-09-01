package com.tricrotism.cryon.common.server

/**
 * Finds a node that fits, and creates one when none does.
 *
 * **The rung that was missing.** `ServerRegistry` says which nodes exist, `PlayerRouter` sends a
 * player to one, and `AgonesLifecycle` lets a node be reclaimed, but nothing joined them up, so
 * "give this party a dungeon shard, starting one if the pool is full" had no home and every caller
 * would have had to write its own scan-then-scale loop against the registry.
 *
 * **Creation is orchestrator-dependent and honestly optional.** A [NodeAllocator] is what actually
 * knows how to make a node appear, whether Agones, a Kubernetes API or a fleet manager, and there is no such
 * thing on a single server or a static pool. Without one this degrades to a query: the selector is
 * matched against the live registry and [ProvisionResult.Unavailable] is the answer when nothing
 * fits, which for a static deployment is the truth rather than a degraded mode.
 *
 * Registered by the core, so `services.find<Provisioner>()` resolves wherever the registry does.
 */
interface Provisioner {

    suspend fun provision(request: ProvisionRequest): ProvisionResult

    /**
     * Sugar for the common "is there room in this pool" query, with no creation.
     */
    suspend fun find(serverId: String, selector: NodeSelector = NodeSelector.Available): Node? =
        (provision(ProvisionRequest(serverId, selector)) as? ProvisionResult.Ready)?.node
}

