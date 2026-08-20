package com.tricrotism.cryon.common.data

import java.sql.ResultSet

/**
 * How one value maps onto its row. The feature still owns its schema and its columns; this only says
 * how to get a value in and out of them.
 *
 * [columns] excludes `id` and `version`, which every repository table carries and [Repository] owns.
 * [write] must emit values in exactly [columns] order, and [read] must consume them in that order
 * starting at index 1 — the repository builds its `SELECT` from [columns], so the two line up as
 * long as neither is reordered independently.
 */
interface RowCodec<T : Any> {

    val columns: List<String>

    fun write(value: T): Array<out Any?>

    fun read(row: ResultSet): T
}

/**
 * A write-behind keyed store over one SQL table: reads served from memory, writes accumulated and
 * flushed in batches.
 *
 * **This is the shape five features had already hand-rolled** — a `ConcurrentHashMap`, a dirty flag,
 * a periodic save that rewrites everything, and a load on join — each with its own bugs. What it is
 * *not* is an ORM: the feature declares its own table (through `migrate`) and its own [RowCodec], and
 * no query is generated beyond the four this needs.
 *
 * **The consistency model is single-owner, and that is a real precondition rather than a shrug.**
 * Writes are last-write-wins, which is correct exactly because `PlayerHandoff` guarantees one server
 * owns a player's state at a time: the source instance flushes before the target loads. Use this for
 * state that follows a player. Do **not** use it for anything several servers write at once — that
 * is what `CurrencyService`'s compare-and-set is for, and a balance flushed from two nodes would lose
 * one node's writes wholesale.
 *
 * The `version` column exists to *detect* a violation of that precondition rather than to resolve it:
 * a flush whose guard misses means somebody else wrote the row while this server thought it owned it,
 * which is a handoff bug worth a log line, not something to silently merge.
 *
 * Every table needs `id VARCHAR(64) NOT NULL PRIMARY KEY` and `version BIGINT NOT NULL DEFAULT 0`
 * alongside the codec's columns — see [BASE_COLUMNS_DDL].
 */
interface Repository<T : Any> {

    /**
     * The cached value for [id], or null when this process has not loaded one.
     *
     * Synchronous and safe on a tick thread — that is the point of the cache. **Null means "not
     * loaded here", never "absent"**; the same distinction `CurrencyService.cachedBalance` draws, and
     * for the same reason: deciding anything from a null you have not proved is a null read.
     */
    fun cached(id: String): T?

    /** The stored value, reading through to SQL on a miss and populating the cache. */
    suspend fun get(id: String): T?

    /** Load [id] into the cache. Call on join. Returns what was loaded, if anything. */
    suspend fun load(id: String): T?

    /**
     * Replace [id]'s value in memory and mark it for the next [flush].
     *
     * Synchronous: the write a player can observe is the one in memory, and the durable one follows
     * at the checkpoint. That is the trade the whole type exists to make — per-event SQL is what it
     * removes — so anything that must survive an unclean kill immediately wants [put] instead.
     */
    fun stage(id: String, value: T)

    /** Write [id] through to SQL now, and cache it. For the rare value that cannot wait. */
    suspend fun put(id: String, value: T)

    /** Remove [id] from memory and from SQL. */
    suspend fun delete(id: String): Boolean

    /** Drop [id] from the cache without touching SQL. Call on quit, **after** the last flush. */
    fun evict(id: String)

    /**
     * Write every staged change, in one transaction and one batched statement per operation.
     *
     * Returns how many rows were written. Safe to call on an interval and again on disable; a flush
     * with nothing staged does no I/O at all. Entries staged *while* a flush runs are kept for the
     * next one rather than being lost.
     */
    suspend fun flush(): Int

    companion object {
        /**
         * The two columns every repository table carries, for pasting into a `CREATE TABLE`.
         *
         * ```
         * Migration(1, "create homes") { it.update("CREATE TABLE IF NOT EXISTS x (${Repository.BASE_COLUMNS_DDL}, …)") }
         * ```
         */
        const val BASE_COLUMNS_DDL =
            "id VARCHAR(64) NOT NULL PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0"
    }
}
