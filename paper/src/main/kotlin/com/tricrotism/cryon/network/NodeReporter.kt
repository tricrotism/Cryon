package com.tricrotism.cryon.network

import com.tricrotism.cryon.common.server.Node
import com.tricrotism.cryon.common.server.NodeIdentity
import com.tricrotism.cryon.common.server.NodeState
import com.tricrotism.cryon.common.server.ServerRegistry
import com.tricrotism.cryon.paper.api.event.Events
import com.tricrotism.cryon.paper.api.event.Subscription
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bukkit.Server
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.slf4j.Logger
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

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
class NodeReporter(
    private val registry: ServerRegistry,
    private val identity: NodeIdentity,
    private val server: Server,
    private val heartbeat: Duration,
    private val logger: Logger,
    private val scope: CoroutineScope,
) {

    /**
     * Extra facts this node advertises alongside its liveness, merged into every heartbeat.
     *
     * A supplier rather than a fixed map because the things worth advertising are decided after the
     * reporter exists, and because a heartbeat should carry what is true now rather than what was
     * true at construction. This is what fills `Node.metadata`, which `NodeSelector.tagged` matches
     * a provisioning request against.
     */
    @Volatile
    private var metadata: () -> Map<String, String> = { emptyMap() }

    /** Contribute [supplier]'s entries to every future heartbeat. Replaces any previous supplier. */
    fun advertise(supplier: () -> Map<String, String>) {
        metadata = supplier
    }

    private val playerCount = AtomicInteger(0)
    private val subscriptions = ArrayList<Subscription>()
    private var task: ScheduledTask? = null

    private companion object {
        /** How long shutdown waits for the deregister before giving up and letting the TTL do it. */
        const val DEREGISTER_TIMEOUT_MILLIS = 2_000L
    }

    /**
     * Publish this instance as STARTING and begin tracking its player count. Main thread.
     */
    fun register() {
        playerCount.set(server.onlinePlayers.size)
        scope.launch {
            runCatching { registry.register(snapshot(NodeState.STARTING)) }
                .onFailure { logger.error("Failed to register instance {}", identity.nodeId, it) }
        }

        subscriptions += Events.subscribe<PlayerJoinEvent>().handler { playerCount.incrementAndGet() }
        subscriptions += Events.subscribe<PlayerQuitEvent>().handler { playerCount.decrementAndGet() }
    }

    /**
     * Flip this instance to READY and start the heartbeat. Call once modules are enabled.
     */
    fun ready() {
        scope.launch {
            runCatching { registry.heartbeat(identity.nodeId, playerCount.get(), NodeState.READY) }
                .onFailure { logger.error("Failed to ready instance {}", identity.nodeId, it) }
        }

        val seconds = heartbeat.toSeconds().coerceAtLeast(1)
        task = Schedulers.asyncTimer(seconds, seconds, TimeUnit.SECONDS) {
            scope.launch { registry.heartbeat(identity.nodeId, playerCount.get(), NodeState.READY) }
        }
        logger.info(
            "Node {} of server {} is ready, reporting to the registry every {}s",
            identity.nodeId, identity.serverId, seconds,
        )
    }

    /**
     * This instance's live player count (thread-safe; safe to read off the main thread).
     */
    fun currentPlayers(): Int = playerCount.get()

    /**
     * Mark this instance DRAINING so proxies stop routing new players here.
     */
    fun drain() {
        scope.launch { registry.heartbeat(identity.nodeId, playerCount.get(), NodeState.DRAINING) }
    }

    /**
     * Stop the heartbeat and remove this instance from the registry.
     */
    fun stop() {
        task?.cancel()
        subscriptions.forEach { it.unregister() }
        subscriptions.clear()
        runCatching {
            runBlocking { withTimeout(DEREGISTER_TIMEOUT_MILLIS.milliseconds) { registry.deregister(identity.nodeId) } }
        }.onFailure { logger.warn("Timed out deregistering instance {}", identity.nodeId) }
    }

    private fun snapshot(state: NodeState): Node = Node(
        nodeId = identity.nodeId,
        serverId = identity.serverId,
        address = identity.address,
        port = identity.port,
        playerCount = playerCount.get(),
        maxPlayers = identity.maxPlayers,
        state = state,
        lastHeartbeat = System.currentTimeMillis(),
        metadata = runCatching(metadata).getOrElse { emptyMap() },
    )
}
