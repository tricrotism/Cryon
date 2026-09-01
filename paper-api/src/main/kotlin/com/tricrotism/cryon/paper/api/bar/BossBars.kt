package com.tricrotism.cryon.paper.api.bar

import com.tricrotism.cryon.paper.api.event.Events
import com.tricrotism.cryon.paper.api.event.Subscription
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.event.player.PlayerQuitEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates boss bars and makes sure a disconnect does not leave one believing it still has a viewer.
 *
 * The registry exists for exactly one reason: **a player who quits must be dropped from every bar
 * showing to them.** Adventure removes the audience itself, but the viewer *set* on this side would
 * keep their uuid, so `viewerCount` would drift upward forever and a bar keyed on "is anyone
 * watching" would never release. One quit listener, installed once by the core, walks the live bars.
 *
 * The set of live bars is strong, not weak: a bar is something acquired that names its release
 * ([CryonBossBar.close]), and a weak set would quietly paper over a module that forgot to close one.
 */
object BossBars {

    private val live = ConcurrentHashMap.newKeySet<CryonBossBar>()

    @Volatile
    private var quit: Subscription? = null

    /**
     * Start pruning disconnected viewers. Called once by the core; a second call is a no-op.
     *
     * Not done lazily on first [create] because a listener registered from whatever thread happens to
     * make the first bar is a worse place for it than a known point in the core's enable.
     */
    fun install() {
        if (quit != null) return
        quit = Events.subscribe<PlayerQuitEvent>().handler { event ->
            val id = event.player.uniqueId
            for (bar in live) bar.forget(id)
        }
    }

    /**
     * Stop pruning and close every bar still open. The core's teardown.
     */
    fun uninstall() {
        quit?.let { runCatching { it.unregister() } }
        quit = null
        live.toList().forEach { runCatching { it.close() } }
        live.clear()
    }

    fun create(
        name: Component,
        progress: Float = BossBar.MAX_PROGRESS,
        color: BossBar.Color = BossBar.Color.WHITE,
        overlay: BossBar.Overlay = BossBar.Overlay.PROGRESS,
    ): CryonBossBar {
        val bar = CryonBossBar(
            BossBar.bossBar(name, progress.coerceIn(BossBar.MIN_PROGRESS, BossBar.MAX_PROGRESS), color, overlay)
        )
        live += bar
        return bar
    }

    internal fun forget(bar: CryonBossBar) {
        live.remove(bar)
    }
}
