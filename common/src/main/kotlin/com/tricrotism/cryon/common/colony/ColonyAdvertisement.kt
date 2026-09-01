package com.tricrotism.cryon.common.colony

/**
 * One node's advertisement: what it claims, and when it last said so.
 *
 * Encoded as a line rather than JSON because `KeyValueStore` speaks strings and this is written once
 * per heartbeat by every node. A format with no parser is one less thing between a stall and a
 * diagnosis in `redis-cli`.
 */
data class ColonyAdvertisement(
    val nodeId: String,
    val heartbeat: Long,
    val leaving: Boolean,
    /**
     * Service ids this node hosts, and whether it currently claims the crown for each.
     */
    val claims: Map<String, ColonyMode>,
) {

    fun encode(): String = buildString {
        append(nodeId).append(FIELD)
        append(heartbeat).append(FIELD)
        append(if (leaving) '1' else '0')
        for ((service, mode) in claims) {
            append(FIELD).append(service).append(PAIR).append(if (mode == ColonyMode.Queen) 'q' else 'd')
        }
    }

    companion object {
        /**
         * Built at runtime rather than written as literals: a control character in the source makes
         * git treat the file as binary and refuse to diff it. Same reason `RedisMessenger` does it.
         */
        private val FIELD = Char(1)
        private val PAIR = Char(2)

        fun decode(raw: String): ColonyAdvertisement? {
            val parts = raw.split(FIELD)
            if (parts.size < 3) return null
            val heartbeat = parts[1].toLongOrNull() ?: return null
            val claims = LinkedHashMap<String, ColonyMode>(parts.size)
            for (i in 3 until parts.size) {
                val split = parts[i].indexOf(PAIR)
                if (split <= 0) continue
                claims[parts[i].substring(0, split)] =
                    if (parts[i][split + 1] == 'q') ColonyMode.Queen else ColonyMode.Drone
            }
            return ColonyAdvertisement(parts[0], heartbeat, parts[2] == "1", claims)
        }
    }
}
