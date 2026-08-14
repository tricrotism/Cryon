package com.tricrotism.cryon.common.currency

import com.tricrotism.cryon.common.number.NumberUtils
import com.tricrotism.cryon.common.number.PackedDecimal
import java.util.*

/**
 * How far a currency's balances reach.
 *
 * The distinction is the same one [com.tricrotism.cryon.common.flag.FeatureFlags] draws between its
 * server and global scopes, and it is a property of the *currency*, not of the deployment: a shop
 * token that only means something on one gamemode is [SERVER] even on a single-server network, and a
 * network-wide premium currency is [GLOBAL] even when only one instance is running.
 */
enum class CurrencyScope {

    /**
     * One balance per serverId. `survival` and `skyblock` keep separate books, and every instance of a
     * serverId shares one, which is the only sane reading of a pooled serverId, since a player may land
     * on any instance of it.
     */
    SERVER,

    /**
     * One balance across the whole network, wherever the player is.
     */
    GLOBAL,
}

/**
 * An optional ranking over a currency's balances.
 *
 * Opt-in per currency because a leaderboard is a periodic `ORDER BY` over every row of that currency
 *. Cheap for one currency and not free for twenty. A currency with no [Currency.leaderboard] never
 * runs the query at all.
 */
data class Leaderboard(

    /**
     * How many entries to keep. The query is `LIMIT`ed to this, so it is a cost, not a display cap.
     */
    val size: Int = 10,
) {
    init {
        require(size > 0) { "Leaderboard size must be positive, got $size" }
    }
}

/**
 * One currency: an id, how it reads, and how it behaves. Registered with
 * [CurrencyService.register], after which balances can be moved.
 *
 * Adding a currency needs no schema change, every balance lives in one table keyed by
 * `(scope, currency, uuid)`: so "as many as we want" costs one registration call each.
 */
data class Currency(

    /** Stable identifier, lowercase. Used as the storage key and in commands; never shown to players. */
    val id: String,

    /** What players see. Free text, so it may carry formatting. */
    val displayName: String = id.replaceFirstChar(Char::uppercase),

    val scope: CurrencyScope = CurrencyScope.SERVER,

    /** Balance a player starts with the first time they are touched. */
    val starting: PackedDecimal = PackedDecimal.ZERO,

    /** Whether a balance may go below zero. Off by default: a debt is almost never what was meant. */
    val allowNegative: Boolean = false,

    /**
     * How one amount of this currency reads, as a template over `<amount>` and `<name>`.
     *
     * `<amount>` is always rendered with the core's own balance format, `1.5k`, `2.35M`: so every
     * currency reads the way every other number on the network does. The template is what varies:
     * `"<amount> <name>"` gives `2.35M Money`, `"<gold><amount>⛃</gold>"` gives a symbol form.
     * A plain string rather than a lambda so the definition stays comparable and printable.
     */
    val pattern: String = "<amount> <name>",

    /** Spell the magnitude out (`2.35 Million`) instead of suffixing it (`2.35M`). */
    val longForm: Boolean = false,

    /** Null for no ranking. See [Leaderboard]. */
    val leaderboard: Leaderboard? = null,
) {
    init {
        require(id.isNotBlank()) { "A currency id cannot be blank" }
        require(id == id.lowercase()) { "Currency id '$id' must be lowercase" }
        require(starting.signum() >= 0 || allowNegative) {
            "Currency '$id' starts negative but does not allow negative balances"
        }
    }

    /** [amount] written the way this currency reads. The one place a balance becomes text. */
    fun format(amount: PackedDecimal): String = pattern
        .replace("<amount>", NumberUtils.formatBalance(amount, longForm))
        .replace("<name>", displayName)
}

/**
 * How a [CurrencyService.transfer] ended.
 *
 * Three outcomes rather than a boolean, because "false" would otherwise mean both "they could not
 * afford it" and "the store is down", and the sender has to be told something different in each.
 *
 * There is deliberately no "half applied" value. The move is one transaction in the store, so either
 * both balances changed or neither did; an outcome for money that exists nowhere would describe a
 * state this cannot reach.
 */
enum class TransferResult {

    /** The amount moved. */
    COMPLETED,

    /** The sender did not have it. Neither side was touched. */
    INSUFFICIENT,

    /** The store could not be read or written. Neither side was touched. */
    FAILED,
}

/** One ranked balance, as of the last [CurrencyService.refreshLeaderboards]. */
data class Ranking(val position: Int, val player: UUID, val balance: PackedDecimal)

/**
 * Why a balance moved, for anything watching. Not persisted, this is a hook for audit logging,
 * achievements and quests, exactly what mchub2 wired by hand into every mutation.
 */
data class CurrencyChange(
    val currency: Currency,
    val player: UUID,
    val before: PackedDecimal,
    val after: PackedDecimal,
    /**
     * How much moved, packed from the **exact** difference.
     *
     * Not `after - before`: those are rounded for display, and their difference collapses to zero for
     * any change small relative to the balance, which is exactly the change an audit hook most needs
     * to see.
     */
    val delta: PackedDecimal,
    val reason: String,
)
