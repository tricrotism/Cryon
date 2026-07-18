package com.tricrotism.cryon.common.server

import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Where a feature says how to write one player's state down, so the core can decide *when*.
 *
 * The problem it solves only exists once a family has more than one instance. A proxy moves a player
 * from instance A to instance B by connecting B first and dropping A afterwards, so B's login — and
 * whatever the feature loads there — happens **before** A's quit handler has saved anything. A
 * feature that saves on quit is therefore always one step behind: B reads the previous save, and
 * whichever of the two writes last wins. No amount of care inside the feature fixes it, because the
 * ordering is imposed from outside.
 *
 * So the core takes the save out of the quit path. Before any transfer, the proxy asks A to flush and
 * waits for the acknowledgement; only then does B connect and read. A feature registers its flush
 * here once and stops thinking about it: the same callback is what runs on an ordinary quit and on
 * shutdown, so single-server deployments — where no transfer ever happens — exercise exactly the same
 * code with the same guarantees.
 *
 * ```kotlin
 * onFlush("balances") { uuid -> repository.save(uuid, cache[uuid]) }  // in onEnable
 * ```
 *
 * The callback runs **off the main thread** and must not touch the Bukkit API: it writes state the
 * feature already holds, it does not go and collect it. It must also be safe to call while the player
 * is still online, because during a handoff that is exactly the case. Return a future that completes
 * when the write has landed — the transfer waits on it, so a future that never completes stalls the
 * player, and one that completes early defeats the whole exercise.
 */
interface PlayerHandoff {

    /**
     * Register [flush] under [id] (a short name of what it saves, used in logs). Returns a handle
     * that unregisters it; `PaperModule.onFlush` closes that for you on disable.
     */
    fun onFlush(id: String, flush: (UUID) -> CompletableFuture<Void>): AutoCloseable
}
