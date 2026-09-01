package com.tricrotism.cryon.common.server

/**
 * How many nodes the operator means to run for this server. Declared (`network.expect`, or
 * `CRYON_EXPECT`) rather than inferred from whether Redis happens to be reachable, so that "I meant
 * to run one" and "I meant to run a pool and my Redis URI is wrong" stop looking identical at boot.
 *
 * Deliberately **inert**: nothing branches on this. A second code path keyed on the deployment shape
 * is exactly what the design exists to remove. It states intent so the core can check reality
 * against it, report both, and be loud when they disagree. What actually changes is the transport
 * under `Messenger`/`KeyValueStore`, which is `redis.enabled`'s job.
 */
enum class NodeExpectation {

    // This process is the whole server. State that never leaves it is correct here
    ONE_NODE,

    // One of N interchangeable nodes serving [NodeIdentity.serverId], sharing players and state
    MANY_NODES;

    companion object {
        /**
         * Parse [value] (case- and space-insensitive), or null if it is blank or unrecognised. Callers
         * pick the default themselves and say so: quietly reading a typo'd `instanced` as [SINGLE]
         * would switch off every check that exists to catch exactly that mistake.
         */
        fun parse(value: String?): NodeExpectation? {
            val text = value?.trim()?.replace('-', '_')?.takeIf { it.isNotEmpty() } ?: return null
            FORMER[text.lowercase()]?.let { return it }
            return entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
        }

        private val FORMER = mapOf("single" to ONE_NODE, "instanced" to MANY_NODES)
    }
}
