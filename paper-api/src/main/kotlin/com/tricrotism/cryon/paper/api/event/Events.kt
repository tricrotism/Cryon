package com.tricrotism.cryon.paper.api.event

import com.tricrotism.cryon.paper.api.CryonPaper
import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

/**
 * Functional event subscription — filter and handle without writing a `@EventHandler` class.
 *
 * ```
 * Events.subscribe(PlayerInteractEvent::class.java, EventPriority.HIGHEST)
 *     .filter { it.hand == EquipmentSlot.HAND }
 *     .filter { it.item?.type == Material.TRIDENT }
 *     .handler { event -> /* … */ }
 * ```
 *
 * [handler] returns a [Subscription] you can [Subscription.unregister]. Handler exceptions are
 * logged, never propagated to the caller.
 */
object Events {
    fun <T : Event> subscribe(type: Class<T>, priority: EventPriority = EventPriority.NORMAL): SubscriptionBuilder<T> =
        SubscriptionBuilder(type, priority)
}

class SubscriptionBuilder<T : Event> internal constructor(
    private val type: Class<T>,
    private var priority: EventPriority,
) {
    private val filters = ArrayList<(T) -> Boolean>()
    private var ignoreCancelled = false
    private var expiry = -1L

    fun priority(priority: EventPriority): SubscriptionBuilder<T> = apply { this.priority = priority }
    fun ignoreCancelled(value: Boolean = true): SubscriptionBuilder<T> = apply { ignoreCancelled = value }
    fun filter(predicate: (T) -> Boolean): SubscriptionBuilder<T> = apply { filters.add(predicate) }

    /** Auto-unregister after [calls] successful handler invocations. */
    fun expireAfter(calls: Long): SubscriptionBuilder<T> = apply { expiry = calls }

    fun handler(handler: (T) -> Unit): Subscription {
        val plugin = CryonPaper.plugin
        val listener = object : Listener {}
        val active = AtomicBoolean(true)
        val subscription = Subscription(listener, active)
        val count = AtomicLong(0)
        val filters = this.filters.toList() // snapshot
        val expiry = this.expiry

        val executor = EventExecutor { _, event ->
            if (!active.get() || !type.isInstance(event)) return@EventExecutor
            @Suppress("UNCHECKED_CAST")
            val typed = event as T
            for (predicate in filters) if (!predicate(typed)) return@EventExecutor
            try {
                handler(typed)
            } catch (t: Throwable) {
                plugin.logger.log(Level.SEVERE, "Error in event handler for ${type.simpleName}", t)
            }
            if (expiry > 0 && count.incrementAndGet() >= expiry) subscription.unregister()
        }

        Bukkit.getPluginManager().registerEvent(type, listener, priority, executor, plugin, ignoreCancelled)
        return subscription
    }
}

class Subscription internal constructor(
    private val listener: Listener,
    private val active: AtomicBoolean,
) {
    val isActive: Boolean get() = active.get()

    fun unregister() {
        if (active.compareAndSet(true, false)) HandlerList.unregisterAll(listener)
    }
}
