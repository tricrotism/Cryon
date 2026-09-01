package com.tricrotism.cryon.common.data

import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * [Repository] over a [Database]. See that interface for the consistency model before using it.
 *
 * A checkpoint that cannot reach the database keeps its dirty marks and retries, and also writes the
 * rows to a local [SpillStore] so they survive the process ending while the database is still down.
 * See that type for why only this path spills and the currency ledger does not.
 *
 * @param table the table, which must already exist with [Repository.BASE_COLUMNS_DDL] plus the
 *   codec's columns. Creating it belongs in the feature's own `migrate` call, not here. Schema is
 *   `SchemaMigrator`'s job and duplicating it would give one table two owners.
 */
class SqlRepository<T : Any>(
    private val database: Database,
    private val table: String,
    private val codec: RowCodec<T>,
    private val logger: Logger,
) : Repository<T> {

    /**
     * The cached value plus the row version it was read at, so a flush can guard on it.
     */
    private class Entry<T>(val value: T, val version: Long)

    private val cache = ConcurrentHashMap<String, Entry<T>>()

    // Ids staged since the last flush.
    //
    // A set of ids rather than a map of pending values, deliberately: staging the same id twice
    // between checkpoints should write once, and the value to write is whatever [cache] holds at
    // flush time rather than whichever copy was staged first
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    private val spill = SpillStore.forTable(table, logger)

    // The in-memory mirror of the spill file: rows a checkpoint failed to write, encoded.
    //
    // They are held encoded rather than as `T` because recovering one from disk cannot rebuild a
    // value, [RowCodec] only reads from a `ResultSet`. They do not need to be values: a recovered row
    // is only ever written, never read back through [cached]
    private val spilled = ConcurrentHashMap<String, SpilledRow>()

    private val selectColumns = (codec.columns + VERSION).joinToString(", ")

    private val selectSql = "SELECT $selectColumns FROM $table WHERE id = ?"

    // `SET col = ?, … , version = ? WHERE id = ? AND version = ?`, the guarded write
    private val updateSql = buildString {
        append("UPDATE ").append(table).append(" SET ")
        codec.columns.joinTo(this, ", ") { "$it = ?" }
        append(", ").append(VERSION).append(" = ? WHERE id = ? AND ").append(VERSION).append(" = ?")
    }

    private val insertColumns = listOf("id") + codec.columns + VERSION

    init {
        val recovered = spill?.read().orEmpty()
        for (row in recovered) spilled[row.id] = row
        if (recovered.isNotEmpty()) {
            logger.warn(
                "Recovered {} unwritten row(s) for {} from the previous run; the next checkpoint writes them",
                recovered.size, table,
            )
        }
    }

    override fun cached(id: String): T? = cache[id]?.value

    override suspend fun get(id: String): T? = cached(id) ?: load(id)

    override suspend fun load(id: String): T? {
        // A recovered row is newer than what SQL holds, so it has to land before a read of the same
        // id can be trusted. One attempt: a database still unreachable throws out of the query below
        // anyway, and the guards keep a stale row out of the cache if it somehow does not.
        if (spilled.containsKey(id)) {
            runCatching { flush() }.onFailure {
                logger.warn("Could not write recovered rows for {} before loading {}", table, id, it)
            }
        }

        val entry = database.query(selectSql, id) { row ->
            Entry(codec.read(row), row.getLong(codec.columns.size + 1))
        }.firstOrNull()
        if (entry == null) {
            // Absent in SQL means absent, so drop any stale cached copy, but only if nothing has
            // been staged for it since, or a read would silently discard an unflushed write.
            if (id !in dirty && !spilled.containsKey(id)) cache.remove(id)
            return null
        }
        // Same guard: a read that lands after a stage must not overwrite the newer in-memory value.
        if (id !in dirty && !spilled.containsKey(id)) cache[id] = entry
        return entry.value
    }

    override fun stage(id: String, value: T) {
        // The version is carried forward from whatever was last read or written, so the flush guards
        // against the row having moved underneath this server rather than against its own writes.
        cache.compute(id) { _, existing -> Entry(value, existing?.version ?: ABSENT) }
        dirty += id
    }

    override suspend fun put(id: String, value: T) {
        // Deliberately not via [stage]: a write-through has no reason to leave a dirty mark behind
        // for the next checkpoint to write a second time.
        val entry = cache.compute(id) { _, existing -> Entry(value, existing?.version ?: ABSENT) }!!
        val row = SpilledRow(id, codec.write(entry.value), entry.version)
        val written = try {
            writeAll(listOf(row))
        } catch (t: Throwable) {
            // This is the path a caller reached for precisely because the value could not wait, so
            // it spills on its own rather than waiting for a checkpoint that may never come: nothing
            // marked it dirty.
            spillAll(spilled.values.filter { it.id != id } + row)
            throw t
        }
        bumpVersions(listOf(id to entry))
        report(written, 1)
    }

    override suspend fun delete(id: String): Boolean {
        dirty -= id
        cache.remove(id)
        if (spilled.remove(id) != null) spillAll(spilled.values.toList())
        return database.update("DELETE FROM $table WHERE id = ?", id) > 0
    }

    override fun evict(id: String) {
        // Evicting a staged id drops its value: the next checkpoint finds no cached entry for a
        // dirty mark and clears the mark unwritten. [Repository.evict] says to flush first, and a
        // caller that does not is losing exactly the write this type exists to make, silently. Point
        // at the ordering bug rather than papering over it, the same way a missed version guard does.
        if (id in dirty) {
            logger.warn(
                "Evicted '{}' from {} while it was still staged, so its last change is lost. " +
                        "Flush before evicting.",
                id, table,
            )
        }
        cache.remove(id)
    }

    override suspend fun flush(): Int {
        if (dirty.isEmpty() && spilled.isEmpty()) return 0

        val ids = HashSet(dirty)
        val staged = ids.mapNotNull { id -> cache[id]?.let { id to it } }
        val stagedIds = staged.mapTo(HashSet()) { it.first }
        // Anything staged since a row was spilled is newer, so the live value wins and the recovered
        // copy is dropped rather than replayed over it.
        val recovered = spilled.values.filter { it.id !in stagedIds }

        if (staged.isEmpty() && recovered.isEmpty()) {
            dirty.removeAll(ids)
            if (spilled.isNotEmpty()) dropSpill()
            return 0
        }

        val rows = staged.map { (id, entry) -> SpilledRow(id, codec.write(entry.value), entry.version) } + recovered

        val written = try {
            writeAll(rows)
        } catch (t: Throwable) {
            // Spill before rethrowing, and spill the compacted set: a row superseded by a newer
            // staged value must not come back on the next boot to overwrite it.
            spillAll(rows)
            throw t
        }

        // The mark is cleared **after** the write, and only where the cached value is still the one
        // that was written. Clearing up front instead loses anything staged while the batch was in
        // flight: its mark would go with the snapshot and its value would never reach the next
        // checkpoint. On a failure nothing is cleared, so a failed checkpoint retries rather than
        // silently dropping what it was carrying.
        for ((id, entry) in staged) {
            if (cache[id]?.value === entry.value) dirty.remove(id)
        }
        bumpVersions(staged)
        if (spilled.isNotEmpty()) dropSpill()
        report(written, rows.size)
        return written
    }

    /**
     * Write [rows] in one transaction: guarded updates first, then inserts for whatever had no row.
     *
     * Two batched statements rather than a portable upsert, because the guard is the point, an
     * upsert would overwrite a row another node had moved, which is precisely the case worth
     * reporting. A row the update misses is either new (insert it) or contended (logged).
     */
    private suspend fun writeAll(rows: List<SpilledRow>): Int {
        if (rows.isEmpty()) return 0

        val existing = rows.filter { it.version != ABSENT }
        val fresh = rows.filter { it.version == ABSENT }

        return database.transaction { session ->
            var count = 0
            if (existing.isNotEmpty()) {
                count += session.batch(updateSql, existing.map { updateRow(it) })
            }
            if (fresh.isNotEmpty()) {
                count += session.batch(
                    database.dialect.insertIfAbsent(table, ID_KEY, insertColumns),
                    fresh.map { insertRow(it) },
                )
            }
            count
        }
    }

    /**
     * Advance the cached version for everything just committed.
     *
     * Only after the transaction commits, so a rollback cannot leave the cache claiming a version the
     * database never reached. Guarded on identity: an entry re-staged mid-flush keeps its own.
     */
    private fun bumpVersions(committed: List<Pair<String, Entry<T>>>) {
        for ((id, entry) in committed) {
            cache.computeIfPresent(id) { _, current ->
                if (current.value === entry.value) Entry(current.value, entry.version + 1) else current
            }
        }
    }

    /**
     * Replace the spill with [rows], keeping the in-memory mirror in step.
     *
     * A failure here is the only case where rows exist nowhere but memory, so it is logged loudly.
     * It cannot rethrow: the caller is already unwinding the database failure that got it here, and
     * that is the error worth surfacing.
     */
    private fun spillAll(rows: List<SpilledRow>) {
        val store = spill ?: return
        spilled.clear()
        for (row in rows) spilled[row.id] = row
        runCatching { store.write(rows) }.onFailure {
            logger.error(
                "Could not spill {} row(s) for {} to disk, so they will not survive a restart",
                rows.size, table, it,
            )
        }
    }

    private fun dropSpill() {
        spilled.clear()
        spill?.clear()
    }

    private fun report(written: Int, expected: Int) {
        if (written < expected) {
            // Reachable when a guarded update matched no row, which under the single-owner model this
            // type assumes should be impossible: another node wrote a row this server believed it
            // owned. Worth a line. It points at a handoff bug, not a SQL one.
            logger.warn(
                "Flushed {} of {} rows to {}; the rest were written by another node",
                written, expected, table,
            )
        }
    }

    /**
     * Value columns, then the new version, then the `WHERE id = ? AND version = ?` guard.
     */
    private fun updateRow(row: SpilledRow): Array<out Any?> {
        val out = ArrayList<Any?>(codec.columns.size + 3)
        out.addAll(row.values)
        out.add(row.version + 1)
        out.add(row.id)
        out.add(row.version)
        return out.toTypedArray()
    }

    /**
     * `id`, then the value columns, then the starting version, matching [insertColumns].
     */
    private fun insertRow(row: SpilledRow): Array<out Any?> {
        val out = ArrayList<Any?>(codec.columns.size + 2)
        out.add(row.id)
        out.addAll(row.values)
        out.add(FIRST)
        return out.toTypedArray()
    }

    private companion object {
        const val VERSION = "version"
        val ID_KEY = listOf("id")

        // Marks a cached value that has never been seen in SQL, so the flush inserts it
        const val ABSENT = -1L

        // The version an inserted row starts at
        const val FIRST = 0L
    }
}
