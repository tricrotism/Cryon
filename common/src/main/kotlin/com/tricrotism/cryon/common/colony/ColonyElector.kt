package com.tricrotism.cryon.common.colony

import java.util.concurrent.ConcurrentHashMap

/**
 * The election itself, with no I/O in it.
 *
 * Kept separate from the transport for one reason: this is the only part that can be *wrong* in a
 * way that matters, and separating it means the whole algorithm can be reasoned about, and
 * exercised, by handing it a list of advertisements and reading back what it decided, with no Redis
 * and no clock involved.
 */
class ColonyElector(private val nodeId: String) {

    // What this node believes it is, per service. The only mutable state that outlives a tick
    private val local = ConcurrentHashMap<String, ColonyMode>()

    // The cluster's view, per service, as of the last [apply]
    private val view = ConcurrentHashMap<String, ServiceView>()

    data class ServiceView(
        val queen: String?,
        val contested: Boolean,
        val shards: List<String>,
    )

    /**
     * Outcome of a tick: services this node just took, and ones it just gave up.
     */
    data class Decisions(val promoted: Set<String>, val demoted: Set<String>)

    fun host(serviceId: String) {
        local.putIfAbsent(serviceId, ColonyMode.Drone)
    }

    fun mode(serviceId: String): ColonyMode = local[serviceId] ?: ColonyMode.Drone

    fun status(serviceId: String): QueenStatus {
        val cluster = view[serviceId] ?: return QueenStatus.Unclaimed
        if (cluster.contested) return QueenStatus.Contested
        return cluster.queen?.let(QueenStatus::Elected) ?: QueenStatus.Unclaimed
    }

    fun shards(serviceId: String): List<String> = view[serviceId]?.shards ?: emptyList()

    /**
     * What this node should be claiming right now, for the next advertisement.
     */
    fun claims(): Map<String, ColonyMode> = local.toMap()

    /**
     * Rebuild the view from [advertisements], then promote or demote this node accordingly.
     */
    fun apply(advertisements: List<ColonyAdvertisement>, leaving: Boolean): Decisions {
        refresh(advertisements)
        return decide(leaving)
    }

    private fun refresh(advertisements: List<ColonyAdvertisement>) {
        val byService = HashMap<String, MutableList<ColonyAdvertisement>>()
        for (ad in advertisements) {
            for (service in ad.claims.keys) {
                byService.getOrPut(service) { ArrayList() } += ad
            }
        }

        for ((serviceId, ads) in byService) {
            val claimants = ads.filter { it.claims[serviceId] == ColonyMode.Queen }.map { it.nodeId }
            val shards = ads.filterNot { it.leaving }.map { it.nodeId }.sorted()

            view[serviceId] = ServiceView(
                queen = elect(serviceId, claimants),
                contested = claimants.size > 1,
                shards = shards,
            )
        }

        view.keys.retainAll(byService.keys)
    }

    private fun decide(leaving: Boolean): Decisions {
        val promoted = HashSet<String>()
        val demoted = HashSet<String>()

        for (serviceId in local.keys) {
            val cluster = view[serviceId]
            val canonical = cluster?.queen
            val mine = mode(serviceId)

            when {
                // Split brain: two nodes claimed it, and the hash says the other one won. Stepping
                // down is unconditional and immediate. Both sides run this, both agree who the
                // loser is, so the contest resolves in one tick without anybody arbitrating.
                mine == ColonyMode.Queen && canonical != null && canonical != nodeId -> {
                    local[serviceId] = ColonyMode.Drone
                    demoted += serviceId
                }

                // Vacant, and the hash picks this node out of the live shards. Every other node
                // computes the same winner from the same view, so exactly one promotes.
                mine == ColonyMode.Drone &&
                        canonical == null &&
                        !leaving &&
                        cluster != null &&
                        nodeId in cluster.shards &&
                        elect(serviceId, cluster.shards) == nodeId -> {
                    local[serviceId] = ColonyMode.Queen
                    promoted += serviceId
                }

                // Shutting down with somebody left to take over: resign rather than making the pool
                // wait out a heartbeat expiry to notice.
                leaving && mine == ColonyMode.Queen && cluster != null &&
                        cluster.shards.any { it != nodeId } -> {
                    local[serviceId] = ColonyMode.Drone
                    demoted += serviceId
                }
            }
        }

        return Decisions(promoted, demoted)
    }

    /**
     * Rendezvous hashing: the winner is the candidate whose `(service, node)` hash is lowest.
     *
     * The property that makes the whole design work is that this is a **pure function of the view**,
     * so every node computes the same winner without talking to any other node. The second property
     * is that losing a node only re-elects the services that node held, a modulo over a sorted list
     * would reshuffle every service whenever the pool changed size, which is a stampede of handovers
     * for a scaling event that should have moved almost nothing.
     *
     * The node id breaks ties so two nodes cannot both believe they won a hash collision.
     */
    private fun elect(serviceId: String, candidates: List<String>): String? =
        candidates.minWithOrNull(compareBy<String> { fnv1a(serviceId, it) }.thenBy { it })

    private companion object {

        /**
         * FNV-1a over `service|node`. Chosen for being cheap, well-spread and, the part that
         * matters, **identical on every node and every JVM version**, which `String.hashCode` also
         * is but `Objects.hash` and friends are not.
         */
        fun fnv1a(a: String, b: String): Int {
            var hash = OFFSET_BASIS
            for (c in a) {
                hash = hash xor (c.code and 0xFF)
                hash *= PRIME
            }
            hash = hash xor SEPARATOR
            hash *= PRIME
            for (c in b) {
                hash = hash xor (c.code and 0xFF)
                hash *= PRIME
            }
            return hash
        }

        const val OFFSET_BASIS = -2128831035
        const val PRIME = 16777619
        const val SEPARATOR = '|'.code
    }
}

