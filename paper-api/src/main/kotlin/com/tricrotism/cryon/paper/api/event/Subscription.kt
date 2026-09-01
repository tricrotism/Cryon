package com.tricrotism.cryon.paper.api.event

import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import java.util.concurrent.atomic.AtomicBoolean

class Subscription internal constructor(
    private val listener: Listener,
    private val active: AtomicBoolean,
) : AutoCloseable {
    val isActive: Boolean get() = active.get()

    fun unregister() {
        if (active.compareAndSet(true, false)) HandlerList.unregisterAll(listener)
    }

    /**
     * Same as [unregister]. Exists so a subscription can go straight into `PaperModule.track(…)` and
     * be torn down with the module, instead of every feature hand-rolling a list of these.
     */
    override fun close() = unregister()
}
