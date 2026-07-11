package com.tricrotism.cryon.common.server

import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Claims an instance of an ephemeral [family] (a minigame match) for a set of players, allocating a
 * fresh one through the orchestrator when none is free. Phase 1 ships only this seam; until a module
 * registers an implementation, [PlayerRouter.route] returns [RouteResult.NoInstance] for ephemeral
 * families. Persistent families never need it (they route via [ServerRegistry.bestInstance]).
 */
interface Matchmaker {
    fun claim(family: String, players: Set<UUID>): CompletableFuture<ServerInstance>
}
