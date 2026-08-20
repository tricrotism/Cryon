package com.tricrotism.cryon.common.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.sql.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [Database] over a HikariCP pool, for any backend in [SqlDialect] (PostgreSQL, MySQL, or embedded
 * H2). The backend only changes the JDBC URL and driver; all query/update work is plain JDBC.
 * Construct from [DatabaseConfig]; throws if the pool can't initialize (the core catches and degrades
 * gracefully).
 *
 * **Blocking JDBC runs on a daemon pool sized to the connection pool, and the suspending API is a
 * `withContext` onto it.** The pool stays platform-threaded and stays that size on purpose: threads
 * here are not the scarce resource, connections are, so a wider pool would only move the queue from
 * the executor to `getConnection` — where waiting is bounded by Hikari's timeout and surfaces as a
 * thrown exception rather than as backpressure. Bounding at the executor makes a burst of queries
 * wait quietly instead of failing.
 */
class SqlDatabase(config: DatabaseConfig) : Database {

    override val dialect: SqlDialect = config.dialect

    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.dialect.jdbcUrl(config)
        username = config.username
        password = config.password
        maximumPoolSize = config.maxPoolSize
        poolName = "cryon-db"
        driverClassName = config.dialect.driverClass
    })

    private val threadId = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(config.maxPoolSize) { r ->
        Thread(r, "cryon-db-${threadId.incrementAndGet()}").apply { isDaemon = true }
    }

    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    override suspend fun <T> transaction(body: (SqlSession) -> T): T =
        withContext(dispatcher) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val result = body(ConnectionSession(conn))
                    conn.commit()
                    result
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    runCatching { conn.autoCommit = true }
                }
            }
        }

    override suspend fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T> =
        withContext(dispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    bind(stmt, params)
                    stmt.executeQuery().use { rs ->
                        val out = ArrayList<T>()
                        while (rs.next()) out.add(mapper(rs))
                        out
                    }
                }
            }
        }

    override suspend fun update(sql: String, vararg params: Any?): Int =
        withContext(dispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    bind(stmt, params)
                    stmt.executeUpdate()
                }
            }
        }

    /**
     * Drain the query threads before the pool goes away.
     *
     * `shutdown()` only stops new submissions; it does not wait. Closing the [dataSource] straight
     * after would evict connections out from under statements still executing, which on a normal
     * shutdown is every write issued during teardown: module `onDisable` state, the last flag and
     * locale updates, the registry deregister. Those are launched rather than awaited, so the failure
     * would also be silent. Wait the drain out, then force what is left rather than letting one
     * wedged statement hold the server up forever.
     *
     * Module scopes are cancelled before this runs, which unblocks anything *suspended* on the way to
     * a statement — but a thread already inside `executeUpdate` is not at a suspension point and runs
     * to completion regardless. That is what the drain is still here for.
     */
    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        dataSource.close()
    }

    private fun bind(stmt: PreparedStatement, params: Array<out Any?>) {
        for (i in params.indices) stmt.setObject(i + 1, params[i])
    }

    /**
     * [SqlSession] over one borrowed connection. Blocking, because the caller is already off-thread.
     */
    private class ConnectionSession(private val conn: Connection) : SqlSession {

        override fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T> =
            conn.prepareStatement(sql).use { stmt ->
                bind(stmt, params)
                stmt.executeQuery().use { rs ->
                    val out = ArrayList<T>()
                    while (rs.next()) out.add(mapper(rs))
                    out
                }
            }

        override fun update(sql: String, vararg params: Any?): Int =
            conn.prepareStatement(sql).use { stmt ->
                bind(stmt, params)
                stmt.executeUpdate()
            }

        override fun batch(sql: String, rows: List<Array<out Any?>>): Int {
            if (rows.isEmpty()) return 0
            return conn.prepareStatement(sql).use { stmt ->
                for (row in rows) {
                    bind(stmt, row)
                    stmt.addBatch()
                }
                stmt.executeBatch().sumOf { if (it > 0) it else 0 }
            }
        }

        private fun bind(stmt: PreparedStatement, params: Array<out Any?>) {
            for (i in params.indices) stmt.setObject(i + 1, params[i])
        }
    }

    companion object {

        /** How long [close] waits for in-flight statements before forcing the query threads down. */
        private const val DRAIN_TIMEOUT_SECONDS = 5L

        /**
         * Connect, creating the database first if that is the only thing wrong.
         *
         * A fresh deployment points at a database nobody has created yet, and the failure that
         * produces is indistinguishable at a glance from the ones that mean something else, so the
         * server would come up with no flags, no locale store and no metrics over a missing `CREATE
         * DATABASE`. This closes that gap and nothing else: [SqlDialect.isMissingDatabase] answers
         * true for exactly one error, so a refused connection, a bad password or an absent
         * `CREATEDB` grant still fail the way they always did, with their own message.
         *
         * One retry, never a loop. If the second connect fails the original exception is what
         * propagates. The creation was a guess and the first error is the one worth reading.
         */
        fun connect(config: DatabaseConfig, logger: Logger): SqlDatabase = try {
            SqlDatabase(config)
        } catch (failure: Exception) {
            // Hikari wraps the driver's SQLException in a PoolInitializationException, so the reason
            // is down the cause chain rather than on the throwable we caught.
            val missing = failure.causes()
                .filterIsInstance<SQLException>()
                .any { config.dialect.isMissingDatabase(it) }
            if (!missing) throw failure

            logger.warn("Database '{}' does not exist, creating it", config.database)
            createDatabase(config)
            logger.info("Created database '{}'", config.database)
            SqlDatabase(config)
        }

        /**
         * Run the backend's `CREATE DATABASE` against its maintenance URL.
         *
         * The driver is instantiated directly rather than gone through `DriverManager`, which only
         * sees drivers registered by the caller's own classloader, and these drivers are loaded at
         * runtime from `plugin.yml` `libraries:`, one loader away. Autocommit is on for a fresh
         * connection, which Postgres requires: it refuses `CREATE DATABASE` inside a transaction.
         */
        private fun createDatabase(config: DatabaseConfig) {
            val url = config.dialect.maintenanceUrl(config) ?: return
            val sql = config.dialect.createDatabaseSql(config) ?: return
            val driver = Class.forName(config.dialect.driverClass)
                .getDeclaredConstructor()
                .newInstance() as Driver
            val credentials = Properties().apply {
                setProperty("user", config.username)
                setProperty("password", config.password)
            }
            driver.connect(url, credentials).use { conn ->
                conn.createStatement().use { it.executeUpdate(sql) }
            }
        }

        /** This throwable and every cause behind it, stopping on a self-referencing chain. */
        private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { current ->
            current.cause?.takeIf { it !== current }
        }
    }
}
