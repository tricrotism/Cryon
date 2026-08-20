package com.tricrotism.cryon.common.currency

import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.data.SqlIndex
import com.tricrotism.cryon.common.data.SqlSession
import com.tricrotism.cryon.common.number.PackedDecimal
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Where balances actually live, **exactly**.
 *
 * The store speaks [BigDecimal] while the service above it speaks [PackedDecimal], and that split is
 * the whole point. A packed value carries 14 significant figures, which is right for an amount, a
 * display and a multiplier, and wrong for a *running total*: adding 100 to 1e20 in 14 figures is a
 * no-op, so the deposit disappears and the matching purchase is free. The ledger therefore keeps the
 * full value and only the presentation rounds.
 *
 * The mutating primitive is [compareAndSet]: the arithmetic happens in the JVM, so the write must
 * only land if the stored value has not moved since it was read. A losing write is refused and the
 * caller retries against the new balance.
 */
internal interface CurrencyStore {

    suspend fun init()

    /** The stored balance, or null when the player has no account in this currency. */
    suspend fun balance(scope: String, currency: String, player: UUID): BigDecimal?

    /** Every stored balance for [player] in [scope], keyed by currency id. */
    suspend fun balances(scope: String, player: UUID): Map<String, BigDecimal>

    /** Create the account at [starting] if absent. No-op when it already exists. */
    suspend fun open(
        scope: String,
        currency: String,
        player: UUID,
        starting: BigDecimal,
        ranked: Boolean,
    )

    /**
     * Store [next] only if the stored balance is still exactly [expected].
     *
     * False means somebody else wrote first and nothing changed. The caller must re-read and redo
     * its arithmetic. An unconditional write would silently discard whatever landed in between,
     * which for money is a lost deposit or a free purchase.
     */
    suspend fun compareAndSet(
        scope: String,
        currency: String,
        player: UUID,
        expected: BigDecimal,
        next: BigDecimal,
    ): Boolean

    /**
     * Overwrite unconditionally, answering the balance that was there. Administration only, every
     * ordinary path goes through the CAS.
     *
     * The previous value comes back because the change event needs it: read separately it would be
     * whatever the read cache happened to hold, which on a node the player has not touched is the
     * currency's starting balance rather than their actual one.
     */
    suspend fun set(scope: String, currency: String, player: UUID, amount: BigDecimal): BigDecimal?

    /** The [size] highest ranked balances, descending. */
    suspend fun top(scope: String, currency: String, size: Int): List<Pair<UUID, BigDecimal>>

    /**
     * Move [amount] from one account to the other, **all or nothing**.
     *
     * Null means [from] could not afford it and neither row was touched. An exception means the move
     * did not happen, not that it half happened: that is the whole reason this is one call rather
     * than a withdraw followed by a deposit. Two separate writes cannot be made safe from above,
     * because the gap between them is not an error path — a process killed there leaves the money
     * gone with nothing thrown and nothing to log.
     */
    suspend fun transfer(
        scope: String,
        currency: String,
        from: UUID,
        to: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
        allowNegative: Boolean,
    ): TransferMove?
}

/** The four balances a completed [CurrencyStore.transfer] moved between, for the change events. */
internal data class TransferMove(
    val fromBefore: BigDecimal,
    val fromAfter: BigDecimal,
    val toBefore: BigDecimal,
    val toAfter: BigDecimal,
)

/**
 * Canonical text for an exact balance, and back.
 *
 * The authoritative column is a string rather than a `DECIMAL` because `DECIMAL` has a fixed maximum
 * precision. 65 digits on MySQL, and [PackedDecimal] ranges to 10^32767. Text has no ceiling, so
 * the ledger can represent anything the number type can, exactly.
 *
 * `toPlainString` is what makes the compare-and-set sound: it is a *canonical* form, so two equal
 * values always produce the same characters and the `WHERE exact = ?` guard compares like for like.
 * `BigDecimal.toString` would not do, it switches to scientific notation for small scales, giving
 * one value two spellings.
 *
 * The column is [SqlDialect.longText] and not a `VARCHAR(n)` for the same reason it is not a
 * `DECIMAL`. A width picked here is a balance ceiling picked here, and the way a caller meets it is
 * a failing write: harmless on a deposit, which now propagates, and money destroyed on a transfer,
 * whose debit has already landed by the time the credit is refused.
 */
internal object ExactBalance {
    fun encode(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
    fun decode(text: String): BigDecimal = BigDecimal(text)
}

/**
 * SQL-backed balances: one table for every currency, keyed by `(scope, currency, uuid)`.
 *
 * One table rather than mchub2's table-per-currency, which is what makes a new currency a
 * registration call instead of a migration.
 *
 * `exact` is the authority. The other three columns are **derived on every write and never read back
 * as truth**: they exist only so the database can order what a string cannot: `magnitude` for the
 * cross-exponent order, `positive` to keep negatives out of a ranking, and `packed` (the
 * [PackedDecimal.raw] bits) to break ties inside one magnitude, where the exponent is fixed and the
 * bits therefore order by mantissa.
 */
internal class SqlCurrencyStore(private val database: Database) : CurrencyStore {

    override suspend fun init() {
        database.update(
            """
        CREATE TABLE IF NOT EXISTS $TABLE (
            scope VARCHAR(96) NOT NULL,
            currency VARCHAR(64) NOT NULL,
            uuid VARCHAR(36) NOT NULL,
            exact ${database.dialect.longText} NOT NULL,
            packed BIGINT NOT NULL,
            magnitude INT NOT NULL,
            positive BOOLEAN NOT NULL,
            ranked BOOLEAN NOT NULL DEFAULT TRUE,
            PRIMARY KEY (scope, currency, uuid)${database.dialect.inlineIndexes(INDEXES)}
        )
        """.trimIndent()
        )
        database.createIndexes(TABLE, INDEXES)
    }

    override suspend fun balance(scope: String, currency: String, player: UUID): BigDecimal? =
        database.query(
            "SELECT exact FROM $TABLE WHERE scope = ? AND currency = ? AND uuid = ?",
            scope, currency, player.toString(),
        ) { ExactBalance.decode(it.getString(1)) }.firstOrNull()

    override suspend fun balances(scope: String, player: UUID): Map<String, BigDecimal> =
        database.query(
            "SELECT currency, exact FROM $TABLE WHERE scope = ? AND uuid = ?",
            scope, player.toString(),
        ) { it.getString(1) to ExactBalance.decode(it.getString(2)) }.toMap()

    override suspend fun open(
        scope: String,
        currency: String,
        player: UUID,
        starting: BigDecimal,
        ranked: Boolean,
    ) {
        database.insertIfAbsent(
            TABLE, KEYS, COLUMNS,
            scope, currency, player.toString(), *derived(starting), ranked,
        )
    }

    override suspend fun compareAndSet(
        scope: String,
        currency: String,
        player: UUID,
        expected: BigDecimal,
        next: BigDecimal,
    ): Boolean = database.update(
        "UPDATE $TABLE SET exact = ?, packed = ?, magnitude = ?, positive = ? " +
                "WHERE scope = ? AND currency = ? AND uuid = ? AND exact = ?",
        *derived(next), scope, currency, player.toString(), ExactBalance.encode(expected),
    ) > 0

    /**
     * Read and overwrite in one transaction, so the value reported back is the one this write
     * replaced rather than one an interleaving write had already moved on from.
     */
    override suspend fun set(
        scope: String,
        currency: String,
        player: UUID,
        amount: BigDecimal,
    ): BigDecimal? = database.transaction { session ->
        val previous = read(session, scope, currency, player)
        session.update(
            database.dialect.upsert(TABLE, KEYS, VALUE_COLUMNS),
            scope, currency, player.toString(), *derived(amount),
        )
        previous
    }

    override suspend fun top(
        scope: String,
        currency: String,
        size: Int,
    ): List<Pair<UUID, BigDecimal>> = database.query(
        "SELECT uuid, exact FROM $TABLE " +
                "WHERE scope = ? AND currency = ? AND ranked = ? AND positive = ? " +
                "ORDER BY magnitude DESC, packed DESC LIMIT $size",
        scope, currency, true, true,
    ) { UUID.fromString(it.getString(1)) to ExactBalance.decode(it.getString(2)) }

    /**
     * Both writes in one transaction, so there is no instant at which the money is nowhere.
     *
     * The compare-and-set guards stay inside it. They are no longer defending against a lost update
     * (the transaction's row locks do that) but against a stale read *within* this attempt, and a
     * refusal costs a rollback and one retry instead of a wrong balance. Ordering the two accounts
     * by uuid keeps concurrent opposite-direction transfers from deadlocking each other; the retry
     * covers the case the backend detects one anyway and aborts us.
     */
    override suspend fun transfer(
        scope: String,
        currency: String,
        from: UUID,
        to: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
        allowNegative: Boolean,
    ): TransferMove? {
        repeat(MAX_TRANSFER_ATTEMPTS) {
            when (val outcome = attemptTransfer(scope, currency, from, to, amount, starting, allowNegative)) {
                Refused -> return null
                Contended -> Unit
                else -> return outcome as TransferMove
            }
        }

        throw IllegalStateException("Gave up moving $currency from $from to $to after contention")
    }

    private suspend fun attemptTransfer(
        scope: String,
        currency: String,
        from: UUID,
        to: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
        allowNegative: Boolean,
    ): Any? = database.transaction { session ->
        val insert = database.dialect.insertIfAbsent(TABLE, KEYS, COLUMNS)
        for (player in listOf(from, to).sortedBy(UUID::toString)) {
            session.update(insert, scope, currency, player.toString(), *derived(starting), true)
        }

        val fromBefore = read(session, scope, currency, from) ?: starting
        val fromAfter = fromBefore.subtract(amount)
        if (!allowNegative && fromAfter.signum() < 0) return@transaction Refused

        val debited = session.update(
            CAS_SQL, *derived(fromAfter), scope, currency, from.toString(), ExactBalance.encode(fromBefore),
        )
        if (debited == 0) return@transaction Contended

        val toBefore = read(session, scope, currency, to) ?: starting
        val toAfter = toBefore.add(amount)
        val credited = session.update(
            CAS_SQL, *derived(toAfter), scope, currency, to.toString(), ExactBalance.encode(toBefore),
        )
        if (credited == 0) return@transaction Contended

        TransferMove(fromBefore, fromAfter, toBefore, toAfter)
    }

    private fun read(session: SqlSession, scope: String, currency: String, player: UUID): BigDecimal? =
        session.query(
            "SELECT exact FROM $TABLE WHERE scope = ? AND currency = ? AND uuid = ?",
            scope, currency, player.toString(),
        ) { ExactBalance.decode(it.getString(1)) }.firstOrNull()

    /** The four value columns, in [COLUMNS] order, all from the one authoritative number. */
    private fun derived(value: BigDecimal): Array<Any> {
        val packed = PackedDecimal.of(value)
        return arrayOf(
            ExactBalance.encode(value),
            packed.raw(),
            packed.magnitude,
            value.signum() >= 0,
        )
    }

    /** Rolled-back outcomes, kept apart from a [TransferMove] so `null` can keep meaning "refused". */
    private object Refused
    private object Contended

    private companion object {
        const val TABLE = "cryon_currency"

        const val CAS_SQL =
            "UPDATE cryon_currency SET exact = ?, packed = ?, magnitude = ?, positive = ? " +
                    "WHERE scope = ? AND currency = ? AND uuid = ? AND exact = ?"

        /** Retries for a transfer losing its guard to a concurrent write, or aborted as a deadlock victim. */
        const val MAX_TRANSFER_ATTEMPTS = 8
        val KEYS = listOf("scope", "currency", "uuid")
        val VALUE_COLUMNS = listOf("scope", "currency", "uuid", "exact", "packed", "magnitude", "positive")
        val COLUMNS = VALUE_COLUMNS + "ranked"

        val INDEXES = listOf(
            SqlIndex("idx_cryon_currency_top", listOf("scope", "currency", "magnitude", "packed")),
        )
    }
}

/**
 * In-process balances, for a server with no database configured.
 *
 * Same contract, same exactness, same atomicity. The `computeIfPresent` below only rewrites the
 * account when it still holds the expected value, and it holds the bin while it decides. Balances
 * are lost on restart, which is the honest consequence of running an economy with nowhere to write.
 */
internal class MemoryCurrencyStore : CurrencyStore {

    private data class Account(val balance: BigDecimal, val ranked: Boolean)

    /** "scope|currency" -> (player -> account). */
    private val books = ConcurrentHashMap<String, ConcurrentHashMap<UUID, Account>>()

    override suspend fun init() = Unit

    override suspend fun balance(scope: String, currency: String, player: UUID): BigDecimal? =
        book(scope, currency)[player]?.balance

    override suspend fun balances(scope: String, player: UUID): Map<String, BigDecimal> {
        val prefix = "$scope|"
        return books.entries
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { (key, accounts) ->
                accounts[player]?.let { key.removePrefix(prefix) to it.balance }
            }
            .toMap()
    }

    override suspend fun open(
        scope: String,
        currency: String,
        player: UUID,
        starting: BigDecimal,
        ranked: Boolean,
    ) {
        book(scope, currency).putIfAbsent(player, Account(starting, ranked))
    }

    override suspend fun compareAndSet(
        scope: String,
        currency: String,
        player: UUID,
        expected: BigDecimal,
        next: BigDecimal,
    ): Boolean {
        var won = false
        book(scope, currency).computeIfPresent(player) { _, account ->
            if (account.balance.compareTo(expected) != 0) return@computeIfPresent account
            won = true
            account.copy(balance = next)
        }
        return won
    }

    override suspend fun set(
        scope: String,
        currency: String,
        player: UUID,
        amount: BigDecimal,
    ): BigDecimal? {
        var previous: BigDecimal? = null
        book(scope, currency).compute(player) { _, account ->
            previous = account?.balance
            account?.copy(balance = amount) ?: Account(amount, true)
        }
        return previous
    }

    override suspend fun top(
        scope: String,
        currency: String,
        size: Int,
    ): List<Pair<UUID, BigDecimal>> = book(scope, currency).entries
        .filter { it.value.ranked && it.value.balance.signum() >= 0 }
        .sortedByDescending { it.value.balance }
        .take(size)
        .map { it.key to it.value.balance }

    /**
     * Both sides under one lock on the book, which is this store's whole transaction story: nothing
     * here can observe or interleave with a half-applied move.
     */
    override suspend fun transfer(
        scope: String,
        currency: String,
        from: UUID,
        to: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
        allowNegative: Boolean,
    ): TransferMove? = moveLocked(scope, currency, from, to, amount, starting, allowNegative)

    /**
     * The monitor sits on this non-suspending helper rather than on [transfer].
     *
     * A monitor cannot be held across a suspension point: the coroutine may resume on a different
     * thread, which would then try to release a lock it never took. Nothing in here suspends or does
     * I/O, so the hold is bounded by a handful of map operations.
     */
    @Synchronized
    private fun moveLocked(
        scope: String,
        currency: String,
        from: UUID,
        to: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
        allowNegative: Boolean,
    ): TransferMove? {
        val accounts = book(scope, currency)
        val fromBefore = accounts[from]?.balance ?: starting
        val fromAfter = fromBefore.subtract(amount)
        if (!allowNegative && fromAfter.signum() < 0) return null

        val toBefore = accounts[to]?.balance ?: starting
        val toAfter = toBefore.add(amount)
        accounts.compute(from) { _, account -> account?.copy(balance = fromAfter) ?: Account(fromAfter, true) }
        accounts.compute(to) { _, account -> account?.copy(balance = toAfter) ?: Account(toAfter, true) }
        return TransferMove(fromBefore, fromAfter, toBefore, toAfter)
    }

    private fun book(scope: String, currency: String) =
        books.computeIfAbsent("$scope|$currency") { ConcurrentHashMap() }
}
