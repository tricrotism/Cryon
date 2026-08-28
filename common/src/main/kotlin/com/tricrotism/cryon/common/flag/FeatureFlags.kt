package com.tricrotism.cryon.common.flag

import com.tricrotism.cryon.common.concurrent.CryonIO
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Global feature-flag system: turn features on/off at runtime without restarting servers. Every
 * distinct slice of a feature (a command, a payout path, an event handler) checks its own bare
 * uppercase ID (`SHOP_SELL`, `SKILLS_MINING`: **no** gamemode prefixes; scope via [set]'s scope
 * argument instead) at its entry point, so any slice can be killed independently.
 *
 * Where a flag applies. Most specific wins:
 *  1. **Player override**: forced on/off for one player ([playerScope]).
 *  2. **Server override**: forced on/off for this server's pool (`network.server`),
 *     so it kills the feature across every instance of the pool at once.
 *  3. **Global override**: forced on/off everywhere ([GLOBAL_SCOPE]).
 *  4. **Default**: enabled.
 *
 * Every change is broadcast over the [Messenger] and applied idempotently, so a toggle reaches every
 * instance of a pool at once, or just this process on a single server, which is the same code
 * reaching the same audience. The `Database` is the source of truth when configured and optional
 * otherwise: without it the flags are in-memory (reset on restart) and everything else is unchanged.
 * Modules [register] their flags on enable so `/cryon flags` lists every kill switch with its default.
 *
 * Thread-safe; [isEnabled] is a few map reads, cheap enough for event handlers. Created once by
 * the core and shared through the `ServiceRegistry`.
 */
class FeatureFlags(
    val serverId: String,
    private val database: Database?,
    private val messenger: Messenger,
    private val logger: Logger,
) {

    // scope -> (feature -> enabled)
    private val flags = ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>()
    private val playerOverrides = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Boolean>>()
    private val serverScope = normalizeScope(serverId)
    private var subscription: MessengerSubscription? = null

    @Volatile
    private var hasPlayerOverrides = false

    /** What the last [load] put in [flags], so the next one can tell a deletion from a fresh register. */
    @Volatile
    private var loaded: Set<Pair<String, String>> = emptySet()

    /**
     * Owns the persistence behind every mutation.
     *
     * The in-memory update stays **synchronous** and the SQL write and broadcast are launched
     * behind it, which is what the futures did before. That split is the contract, not an
     * implementation detail: [isEnabled] is a map read on an event-handler path and [register] is
     * called from a module's `onEnable`, neither of which is a coroutine. Making them suspend would
     * push the colour through every caller to buy nothing, since the durable write was never what a
     * flag check waited on.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + CryonIO.dispatcher + CoroutineExceptionHandler { _, error ->
            logger.error("Unhandled failure in a coroutine of the feature flag service", error)
        }
    )

    /**
     * Write one key's **current** in-memory state down and broadcast it.
     *
     * Reading the value inside the coroutine rather than capturing it at the call site is what makes
     * concurrent toggles converge. Two launched writes for the same key can land in either order, so
     * a captured value lets the older write win and leaves the durable row disagreeing with memory
     * until the next restart reads it back. Re-reading means whichever runs last writes what memory
     * actually says, which is the same answer either way.
     */
    private fun syncKey(scopeId: String, id: String) {
        persist("sync $scopeId/$id") {
            when (val current = flags[scopeId]?.get(id)) {
                null -> {
                    database?.update("DELETE FROM $TABLE WHERE scope = ? AND feature = ?", scopeId, id)
                    messenger.publish(CHANNEL, sync(scopeId, id, REMOVE_MARKER))
                }

                else -> {
                    database?.upsert(TABLE, KEYS, COLUMNS, scopeId, id, current)
                    messenger.publish(CHANNEL, sync(scopeId, id, current.toString()))
                }
            }
        }
    }

    /**
     * Log a launched write that failed rather than letting it vanish into a canceled scope.
     */
    private fun persist(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to persist feature flag change ({})", what, e)
            }
        }
    }

    fun init() {
        val db = database
        if (db == null) {
            logger.info("Feature flags are in-memory only (no database). Overrides reset on restart")
        } else {
            persist("init") {
                db.update(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE (
                        scope VARCHAR(96) NOT NULL,
                        feature VARCHAR(128) NOT NULL,
                        enabled BOOLEAN NOT NULL DEFAULT TRUE,
                        PRIMARY KEY (scope, feature)
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
        scope.cancel("Feature flags closed")
    }

    /**
     * Effective state of [feature]: the [player]'s override, else this server's, else the global
     * one, else **enabled**. Pass the player whenever one is in context so per-player overrides
     * (canary rollouts, support cases) apply.
     */
    fun isEnabled(feature: String, player: UUID? = null): Boolean {
        val id = normalize(feature)
        if (player != null && hasPlayerOverrides) playerOverrides[player]?.get(id)?.let { return it }
        flags[serverScope]?.get(id)?.let { return it }
        flags[GLOBAL_SCOPE]?.get(id)?.let { return it }
        return true
    }

    /**
     * Declare [feature] with its [defaultEnabled] state so it exists (and is listed) before its
     * first check. No-op if the flag is already known, an admin-set value always wins. A flag that
     * only applies to one gamemode is scoped via [scope], not an ID prefix.
     */
    fun register(feature: String, defaultEnabled: Boolean = true, scope: String = GLOBAL_SCOPE) {
        val id = normalize(feature)
        val scopeId = normalizeScope(scope)
        if (scopeFlags(scopeId).putIfAbsent(id, defaultEnabled) != null) return
        val db = database ?: return
        persist("register $scopeId/$id") { db.insertIfAbsent(TABLE, KEYS, COLUMNS, scopeId, id, defaultEnabled) }
    }

    /**
     * Force [feature] on/off in [scope], persist it, and broadcast to every server.
     */
    fun set(scope: String, feature: String, enabled: Boolean) {
        val id = normalize(feature)
        val scopeId = normalizeScope(scope)
        scopeFlags(scopeId)[id] = enabled
        syncKey(scopeId, id)
    }

    /**
     * Drop [scope]'s entry for [feature] so it falls back to the next layer. True if one existed here.
     */
    fun remove(scope: String, feature: String): Boolean {
        val id = normalize(feature)
        val scopeId = normalizeScope(scope)
        val removed = flags[scopeId]?.remove(id) != null
        syncKey(scopeId, id)
        return removed
    }

    /**
     * Permanently delete [feature] from every scope (registered default plus all overrides).
     */
    fun delete(feature: String) {
        val id = normalize(feature)
        flags.values.forEach { it.remove(id) }
        persist("delete $id") {
            database?.update("DELETE FROM $TABLE WHERE feature = ?", id)
            messenger.publish(CHANNEL, sync(ALL_SCOPES_MARKER, id, REMOVE_MARKER))
        }
    }

    /**
     * Re-read everything from the database. False (and no change) when there is no database.
     */
    fun reload(): Boolean {
        if (database == null) return false
        persist("reload") { load() }
        return true
    }

    /**
     * [scope]'s entry for [feature]: true/false if overridden here, null if this layer is silent.
     */
    fun override(scope: String, feature: String): Boolean? =
        flags[normalizeScope(scope)]?.get(normalize(feature))

    /**
     * Every known feature ID, across all scopes.
     */
    fun features(): SortedSet<String> = flags.values.flatMapTo(TreeSet()) { it.keys }

    /**
     * Snapshot of every non-empty scope and its entries, sorted for display.
     */
    fun scopes(): SortedMap<String, SortedMap<String, Boolean>> {
        val out = TreeMap<String, SortedMap<String, Boolean>>()
        for ((scope, entries) in flags) {
            if (entries.isNotEmpty()) out[scope] = TreeMap(entries)
        }
        return out
    }

    /**
     * The scope name holding [player]'s personal overrides.
     */
    fun playerScope(player: UUID): String = PLAYER_SCOPE_PREFIX + player

    /**
     * Apply the stored flags over what is already in memory, dropping only what a previous load saw
     * and this one did not.
     *
     * Not a wholesale replace. This runs on the database thread while modules are still calling
     * [register] on the main one, and a replace deletes any default registered since the query was
     * issued: the flag then answers its default correctly but vanishes from `/cryon flags`, so the
     * admin loses the kill switch for a feature that is running. Tracking what the last load
     * contributed ([loaded]) keeps a flag deleted on another node from surviving here, without
     * claiming ownership of entries this process registered itself.
     */
    private suspend fun load() {
        val rows = database!!.query("SELECT scope, feature, enabled FROM $TABLE") {
            Triple(it.getString(1), it.getString(2), it.getBoolean(3))
        }
        val stored = HashSet<Pair<String, String>>(rows.size)
        for ((scopeId, feature, enabled) in rows) {
            scopeFlags(scopeId)[feature] = enabled
            stored += scopeId to feature
        }
        for ((scopeId, feature) in loaded - stored) flags[scopeId]?.remove(feature)
        loaded = stored
        logger.info("Loaded {} feature flags", rows.size)
    }

    /**
     * Apply a broadcast from another server (or our own echo, idempotent either way).
     */
    private fun onSync(message: String) {
        val parts = message.split(SEPARATOR)
        if (parts.size != 3) return
        val (scope, feature, value) = parts
        when (value) {
            REMOVE_MARKER if scope == ALL_SCOPES_MARKER -> flags.values.forEach { it.remove(feature) }
            REMOVE_MARKER -> flags[scope]?.remove(feature)
            else -> scopeFlags(scope)[feature] = value.toBoolean()
        }
        logger.info("Feature flag sync: {}/{} = {}", scope, feature, value)
    }

    private fun scopeFlags(scope: String): ConcurrentHashMap<String, Boolean> {
        val entries = flags.computeIfAbsent(scope) { ConcurrentHashMap() }
        playerUuidOf(scope)?.let {
            playerOverrides[it] = entries
            hasPlayerOverrides = true
        }
        return entries
    }

    /**
     * The UUID a `player:<uuid>` scope names, or null for any other scope (or a malformed one).
     */
    private fun playerUuidOf(scope: String): UUID? {
        if (!scope.startsWith(PLAYER_SCOPE_PREFIX)) return null
        return runCatching { UUID.fromString(scope.substring(PLAYER_SCOPE_PREFIX.length)) }.getOrNull()
    }

    private fun sync(scope: String, feature: String, value: String): String =
        "$scope$SEPARATOR$feature$SEPARATOR$value"

    private fun normalize(feature: String): String =
        if (isCanonical(feature)) feature else feature.trim().uppercase()

    private fun isCanonical(feature: String): Boolean {
        if (feature.isEmpty()) return false
        for (c in feature) {
            if (c !in 'A'..'Z' && c !in '0'..'9' && c != '_') return false
        }
        return true
    }

    private fun normalizeScope(scope: String): String =
        scope.trim().let { if (it.startsWith(PLAYER_SCOPE_PREFIX)) it else it.lowercase() }

    companion object {
        const val GLOBAL_SCOPE = "global"
        const val PLAYER_SCOPE_PREFIX = "player:"
        private const val ALL_SCOPES_MARKER = "*"
        private const val REMOVE_MARKER = "REMOVE"
        private const val SEPARATOR = '\u0000'
        private const val CHANNEL = "cryon:flags:sync"
        private const val TABLE = "cryon_feature_flags"
        private val KEYS = listOf("scope", "feature")
        private val COLUMNS = listOf("scope", "feature", "enabled")
    }
}
