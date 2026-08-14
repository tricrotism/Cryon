package com.tricrotism.cryon.common.server

import java.util.*

/**
 * How a running process identifies itself to the network, generalizing the old single static
 * `server-name` into a [serverId] (the interchangeable pool a player may land on any member of) plus a
 * per-process [nodeId], under a declared [mode]. Env wins so a Kubernetes pod needs no baked
 * config; config and the platform's own values are the fallbacks.
 *
 * Registered into the module `ServiceRegistry` by the core, so a feature can ask who it is and how it
 * was meant to be deployed without re-reading config.
 */
data class NodeIdentity(
    val nodeId: String,
    val serverId: String,
    val address: String,
    val port: Int,
    val maxPlayers: Int,
    val expectation: NodeExpectation,
) {
    companion object {
        /**
         * Resolve this process's identity. [onUnknownExpectation] is called with the offending value when the
         * declared mode is set but unrecognized, so the caller can complain before falling back to
         * [NodeExpectation.ONE_NODE]; a blank/absent mode is the ordinary default and stays quiet.
         */
        fun resolve(
            configServerId: String?,
            configNodeId: String?,
            configAddress: String?,
            configPort: Int,
            fallbackPort: Int,
            configMaxPlayers: Int,
            fallbackMaxPlayers: Int,
            configExpectation: String? = null,
            env: (String) -> String? = System::getenv,
            onUnknownExpectation: (String) -> Unit = {},
        ): NodeIdentity {
            val serverId = firstNonBlank(env("CRYON_SERVER"), configServerId) ?: "local"
            val nodeId = firstNonBlank(env("CRYON_NODE"), env("HOSTNAME"), configNodeId)
                ?: "$serverId-${UUID.randomUUID().toString().take(8)}"
            val address = firstNonBlank(env("CRYON_NODE_ADDRESS"), configAddress) ?: "127.0.0.1"
            val port = env("CRYON_NODE_PORT")?.toIntOrNull()
                ?: configPort.takeIf { it > 0 }
                ?: fallbackPort
            val maxPlayers = configMaxPlayers.takeIf { it > 0 } ?: fallbackMaxPlayers
            val declared = firstNonBlank(env("CRYON_EXPECT"), configExpectation)
            val mode = NodeExpectation.parse(declared)
                ?: NodeExpectation.ONE_NODE.also { if (declared != null) onUnknownExpectation(declared) }
            return NodeIdentity(nodeId, serverId, address, port, maxPlayers, mode)
        }

        private fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}
