package com.tricrotism.cryon.network

import com.tricrotism.cryon.common.server.DeploymentMode
import com.tricrotism.cryon.common.server.InstanceIdentity
import com.tricrotism.cryon.common.server.ServerRegistry
import org.slf4j.Logger

/**
 * What this server was told to be, next to what it actually booted as — and every way the two
 * disagree. Read by `/cryon network` and shouted once at boot.
 *
 * The mismatches it looks for are the ones that are invisible until they cost you player data: a pool
 * whose members cannot reach each other, or a pool that never persists anything. Neither stops a
 * server from starting perfectly happily, which is exactly why they need saying out loud.
 */
class NetworkStatus(
    val identity: InstanceIdentity,
    val sharedTransport: Boolean,
    val persistent: Boolean,
    private val registry: () -> ServerRegistry?,
) {

    /** How far state travels: the difference between a pool and ten strangers. */
    val transport: String get() = if (sharedTransport) "redis (shared)" else "in-process"

    /** Live instances of our own family, as this process currently sees them. */
    fun familySize(): Int = registry()?.family(identity.family)?.size ?: 0

    /**
     * Every current disagreement between the declared mode and reality, in plain words. Recomputed on
     * each call, so `/cryon network` reflects the network as it stands rather than as it booted.
     */
    fun warnings(): List<String> {
        val warnings = ArrayList<String>()
        when (identity.mode) {
            DeploymentMode.INSTANCED -> {
                if (!sharedTransport) {
                    warnings += "Mode is 'instanced' but redis is off, so nothing leaves this process. " +
                            "Every instance of family '${identity.family}' will run as its own island: " +
                            "feature flags, the server registry, routing and player handoff all stop at " +
                            "this JVM, and two instances will silently disagree. Enable redis, or set " +
                            "network.mode to 'single'."
                }
                if (!persistent) {
                    warnings += "Mode is 'instanced' but database.enabled is false, so nothing is written " +
                            "down. A player moved between instances cannot carry their state, and every " +
                            "restart starts over. Enable the database."
                }
            }

            DeploymentMode.SINGLE -> {
                val live = familySize()
                if (live > 1) {
                    warnings += "Mode is 'single' but $live live instances share family " +
                            "'${identity.family}'. They are load-balancing players between servers that " +
                            "were each told they were alone. Set network.mode to 'instanced', or give " +
                            "this server its own network.family."
                }
            }
        }
        return warnings
    }

    /** Log the current state, and make any disagreement impossible to scroll past. */
    fun report(logger: Logger) {
        logger.info(
            "Cryon network: mode={} family={} instance={} transport={} database={}",
            identity.mode.name.lowercase(), identity.family, identity.instanceId, transport,
            if (persistent) "on" else "off",
        )
        val warnings = warnings()
        if (warnings.isEmpty()) return
        logger.error(RULE)
        logger.error("  CRYON DEPLOYMENT MISMATCH — this server booted, but not as configured")
        warnings.forEach { logger.error("  * {}", it) }
        logger.error(RULE)
    }

    private companion object {
        private val RULE = "=".repeat(78)
    }
}
