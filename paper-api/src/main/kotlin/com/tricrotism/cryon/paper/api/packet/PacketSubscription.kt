package com.tricrotism.cryon.paper.api.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerCommon
import java.util.concurrent.atomic.AtomicBoolean

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
