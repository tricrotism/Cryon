package com.tricrotism.cryon.paper.api.bar

import com.tricrotism.cryon.paper.api.bar.ActionBars.prune
import com.tricrotism.cryon.paper.api.event.Events
import com.tricrotism.cryon.paper.api.event.Subscription
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The action bar, arbitrated.
 *
 * **Why this is not just `player.sendActionBar`.** The action bar has two properties that make the
 * raw call unusable for anything persistent: the text fades after roughly three seconds, so anything
 * meant to stay must be re-sent; and there is exactly *one* of it, so two features both sending
 * produce a flicker at whatever rate they happen to tick, with the winner decided by scheduling
 * order. This owns the re-send and settles the contest by [priority], so a transient alert can
 * interrupt a persistent readout and the readout comes back when it expires.
 *
 * ```
 * ActionBars.send(player, "<gray>Mining: <gold>32%".mm(), Duration.ofSeconds(5))          // readout
 * ActionBars.send(player, "<red>Out of stamina".mm(), Duration.ofSeconds(2), priority = 10) // alert
 * ```
 *
 * **Costs nothing when nobody is using it.** The ticker walks only the players who have an entry, so
 * an empty map is one `isEmpty` check per interval; a server that never sends an action bar pays for
 * one no-op task. Entries are dropped when they expire and on disconnect, so the map is bounded by
 * the players actually being shown something rather than by everyone who ever was.
 */
object ActionBars {

    /**
     * One thing a feature wants on a player's action bar.
     *
     * [key] is what makes an update an update rather than a second entry: sending again under the
     * same key replaces the previous one, so a per-tick readout does not accumulate. Different
     * features use different keys and therefore compete on [priority] instead of overwriting.
     */
    private class Entry(
        val key: String,
        val message: Component,
        val priority: Int,
        val expiresAt: Long,
    )

    private val entries = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Entry>>()

    @Volatile
    private var task: ScheduledTask? = null

    @Volatile
    private var quit: Subscription? = null

    /** Start the ticker and the disconnect cleanup. Called once by the core. */
    fun install() {
        if (task != null) return
        quit = Events.subscribe<PlayerQuitEvent>().handler { entries.remove(it.player.uniqueId) }
        task = Schedulers.globalTimer(REFRESH_TICKS, REFRESH_TICKS) { tick() }
    }

    fun uninstall() {
        task?.let { runCatching { it.cancel() } }
        task = null
        quit?.let { runCatching { it.unregister() } }
        quit = null
        entries.clear()
    }

    /**
     * Show [message] on [player]'s action bar for [durationMillis].
     *
     * Sent immediately as well as on the next tick, so a one-off acknowledgement is not delayed by up
     * to the refresh interval, which for something answering a click is the difference between
     * feedback and lag. A higher [priority] wins while both are live.
     *
     * The entry lands in one step. Resolving this player's map and then writing into it would let
     * [prune] unlink that same map as empty in between, and the update would be lost into an orphan.
     */
    fun send(
        player: Player,
        message: Component,
        durationMillis: Long = DEFAULT_DURATION_MILLIS,
        priority: Int = 0,
        key: String = DEFAULT_KEY,
    ) {
        val id = player.uniqueId
        val entry = Entry(key, message, priority, System.currentTimeMillis() + durationMillis)
        entries.compute(id) { _, existing -> (existing ?: ConcurrentHashMap()).apply { put(key, entry) } }
        if (best(id) === entry) player.sendActionBar(message)
    }

    /** Drop one entry. Whatever was underneath it reappears on the next tick. */
    fun clear(player: Player, key: String = DEFAULT_KEY) {
        val id = player.uniqueId
        entries[id]?.remove(key) ?: return
        prune(id)
    }

    /** Drop everything this player is being shown. */
    fun clearAll(player: Player) {
        entries.remove(player.uniqueId)
    }

    /**
     * Re-send the winning entry to everyone who has one, dropping what has expired.
     *
     * Runs on the global region thread, and deliberately does not hop per player.
     *
     * Sending an action bar builds one packet and hands it to the connection: on Folia 26.2 there is
     * no thread assertion anywhere on that path, not in `CraftPlayer`, not in
     * `ServerCommonPacketListenerImpl.send`, and not in `Connection`. It reads and mutates no entity
     * state, so there is nothing here for a region to own. A hop per entry per interval would buy
     * nothing and cost a scheduled task each, in the one class whose whole promise is that it costs
     * nothing while nobody is using it.
     */
    private fun tick() {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((id, forPlayer) in entries) {
            forPlayer.values.removeIf { it.expiresAt <= now }
            val winner = forPlayer.values.maxWithOrNull(ORDER)
            if (winner == null) {
                prune(id)
                continue
            }
            Bukkit.getPlayer(id)?.sendActionBar(winner.message)
        }
    }

    /**
     * Unlink a player's map once nothing is left in it, and only while that is still true.
     *
     * Testing emptiness and then removing as two steps would drop a map that [send] had already
     * resolved and was about to write into, losing that update. Deciding it inside the remap holds
     * the bin against the [ConcurrentHashMap.compute] in [send] for the length of the check.
     */
    private fun prune(player: UUID) {
        entries.computeIfPresent(player) { _, forPlayer -> forPlayer.takeIf { it.isNotEmpty() } }
    }

    private fun best(player: UUID): Entry? = entries[player]?.values?.maxWithOrNull(ORDER)

    /** Highest priority wins; the later expiry breaks a tie, so a refresh beats what it replaced. */
    private val ORDER = compareBy<Entry>({ it.priority }, { it.expiresAt })

    private const val DEFAULT_KEY = "default"

    /** Comfortably inside the client's ~3s fade. */
    private const val REFRESH_TICKS = 30L

    private const val DEFAULT_DURATION_MILLIS = 3_000L
}
