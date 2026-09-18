package com.tricrotism.cryon.common.experiment

import com.tricrotism.cryon.common.concurrent.CryonIO
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A/B, A/B/C and A/B/C/D testing: show different players different wording, colours or layouts, and
 * find out which one works.
 *
 * **The counterpart to [com.tricrotism.cryon.common.flag.FeatureFlags], and built the same way**, on
 * purpose: SQL is the source of truth when configured, memory answers every read, and a change is
 * broadcast over the [Messenger] and applied idempotently including to its own echo, so a network of
 * servers agrees without any of them asking. What a flag cannot do is hold an arm - it is a boolean
 * end to end, in the map, on the wire and in its column - so this is a second store rather than a
 * widening of that one.
 *
 * Where an arm comes from. Most specific wins:
 *  1. **Player override** ([playerScope]) - one player pinned to one arm, for support and for looking
 *     at a variant without joining the experiment.
 *  2. **Server override** - this server's pool pinned to one arm. A canary that is a whole server.
 *  3. **Global override** - everybody on one arm. The kill switch that ships a winner.
 *  4. **The hash** - deterministic, stable, independent per experiment. See [Experiment.bucket].
 *
 * ```kotlin
 * // at the display, because that is what an exposure means
 * when (experiments.expose("WELCOME_COPY", player.uniqueId, "chat")) {
 *     "friendly" -> messages.send(player, "welcome.friendly")
 *     "brief" -> messages.send(player, "welcome.brief")
 *     else -> messages.send(player, "welcome")   // not assigned, or the experiment is off
 * }
 * ```
 *
 * **There is always an `else`.** An experiment that is disabled, deleted or was never registered
 * assigns nobody, so every call site needs a default that is a sensible thing to show. That is not a
 * concession: it is what makes an experiment safe to stop at three in the morning.
 *
 * Thread-safe. [arm] is a few map reads and about a dozen arithmetic operations, cheap enough to call
 * from display code without thinking about it.
 */
class Experiments(
    val serverId: String,
    private val database: Database?,
    private val messenger: Messenger,
    private val logger: Logger,
) {

    private class Live(val experiment: Experiment, val seed: Long)

    // id -> definition plus its derived seed, so a salt is hashed once rather than per assignment
    private val experiments = ConcurrentHashMap<String, Live>()
    // scope -> (experiment -> forced arm)
    private val overrides = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val observers = CopyOnWriteArrayList<ExperimentObserver>()
    private val serverScope = normalizeScope(serverId)
    private var subscription: MessengerSubscription? = null

    // Skips the override lookups entirely on the overwhelmingly common path. See [arm]
    @Volatile
    private var hasOverrides = false
    // What the last load() put here, so the next one can tell a deletion on another node from a
    // definition this process registered itself. See load()
    @Volatile
    private var loadedExperiments: Set<String> = emptySet()
    @Volatile
    private var loadedOverrides: Set<Pair<String, String>> = emptySet()

    // Same split as FeatureFlags: the in-memory change is synchronous and the durable write and the
    // broadcast are launched behind it. Assignment is display-path code and registration runs in a
    // module's onEnable; neither is a coroutine, and making them suspend would colour every caller to
    // buy nothing, since no read was ever waiting on the row being written
    private val scope = CoroutineScope(
        SupervisorJob() + CryonIO.dispatcher + CoroutineExceptionHandler { _, error ->
            logger.error("Unhandled failure in a coroutine of the experiment service", error)
        }
    )

    fun init() {
        val db = database
        if (db == null) {
            logger.info("Experiments are in-memory only (no database). Definitions reset on restart")
        } else {
            persist("init") {
                db.update(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE (
                        id VARCHAR(64) NOT NULL PRIMARY KEY,
                        arms VARCHAR(512) NOT NULL,
                        salt VARCHAR(64) NOT NULL,
                        enabled BOOLEAN NOT NULL DEFAULT TRUE
                    )
                    """.trimIndent()
                )
                db.update(
                    """
                    CREATE TABLE IF NOT EXISTS $OVERRIDE_TABLE (
                        scope VARCHAR(96) NOT NULL,
                        experiment VARCHAR(64) NOT NULL,
                        arm VARCHAR(32) NOT NULL,
                        PRIMARY KEY (scope, experiment)
                    )
                    """.trimIndent()
                )
                load()
            }
        }
        subscription = messenger.subscribe(CHANNEL, ::onSync)
    }

    fun close() {
        subscription?.unsubscribe()
        subscription = null
        observers.clear()
        scope.cancel("Experiments closed")
    }

    /**
     * Declare [experiment] so it exists, and is listable, before anybody is assigned by it.
     *
     * **A stored definition always wins.** Registering is how a module says what the experiment is on a
     * server that has never seen it; once an admin has changed the weights or stopped it, the code's
     * idea of the split is the stale one and must not be written back over theirs on the next restart.
     * Same contract [com.tricrotism.cryon.common.flag.FeatureFlags.register] has.
     */
    fun register(experiment: Experiment) {
        val id = normalize(experiment.id)
        val canonical = experiment.copy(id = id)
        if (experiments.putIfAbsent(id, live(canonical)) != null) return
        val db = database ?: return
        persist("register $id") {
            db.insertIfAbsent(
                TABLE, KEYS, COLUMNS,
                id, Experiment.encodeArms(canonical.arms), canonical.salt, canonical.enabled,
            )
        }
    }

    /**
     * **Pure: answering does not record an exposure.** Use it to branch on an arm you are not about to
     * show - preparing data, deciding whether to schedule something. When the player actually sees the
     * result, call [expose] instead, or the experiment ends up with a numerator and no denominator.
     *
     * @return which arm [subject] is in for [experiment], or null when it is unknown or stopped
     */
    fun arm(experiment: String, subject: UUID): String? {
        val id = normalize(experiment)
        val live = experiments[id] ?: return null
        if (!live.experiment.enabled) return null

        if (hasOverrides) {
            overrides[playerScope(subject)]?.get(id)?.let { return it }
            overrides[serverScope]?.get(id)?.let { return it }
            overrides[GLOBAL_SCOPE]?.get(id)?.let { return it }
        }

        return live.experiment.armAt(Experiment.bucket(live.seed, subject)).id
    }

    /**
     * Call this where the player sees the thing, for the same reason a message impression is reported
     * from the send and not the render: an assignment nobody reached is not an exposure, and counting it
     * puts players in the denominator who never had the chance to react.
     *
     * @return the arm, as [arm] would answer it, having told every observer it was shown on [surface]
     */
    fun expose(experiment: String, subject: UUID, surface: String): String? {
        val arm = arm(experiment, subject) ?: return null
        if (observers.isEmpty()) return arm

        val id = normalize(experiment)
        observers.forEach { observer ->
            try {
                observer.exposed(subject, id, arm, surface)
            } catch (e: Exception) {
                logger.warn("Experiment observer failed for {}", id, e)
            }
        }

        return arm
    }

    /**
     * Watches exposures. Nothing in the core consumes them.
     *
     * @return the handle that stops it
     */
    fun observe(observer: ExperimentObserver): AutoCloseable {
        observers += observer
        return AutoCloseable { observers -= observer }
    }

    /**
     * Pins [scope] to [arm], or drops the override and falls back to the next layer when [arm] is null.
     *
     * An arm the experiment does not have is refused rather than stored: a typo would otherwise pin a
     * scope to a variant no call site handles, every one of them would take its `else` branch, and the
     * result would look exactly like the experiment being switched off.
     *
     * @return false when there is no such experiment, or no such arm on it
     */
    fun force(scope: String, experiment: String, arm: String?): Boolean {
        val id = normalize(experiment)
        val scopeId = normalizeScope(scope)

        if (arm != null) {
            val live = experiments[id] ?: return false
            if (live.experiment.arms.none { it.id == arm }) return false

            scopeOverrides(scopeId)[id] = arm
        } else {
            overrides[scopeId]?.remove(id)
        }

        syncOverride(scopeId, id)
        return true
    }

    /**
     * Starts or stops [experiment] everywhere.
     *
     * @return false when there is no such experiment
     */
    fun setEnabled(experiment: String, enabled: Boolean): Boolean {
        val id = normalize(experiment)
        val live = experiments[id] ?: return false
        if (live.experiment.enabled == enabled) return true

        experiments[id] = live(live.experiment.copy(enabled = enabled))
        syncDefinition(id)
        return true
    }

    /**
     * Replaces [experiment]'s arms and weights.
     *
     * **This moves players between arms**, including players already exposed - weights are relative, so
     * every edge shifts when any of them changes. See [Experiment.armAt]. It takes the whole list rather
     * than editing one arm at a time for exactly that reason: the reshuffle should be something you
     * decided, not something that happened while you were adjusting one number.
     *
     * A result measured across such an edit is a blend of two populations. Prefer stopping the
     * experiment and registering a new id.
     *
     * @return false when there is no such experiment, or [arms] is empty
     */
    fun setArms(experiment: String, arms: List<Arm>): Boolean {
        val id = normalize(experiment)
        val live = experiments[id] ?: return false
        if (arms.isEmpty()) return false

        experiments[id] = live(live.experiment.copy(arms = arms))
        syncDefinition(id)
        return true
    }

    /**
     * Gives [experiment] a fresh salt, reshuffling every assignment.
     *
     * What it is for: running the same test again after changing what an arm does. Without it the same
     * players land in the same arms, and the second run inherits the first run's population - the
     * quietest way to get a result that looks clean and means nothing.
     *
     * @return false when there is no such experiment
     */
    fun reshuffle(experiment: String): Boolean {
        val id = normalize(experiment)
        val live = experiments[id] ?: return false

        experiments[id] = live(live.experiment.copy(salt = UUID.randomUUID().toString()))
        syncDefinition(id)
        return true
    }

    /**
     * Permanently deletes [experiment] and every override of it.
     */
    fun delete(experiment: String) {
        val id = normalize(experiment)
        experiments.remove(id)
        overrides.values.forEach { it.remove(id) }

        persist("delete $id") {
            database?.update("DELETE FROM $TABLE WHERE id = ?", id)
            database?.update("DELETE FROM $OVERRIDE_TABLE WHERE experiment = ?", id)
            messenger.publish(CHANNEL, join(DELETE_MARKER, id))
        }
    }

    /**
     * Re-reads everything from the database.
     *
     * @return false, having changed nothing, when there is no database
     */
    fun reload(): Boolean {
        if (database == null) return false

        persist("reload") { load() }
        return true
    }

    fun experiments(): SortedMap<String, Experiment> =
        experiments.entries.associateTo(TreeMap()) { (id, live) -> id to live.experiment }

    fun experiment(id: String): Experiment? = experiments[normalize(id)]?.experiment

    /**
     * @return every scope holding an override, and what each one forces
     */
    fun overrides(): SortedMap<String, SortedMap<String, String>> {
        val out = TreeMap<String, SortedMap<String, String>>()

        for ((scope, entries) in overrides) {
            if (entries.isNotEmpty()) out[scope] = TreeMap(entries)
        }

        return out
    }

    /**
     * @return the scope name holding [subject]'s personal overrides
     */
    fun playerScope(subject: UUID): String = PLAYER_SCOPE_PREFIX + subject

    // ------------------------------------------------------------------ internals

    private fun live(experiment: Experiment) = Live(experiment, Experiment.seed(experiment.salt))

    private fun scopeOverrides(scope: String): ConcurrentHashMap<String, String> {
        hasOverrides = true
        return overrides.computeIfAbsent(scope) { ConcurrentHashMap() }
    }

    /**
     * Write one experiment's **current** in-memory definition down and broadcast it.
     *
     * Read inside the coroutine rather than captured at the call site, for the reason
     * [com.tricrotism.cryon.common.flag.FeatureFlags] spells out: two launched writes for the same id
     * can land in either order, and a captured value lets the older one win, leaving the stored row
     * disagreeing with memory until something reads it back.
     */
    private fun syncDefinition(id: String) {
        persist("sync $id") {
            val current = experiments[id]?.experiment ?: return@persist
            val arms = Experiment.encodeArms(current.arms)
            database?.upsert(TABLE, KEYS, COLUMNS, id, arms, current.salt, current.enabled)
            messenger.publish(
                CHANNEL,
                join(DEFINE_MARKER, id, arms, current.salt, current.enabled.toString()),
            )
        }
    }

    private fun syncOverride(scopeId: String, id: String) {
        persist("sync override $scopeId/$id") {
            when (val arm = overrides[scopeId]?.get(id)) {
                null -> {
                    database?.update(
                        "DELETE FROM $OVERRIDE_TABLE WHERE scope = ? AND experiment = ?", scopeId, id,
                    )
                    messenger.publish(CHANNEL, join(OVERRIDE_MARKER, id, scopeId, ""))
                }

                else -> {
                    database?.upsert(OVERRIDE_TABLE, OVERRIDE_KEYS, OVERRIDE_COLUMNS, scopeId, id, arm)
                    messenger.publish(CHANNEL, join(OVERRIDE_MARKER, id, scopeId, arm))
                }
            }
        }
    }

    private fun persist(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to persist experiment change ({})", what, e)
            }
        }
    }

    /**
     * Apply what is stored over what is in memory, dropping only what a previous load contributed.
     *
     * Not a wholesale replace, for the reason the flag loader documents: this runs on a database
     * thread while modules are still calling [register] on another, and replacing would delete a
     * definition registered since the query went out - leaving the experiment assigning correctly and
     * invisible to the admin surface that could stop it.
     */
    private suspend fun load() {
        val db = database ?: return
        val rows = db.query("SELECT id, arms, salt, enabled FROM $TABLE") { row ->
            val arms = Experiment.decodeArms(row.getString(2))
            if (arms == null) null else Experiment(row.getString(1), arms, row.getString(3), row.getBoolean(4))
        }

        val stored = HashSet<String>(rows.size)
        rows.filterNotNull().forEach { experiment ->
            experiments[experiment.id] = live(experiment)
            stored += experiment.id
        }

        (loadedExperiments - stored).forEach { experiments.remove(it) }
        loadedExperiments = stored

        val forced = db.query("SELECT scope, experiment, arm FROM $OVERRIDE_TABLE") { row ->
            Triple(row.getString(1), row.getString(2), row.getString(3))
        }

        val storedOverrides = HashSet<Pair<String, String>>(forced.size)
        forced.forEach { (scopeId, id, arm) ->
            scopeOverrides(scopeId)[id] = arm
            storedOverrides += scopeId to id
        }

        (loadedOverrides - storedOverrides).forEach { (scopeId, id) -> overrides[scopeId]?.remove(id) }
        loadedOverrides = storedOverrides

        logger.info("Loaded {} experiments and {} overrides", stored.size, forced.size)
    }

    /**
     * Applies a broadcast from another server, or our own echo. Idempotent either way.
     *
     * A payload that does not parse is dropped rather than guessed at: the alternative is one node
     * quietly running a different split from its peers, which produces a result nobody can tell is
     * wrong.
     */
    private fun onSync(payload: String) {
        val parts = payload.split(SEPARATOR)

        when (parts.firstOrNull()) {
            DELETE_MARKER -> {
                if (parts.size != 2) return

                experiments.remove(parts[1])
                overrides.values.forEach { it.remove(parts[1]) }
            }

            DEFINE_MARKER -> {
                if (parts.size != 5) return

                val arms = Experiment.decodeArms(parts[2]) ?: return
                experiments[parts[1]] = live(Experiment(parts[1], arms, parts[3], parts[4].toBoolean()))
            }

            OVERRIDE_MARKER -> {
                if (parts.size != 4) return

                val id = parts[1]
                val scopeId = parts[2]
                val arm = parts[3]
                if (arm.isEmpty()) overrides[scopeId]?.remove(id) else scopeOverrides(scopeId)[id] = arm
            }

            else -> return
        }

        logger.info("Experiment sync: {} {}", parts[0], parts[1])
    }

    private fun join(vararg fields: String): String = fields.joinToString(SEPARATOR)

    private fun normalize(id: String): String = if (isCanonical(id)) id else id.trim().uppercase()

    private fun isCanonical(id: String): Boolean {
        if (id.isEmpty()) return false

        for (c in id) {
            if (c !in 'A'..'Z' && c !in '0'..'9' && c != '_') return false
        }

        return true
    }

    private fun normalizeScope(scope: String): String =
        scope.trim().let { if (it.startsWith(PLAYER_SCOPE_PREFIX)) it else it.lowercase() }

    companion object {
        const val GLOBAL_SCOPE = "global"
        const val PLAYER_SCOPE_PREFIX = "player:"
        private const val DEFINE_MARKER = "DEFINE"
        private const val DELETE_MARKER = "DELETE"
        private const val OVERRIDE_MARKER = "OVERRIDE"

        /**
         * Field separator on the wire.
         *
         * Printable, unlike the NUL [com.tricrotism.cryon.common.flag.FeatureFlags] uses, because every
         * field here is already constrained to a character set that excludes it: experiment ids and
         * scopes are normalised, arm ids are checked by [Arm], salts are UUIDs, and the rest are
         * literals. A payload is therefore unambiguous *and* readable in a `MONITOR` when somebody is
         * working out why two servers disagree - which is the only time anyone looks at it.
         */
        private const val SEPARATOR = "|"
        private const val CHANNEL = "cryon:experiments:sync"
        private const val TABLE = "cryon_experiment"
        private const val OVERRIDE_TABLE = "cryon_experiment_override"
        private val KEYS = listOf("id")
        private val COLUMNS = listOf("id", "arms", "salt", "enabled")
        private val OVERRIDE_KEYS = listOf("scope", "experiment")
        private val OVERRIDE_COLUMNS = listOf("scope", "experiment", "arm")
    }
}
