package com.tricrotism.cryon.paper.api.packet

import com.tricrotism.cryon.paper.api.event.Events
import com.tricrotism.cryon.paper.api.event.Subscription
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands out client entity ids and makes sure a disconnect does not leave a packet entity believing it
 * still has a viewer. The [BossBars][com.tricrotism.cryon.paper.api.bar.BossBars] of the packet layer,
 * and it exists for the same two reasons.
 *
 * **The id allocator has to be central.** A packet-only entity needs an id the server will never use
 * for a real one, and the only way two features can both do that safely is to draw from the same
 * counter. Two features each counting down from `Int.MAX_VALUE` in their own jar collide on their
 * first entity, and the symptom is one feature's hologram flickering when the other spawns an NPC,
 * which is not a symptom anyone traces back to id allocation. Anything that owns this has to be
 * visible to every feature at once, so it belongs here rather than in a jar features pass between
 * themselves.
 *
 * **A quitting player has to be dropped from every viewer set.** Nothing else notices: the entity
 * exists only in packets already sent, so there is no server-side entity to be removed and no event
 * to hang the cleanup off. Without this the set grows forever and an entity keyed on "is anyone
 * watching" never releases.
 */
object PacketEntities {

    /**
     * Counts **down** from `Int.MAX_VALUE`. The server allocates real entity ids upward from zero, so
     * starting at the far end is what keeps the two from meeting on any server that will ever exist.
     */
    private val next = AtomicInteger(Int.MAX_VALUE)

    private val live = ConcurrentHashMap.newKeySet<PacketEntity>()

    @Volatile
    private var quit: Subscription? = null

    /**
     * Start pruning disconnected viewers. Called once by the core; a second call is a no-op.
     */
    fun install() {
        if (quit != null) return
        quit = Events.subscribe<PlayerQuitEvent>().handler { event ->
            val id = event.player.uniqueId
            for (entity in live) entity.forget(id)
        }
    }

    /**
     * Stop pruning and close every entity still live. The core's teardown.
     */
    fun uninstall() {
        quit?.let { runCatching { it.unregister() } }
        quit = null
        live.toList().forEach { runCatching { it.close() } }
        live.clear()
    }

    fun allocateId(): Int = next.decrementAndGet()

    fun allocateIds(count: Int): IntArray = IntArray(count) { next.decrementAndGet() }

    internal fun register(entity: PacketEntity) {
        live += entity
    }

    internal fun forget(entity: PacketEntity) {
        live.remove(entity)
    }
}

/**
 * One entity that exists only in the packets sent to its viewers.
 *
 * Owns the half every packet entity has in common: the ids, the viewer set, and the dispatch that
 * turns "this changed" into a packet per viewer. Subclasses own the half that actually differs, the
 * spawn and destroy sequence for their entity type, which is genuinely not shared. A text display is
 * a spawn plus metadata; a player needs announcing in the player list first, with `listed = false`,
 * because it is the one type the client will not render from a spawn packet alone.
 *
 * **Sending is safe from any thread**, so none of this hops. Reading the player's own state is not,
 * so a subclass deciding visibility from `player.world` or `player.location` needs
 * `Schedulers.entity(player)` around that read, not around the send.
 *
 * Registered for quit pruning on its first viewer rather than on construction: an entity nobody can
 * see has no viewer to lose, and publishing `this` from a constructor into a set another thread walks
 * is how a half-built object gets visited.
 */
abstract class PacketEntity : AutoCloseable {

    private val viewers: MutableSet<UUID> = Collections.newSetFromMap(ConcurrentHashMap())

    val viewerCount: Int get() = viewers.size

    fun isViewing(player: Player): Boolean = player.uniqueId in viewers

    /**
     * Show this entity to [player]. False when they were already a viewer, so a caller sweeping a
     * region every tick does not respawn what is already on screen.
     */
    fun addViewer(player: Player): Boolean {
        if (!viewers.add(player.uniqueId)) return false
        PacketEntities.register(this)
        spawnFor(player)
        return true
    }

    fun removeViewer(player: Player): Boolean {
        if (!viewers.remove(player.uniqueId)) return false
        despawnFor(player)
        return true
    }

    /**
     * Drop a viewer without sending anything, for a player who has already disconnected. Called by
     * [PacketEntities] on quit; a subclass tracking its own visibility can call it too.
     */
    fun forget(player: UUID) {
        viewers.remove(player)
    }

    /**
     * Run [action] for every viewer still online. A uuid whose player has gone is skipped rather than
     * pruned: the quit listener owns removal, and removing here would mean mutating the set from
     * inside a walk of it for no gain.
     */
    protected fun forEachViewer(action: (Player) -> Unit) {
        for (id in viewers) {
            val player = Bukkit.getPlayer(id) ?: continue
            action(player)
        }
    }

    /** Send a destroy to every current viewer and forget them all. */
    fun despawnAll() {
        forEachViewer { player -> despawnFor(player) }
        viewers.clear()
    }

    /**
     * Despawn for everyone and stop being pruned. A module must call this on disable: the entity
     * lives on other people's connections, so one left behind renders with nothing owning it.
     */
    override fun close() {
        despawnAll()
        PacketEntities.forget(this)
    }

    protected abstract fun spawnFor(player: Player)

    protected abstract fun despawnFor(player: Player)
}
