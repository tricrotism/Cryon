package com.tricrotism.cryon.paper.api.packet

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live packet subscription. `AutoCloseable`, so it goes straight into `PaperModule.track(…)`.
 *
 * Unregistering no longer removes a PacketEvents listener, because a subscription is no longer one:
 * it is an entry in a shared lane's type map (see [PacketDispatcher]). The flag is flipped first and
 * checked on dispatch, so a handler already running on a Netty thread finishes and the next one does
 * not start, without the removal having to be visible to that thread first.
 */
class PacketSubscription internal constructor(
    private val active: AtomicBoolean,
    private val remove: () -> Unit,
) : AutoCloseable {

    val isActive: Boolean get() = active.get()

    fun unregister() {
        if (active.compareAndSet(true, false)) remove()
    }

    /** Same as [unregister], so a subscription can go straight into `PaperModule.track(…)`. */
    override fun close() = unregister()
}
