package com.tricrotism.cryon.common.server

import java.util.*

/**
 * How a running process identifies itself to the network, generalizing the old single static
 * `server-name` into a [family] (the interchangeable pool, and the FeatureFlags server-scope) plus a
 * per-process [instanceId], under a declared [mode]. Env wins so a Kubernetes pod needs no baked
 * config; config and the platform's own values are the fallbacks.
 *
 * Registered into the module `ServiceRegistry` by the core, so a feature can ask who it is and how it
 * was meant to be deployed without re-reading config.
 */
data class InstanceIdentity(
    val instanceId: String,
    val family: String,
    val address: String,
    val port: Int,
    val maxPlayers: Int,
    val mode: DeploymentMode,
) {
    companion object {
        /**
         * Resolve this process's identity. [onUnknownMode] is called with the offending value when the
         * declared mode is set but unrecognised, so the caller can complain before falling back to
         * [DeploymentMode.SINGLE]; a blank/absent mode is the ordinary default and stays quiet.
         */
        fun resolve(
            configFamily: String?,
            configInstanceId: String?,
            configAddress: String?,
            configPort: Int,
            fallbackPort: Int,
            configMaxPlayers: Int,
            fallbackMaxPlayers: Int,
            configMode: String? = null,
            env: (String) -> String? = System::getenv,
            onUnknownMode: (String) -> Unit = {},
        ): InstanceIdentity {
            val family = firstNonBlank(env("CRYON_SERVER_FAMILY"), configFamily) ?: "local"
            val instanceId = firstNonBlank(env("CRYON_INSTANCE_ID"), env("HOSTNAME"), configInstanceId)
                ?: "$family-${UUID.randomUUID().toString().take(8)}"
            val address = firstNonBlank(env("CRYON_INSTANCE_ADDRESS"), configAddress) ?: "127.0.0.1"
            val port = env("CRYON_INSTANCE_PORT")?.toIntOrNull()
                ?: configPort.takeIf { it > 0 }
                ?: fallbackPort
            val maxPlayers = configMaxPlayers.takeIf { it > 0 } ?: fallbackMaxPlayers
            val declared = firstNonBlank(env("CRYON_NETWORK_MODE"), configMode)
            val mode = DeploymentMode.parse(declared)
                ?: DeploymentMode.SINGLE.also { if (declared != null) onUnknownMode(declared) }
            return InstanceIdentity(instanceId, family, address, port, maxPlayers, mode)
        }

        private fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}
