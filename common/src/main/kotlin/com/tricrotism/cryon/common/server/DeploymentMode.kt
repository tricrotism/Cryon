package com.tricrotism.cryon.common.server

/**
 * Which of the two shapes a deployment is meant to be. Declared by the operator (`network.mode`, or
 * `CRYON_NETWORK_MODE`) rather than inferred from whether Redis happens to be reachable — the whole
 * point is that "I meant to run one server" and "I meant to run a pool and my Redis URI is wrong"
 * stop looking identical at boot.
 *
 * Deliberately **thin**: the mode never silently changes behaviour, because a second code path keyed
 * on the mode is exactly what this system exists to remove. It declares intent, so the core can check
 * the deployment against it, say what it found, and be loud when the two disagree. What actually
 * changes is the *transport* under `Messenger`/`KeyValueStore`, and that is `redis.enabled`'s job.
 */
enum class DeploymentMode {

    /** One server is the whole family. State that never leaves the process is correct here. */
    SINGLE,

    /** One of N interchangeable instances of [InstanceIdentity.family], sharing players and state. */
    INSTANCED;

    companion object {
        /**
         * Parse [value] (case- and space-insensitive), or null if it is blank or unrecognised. Callers
         * pick the default themselves and say so: quietly reading a typo'd `instanced` as [SINGLE]
         * would switch off every check that exists to catch exactly that mistake.
         */
        fun parse(value: String?): DeploymentMode? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
