package com.tricrotism.cryon.common.server

import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Where a feature says how to write one player's state down, so the core can decide *when*.
 *
 * The problem it solves only exists once a server has more than one instance. A proxy moves a player
 * from instance A to instance B by connecting B first and dropping A afterwards, so B's login, and
 * whatever the feature loads there. Happens **before** A's quit handler has saved anything. A
 * feature that saves on quit is therefore always one step behind: B reads the previous save, and
 * whichever of the two writes last wins. No amount of care inside the feature fixes it, because the
 * ordering is imposed from outside.
 *
 * So the core takes the save out of the quit path. Before any transfer, the proxy asks A to flush and
 * waits for the acknowledgement; only then does B connect and read. A feature registers its flush
 * here once and stops thinking about it: the same callback is what runs on an ordinary quit and on
 * shutdown, so single-server deployments (where no transfer ever happens) exercise exactly the same
 * code with the same guarantees.
 *
 * ```kotlin
 * onFlush("balances") { uuid -> repository.save(uuid, cache[uuid]) }  // in onEnable
 * ```
 *
 * The callback runs **off the main thread** and must not touch the Bukkit API: it writes state the
 * feature already holds, it does not go and collect it. It must also be safe to call while the player
 * is still online, because during a handoff that is exactly the case. Return a future that completes
 * when the write has landed. The transfer waits on it, so a future that never completes stalls the
 * player, and one that completes early defeats the whole exercise.
 */
interface PlayerHandoff {

    /**
     * Register [flush] under [id] (a short name of what it saves, used in logs). Returns a handle
     * that unregisters it; `PaperModule.onFlush` closes that for you on disable.
     *
     * Flushes at the same [stage] run together; lower stages finish before higher ones start. Almost
     * everything belongs in the default stage, because almost every feature owns the state it writes
     * and can save it without anyone's help. [BEFORE_OWNERS] exists for the feature that does not:
     * one whose "save" is really a *hand-back* into another feature's memory, which that feature then
     * writes to disk. A battle is the case: the live HP lives in the battle's own wrappers and has to
     * land in Storage before Storage saves, and running the two together is a coin flip over whether
     * the hand-back beats the snapshot.
     */
    fun onFlush(id: String, stage: Int = DEFAULT_STAGE, flush: (UUID) -> CompletableFuture<Void>): AutoCloseable

    companion object {

        /** For a flush that hands state back to another feature, so it lands before that feature saves. */
        const val BEFORE_OWNERS: Int = -100

        /** Where a feature that owns what it writes belongs. */
        const val DEFAULT_STAGE: Int = 0
    }
}
