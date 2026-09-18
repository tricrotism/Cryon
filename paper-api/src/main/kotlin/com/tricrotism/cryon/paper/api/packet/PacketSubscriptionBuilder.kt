package com.tricrotism.cryon.paper.api.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.ProtocolPacketEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon

class PacketSubscriptionBuilder<T : ProtocolPacketEvent> internal constructor(
    private val types: Array<out PacketTypeCommon>,
    private val direction: Packets.Direction,
    private var priority: PacketListenerPriority,
) {
    private val filters = ArrayList<(T) -> Boolean>()
    private var expiry = -1L

    fun priority(priority: PacketListenerPriority): PacketSubscriptionBuilder<T> =
        apply { this.priority = priority }

    /**
     * Only run the handler when [predicate] holds.
     *
     * **This is where a subscription is scoped to particular players.** A packet listener sees every
     * packet of every online player, so a feature that concerns one player, one world or one gamemode
     * says so here rather than checking at the top of a handler that is otherwise doing work. Filters
     * run on a Netty thread for every packet of a subscribed type, so keep them to field reads: no
     * Bukkit API, no lookups that touch disk or a database.
     */
    fun filter(predicate: (T) -> Boolean): PacketSubscriptionBuilder<T> = apply { filters.add(predicate) }

    /** Auto-unregister after [calls] successful handler invocations. */
    fun expireAfter(calls: Long): PacketSubscriptionBuilder<T> = apply { expiry = calls }

    @Suppress("UNCHECKED_CAST")
    fun handler(handler: (T) -> Unit): PacketSubscription {
        checkNotNull(PacketEvents.getAPI()) { "The packet layer is not initialized yet" }

        val registration = PacketDispatcher.Registration(
            filters = filters.toTypedArray() as Array<(ProtocolPacketEvent) -> Boolean>,
            handler = handler as (ProtocolPacketEvent) -> Unit,
            expiry = expiry,
        )
        val subscription = PacketDispatcher.register(types, direction, priority, registration)

        // Set after registering, because expiry can only unregister something that exists. A packet
        // arriving in between is dispatched normally and simply does not count toward the expiry.
        registration.onExpiry = subscription::unregister

        return subscription
    }
}
