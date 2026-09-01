package com.tricrotism.cryon.common.server

/**
 * A live snapshot of one running node in the network. Interchangeable within a
 * [serverId]; [address]/[port] is what a proxy dials to reach it. [lastHeartbeat] is stamped locally by
 * each node when it last saw this instance, so the registry reaper can detect a crashed one uniformly.
 */
data class Node(
    val nodeId: String,
    val serverId: String,
    val address: String,
    val port: Int,
    val playerCount: Int,
    val maxPlayers: Int,
    val state: NodeState,
    val lastHeartbeat: Long,
    val metadata: Map<String, String> = emptyMap(),
)
