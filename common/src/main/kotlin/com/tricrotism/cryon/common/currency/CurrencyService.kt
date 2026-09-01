package com.tricrotism.cryon.common.currency

import com.tricrotism.cryon.common.number.PackedDecimal
import java.util.*

/**
 * Balances, for any number of currencies, scoped per serverId or network-wide.
 *
 * **Every mutation is a compare-and-set, and its result is the answer.** [withdraw] does not check a
 * balance and then subtract; it computes the new balance and writes it *only if the stored one has
 * not moved*, retrying if it has. That is what makes it safe for two instances of a server to spend
 * the same player's money at the same time, which a cached balance plus a later write can never be.
 *
 * **Balances are [PackedDecimal], so they carry 14 significant figures.** Adding or removing an
 * amount more than fourteen orders of magnitude below a balance is a no-op, a purchase costing 100
 * against a balance of 1e20 succeeds and charges nothing. That is inherent to the number type and is
 * the accepted trade for its range; keep it in mind when pricing against very large balances.
 *
 * Reads come in two shapes, matching `PlayerNameService`:
 *  - [balance] is authoritative and asynchronous.
 *  - [cachedBalance] is synchronous and cache-only, for HUDs, scoreboards and placeholders that
 *    render every tick and must never touch a database. Null means "not known here", never "zero".
 *
 * Registered in the `ServiceRegistry` by the core. Thread-safe.
 */
interface CurrencyService {

    /**
     * Declare [currency] so balances can be moved in it. Idempotent by id: re-registering an id
     * returns the existing definition and changes nothing, so a module reload cannot redefine a
     * currency out from under balances already in it.
     */
    fun register(currency: Currency): Currency

    fun currency(id: String): Currency?

    fun all(): Collection<Currency>

    /**
     * The authoritative balance, or the currency's starting value if the player has no account yet.
     */
    suspend fun balance(currency: Currency, player: UUID): PackedDecimal

    /**
     * Every registered currency's balance for [player], in one round trip per scope.
     */
    suspend fun balances(player: UUID): Map<Currency, PackedDecimal>

    /**
     * The last known balance without touching the store, or null when this process has not seen one.
     *
     * Safe on a tick thread. Populated by any read or write through this service on this instance and
     * dropped when another instance reports a change, so it trails the truth by one message rather
     * than by a flush interval. **Display only**: deciding anything with it reintroduces exactly the
     * race [withdraw] exists to avoid.
     */
    fun cachedBalance(currency: Currency, player: UUID): PackedDecimal?

    /**
     * Add [amount] and answer with the balance afterwards. [amount] must be positive. Use [withdraw]
     * to take, so that every removal goes through the guarded path.
     */
    suspend fun deposit(
        currency: Currency,
        player: UUID,
        amount: PackedDecimal,
        reason: String = "unspecified",
    ): PackedDecimal

    /**
     * Take [amount] **atomically**, answering whether the player had it.
     *
     * False means nothing was taken. Gate the reward on this value and nothing else: a balance read
     * beforehand is a prediction, and by the time it is acted on it may be describing money that has
     * already been spent elsewhere in the same tick.
     *
     * **Use [tryWithdraw] for anything that tells the player why.** This collapses "they were short"
     * and "the ledger is unreachable" into one `false`, which is correct for deciding whether to hand
     * the goods over and wrong for the message that follows: it makes a shop call a player broke
     * during a database outage.
     */
    suspend fun withdraw(
        currency: Currency,
        player: UUID,
        amount: PackedDecimal,
        reason: String = "unspecified",
    ): Boolean

    /**
     * [withdraw], with the two ways of failing kept apart.
     *
     * The same distinction [transfer] is already required to make. Branch all three: refusing a
     * purchase and being unable to attempt one are different facts, and only one of them is the
     * player's fault.
     */
    suspend fun tryWithdraw(
        currency: Currency,
        player: UUID,
        amount: PackedDecimal,
        reason: String = "unspecified",
    ): WithdrawResult

    /**
     * Overwrite the balance outright. For administration; ordinary flows use [deposit]/[withdraw].
     */
    suspend fun set(
        currency: Currency,
        player: UUID,
        amount: PackedDecimal,
        reason: String = "unspecified",
    )

    /**
     * Move [amount] from one player to another, taking before giving.
     *
     * Both sides move in one transaction, so there is no instant at which the money is nowhere and
     * a failure cannot mint or destroy currency. Branch on the [TransferResult] rather than on "not
     * completed": [TransferResult.INSUFFICIENT] is the sender's fault and [TransferResult.FAILED] is
     * ours, and a caller that collapses them tells the sender they were short at the one moment that
     * is untrue.
     */
    suspend fun transfer(
        currency: Currency,
        from: UUID,
        to: UUID,
        amount: PackedDecimal,
        reason: String = "transfer",
    ): TransferResult

    /**
     * Create [player]'s account at the currency's starting balance if they have none.
     *
     * Only needed to opt an account **out** of rankings, a shared faction or shop account that would
     * otherwise sit at the top of a player leaderboard. Ordinary accounts are created on first use.
     */
    suspend fun openAccount(currency: Currency, player: UUID, ranked: Boolean = true)

    /**
     * The most recent ranking for [currency], newest-first, or empty when it has no [Leaderboard] or
     * has not been refreshed yet. A cached snapshot: synchronous, and safe on a tick thread.
     */
    fun leaderboard(currency: Currency): List<Ranking>

    /**
     * Recompute every ranked currency's leaderboard.
     *
     * Driven by the platform rather than a timer in here, because this module is platform-neutral and
     * has no scheduler of its own. The core calls it on an interval.
     */
    suspend fun refreshLeaderboards()

    /**
     * Watch every balance change made on this instance. Close the handle to stop watching.
     */
    fun onChange(listener: (CurrencyChange) -> Unit): AutoCloseable
}
