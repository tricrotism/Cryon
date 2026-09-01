package com.tricrotism.cryon.common.colony

/**
 * Elects one queen per service across a pool and routes messages to it.
 *
 * **The election is a hash, not a consensus protocol.** Every node writes a heartbeat advertisement
 * listing what it claims, reads the whole set, and computes the winner with rendezvous hashing:
 * `min by fnv1a(serviceId, nodeId)`. Given the same view, every node independently arrives at the
 * same answer, so there is no lock to acquire, no CAS to lose, and no leader to elect the leader.
 * A node that finds itself claiming a crown the hash says belongs elsewhere demotes on its next
 * tick, which is what makes a split brain self-healing rather than something to page about.
 *
 * The cost of that simplicity is honest and bounded: the view is only as fresh as the heartbeat
 * interval, so during a failover there is a window where the old queen is gone and the new one has
 * not noticed. Work under a crown must therefore be **idempotent or resumable**, the same rule that
 * applies to anything under `DistributedLock`, and for the same reason.
 *
 * Without Redis this runs in-process: one node, which is trivially the queen of everything, and the
 * same code path. Nothing branches on the deployment shape.
 *
 * [tick] is driven by the platform rather than a timer in here, because `:common` has no scheduler,
 * the same arrangement `CurrencyService.refreshLeaderboards` uses.
 */
interface Colony {

    /**
     * Declare that this node hosts [service]. Call once, on enable, before the first [tick].
     */
    fun register(service: ClusterService, listener: ColonyListener? = null)

    /**
     * @return whether this node currently holds the crown. Synchronous, cheap enough for a task guard.
     */
    fun isQueen(service: ClusterService): Boolean

    /**
     * What the cluster believes about [service]'s queen, as of the last [tick].
     */
    fun status(service: ClusterService): QueenStatus

    /**
     * Every node hosting [service], including this one. Sorted, so it is stable across nodes.
     */
    fun shards(service: ClusterService): List<String>

    /**
     * The node a message for [service] should go to, or null when there is nowhere to send it.
     *
     * Retries briefly across an unsettled election rather than answering null immediately, a queen
     * that is one heartbeat away is worth waiting for, and a caller that got null would have to
     * invent its own retry anyway.
     */
    suspend fun route(service: ClusterService, strategy: ShardingStrategy = ShardingStrategy.Queen): String?

    /**
     * Publish this node's advertisement, re-read the cluster, and promote or demote accordingly.
     */
    suspend fun tick()

    /**
     * Resign every crown and drop this node's advertisement, so a successor is elected at once.
     */
    suspend fun shutdown()
}

