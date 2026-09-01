package com.tricrotism.cryon.paper.api.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.*
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import com.tricrotism.cryon.paper.api.CryonPaper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

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
        val subscription = AtomicReference<PacketSubscription>()

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
            if (expiry > 0 && count.incrementAndGet() >= expiry) subscription.get()?.unregister()
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
        val registered = PacketSubscription(handle, active)
        subscription.set(registered)
        return registered
    }
}
