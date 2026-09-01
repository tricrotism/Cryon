package com.tricrotism.cryon.common.colony

/**
 * A capability that at most one node in a pool should be *running*, while every node can serve
 * requests for it.
 *
 * The distinction the whole package exists for: a scheduled world event, a market tick, an auction
 * sweep or a leaderboard rebuild must happen **once** across ten shards, not ten times, but the
 * command that reads its result can be answered anywhere. So one node is elected **queen** and does
 * the work; the rest are **drones** and route to it.
 *
 * Not to be confused with `ServiceRegistry`, which is the *in-process* seam between modules on one
 * server. This is about which **process** owns a job.
 */
data class ClusterService(
    /**
     * Stable across restarts and identical on every node. It is what the election hashes.
     */
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
