package com.tricrotism.cryon.common.server

import java.util.*

/**
 * How a running process identifies itself to the network, generalizing the old single static
 * `server-name` into a [family] (the interchangeable pool, and the FeatureFlags server-scope) plus a
 * per-process [instanceId]. Env wins so a Kubernetes pod needs no baked config; config and the
 * platform's own values are the fallbacks.
 */
data class InstanceIdentity(
    val instanceId: String,
    val family: String,
    val address: String,
    val port: Int,
    val maxPlayers: Int,
) {
    companion object {
        fun resolve(
            configFamily: String?,
            configInstanceId: String?,
            configAddress: String?,
            configPort: Int,
            fallbackPort: Int,
            configMaxPlayers: Int,
            fallbackMaxPlayers: Int,
            env: (String) -> String? = System::getenv,
        ): InstanceIdentity {
            val family = firstNonBlank(env("CRYON_SERVER_FAMILY"), configFamily) ?: "local"
            val instanceId = firstNonBlank(env("CRYON_INSTANCE_ID"), env("HOSTNAME"), configInstanceId)
                ?: "$family-${UUID.randomUUID().toString().take(8)}"
            val address = firstNonBlank(env("CRYON_INSTANCE_ADDRESS"), configAddress) ?: "127.0.0.1"
            val port = env("CRYON_INSTANCE_PORT")?.toIntOrNull()
                ?: configPort.takeIf { it > 0 }
                ?: fallbackPort
            val maxPlayers = configMaxPlayers.takeIf { it > 0 } ?: fallbackMaxPlayers
            return InstanceIdentity(instanceId, family, address, port, maxPlayers)
        }

        private fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}
