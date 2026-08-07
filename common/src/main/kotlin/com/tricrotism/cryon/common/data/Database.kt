package com.tricrotism.cryon.common.data

import java.sql.ResultSet
import java.util.concurrent.CompletableFuture

/** Connection settings for the SQL backend. [dialect] selects which backend and driver to use. */
data class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int = 10,
    val dialect: SqlDialect = SqlDialect.MYSQL,
)

/**
 * Async SQL access — every call returns a [CompletableFuture] run off the main thread, so callers
 * never block the server. A thin primitive over a pooled connection (no ORM); features run their own
 * SQL. Shared via the module `ServiceRegistry` when `database.enabled` is set.
 */
interface Database {

    /** Which backend this is. Needed only where a statement cannot be written portably — see [upsert]. */
    val dialect: SqlDialect

    /** Run a query and map each row, off-thread. Trailing-lambda friendly: `query(sql, a, b) { rs -> … }`. */
    fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): CompletableFuture<List<T>>

    /** Run an INSERT/UPDATE/DELETE/DDL and return the affected row count, off-thread. */
    fun update(sql: String, vararg params: Any?): CompletableFuture<Int>

    /**
     * Insert a row into [table], overwriting the non-key columns of one whose [keys] already match.
     * [params] are bound in [columns] order, so the two must line up.
     *
     * ```kotlin
     * database.upsert("cryon_player_locale", listOf("uuid"), listOf("uuid", "locale"), id, locale)
     * ```
     *
     * Go through this rather than writing the statement out. Upserts are the one place the SQL above
     * this interface stops being portable: Postgres spells it `ON CONFLICT`, MySQL
     * `ON DUPLICATE KEY UPDATE`, and H2 accepts neither in any mode. A hand-written upsert therefore
     * works on exactly the backend it was written against and throws a syntax error on the other two,
     * at runtime, inside a fire-and-forget future where nothing is waiting to notice.
     */
    fun upsert(
        table: String,
        keys: List<String>,
        columns: List<String>,
        vararg params: Any?,
    ): CompletableFuture<Int> = update(dialect.upsert(table, keys, columns), *params)

    /**
     * As [upsert], but an existing row is left exactly as it is.
     *
     * The count answers "did I insert it": greater than zero means this caller created the row, zero
     * means it was already there. That makes this usable as a first-writer gate — see the portability
     * note on [upsertIfGreater] before comparing the number to anything but zero.
     */
    fun insertIfAbsent(
        table: String,
        keys: List<String>,
        columns: List<String>,
        vararg params: Any?,
    ): CompletableFuture<Int> = update(dialect.insertIfAbsent(table, keys, columns), *params)

    /**
     * As [upsert], but an existing row is overwritten only when the incoming [guard] column is
     * strictly greater than the stored one — see [SqlDialect.upsertIfGreater].
     *
     * The returned count is the answer to "did my write win?", so branch on it rather than dropping
     * it: zero means a newer row was already there and nothing changed.
     *
     * **Compare it against zero and nothing else.** The affected-row count is not portable beyond
     * that: MySQL reports 2 for an update it actually applied, 1 for an insert and 0 for a row left
     * alone, while Postgres and H2 report 1 for either kind of write. `count > 0` means the write
     * landed on all three; `count == 1` is a test that quietly means something different on each.
     *
     * The MySQL half of that holds only because [SqlDialect.MYSQL] pins `useAffectedRows=true` on the
     * JDBC URL — Connector/J's default reports rows *matched*, under which a refused write is
     * indistinguishable from an applied one. Do not drop that parameter.
     */
    fun upsertIfGreater(
        table: String,
        keys: List<String>,
        columns: List<String>,
        guard: String,
        vararg params: Any?,
    ): CompletableFuture<Int> = update(dialect.upsertIfGreater(table, keys, columns, guard), *params)

    /**
     * Create [indexes] on [table], for the backends that take them as separate statements.
     *
     * Pair with `dialect.inlineIndexes(indexes)` inside the `CREATE TABLE` body and call this right
     * after: exactly one of the two does the work, so the same schema code covers all three backends.
     * A no-op on MySQL, where the inline form already made them.
     */
    fun createIndexes(table: String, indexes: List<SqlIndex>): CompletableFuture<Void> {
        val statements = dialect.indexStatements(table, indexes)
        if (statements.isEmpty()) return CompletableFuture.completedFuture(null)
        return CompletableFuture.allOf(*statements.map { update(it) }.toTypedArray())
    }

    fun close()
}
