package com.tricrotism.cryon.common.currency

import java.math.BigDecimal
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Applied credit ids, so a replay is refused here exactly as it is in SQL.
     *
     * Unbounded in principle and bounded in practice: nothing writes here unless a deposit failed,
     * and a deposit cannot fail against a store that is this process's own memory. It exists so the
     * contract holds on both implementations rather than only on the one that can lose a write.
     */
    private val appliedOps = ConcurrentHashMap<String, Long>()

    override suspend fun init() = Unit

    override suspend fun applyCredit(
        opId: String,
        scope: String,
        currency: String,
        player: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
    ): Boolean {
        // The claim is the gate here too: putIfAbsent decides and records in one step, so two
        // replays of the same id cannot both pass it.
        if (appliedOps.putIfAbsent(opId, System.currentTimeMillis()) != null) return false
        book(scope, currency).compute(player) { _, existing ->
            val current = existing?.balance ?: starting
            Account(current.add(amount), existing?.ranked ?: true)
        }
        return true
    }

    override suspend fun pruneOps(before: Long) {
        appliedOps.values.removeIf { it < before }
    }

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
