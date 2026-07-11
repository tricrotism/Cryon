package com.tricrotism.cryon.common.server

/**
 * A live snapshot of one running game-server instance in the network. Interchangeable within a
 * [family]; [address]/[port] is what a proxy dials to reach it. [lastHeartbeat] is stamped locally by
 * each node when it last saw this instance, so the registry reaper can detect a crashed one uniformly.
 */
data class ServerInstance(
    val instanceId: String,
    val family: String,
    val address: String,
    val port: Int,
    val playerCount: Int,
    val maxPlayers: Int,
    val state: InstanceState,
    val lastHeartbeat: Long,
    val metadata: Map<String, String> = emptyMap(),
)

/** Where an instance is in its lifecycle. Only [READY] instances accept routed players. */
enum class InstanceState { STARTING, READY, DRAINING, STOPPING }
