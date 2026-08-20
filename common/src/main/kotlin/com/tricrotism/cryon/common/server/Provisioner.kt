package com.tricrotism.cryon.common.server

/**
 * Which node a request should land on, expressed as a predicate rather than a name.
 *
 * The caller almost never wants *a specific node* — it wants "one with room", "the one already
 * hosting this party", "an empty one". Saying that as a predicate lets [Provisioner] answer from the
 * live registry and, when nothing matches, decide whether creating one is appropriate.
 */
fun interface NodeSelector {

    fun matches(node: Node): Boolean

    companion object {

        /** Ready and not full, the default for putting a player somewhere sensible. */
        val Available: NodeSelector = NodeSelector {
            it.state == NodeState.READY && it.playerCount < it.maxPlayers
        }

        /** Ready and empty. For work that wants a node to itself. */
        val Empty: NodeSelector = NodeSelector {
            it.state == NodeState.READY && it.playerCount == 0
        }

        /** Ready and advertising [key] = [value] in its metadata. */
        fun tagged(key: String, value: String): NodeSelector = NodeSelector {
            it.state == NodeState.READY && it.metadata[key] == value
        }
    }
}

/** What [Provisioner.provision] managed to do. */
sealed interface ProvisionResult {

    /** A node matching the selector already existed, or one was created and became ready. */
    data class Ready(val node: Node) : ProvisionResult

    /**
     * Nothing matched and nothing was created — either [ProvisionRequest.createIfMissing] was false,
     * or creation is not possible on this deployment.
     */
    data object Unavailable : ProvisionResult

    /**
     * Creation was asked for and started, but the node did not report ready inside the wait.
     *
     * Distinct from [Unavailable] on purpose: a node is probably still coming up, so the caller
     * should tell the player to try shortly rather than that nothing exists. Collapsing the two
     * turns "wait ten seconds" into "it is broken".
     */
    data object Pending : ProvisionResult

    /** The backing orchestrator refused or failed. [reason] is for the log, not for a player. */
    data class Failed(val reason: String) : ProvisionResult
}

/** What to provision, and how hard to try. */
data class ProvisionRequest(
    /** The pool to look in and, if it comes to it, to create a node of. */
    val serverId: String,
    val selector: NodeSelector = NodeSelector.Available,
    /**
     * Whether to ask the orchestrator for a new node when nothing matches.
     *
     * False makes this a pure query over the registry, which is what a "send them if there's room"
     * path wants — creating a shard because a hub happened to be full is rarely the intent.
     */
    val createIfMissing: Boolean = false,
    /** How long to wait for a newly created node to report `READY`. */
    val waitMillis: Long = DEFAULT_WAIT_MILLIS,
) {
    companion object {
        const val DEFAULT_WAIT_MILLIS = 15_000L
    }
}

/**
 * Finds a node that fits, and creates one when none does.
 *
 * **The rung that was missing.** `ServerRegistry` says which nodes exist, `PlayerRouter` sends a
 * player to one, and `AgonesLifecycle` lets a node be reclaimed — but nothing joined them up, so
 * "give this party a dungeon shard, starting one if the pool is full" had no home and every caller
 * would have had to write its own scan-then-scale loop against the registry.
 *
 * **Creation is orchestrator-dependent and honestly optional.** A [NodeAllocator] is what actually
 * knows how to make a node appear — Agones, a Kubernetes API, a fleet manager — and there is no such
 * thing on a single server or a static pool. Without one this degrades to a query: the selector is
 * matched against the live registry and [ProvisionResult.Unavailable] is the answer when nothing
 * fits, which for a static deployment is the truth rather than a degraded mode.
 *
 * Registered by the core, so `services.find<Provisioner>()` resolves wherever the registry does.
 */
interface Provisioner {

    suspend fun provision(request: ProvisionRequest): ProvisionResult

    /** Sugar for the common "is there room in this pool" query, with no creation. */
    suspend fun find(serverId: String, selector: NodeSelector = NodeSelector.Available): Node? =
        (provision(ProvisionRequest(serverId, selector)) as? ProvisionResult.Ready)?.node
}

/**
 * The seam to whatever can actually make a node exist.
 *
 * Deliberately tiny and deliberately not implemented here: the core has no business knowing about
 * Agones fleets or Kubernetes, and a deployment that scales by hand should be able to leave this
 * absent rather than stub it. Publish an implementation into the `ServiceRegistry` and [Provisioner]
 * starts being able to create; leave it out and it stays a query.
 */
interface NodeAllocator {

    /**
     * Ask for one more node of [serverId]. Answers whether the request was accepted, **not** whether
     * a node is ready — readiness is observed through the registry, because the orchestrator's idea
     * of "created" and the server's idea of "accepting players" are different events.
     */
    suspend fun allocate(serverId: String): Boolean
}
