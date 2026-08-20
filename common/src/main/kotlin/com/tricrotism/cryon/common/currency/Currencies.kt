package com.tricrotism.cryon.common.currency

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import com.tricrotism.cryon.common.number.PackedDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import java.math.BigDecimal
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The core's [CurrencyService].
 *
 * Four things are worth knowing before changing anything here.
 *
 * **The store is the source of truth, not the cache.** The in-memory map is a *read* cache that
 * trails it. The system this replaces did the reverse. Balances lived in memory and were flushed on
 * a timer, which loses the tail of every crash.
 *
 * **The ledger is exact; only the presentation is packed.** The API speaks [PackedDecimal] because
 * that is the project number type, but the arithmetic and the stored value are [BigDecimal]. Adding
 * in 14 significant figures would make a 100 deposit onto a 1e20 balance a no-op. The money would
 * vanish and the matching purchase would be free. Amounts, displays and multipliers round; the
 * running total never does.
 *
 * **Every mutation is a compare-and-set with a retry.** The arithmetic happens here, so the write
 * only lands if the stored value has not moved since it was read. That is what stops two instances of a server spending the same
 * money: the loser's write is refused and it retries against the new balance. In-process contention
 * never reaches the CAS at all, because [AccountLocks] has already serialised it.
 *
 * **Scope decides the book, and it is per currency.** [CurrencyScope.SERVER] keys on the server id,
 * [CurrencyScope.GLOBAL] on one shared scope. Nothing else branches on deployment.
 *
 * **Cache invalidation is a broadcast.** A write publishes the touched account and every other
 * instance drops its cached copy, the same shape `FeatureFlags` and `Settings` use.
 */
class Currencies(
    private val serverId: String,
    database: Database?,
    private val messenger: Messenger,
    private val logger: Logger,
) : CurrencyService {

    private val hasDatabase = database != null
    private val store: CurrencyStore = database?.let(::SqlCurrencyStore) ?: MemoryCurrencyStore()

    private val currencies = ConcurrentHashMap<String, Currency>()

    /**
     * "scope|currency" -> (player -> balance). A read cache; never consulted to decide a spend.
     *
     * Each book is bounded and self-evicting. A plain map here would hold one entry per player this
     * process has ever touched in that currency, for the life of the process: nothing removes them,
     * because a balance is not session state and a player offline here may well be spending on
     * another node of the same server.
     */
    private val cache = ConcurrentHashMap<String, Cache<UUID, PackedDecimal>>()

    /**
     * Tells our own broadcast apart from another instance's.
     *
     * Both transports deliver a publish back to the publisher, deliberately: `SharedServerRegistry`
     * is built on that echo. Without this token the write that just filled the cache arrives as an
     * invalidation and empties it again, leaving [cachedBalance] answering null on the one instance
     * that actually knows the number.
     */
    private val origin = UUID.randomUUID().toString()

    /**
     * Serializes multi-statement sequences per account. See [AccountLocks].
     * */
    private val locks = AccountLocks()

    private val leaderboards = ConcurrentHashMap<String, List<Ranking>>()
    private val listeners = CopyOnWriteArrayList<(CurrencyChange) -> Unit>()

    private var subscription: MessengerSubscription? = null

    suspend fun init() {
        if (!hasDatabase) {
            logger.warn("Currencies are in-memory only (no database), every balance resets on restart")
        }
        try {
            store.init()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to initialize currency storage", e)
        }
        subscription = messenger.subscribe(CHANNEL, ::onSync)
    }

    fun close() {
        subscription?.unsubscribe()
        subscription = null
        listeners.clear()
    }

    override fun register(currency: Currency): Currency {
        val existing = currencies.putIfAbsent(currency.id, currency)
        if (existing != null) {
            if (existing != currency) {
                logger.warn(
                    "Currency '{}' is already registered with a different definition. Keeping the first",
                    currency.id,
                )
            }
            return existing
        }
        logger.info("Registered currency '{}' ({})", currency.id, currency.scope)
        return currency
    }

    override fun currency(id: String): Currency? = currencies[id.lowercase().trim()]

    override fun all(): Collection<Currency> = currencies.values.toList()

    override suspend fun balance(currency: Currency, player: UUID): PackedDecimal = try {
        val stored = store.balance(scopeOf(currency), currency.id, player)
        PackedDecimal.of(stored ?: startingOf(currency)).also { remember(currency, player, it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Failed to read {} for {}", currency.id, player, e)
        throw e
    }

    override suspend fun balances(player: UUID): Map<Currency, PackedDecimal> = try {
        val scopes = currencies.values.map(::scopeOf).distinct()
        val byScope = coroutineScope {
            scopes.map { scope -> async { scope to store.balances(scope, player) } }.awaitAll()
        }.toMap()
        currencies.values.associateWith { currency ->
            val exact = byScope[scopeOf(currency)]?.get(currency.id) ?: startingOf(currency)
            PackedDecimal.of(exact).also { remember(currency, player, it) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Failed to read balances for {}", player, e)
        emptyMap()
    }

    override fun cachedBalance(currency: Currency, player: UUID): PackedDecimal? =
        cache[key(currency)]?.getIfPresent(player)

    override suspend fun deposit(
        currency: Currency,
        player: UUID,
        amount: PackedDecimal,
        reason: String,
    ): PackedDecimal {
        require(amount.signum() > 0) { "deposit amount must be positive, got $amount" }
        return locks.withLock(account(currency, player)) {
            try {
                ensureAccount(currency, player)
                val after = mutate(currency, player, reason) { before -> before.add(amount.toBigDecimal()) }
                checkNotNull(after) { "deposit for ${currency.id} produced no balance" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to deposit {} {} for {}", amount, currency.id, player, e)
                throw e
            }
        }
    }

    override suspend fun withdraw(
        currency: Currency,
        player: UUID,
        amount: PackedDecimal,
        reason: String,
    ): Boolean {
        require(amount.signum() > 0) { "withdraw amount must be positive, got $amount" }
        return locks.withLock(account(currency, player)) {
            try {
                debit(currency, player, amount, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to withdraw {} {} from {}", amount, currency.id, player, e)
                false
            }
        }
    }

    /**
     * The debit itself, with the failure left on the future.
     *
     * [withdrawLocked] folds a failure into `false` because its callers only ask "may I hand the
     * goods over", and both answers to that are no. [transfer] needs the two apart: it has to tell
     * the sender whether they were short or whether the store is down.
     */
    private suspend fun debit(
        currency: Currency,
        player: UUID,
        amount: PackedDecimal,
        reason: String,
    ): Boolean {
        ensureAccount(currency, player)
        return mutate(currency, player, reason) { before ->
            val next = before.subtract(amount.toBigDecimal())
            if (!currency.allowNegative && next.signum() < 0) null else next
        } != null
    }

    /**
     * Read, compute, write-if-unchanged, retry.
     *
     * [change] returns the new balance, or null to refuse (which answers null without writing). A
     * refusal and an exhausted retry are deliberately different outcomes: the first is "they could
     * not afford it", the second is a failure, and a caller must not hand over goods for either.
     */
    private suspend fun mutate(
        currency: Currency,
        player: UUID,
        reason: String,
        change: (BigDecimal) -> BigDecimal?,
    ): PackedDecimal? {
        val scope = scopeOf(currency)
        repeat(MAX_ATTEMPTS) {
            val before = store.balance(scope, currency.id, player) ?: startingOf(currency)
            val next = change(before) ?: return null
            if (store.compareAndSet(scope, currency.id, player, before, next)) {
                val after = PackedDecimal.of(next)
                remember(currency, player, after)
                announce(currency, player)
                fire(
                    CurrencyChange(
                        currency, player,
                        PackedDecimal.of(before), after,
                        PackedDecimal.of(next.subtract(before)), reason,
                    )
                )
                return after
            }
        }

        throw IllegalStateException("Gave up rewriting ${currency.id} for $player after $MAX_ATTEMPTS attempts")
    }

    override suspend fun set(
        currency: Currency,
        player: UUID,
        amount: PackedDecimal,
        reason: String,
    ) {
        require(amount.signum() >= 0 || currency.allowNegative) {
            "currency '${currency.id}' cannot hold a negative balance"
        }
        try {
            locks.withLock(account(currency, player)) {
                val previous = store.set(scopeOf(currency), currency.id, player, amount.toBigDecimal())
                settle(currency, player, previous ?: startingOf(currency), amount.toBigDecimal(), reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to set {} for {}", currency.id, player, e)
            throw e
        }
    }

    override suspend fun transfer(
        currency: Currency,
        from: UUID,
        to: UUID,
        amount: PackedDecimal,
        reason: String,
    ): TransferResult {
        require(from != to) { "cannot transfer to the same player" }
        require(amount.signum() > 0) { "transfer amount must be positive, got $amount" }
        return locks.withLocks(account(currency, from), account(currency, to)) {
            try {
                val move = store.transfer(
                    scopeOf(currency), currency.id, from, to,
                    amount.toBigDecimal(), startingOf(currency), currency.allowNegative,
                ) ?: return@withLocks TransferResult.INSUFFICIENT
                settle(currency, from, move.fromBefore, move.fromAfter, "$reason/out")
                settle(currency, to, move.toBefore, move.toAfter, "$reason/in")
                TransferResult.COMPLETED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to move {} {} from {} to {}", amount, currency.id, from, to, e)
                TransferResult.FAILED
            }
        }
    }

    /**
     * Cache, broadcast and announce one side of a completed move.
     */
    private suspend fun settle(
        currency: Currency,
        player: UUID,
        before: BigDecimal,
        after: BigDecimal,
        reason: String,
    ) {
        val packed = PackedDecimal.of(after)
        remember(currency, player, packed)
        announce(currency, player)
        fire(
            CurrencyChange(
                currency, player,
                PackedDecimal.of(before), packed,
                PackedDecimal.of(after.subtract(before)), reason,
            )
        )
    }

    override suspend fun openAccount(currency: Currency, player: UUID, ranked: Boolean) {
        runCatching { ensureAccount(currency, player, ranked) }
    }

    /**
     * Open the account and let a failure through.
     *
     * The public [openAccount] absorbs the failure because a caller of it is only asking for the row
     * to exist. The mutating paths cannot: a missing row makes every
     * [compareAndSet] match zero rows, so the retry loop would spend all [MAX_ATTEMPTS] round trips
     * rediscovering a fault the store already reported, at the moment the store can least afford it.
     */
    private suspend fun ensureAccount(currency: Currency, player: UUID, ranked: Boolean = true) {
        try {
            store.open(scopeOf(currency), currency.id, player, startingOf(currency), ranked)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to open {} account for {}", currency.id, player, e)
            throw e
        }
    }

    override fun leaderboard(currency: Currency): List<Ranking> = leaderboards[key(currency)] ?: emptyList()

    /**
     * Every ranked currency refreshed together, and one failure does not cost the others their
     * ranking — each `async` absorbs its own, so a single bad query cannot cancel the siblings the
     * way an escaping exception inside `coroutineScope` would.
     */
    override suspend fun refreshLeaderboards() {
        val ranked = currencies.values.filter { it.leaderboard != null }
        if (ranked.isEmpty()) return
        coroutineScope {
            ranked.map { currency ->
                async {
                    try {
                        val rows = store.top(scopeOf(currency), currency.id, currency.leaderboard!!.size)
                        leaderboards[key(currency)] = Collections.unmodifiableList(
                            rows.mapIndexed { index, (player, balance) ->
                                Ranking(index + 1, player, PackedDecimal.of(balance))
                            }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Failed to rank currency '{}'", currency.id, e)
                    }
                }
            }.awaitAll()
        }
    }

    override fun onChange(listener: (CurrencyChange) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /**
     * A currency starting balance, exactly. The packed definition converted without loss.
     */
    private fun startingOf(currency: Currency): BigDecimal = currency.starting.toBigDecimal()

    /**
     * The lock key for one account: the same string the read cache and leaderboards are keyed by.
     */
    private fun account(currency: Currency, player: UUID) = "${key(currency)}|$player"

    private fun scopeOf(currency: Currency): String =
        if (currency.scope == CurrencyScope.GLOBAL) GLOBAL_SCOPE else serverId.trim().lowercase()

    private fun key(currency: Currency) = "${scopeOf(currency)}|${currency.id}"

    private fun remember(currency: Currency, player: UUID, balance: PackedDecimal) {
        cache.computeIfAbsent(key(currency)) { book() }.put(player, balance)
    }

    private fun book(): Cache<UUID, PackedDecimal> = Caffeine.newBuilder()
        .maximumSize(CACHE_ENTRIES)
        .expireAfterAccess(Duration.ofMinutes(CACHE_MINUTES))
        .build()

    /**
     * Tell the other instances that this account moved, so their cached copy is dropped.
     */
    private suspend fun announce(currency: Currency, player: UUID) {
        messenger.publish(CHANNEL, "${key(currency)}$SEPARATOR$player$SEPARATOR$origin")
    }

    private fun onSync(message: String) {
        val parts = message.split(SEPARATOR)
        if (parts.size != 3) return
        if (parts[2] == origin) return
        val player = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return
        cache[parts[0]]?.invalidate(player)
    }

    private fun fire(change: CurrencyChange) {
        for (listener in listeners) {
            runCatching { listener(change) }
                .onFailure { logger.error("A currency listener failed for '{}'", change.currency.id, it) }
        }
    }

    private companion object {
        const val CHANNEL = "cryon:currency"
        const val GLOBAL_SCOPE = "global"

        /**
         * Retry budget for a contended account. Reached only under cross-instance contention, since
         * same-process callers are already serialised, so anything approaching it means the account
         * is being written from several servers faster than a round trip.
         */
        const val MAX_ATTEMPTS = 8

        /**
         * Built at runtime, never a literal NUL. See `RedisMessenger`.
         */
        val SEPARATOR = Char(0)

        const val CACHE_ENTRIES = 20_000L
        const val CACHE_MINUTES = 30L
    }
}
