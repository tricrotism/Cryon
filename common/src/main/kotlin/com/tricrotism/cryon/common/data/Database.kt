package com.tricrotism.cryon.common.data

import java.sql.ResultSet

/**
 * Async SQL access: every call is a `suspend` function that runs its statement on the query
 * executor, so callers never block a server thread. A thin primitive over a pooled connection (no
 * ORM); features run their own SQL. Shared via the module `ServiceRegistry` when `database.enabled`
 * is set.
 *
 * **Suspending, not blocking.** A caller is released while the statement runs and resumed when it
 * lands, so there is no future to forget to consume and no `.get()` to accidentally park a region
 * thread on. Call these from a module's `scope`, never from a raw scheduler callback.
 */
interface Database {

    // Which backend this is. Needed only where a statement cannot be written portably. See [upsert]
    val dialect: SqlDialect

    /**
     * Run [body]'s statements on **one** connection, inside **one** transaction, off-thread.
     *
     * Every other method here takes a fresh pooled connection and autocommits, which is right for the
     * single statements they run and wrong the moment two writes have to hold together. A pair issued
     * separately is two transactions, and a process that dies between them leaves the half-applied
     * state behind with nothing thrown and nothing logged: no caller can detect it, because from
     * inside the JVM there was no failure. Currency moving between two accounts is the case that
     * forces this to exist.
     *
     * The session is **synchronous**: [body] already runs on the query executor, so the statements
     * are plain blocking calls in order. Returning normally commits, throwing rolls back, and the
     * exception propagates to the caller.
     *
     * [body] is **not** a suspending block, deliberately. A suspension inside a transaction would let
     * the coroutine be resumed on a different thread while the connection, and every row lock it
     * holds, stays bound to this one, which is both a JDBC violation and an unbounded lock hold.
     *
     * Two things a caller has to know. A transaction holds row locks until it ends, so keep [body]
     * short and put no scheduler hop, no future wait, and no lock acquisition inside it. And two
     * transactions touching the same rows in opposite order can deadlock: the backend detects it and
     * aborts one, which surfaces here as an exception, so anything that could interleave that way
     * needs a bounded retry rather than a single attempt.
     */
    suspend fun <T> transaction(body: (SqlSession) -> T): T

    /**
     * Run a query and map each row, off-thread. Trailing-lambda friendly: `query(sql, a, b) { rs -> … }`.
     */
    suspend fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T>

    /**
     * Run an INSERT/UPDATE/DELETE/DDL and return the affected row count, off-thread.
     */
    suspend fun update(sql: String, vararg params: Any?): Int

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
     * at runtime, often inside a launched coroutine where nothing is waiting to notice.
     */
    suspend fun upsert(
        table: String,
        keys: List<String>,
        columns: List<String>,
        vararg params: Any?,
    ): Int = update(dialect.upsert(table, keys, columns), *params)

    /**
     * As [upsert], but an existing row is left exactly as it is.
     *
     * The count answers "did I insert it": greater than zero means this caller created the row, zero
     * means it was already there. That makes this usable as a first-writer gate. See the portability
     * note on [upsertIfGreater] before comparing the number to anything but zero.
     */
    suspend fun insertIfAbsent(
        table: String,
        keys: List<String>,
        columns: List<String>,
        vararg params: Any?,
    ): Int = update(dialect.insertIfAbsent(table, keys, columns), *params)

    /**
     * As [upsert], but an existing row is overwritten only when the incoming [guard] column is
     * strictly greater than the stored one. See [SqlDialect.upsertIfGreater].
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
     * JDBC URL. Connector/J's default reports rows *matched*, under which a refused write is
     * indistinguishable from an applied one. Do not drop that parameter.
     */
    suspend fun upsertIfGreater(
        table: String,
        keys: List<String>,
        columns: List<String>,
        guard: String,
        vararg params: Any?,
    ): Int = update(dialect.upsertIfGreater(table, keys, columns, guard), *params)

    /**
     * Create [indexes] on [table], for the backends that take them as separate statements.
     *
     * Pair with `dialect.inlineIndexes(indexes)` inside the `CREATE TABLE` body and call this right
     * after: exactly one of the two does the work, so the same schema code covers all three backends.
     * A no-op on MySQL, where the inline form already made them.
     */
    suspend fun createIndexes(table: String, indexes: List<SqlIndex>) {
        for (statement in dialect.indexStatements(table, indexes)) update(statement)
    }

    fun close()
}

