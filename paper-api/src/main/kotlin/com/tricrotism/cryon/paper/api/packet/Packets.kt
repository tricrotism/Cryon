package com.tricrotism.cryon.paper.api.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.*
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.tricrotism.cryon.paper.api.CryonPaper
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

/**
 * Functional packet subscription — the `Events` builder's twin for the protocol layer, over
 * PacketEvents (shaded into the core unrelocated, so a feature `compileOnly`s it and never shades it).
 *
 * ```
 * Packets.onSend(PacketType.Play.Server.PLAYER_INFO_UPDATE)
 *     .filter { it.user.uuid != null }
 *     .handler { event -> event.isCancelled = true }
 * ```
 *
 * [PacketSubscriptionBuilder.handler] returns a [PacketSubscription] that is [AutoCloseable], so it
 * goes straight into `PaperModule.track(…)` and dies with the module. Handler exceptions are logged,
 * never propagated — an exception escaping onto a Netty thread can drop the player's connection.
 *
 * **Handlers run on a Netty I/O thread, never a region or global thread.** No Bukkit API call is legal
 * inside one: no entities, no inventories, no `sendMessage`. This is not a Folia-only rule, it holds on
 * Paper too. Read what you need off the event, then hop:
 *
 * ```
 * Packets.onReceive(PacketType.Play.Client.INTERACT_ENTITY).handler { event ->
 *     val player = event.getPlayer<Player>() ?: return@handler
 *     Schedulers.entity(player) { player.sendMessage("…") }   // Bukkit work goes here
 * }
 * ```
 *
 * Cancellation is the reason handlers are not hopped for you: it has to be decided before the packet
 * moves on, and a scheduled handler always runs too late.
 */
object Packets {

    /** Subscribe to incoming (client → server) packets of [types]. */
    fun onReceive(vararg types: PacketTypeCommon): PacketSubscriptionBuilder<PacketReceiveEvent> =
        PacketSubscriptionBuilder(types, Direction.RECEIVE, PacketListenerPriority.NORMAL)

    /** Subscribe to outgoing (server → client) packets of [types]. */
    fun onSend(vararg types: PacketTypeCommon): PacketSubscriptionBuilder<PacketSendEvent> =
        PacketSubscriptionBuilder(types, Direction.SEND, PacketListenerPriority.NORMAL)

    /** Whether the packet layer is initialized. False before the core enables. */
    val isReady: Boolean get() = PacketEvents.getAPI()?.isInitialized == true

    internal enum class Direction { RECEIVE, SEND }
}

/** Send [wrapper] to this player. The counterpart to [Packets.onSend]; safe from any thread. */
fun Player.sendPacket(wrapper: PacketWrapper<*>) {
    PacketEvents.getAPI().playerManager.sendPacket(this, wrapper)
}

class PacketSubscriptionBuilder<T : ProtocolPacketEvent> internal constructor(
    private val types: Array<out PacketTypeCommon>,
    private val direction: Packets.Direction,
    private var priority: PacketListenerPriority,
) {
    private val filters = ArrayList<(T) -> Boolean>()
    private var expiry = -1L

    fun priority(priority: PacketListenerPriority): PacketSubscriptionBuilder<T> =
        apply { this.priority = priority }

    fun filter(predicate: (T) -> Boolean): PacketSubscriptionBuilder<T> = apply { filters.add(predicate) }

    /** Auto-unregister after [calls] successful handler invocations. */
    fun expireAfter(calls: Long): PacketSubscriptionBuilder<T> = apply { expiry = calls }

    fun handler(handler: (T) -> Unit): PacketSubscription {
        val api = PacketEvents.getAPI() ?: error("The packet layer is not initialized yet")
        val plugin = CryonPaper.plugin
        val active = AtomicBoolean(true)
        val count = AtomicLong(0)
        val types = this.types
        val filters = this.filters.toTypedArray()
        val expiry = this.expiry
        lateinit var subscription: PacketSubscription

        fun dispatch(event: T) {
            if (!active.get()) return
            var matched = false
            for (type in types) if (event.packetType === type) {
                matched = true; break
            }
            if (!matched) return
            for (predicate in filters) if (!predicate(event)) return
            try {
                handler(event)
            } catch (t: Throwable) {
                plugin.logger.log(Level.SEVERE, "Error in packet handler for ${event.packetType}", t)
                return
            }
            if (expiry > 0 && count.incrementAndGet() >= expiry) subscription.unregister()
        }

        @Suppress("UNCHECKED_CAST")
        val listener = when (direction) {
            Packets.Direction.RECEIVE -> object : PacketListener {
                override fun onPacketReceive(event: PacketReceiveEvent) = dispatch(event as T)
            }

            Packets.Direction.SEND -> object : PacketListener {
                override fun onPacketSend(event: PacketSendEvent) = dispatch(event as T)
            }
        }

        val handle = api.eventManager.registerListener(listener, priority)
        subscription = PacketSubscription(handle, active)
        return subscription
    }
}

class PacketSubscription internal constructor(
    private val listener: PacketListenerCommon,
    private val active: AtomicBoolean,
) : AutoCloseable {
    val isActive: Boolean get() = active.get()

    fun unregister() {
        if (active.compareAndSet(true, false)) {
            PacketEvents.getAPI()?.eventManager?.unregisterListener(listener)
        }
    }

    /** Same as [unregister], so a subscription can go straight into `PaperModule.track(…)`. */
    override fun close() = unregister()
}
