package com.tricrotism.cryon.common.data

import org.slf4j.Logger
import java.util.concurrent.CompletableFuture

/**
 * One forward step in a namespace's schema.
 *
 * [version] is what gets recorded, so it must never be reused or reordered once a migration has run
 * anywhere. [name] appears in the log and nowhere else; write it for whoever reads that log at 3am.
 *
 * [apply] receives the transaction's own session and runs whatever the step needs. It must be safe to
 * fail: an exception rolls the step back (see the MySQL note on [migrate]) and leaves the recorded
 * version where it was, so the next boot retries this same step rather than skipping past it.
 */
class Migration(val version: Int, val name: String, val apply: (SqlSession) -> Unit)

/**
 * Bring [namespace]'s schema up to the highest version in [migrations], and answer how many steps ran.
 *
 * This exists because every module that needed one had written its own. Five separate
 * `*_schema_version` tables shipped in one gamemode, each with its own version constant, its own
 * read-then-ALTER ladder, and its own hand-maintained agreement between the `CREATE` and the `ALTER`.
 * That is a lot of bespoke code guarding player data, written five times to five deadlines.
 *
 * **Forward-only, and versions are permanent.** There is no down migration, because a rollback that
 * has to discard columns is a data-loss decision nobody should be able to make by editing a list.
 * Fix a bad migration by adding another one.
 *
 * **Safe to run from every node at once.** The whole thing happens inside one transaction that takes
 * a row lock on this namespace's version row, so ten shards booting together produce one migration
 * and nine no-ops rather than ten concurrent `ALTER`s.
 *
 * **One caveat, and it is a real one.** MySQL commits implicitly on DDL, so on that backend a step
 * that creates or alters a table cannot roll back and does not hold its lock for the rest of the
 * transaction. Postgres and H2 both do transactional DDL and behave exactly as described above. Keep
 * each migration to a single coherent change and the MySQL failure mode stays diagnosable: the step
 * that failed is named in the log, and the recorded version still points at the step before it.
 *
 * ```
 * database.migrate("pixelmon-storage", listOf(
 *     Migration(1, "create pokemon table") { it.update(CREATE_POKEMON) },
 *     Migration(2, "add form column") { it.update("ALTER TABLE pixelmon_pokemon ADD COLUMN form VARCHAR(24)") },
 * ), logger)
 * ```
 */
fun Database.migrate(
    namespace: String,
    migrations: List<Migration>,
    logger: Logger,
): CompletableFuture<Int> {
    require(namespace.isNotBlank()) { "A migration namespace cannot be blank" }
    val ordered = migrations.sortedBy(Migration::version)
    require(ordered.distinctBy(Migration::version).size == ordered.size) {
        "Namespace '$namespace' declares two migrations with the same version"
    }
    require(ordered.all { it.version > 0 }) { "Migration versions start at 1" }
    if (ordered.isEmpty()) return CompletableFuture.completedFuture(0)

    return update(CREATE_VERSION_TABLE)
        .thenCompose { insertIfAbsent(VERSION_TABLE, VERSION_KEYS, VERSION_COLUMNS, namespace, 0) }
        .thenCompose { transaction { session -> advance(session, namespace, ordered, logger) } }
        .whenComplete { _, error ->
            if (error != null) logger.error("Schema migration failed for '{}'", namespace, error)
        }
}

/**
 * The locked section: read where this namespace is, run what comes after, record the new high-water
 * mark. `FOR UPDATE` is what makes a concurrent boot wait here rather than race the same `ALTER`.
 */
private fun advance(
    session: SqlSession,
    namespace: String,
    migrations: List<Migration>,
    logger: Logger,
): Int {
    val current = session.query(
        "SELECT version FROM $VERSION_TABLE WHERE namespace = ? FOR UPDATE",
        namespace,
    ) { it.getInt(1) }.firstOrNull() ?: 0

    val pending = migrations.filter { it.version > current }
    if (pending.isEmpty()) return 0

    logger.info("Migrating '{}' from v{} ({} step(s) pending)", namespace, current, pending.size)
    for (migration in pending) {
        migration.apply(session)
        logger.info("  v{} {}", migration.version, migration.name)
    }
    session.update(
        "UPDATE $VERSION_TABLE SET version = ? WHERE namespace = ?",
        pending.last().version, namespace,
    )
    return pending.size
}

private const val VERSION_TABLE = "cryon_schema_version"
private val VERSION_KEYS = listOf("namespace")
private val VERSION_COLUMNS = listOf("namespace", "version")

private val CREATE_VERSION_TABLE = """
    CREATE TABLE IF NOT EXISTS $VERSION_TABLE (
        namespace VARCHAR(96) NOT NULL,
        version INT NOT NULL,
        PRIMARY KEY (namespace)
    )
""".trimIndent()
