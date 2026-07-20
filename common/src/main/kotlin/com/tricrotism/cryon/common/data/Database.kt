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

    /** Run a query and map each row, off-thread. Trailing-lambda friendly: `query(sql, a, b) { rs -> … }`. */
    fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): CompletableFuture<List<T>>

    /** Run an INSERT/UPDATE/DELETE/DDL and return the affected row count, off-thread. */
    fun update(sql: String, vararg params: Any?): CompletableFuture<Int>

    fun close()
}
