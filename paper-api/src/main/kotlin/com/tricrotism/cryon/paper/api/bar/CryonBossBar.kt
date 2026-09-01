package com.tricrotism.cryon.paper.api.bar

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A boss bar and the set of players currently seeing it.
 *
 * **Close it.** A bar is an object living on other people's connections: nothing about it is
 * reachable from the module that made it once the module is gone, so a bar left open after a
 * hot-unload keeps rendering with no owner, and a reload adds a second one beside it. It is
 * [AutoCloseable] precisely so `track(…)` handles that, the same rule as an open menu.
 *
 * ```
 * private val bar = track(BossBars.create("<gold>Event".mm(), color = BossBar.Color.YELLOW))
 * bar.show(player)
 * bar.progress(0.5f)
 * ```
 *
 * Mutating a bar updates it for every viewer at once, which is the reason to share one bar across a
 * group rather than give each player their own: a raid timer is one fact, and one bar means one
 * packet per change instead of one per player per change.
 */
class CryonBossBar internal constructor(private val bar: BossBar) : AutoCloseable {

    /**
     * Who is being shown this bar, by uuid.
     *
     * Tracked here rather than read back from [BossBar.viewers] because that answers `Audience`s,
     * which cannot be compared against a quitting player without resolving each one, and this is
     * walked on every disconnect.
     */
    private val viewers = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * Set before [close] sweeps [viewers], which is what lets [show] settle a race against it by
     * re-reading rather than by locking.
     */
    private val closed = AtomicBoolean(false)

    val name: Component get() = bar.name()
    val progress: Float get() = bar.progress()

    fun name(value: Component): CryonBossBar = apply { bar.name(value) }

    /**
     * Clamped rather than rejected: a progress computed from live values will overshoot eventually.
     */
    fun progress(value: Float): CryonBossBar =
        apply { bar.progress(value.coerceIn(BossBar.MIN_PROGRESS, BossBar.MAX_PROGRESS)) }

    fun color(value: BossBar.Color): CryonBossBar = apply { bar.color(value) }

    fun overlay(value: BossBar.Overlay): CryonBossBar = apply { bar.overlay(value) }

    /**
     * Show this bar to [player], unless it has been closed.
     *
     * The closed check is repeated after the viewer lands in the set, because a [close] on another
     * thread can pass between the two. Whichever order they interleave in, one of them sees the
     * other: an add before the sweep is hidden by it, and an add after it finds the flag already
     * set. Without the second read a bar could stay rendered on a client with nothing left owning
     * it, which is the leak this class exists to prevent.
     */
    fun show(player: Player): CryonBossBar = apply {
        if (closed.get()) return@apply
        if (!viewers.add(player.uniqueId)) return@apply
        if (closed.get()) {
            viewers.remove(player.uniqueId)
            return@apply
        }
        player.showBossBar(bar)
    }

    fun hide(player: Player): CryonBossBar = apply {
        if (viewers.remove(player.uniqueId)) player.hideBossBar(bar)
    }

    fun isShownTo(player: Player): Boolean = player.uniqueId in viewers

    fun viewerCount(): Int = viewers.size

    /**
     * Called by [BossBars] when a viewer disconnects; the client is gone, so only the set is pruned.
     */
    internal fun forget(player: UUID) {
        viewers.remove(player)
    }

    /**
     *  Hide from everyone and stop tracking. Idempotent, and safe from any thread.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        BossBars.forget(this)
        for (id in viewers) {
            Bukkit.getPlayer(id)?.hideBossBar(bar)
        }
        viewers.clear()
    }
}
