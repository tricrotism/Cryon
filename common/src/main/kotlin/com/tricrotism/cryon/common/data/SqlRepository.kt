package com.tricrotism.cryon.common.data

import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * [Repository] over a [Database]. See that interface for the consistency model before using it.
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

    /** The cached value plus the row version it was read at, so a flush can guard on it. */
    private class Entry<T>(val value: T, val version: Long)

    private val cache = ConcurrentHashMap<String, Entry<T>>()

    /**
     * Ids staged since the last flush.
     *
     * A set of ids rather than a map of pending values, deliberately: staging the same id twice
     * between checkpoints should write once, and the value to write is whatever [cache] holds at
     * flush time rather than whichever copy was staged first.
     */
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    private val selectColumns = (codec.columns + VERSION).joinToString(", ")

    private val selectSql = "SELECT $selectColumns FROM $table WHERE id = ?"

    /** `SET col = ?, … , version = ? WHERE id = ? AND version = ?`, the guarded write. */
    private val updateSql = buildString {
        append("UPDATE ").append(table).append(" SET ")
        codec.columns.joinTo(this, ", ") { "$it = ?" }
        append(", ").append(VERSION).append(" = ? WHERE id = ? AND ").append(VERSION).append(" = ?")
    }

    private val insertColumns = listOf("id") + codec.columns + VERSION

    override fun cached(id: String): T? = cache[id]?.value

    override suspend fun get(id: String): T? = cached(id) ?: load(id)

    override suspend fun load(id: String): T? {
        val entry = database.query(selectSql, id) { row ->
            Entry(codec.read(row), row.getLong(codec.columns.size + 1))
        }.firstOrNull()
        if (entry == null) {
            // Absent in SQL means absent, so drop any stale cached copy, but only if nothing has
            // been staged for it since, or a read would silently discard an unflushed write.
            if (id !in dirty) cache.remove(id)
            return null
        }
        // Same guard: a read that lands after a stage must not overwrite the newer in-memory value.
        if (id !in dirty) cache[id] = entry
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
        writeAll(listOf(id to entry))
    }

    override suspend fun delete(id: String): Boolean {
        dirty -= id
        cache.remove(id)
        return database.update("DELETE FROM $table WHERE id = ?", id) > 0
    }

    override fun evict(id: String) {
        cache.remove(id)
    }

    override suspend fun flush(): Int {
        if (dirty.isEmpty()) return 0
        val ids = HashSet(dirty)
        val pending = ids.mapNotNull { id -> cache[id]?.let { id to it } }
        if (pending.isEmpty()) {
            dirty.removeAll(ids)
            return 0
        }

        val written = writeAll(pending)

        // The mark is cleared **after** the write, and only where the cached value is still the one
        // that was written. Clearing up front instead loses anything staged while the batch was in
        // flight: its mark would go with the snapshot and its value would never reach the next
        // checkpoint. On a failure nothing is cleared, so a failed checkpoint retries rather than
        // silently dropping what it was carrying.
        for ((id, entry) in pending) {
            if (cache[id]?.value === entry.value) dirty.remove(id)
        }
        return written
    }

    /**
     * Write [pending] in one transaction: guarded updates first, then inserts for whatever had no row.
     *
     * Two batched statements rather than a portable upsert, because the guard is the point, an
     * upsert would overwrite a row another node had moved, which is precisely the case worth
     * reporting. A row the update misses is either new (insert it) or contended (logged).
     */
    private suspend fun writeAll(pending: List<Pair<String, Entry<T>>>): Int {
        if (pending.isEmpty()) return 0

        val existing = pending.filter { it.second.version != ABSENT }
        val fresh = pending.filter { it.second.version == ABSENT }

        val written = database.transaction { session ->
            var count = 0
            if (existing.isNotEmpty()) {
                count += session.batch(updateSql, existing.map { (id, entry) -> updateRow(id, entry) })
            }
            if (fresh.isNotEmpty()) {
                count += session.batch(
                    database.dialect.insertIfAbsent(table, ID_KEY, insertColumns),
                    fresh.map { (id, entry) -> insertRow(id, entry) },
                )
            }
            count
        }

        // Only after the transaction commits, so a rollback cannot leave the cache claiming a version
        // the database never reached. Guarded on identity: an entry re-staged mid-flush keeps its own.
        for ((id, entry) in pending) {
            cache.computeIfPresent(id) { _, current ->
                if (current.value === entry.value) Entry(current.value, entry.version + 1) else current
            }
        }

        if (written < pending.size) {
            // Reachable when a guarded update matched no row, which under the single-owner model this
            // type assumes should be impossible: another node wrote a row this server believed it
            // owned. Worth a line. It points at a handoff bug, not a SQL one.
            logger.warn(
                "Flushed {} of {} rows to {}; the rest were written by another node",
                written, pending.size, table,
            )
        }
        return written
    }

    /** Value columns, then the new version, then the `WHERE id = ? AND version = ?` guard. */
    private fun updateRow(id: String, entry: Entry<T>): Array<out Any?> {
        val out = ArrayList<Any?>(codec.columns.size + 3)
        out.addAll(codec.write(entry.value))
        out.add(entry.version + 1)
        out.add(id)
        out.add(entry.version)
        return out.toTypedArray()
    }

    /** `id`, then the value columns, then the starting version, matching [insertColumns]. */
    private fun insertRow(id: String, entry: Entry<T>): Array<out Any?> {
        val out = ArrayList<Any?>(codec.columns.size + 2)
        out.add(id)
        out.addAll(codec.write(entry.value))
        out.add(FIRST)
        return out.toTypedArray()
    }

    private companion object {
        const val VERSION = "version"
        val ID_KEY = listOf("id")

        /** Marks a cached value that has never been seen in SQL, so the flush inserts it. */
        const val ABSENT = -1L

        /** The version an inserted row starts at. */
        const val FIRST = 0L
    }
}
