package com.tricrotism.cryon.common.server

import java.util.*

/**
 * Claims an instance of an ephemeral [serverId] (a minigame match) for a set of players, allocating a
 * fresh one through the orchestrator when none is free. Phase 1 ships only this seam; until a module
 * registers an implementation, [PlayerRouter.route] returns [RouteResult.NoInstance] for ephemeral
 * servers. Persistent servers never need it (they route via [ServerRegistry.bestNode]).
 */
interface Matchmaker {
    suspend fun claim(serverId: String, players: Set<UUID>): Node
}
