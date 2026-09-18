package com.tricrotism.cryon.paper.api.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.ProtocolPacketEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import com.tricrotism.cryon.paper.api.CryonPaper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

/**
 * Routes packets to the subscriptions that actually asked for them.
 *
 * **Every packet listener sees every packet of every online player**, which is the thing to understand
 * before writing one. PacketEvents has no notion of a listener that only applies to some players or
 * some packet types: it hands each registered listener the whole stream, and it is the listener's job
 * to decide it was not interested.
 *
 * That made the old shape cost more the more features a server ran. One PacketEvents listener was
 * registered per subscription, so a module watching `INTERACT_ENTITY` was still invoked for every
 * movement packet from every player, the highest-volume packet in the game, and then scanned its own
 * type list to conclude it did not care. With M modules holding K subscriptions each, every packet
 * paid M*K invocations plus M*K linear scans, on a Netty thread, and none of that cost was visible to
 * the module that caused it.
 *
 * So there is now **one shared listener per lane** (a direction and a priority), and a lane dispatches
 * through a map keyed by packet type. A packet nobody subscribed to costs one hash lookup and a null
 * check. A module is consulted only for the types it named, so the cost of a feature is proportional
 * to what it actually watches rather than to what the server is doing.
 *
 * Ordering is unchanged: PacketEvents still orders lanes by priority, and within a lane registration
 * order decides, exactly as separate listeners did.
 *
 * **Scope to a player with `filter`**, not by checking inside the handler. A subscription that only
 * concerns one player still runs for everyone, and the filter is where that is said once rather than
 * at the top of a handler that is otherwise doing work.
 */
internal object PacketDispatcher {

    private val lanes = ConcurrentHashMap<Lane, Router>()

    fun register(
        types: Array<out PacketTypeCommon>,
        direction: Packets.Direction,
        priority: PacketListenerPriority,
        registration: Registration,
    ): PacketSubscription {
        require(types.isNotEmpty()) { "A packet subscription must name at least one packet type" }

        val router = lanes.computeIfAbsent(Lane(direction, priority)) { Router(it) }
        router.add(types, registration)

        return PacketSubscription(registration.active) { router.remove(types, registration) }
    }

    /** Drop every lane and its PacketEvents listener. The core's teardown. */
    fun uninstall() {
        lanes.values.forEach { runCatching { it.close() } }
        lanes.clear()
    }

    private data class Lane(val direction: Packets.Direction, val priority: PacketListenerPriority)

    /**
     * One subscription's worth of state. Held in however many type buckets it named, so the same
     * instance is compared by identity on removal.
     */
    internal class Registration(
        private val filters: Array<(ProtocolPacketEvent) -> Boolean>,
        private val handler: (ProtocolPacketEvent) -> Unit,
        private val expiry: Long,
    ) {
        val active = AtomicBoolean(true)
        private val count = AtomicLong(0)

        @Volatile
        var onExpiry: (() -> Unit)? = null

        fun dispatch(event: ProtocolPacketEvent) {
            if (!active.get()) return
            for (predicate in filters) if (!predicate(event)) return
            try {
                handler(event)
            } catch (t: Throwable) {
                // Never propagated: an exception escaping onto a Netty thread can drop the
                // connection, which would make one module's bug everybody's disconnect.
                CryonPaper.plugin.logger.log(Level.SEVERE, "Error in packet handler for ${event.packetType}", t)
                return
            }
            if (expiry > 0 && count.incrementAndGet() >= expiry) onExpiry?.invoke()
        }
    }

    /**
     * The shared listener for one lane, plus its type map.
     *
     * The PacketEvents listener is registered on construction and never swapped, so a lane that has
     * gone empty keeps a listener that finds nothing. Deliberate: unregistering on the last removal
     * and re-registering on the next addition would reorder this lane against its peers, and an empty
     * lane costs one failed map lookup.
     */
    private class Router(lane: Lane) : AutoCloseable {

        private val byType = ConcurrentHashMap<PacketTypeCommon, CopyOnWriteArrayList<Registration>>()

        private val handle: PacketListenerCommon? = PacketEvents.getAPI()?.eventManager?.registerListener(
            when (lane.direction) {
                Packets.Direction.RECEIVE -> object : PacketListener {
                    override fun onPacketReceive(event: PacketReceiveEvent) = dispatch(event)
                }

                Packets.Direction.SEND -> object : PacketListener {
                    override fun onPacketSend(event: PacketSendEvent) = dispatch(event)
                }
            },
            lane.priority,
        )

        fun add(types: Array<out PacketTypeCommon>, registration: Registration) {
            for (type in types) {
                byType.computeIfAbsent(type) { CopyOnWriteArrayList() } += registration
            }
        }

        fun remove(types: Array<out PacketTypeCommon>, registration: Registration) {
            for (type in types) byType[type]?.remove(registration)
        }

        private fun dispatch(event: ProtocolPacketEvent) {
            // The whole point: a type nobody subscribed to leaves here, having cost one lookup.
            val bucket = byType[event.packetType] ?: return
            for (registration in bucket) registration.dispatch(event)
        }

        override fun close() {
            byType.clear()
            handle?.let { PacketEvents.getAPI()?.eventManager?.unregisterListener(it) }
        }
    }
}
