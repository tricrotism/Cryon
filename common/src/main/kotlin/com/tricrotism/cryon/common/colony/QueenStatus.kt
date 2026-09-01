package com.tricrotism.cryon.common.colony

/**
 * What the cluster currently believes about a service's queen.
 */
sealed interface QueenStatus {

    /** Exactly one node claims it, and every node agrees which. */
    data class Elected(val nodeId: String) : QueenStatus

    /**
     * More than one node is claiming the crown.
     *
     * Transient and self-healing, the losers demote themselves on their next tick, because every
     * node derives the same winner from the same view. Routing waits it out rather than picking,
     * since picking is how you get two nodes acting on the same answer.
     */
    data object Contested : QueenStatus

    /** Nobody claims it. Either nothing hosts the service, or an election has not settled yet. */
    data object Unclaimed : QueenStatus
}
