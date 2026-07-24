package com.tricrotism.cryon.common.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.Driver
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * [Database] over a HikariCP pool, for any backend in [SqlDialect] (PostgreSQL, MySQL, or embedded
 * H2). The backend only changes the JDBC URL and driver; all query/update work is plain JDBC.
 * Blocking JDBC runs on a daemon pool sized to the connection pool, exposed through
 * [CompletableFuture]s. Construct from [DatabaseConfig]; throws if the pool can't initialize (the
 * core catches and degrades gracefully).
 */
class SqlDatabase(config: DatabaseConfig) : Database {

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

    override fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): CompletableFuture<List<T>> =
        CompletableFuture.supplyAsync({
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
        }, executor)

    override fun update(sql: String, vararg params: Any?): CompletableFuture<Int> =
        CompletableFuture.supplyAsync({
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    bind(stmt, params)
                    stmt.executeUpdate()
                }
            }
        }, executor)

    override fun close() {
        executor.shutdown()
        dataSource.close()
    }

    private fun bind(stmt: PreparedStatement, params: Array<out Any?>) {
        for (i in params.indices) stmt.setObject(i + 1, params[i])
    }

    companion object {

        /**
         * Connect, creating the database first if that is the only thing wrong.
         *
         * A fresh deployment points at a database nobody has created yet, and the failure that
         * produces is indistinguishable at a glance from the ones that mean something else — so the
         * server would come up with no flags, no locale store and no metrics over a missing `CREATE
         * DATABASE`. This closes that gap and nothing else: [SqlDialect.isMissingDatabase] answers
         * true for exactly one error, so a refused connection, a bad password or an absent
         * `CREATEDB` grant still fail the way they always did, with their own message.
         *
         * One retry, never a loop. If the second connect fails the original exception is what
         * propagates — the creation was a guess and the first error is the one worth reading.
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
         * sees drivers registered by the caller's own classloader — and these drivers are loaded at
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
