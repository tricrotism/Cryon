package com.tricrotism.cryon.common.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.PreparedStatement
import java.sql.ResultSet
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
}
