package com.tricrotism.cryon.common.server

/**
 * Single-line encode/decode for [ServerInstance] using control-char separators — the same
 * dependency-free convention as FeatureFlags (no JSON codec ships with the project). Swap to a real
 * codec only if metadata ever grows structured.
 */
object ServerInstanceCodec {

    // Low control chars that never appear in ids/addresses/metadata, so they are collision-free.
    private val FIELD = Char(0)
    private val META = Char(1)
    private val KV = Char(2)

    fun encode(instance: ServerInstance): String {
        val meta = instance.metadata.entries.joinToString(META.toString()) { "${it.key}$KV${it.value}" }
        return listOf(
            instance.instanceId,
            instance.family,
            instance.address,
            instance.port.toString(),
            instance.playerCount.toString(),
            instance.maxPlayers.toString(),
            instance.state.name,
            instance.lastHeartbeat.toString(),
            meta,
        ).joinToString(FIELD.toString())
    }

    fun decode(line: String): ServerInstance? {
        val parts = line.split(FIELD)
        if (parts.size != 9) return null
        val port = parts[3].toIntOrNull() ?: return null
        val playerCount = parts[4].toIntOrNull() ?: return null
        val maxPlayers = parts[5].toIntOrNull() ?: return null
        val state = runCatching { InstanceState.valueOf(parts[6]) }.getOrNull() ?: return null
        val lastHeartbeat = parts[7].toLongOrNull() ?: return null
        val metadata = if (parts[8].isEmpty()) emptyMap() else parts[8].split(META).mapNotNull {
            val kv = it.split(KV, limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        return ServerInstance(
            parts[0],
            parts[1],
            parts[2],
            port,
            playerCount,
            maxPlayers,
            state,
            lastHeartbeat,
            metadata
        )
    }
}
