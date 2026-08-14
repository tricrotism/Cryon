package com.tricrotism.cryon.common.server

import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import com.tricrotism.cryon.common.server.HandoffCoordinator.Companion.HANDOFF_WINDOW_MILLIS
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the flushes registered through [PlayerHandoff], and answers the proxy's "flush this player
 * before I move them" request.
 *
 * It listens on a channel named after this instance ([channel]), so a request reaches exactly the one
 * server holding the player and no other has to work out whether the message is for it. That mirrors
 * the private reply channel `RedisMessenger` already keys by instance.
 *
 * Once a handoff flush succeeds the player is marked, and the quit that follows moments later skips
 * its flush: by then the player is on the new instance, and writing our now-stale copy again would
 * undo whatever they have done since. The mark expires ([HANDOFF_WINDOW_MILLIS]) rather than being
 * cleared on quit, because a transfer can *fail*: the player then stays here and keeps playing, and
 * their eventual real quit must still save.
 */
class HandoffCoordinator(
    private val nodeId: String,
    private val messenger: Messenger,
    private val logger: Logger,
) : PlayerHandoff {

    private class Registered(val stage: Int, val flush: (UUID) -> CompletableFuture<Void>)

    private val flushes = ConcurrentHashMap<String, Registered>()

    // player -> when we flushed them for a handoff.
    private val handedOff = ConcurrentHashMap<UUID, Long>()

    private var subscription: MessengerSubscription? = null

    /** Start answering handoff requests addressed to this instance. */
    fun init() {
        subscription = messenger.handle(channel(nodeId)) { payload ->
            val player = runCatching { UUID.fromString(payload) }.getOrNull()
                ?: return@handle CompletableFuture.completedFuture(REPLY)
            flush(player).thenApply {
                val now = System.currentTimeMillis()
                handedOff.values.removeIf { now - it >= HANDOFF_WINDOW_MILLIS }
                handedOff[player] = now
                REPLY
            }
        }
    }

    override fun onFlush(
        id: String,
        stage: Int,
        flush: (UUID) -> CompletableFuture<Void>,
    ): AutoCloseable {
        flushes[id] = Registered(stage, flush)
        return AutoCloseable { flushes.remove(id) }
    }

    /**
     * Write [player]'s state down through every registered flush.
     *
     * Parallel within a stage, sequential between stages: features that own their own state have no
     * ordering between them, but one that hands state back to another has to land before that other
     * one saves. See [PlayerHandoff.onFlush]. A flush that fails is logged and treated as done, and
     * a whole stage failing still lets the next one run: one broken feature must not strand the
     * player mid-transfer, and must not take the rest of their state down with it.
     */
    fun flush(player: UUID): CompletableFuture<Void> {
        val stages = flushes.entries
            .groupBy { it.value.stage }
            .toSortedMap()
        if (stages.isEmpty()) return DONE
        var chain = DONE
        for ((_, group) in stages) {
            chain = chain.thenCompose {
                val pending = group.map { (id, registered) ->
                    runCatching { registered.flush(player) }
                        .getOrElse { CompletableFuture.failedFuture(it) }
                        .exceptionally { logger.error("Flush '{}' failed for {}", id, player, it); null }
                }
                CompletableFuture.allOf(*pending.toTypedArray())
            }
        }
        return chain
    }

    /** The quit path: a no-op when we already flushed [player] to hand them over moments ago. */
    fun flushOnQuit(player: UUID): CompletableFuture<Void> {
        val flushedAt = handedOff.remove(player)
        val handedOverRecently = flushedAt != null &&
                System.currentTimeMillis() - flushedAt < HANDOFF_WINDOW_MILLIS
        return if (handedOverRecently) DONE else flush(player)
    }

    fun close() {
        subscription?.unsubscribe()
        subscription = null
        flushes.clear()
        handedOff.clear()
    }

    companion object {
        /** The request channel for the instance with [nodeId]. */
        fun channel(nodeId: String): String = "cryon:handoff:$nodeId"

        /** The acknowledgement body; the proxy only waits for *a* reply, never reads it. */
        const val REPLY = "ok"

        // How long a handoff flush suppresses the quit flush. Comfortably longer than the moment
        // between a transfer being acknowledged and the old backend dropping the player, and far
        // shorter than a session, so a failed transfer's mark cannot silently eat a real save.
        private const val HANDOFF_WINDOW_MILLIS = 15_000L

        private val DONE: CompletableFuture<Void> get() = CompletableFuture.completedFuture(null)
    }
}
