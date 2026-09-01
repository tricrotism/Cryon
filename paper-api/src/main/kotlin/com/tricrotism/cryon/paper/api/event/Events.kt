package com.tricrotism.cryon.paper.api.event

import org.bukkit.event.Event
import org.bukkit.event.EventPriority

/**
 * Functional event subscription. Filter and handle without writing a `@EventHandler` class.
 *
 * ```
 * Events.subscribe<PlayerInteractEvent>(EventPriority.HIGHEST)
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

    inline fun <reified T : Event> subscribe(priority: EventPriority = EventPriority.NORMAL): SubscriptionBuilder<T> =
        subscribe(T::class.java, priority)
}


