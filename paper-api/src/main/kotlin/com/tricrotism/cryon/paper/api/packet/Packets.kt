package com.tricrotism.cryon.paper.api.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import org.bukkit.entity.Player

/**
 * Functional packet subscription. The `Events` builder's twin for the protocol layer, over
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
 * never propagated, an exception escaping onto a Netty thread can drop the player's connection.
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

    /**
     * Subscribe to incoming (client → server) packets of [types].
     */
    fun onReceive(vararg types: PacketTypeCommon): PacketSubscriptionBuilder<PacketReceiveEvent> =
        PacketSubscriptionBuilder(types, Direction.RECEIVE, PacketListenerPriority.NORMAL)

    /**
     * Subscribe to outgoing (server → client) packets of [types].
     */
    fun onSend(vararg types: PacketTypeCommon): PacketSubscriptionBuilder<PacketSendEvent> =
        PacketSubscriptionBuilder(types, Direction.SEND, PacketListenerPriority.NORMAL)

    /**
     * Whether the packet layer is usable. False before the core enables, and false when PacketEvents
     * is not installed at all.
     *
     * PacketEvents is supplied by its own plugin rather than shaded here, so its classes can be
     * missing entirely. This is the guard features are told to check, which makes it the one place
     * that must not be the thing that throws: naming an absent class raises `NoClassDefFoundError`,
     * an `Error` rather than an `Exception`, so it is caught by type here and nowhere else.
     */
    val isReady: Boolean
        get() = try {
            PacketEvents.getAPI()?.isInitialized == true
        } catch (_: NoClassDefFoundError) {
            false
        }

    /**
     * Drop every shared lane and the PacketEvents listener behind it. The core's teardown; a module
     * closes its own subscriptions instead, which `track(…)` already does.
     */
    fun uninstall() = PacketDispatcher.uninstall()

    internal enum class Direction { RECEIVE, SEND }
}

/**
 * Send [wrapper] to this player. The counterpart to [Packets.onSend]; safe from any thread.
 */
fun Player.sendPacket(wrapper: PacketWrapper<*>) {
    PacketEvents.getAPI().playerManager.sendPacket(this, wrapper)
}

