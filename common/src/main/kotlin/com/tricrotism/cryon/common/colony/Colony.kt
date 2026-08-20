package com.tricrotism.cryon.common.colony

/**
 * A capability that at most one node in a pool should be *running*, while every node can serve
 * requests for it.
 *
 * The distinction the whole package exists for: a scheduled world event, a market tick, an auction
 * sweep or a leaderboard rebuild must happen **once** across ten shards, not ten times — but the
 * command that reads its result can be answered anywhere. So one node is elected **queen** and does
 * the work; the rest are **drones** and route to it.
 *
 * Not to be confused with `ServiceRegistry`, which is the *in-process* seam between modules on one
 * server. This is about which **process** owns a job.
 */
data class ClusterService(
    /** Stable across restarts and identical on every node — it is what the election hashes. */
    val id: String,

    /**
     * When true, routing answers null instead of waiting for a queen that may never appear.
     *
     * For a capability only some servers in the pool run. A required service that is simply slow to
     * elect is worth waiting a few hundred milliseconds for; one that is *absent by design* would
     * otherwise make every call pay the full retry budget before failing.
     */
    val optional: Boolean = false,
)

/** What this node is doing for a service right now. */
enum class ColonyMode { Queen, Drone }

/** What the cluster currently believes about a service's queen. */
sealed interface QueenStatus {

    /** Exactly one node claims it, and every node agrees which. */
    data class Elected(val nodeId: String) : QueenStatus

    /**
     * More than one node is claiming the crown.
     *
     * Transient and self-healing — the losers demote themselves on their next tick, because every
     * node derives the same winner from the same view. Routing waits it out rather than picking,
     * since picking is how you get two nodes acting on the same answer.
     */
    data object Contested : QueenStatus

    /** Nobody claims it. Either nothing hosts the service, or an election has not settled yet. */
    data object Unclaimed : QueenStatus
}

/**
 * Which node a message for a sharded service should go to.
 *
 * Shard `0` is reserved and means **the queen** — that is how a caller says "whoever owns this",
 * without knowing who that is. Anything else selects a shard by `abs(shard) % shards.size`.
 */
fun interface ShardingStrategy {

    /**
     * Which shard this message belongs to. Called once per send, so a stateful strategy such as
     * [roundRobin] advances here rather than at construction.
     */
    fun shard(): Int

    companion object {

        /** Everything to the queen. The default, and what you want unless you know otherwise. */
        val Queen: ShardingStrategy = ShardingStrategy { 0 }

        /**
         * Pin to a shard by id, so the same subject always lands on the same node.
         *
         * The reason to prefer this over [RoundRobin] for anything stateful: a player's requests
         * reaching one node means that node can cache them. Coerced off zero because zero means the
         * queen, and a hash that happened to land there would silently reroute the whole shard.
         */
        fun byId(id: Any): ShardingStrategy = ShardingStrategy { id.hashCode().let { if (it == 0) 1 else it } }

        /**
         * Spread across shards in sequence.
         *
         * The counter is per-process, so each node balances only its own outbound traffic; the
         * cluster-wide spread is even only insofar as the senders are. Right for stateless fan-out,
         * wrong for anything a node would want to remember.
         */
        fun roundRobin(): ShardingStrategy {
            val counter = java.util.concurrent.atomic.AtomicInteger()
            return ShardingStrategy { counter.incrementAndGet().let { if (it == 0) 1 else it } }
        }
    }
}

/** Told when this node takes or loses a crown. Registered per service; never called concurrently. */
interface ColonyListener {

    /**
     * This node is now the queen for [service] and should start doing its work.
     *
     * **Load whatever state the job needs from SQL here, do not expect it handed over.** The
     * previous queen may have died rather than resigned, so there is nothing to hand over — which is
     * why a queen's durable state belongs in the database and not in its heap.
     */
    suspend fun onPromote(service: ClusterService) {}

    /** This node is no longer the queen. Stop the work; somebody else has already started it. */
    suspend fun onDemote(service: ClusterService) {}
}

/**
 * Elects one queen per service across a pool and routes messages to it.
 *
 * **The election is a hash, not a consensus protocol.** Every node writes a heartbeat advertisement
 * listing what it claims, reads the whole set, and computes the winner with rendezvous hashing:
 * `min by fnv1a(serviceId, nodeId)`. Given the same view, every node independently arrives at the
 * same answer — so there is no lock to acquire, no CAS to lose, and no leader to elect the leader.
 * A node that finds itself claiming a crown the hash says belongs elsewhere demotes on its next
 * tick, which is what makes a split brain self-healing rather than something to page about.
 *
 * The cost of that simplicity is honest and bounded: the view is only as fresh as the heartbeat
 * interval, so during a failover there is a window where the old queen is gone and the new one has
 * not noticed. Work under a crown must therefore be **idempotent or resumable** — the same rule that
 * applies to anything under `DistributedLock`, and for the same reason.
 *
 * Without Redis this runs in-process: one node, which is trivially the queen of everything, and the
 * same code path. Nothing branches on the deployment shape.
 *
 * [tick] is driven by the platform rather than a timer in here, because `:common` has no scheduler —
 * the same arrangement `CurrencyService.refreshLeaderboards` uses.
 */
interface Colony {

    /** Declare that this node hosts [service]. Call once, on enable, before the first [tick]. */
    fun register(service: ClusterService, listener: ColonyListener? = null)

    /** Whether this node currently holds the crown. Synchronous — cheap enough for a task guard. */
    fun isQueen(service: ClusterService): Boolean

    /** What the cluster believes about [service]'s queen, as of the last [tick]. */
    fun status(service: ClusterService): QueenStatus

    /** Every node hosting [service], including this one. Sorted, so it is stable across nodes. */
    fun shards(service: ClusterService): List<String>

    /**
     * The node a message for [service] should go to, or null when there is nowhere to send it.
     *
     * Retries briefly across an unsettled election rather than answering null immediately — a queen
     * that is one heartbeat away is worth waiting for, and a caller that got null would have to
     * invent its own retry anyway.
     */
    suspend fun route(service: ClusterService, strategy: ShardingStrategy = ShardingStrategy.Queen): String?

    /** Publish this node's advertisement, re-read the cluster, and promote or demote accordingly. */
    suspend fun tick()

    /** Resign every crown and drop this node's advertisement, so a successor is elected at once. */
    suspend fun shutdown()
}
