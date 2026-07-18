package com.tricrotism.cryon.network

import com.tricrotism.cryon.common.server.InstanceIdentity
import com.tricrotism.cryon.common.server.InstanceState
import com.tricrotism.cryon.common.server.ServerInstance
import com.tricrotism.cryon.common.server.ServerRegistry
import com.tricrotism.cryon.paper.api.event.Events
import com.tricrotism.cryon.paper.api.event.Subscription
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Server
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.slf4j.Logger
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Registers this Paper server in the [ServerRegistry] and keeps its live player count fresh. The
 * count rides an [AtomicInteger] fed by join/quit handlers, so the async heartbeat reads it without
 * ever touching the Bukkit API off the main thread. Drained then deregistered on disable so proxies
 * drop the backend immediately instead of waiting for its TTL to lapse.
 *
 * Starting is two steps on purpose. [register] publishes this instance as `STARTING` early, so the
 * network knows it exists; [ready] flips it to `READY` and begins heartbeating only once the modules
 * that serve players are enabled. Collapsing the two would advertise a half-loaded server and let
 * proxies route real players into it.
 */
class InstanceReporter(
    private val registry: ServerRegistry,
    private val identity: InstanceIdentity,
    private val server: Server,
    private val heartbeat: Duration,
    private val logger: Logger,
) {
    private val playerCount = AtomicInteger(0)
    private val subscriptions = ArrayList<Subscription>()
    private var task: ScheduledTask? = null

    /** Publish this instance as STARTING and begin tracking its player count. Main thread. */
    fun register() {
        playerCount.set(server.onlinePlayers.size)
        registry.register(snapshot(InstanceState.STARTING))
            .exceptionally { logger.error("Failed to register instance {}", identity.instanceId, it); null }

        subscriptions += Events.subscribe<PlayerJoinEvent>().handler { playerCount.incrementAndGet() }
        subscriptions += Events.subscribe<PlayerQuitEvent>().handler { playerCount.decrementAndGet() }
    }

    /** Flip this instance to READY and start heartbeating. Call once modules are enabled. */
    fun ready() {
        registry.heartbeat(identity.instanceId, playerCount.get(), InstanceState.READY)
            .exceptionally { logger.error("Failed to ready instance {}", identity.instanceId, it); null }

        val seconds = heartbeat.toSeconds().coerceAtLeast(1)
        task = Schedulers.asyncTimer(seconds, seconds, TimeUnit.SECONDS) {
            registry.heartbeat(identity.instanceId, playerCount.get(), InstanceState.READY)
        }
        logger.info(
            "Instance {} (family {}) is ready, reporting to the registry every {}s",
            identity.instanceId, identity.family, seconds,
        )
    }

    /** This instance's live player count (thread-safe; safe to read off the main thread). */
    fun currentPlayers(): Int = playerCount.get()

    /** Mark this instance DRAINING so proxies stop routing new players here. */
    fun drain() {
        registry.heartbeat(identity.instanceId, playerCount.get(), InstanceState.DRAINING)
    }

    /** Stop heartbeating and remove this instance from the registry. */
    fun stop() {
        task?.cancel()
        subscriptions.forEach { it.unregister() }
        subscriptions.clear()
        runCatching { registry.deregister(identity.instanceId).orTimeout(2, TimeUnit.SECONDS).join() }
            .onFailure { logger.warn("Timed out deregistering instance {}", identity.instanceId) }
    }

    private fun snapshot(state: InstanceState): ServerInstance = ServerInstance(
        instanceId = identity.instanceId,
        family = identity.family,
        address = identity.address,
        port = identity.port,
        playerCount = playerCount.get(),
        maxPlayers = identity.maxPlayers,
        state = state,
        lastHeartbeat = System.currentTimeMillis(),
    )
}
