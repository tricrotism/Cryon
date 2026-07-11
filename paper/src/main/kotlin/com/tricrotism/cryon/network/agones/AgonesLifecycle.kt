package com.tricrotism.cryon.network.agones

import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.slf4j.Logger
import java.util.concurrent.TimeUnit

/**
 * Drives this server's Agones lifecycle from the game process. Marks the GameServer `Ready` once it
 * has registered, keeps it healthy with periodic pings, and mirrors the live player count into an
 * annotation so allocation/autoscaling can see load. Optionally reclaims an empty **persistent** shard
 * (requests `Shutdown` after a grace window so the fleet shrinks), guarded so it never kills the last
 * [Options.minInstances] of the family. Ephemeral matches instead call [requestShutdown] when they end.
 *
 * Registered into the `ServiceRegistry` so feature modules (a matchmaker, a match-end handler) can
 * reach [requestShutdown]. All ticks run on the async scheduler and only read a thread-safe counter.
 */
class AgonesLifecycle(
    private val agones: AgonesClient,
    private val players: () -> Int,
    private val liveInstances: () -> Int,
    private val options: Options,
    private val logger: Logger,
) {
    data class Options(
        val healthSeconds: Long,
        val shutdownWhenEmpty: Boolean,
        val emptyGraceSeconds: Long,
        val minInstances: Int,
    )

    private var health: ScheduledTask? = null
    private var emptySince: Long = 0L

    @Volatile
    private var shuttingDown = false

    fun start() {
        agones.ready().thenRun { logger.info("Agones: marked Ready") }
        val period = options.healthSeconds.coerceAtLeast(1)
        health = Schedulers.asyncTimer(period, period, TimeUnit.SECONDS) { tick() }
    }

    /** Ask Agones to remove this GameServer (match ended, or admin drain). Idempotent. */
    fun requestShutdown() {
        if (shuttingDown) return
        shuttingDown = true
        logger.info("Agones: shutdown requested")
        agones.shutdown()
    }

    fun stop() {
        health?.cancel()
        health = null
    }

    private fun tick() {
        if (shuttingDown) return
        agones.health()
        val count = players()
        agones.setAnnotation(PLAYERS_ANNOTATION, count.toString())
        if (options.shutdownWhenEmpty) reclaimIfEmpty(count)
    }

    private fun reclaimIfEmpty(count: Int) {
        if (count > 0 || liveInstances() <= options.minInstances) {
            emptySince = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (emptySince == 0L) {
            emptySince = now
            return
        }
        if (now - emptySince >= options.emptyGraceSeconds * 1000) {
            logger.info("Agones: empty for {}s with surplus capacity — reclaiming", options.emptyGraceSeconds)
            requestShutdown()
        }
    }

    private companion object {
        private const val PLAYERS_ANNOTATION = "cryon.dev/players"
    }
}
