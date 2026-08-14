package com.tricrotism.cryon.network

import com.tricrotism.cryon.common.server.NodeExpectation
import com.tricrotism.cryon.common.server.NodeIdentity
import com.tricrotism.cryon.common.server.ServerRegistry
import org.slf4j.Logger

/**
 * What this server was told to be, next to what it actually booted as, and every way the two
 * disagree. Read by `/cryon network` and shouted once at boot.
 *
 * The mismatches it looks for are the ones that are invisible until they cost you player data: a pool
 * whose members cannot reach each other, or a pool that never persists anything. Neither stops a
 * server from starting perfectly happily, which is exactly why they need saying out loud.
 */
class NetworkStatus(
    val identity: NodeIdentity,
    val sharedTransport: Boolean,
    val persistent: Boolean,
    private val registry: () -> ServerRegistry?,
) {

    /** How far state travels: the difference between a pool and ten strangers. */
    val transport: String get() = if (sharedTransport) "redis (shared)" else "in-process"

    /** Live instances of our own serverId, as this process currently sees them. */
    fun nodeCount(): Int = registry()?.nodesOf(identity.serverId)?.size ?: 0

    /**
     * Every current disagreement between what was declared and what is running, in plain words. Recomputed on
     * each call, so `/cryon network` reflects the network as it stands rather than as it booted.
     */
    fun warnings(): List<String> {
        val warnings = ArrayList<String>()
        when (identity.expectation) {
            NodeExpectation.MANY_NODES -> {
                if (!sharedTransport) {
                    warnings += "network.expect is 'many-nodes' but redis is off, so nothing leaves this " +
                            "process. Every node of server '${identity.serverId}' runs as its own island: " +
                            "feature flags, the server registry, routing and player handoff all stop at " +
                            "this JVM, and two nodes will silently disagree. Enable redis, or set " +
                            "network.expect to 'one-node'."
                }
                if (!persistent) {
                    warnings += "network.expect is 'many-nodes' but database.enabled is false, so nothing " +
                            "is written down. A player moved between nodes cannot carry their state, and " +
                            "every restart starts over. Enable the database."
                }
            }

            NodeExpectation.ONE_NODE -> {
                val live = nodeCount()
                if (live > 1) {
                    warnings += "network.expect is 'one-node' but $live live nodes serve '${identity.serverId}'. " +
                            "They are load-balancing players between processes that were each told they " +
                            "were alone. Set network.expect to 'many-nodes', or give this process its own " +
                            "network.server."
                }
            }
        }
        return warnings
    }

    /** Log the current state, and make any disagreement impossible to scroll past. */
    fun report(logger: Logger) {
        logger.info(
            "Cryon network: server={} node={} expect={} transport={} database={}",
            identity.serverId, identity.nodeId, identity.expectation.name.lowercase().replace('_', '-'), transport,
            if (persistent) "on" else "off",
        )
        val warnings = warnings()
        if (warnings.isEmpty()) return
        logger.error(RULE)
        logger.error("  CRYON DEPLOYMENT MISMATCH, this server booted, but not as configured")
        warnings.forEach { logger.error("  * {}", it) }
        logger.error(RULE)
    }

    private companion object {
        private val RULE = "=".repeat(78)
    }
}
