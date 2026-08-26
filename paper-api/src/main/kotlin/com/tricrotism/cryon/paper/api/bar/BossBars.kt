package com.tricrotism.cryon.paper.api.bar

import com.tricrotism.cryon.paper.api.event.Events
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A boss bar and the set of players currently seeing it.
 *
 * **Close it.** A bar is an object living on other people's connections: nothing about it is
 * reachable from the module that made it once the module is gone, so a bar left open after a
 * hot-unload keeps rendering with no owner, and a reload adds a second one beside it. It is
 * [AutoCloseable] precisely so `track(…)` handles that — the same rule as an open menu.
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
     * which cannot be compared against a quitting player without resolving each one — and this is
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

    /** Clamped rather than rejected: a progress computed from live values will overshoot eventually. */
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

    /** Called by [BossBars] when a viewer disconnects; the client is gone, so only the set is pruned. */
    internal fun forget(player: UUID) {
        viewers.remove(player)
    }

    /** Hide from everyone and stop tracking. Idempotent, and safe from any thread. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        BossBars.forget(this)
        for (id in viewers) {
            org.bukkit.Bukkit.getPlayer(id)?.hideBossBar(bar)
        }
        viewers.clear()
    }
}

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
    private var quit: com.tricrotism.cryon.paper.api.event.Subscription? = null

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

    /** Stop pruning and close every bar still open. The core's teardown. */
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
