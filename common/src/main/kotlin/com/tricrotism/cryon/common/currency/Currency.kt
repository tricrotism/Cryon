package com.tricrotism.cryon.common.currency

import com.tricrotism.cryon.common.number.NumberUtils
import com.tricrotism.cryon.common.number.PackedDecimal

/**
 * One currency: an id, how it reads, and how it behaves. Registered with
 * [CurrencyService.register], after which balances can be moved.
 *
 * Adding a currency needs no schema change, every balance lives in one table keyed by
 * `(scope, currency, uuid)`: so "as many as we want" costs one registration call each.
 */
data class Currency(

    // Stable identifier, lowercase. Used as the storage key and in commands; never shown to players
    val id: String,

    // What players see. Free text, so it may carry formatting
    val displayName: String = id.replaceFirstChar(Char::uppercase),

    val scope: CurrencyScope = CurrencyScope.SERVER,

    // Balance a player starts with the first time they are touched
    val starting: PackedDecimal = PackedDecimal.ZERO,

    // Whether a balance may go below zero. Off by default: a debt is almost never what was meant
    val allowNegative: Boolean = false,

    // How one amount of this currency reads, as a template over `<amount>` and `<name>`.
    //
    // `<amount>` is always rendered with the core's own balance format, `1.5k`, `2.35M`: so every
    // currency reads the way every other number on the network does. The template is what varies:
    // `"<amount> <name>"` gives `2.35M Money`, `"<gold><amount>⛃</gold>"` gives a symbol form.
    // A plain string rather than a lambda so the definition stays comparable and printable
    val pattern: String = "<amount> <name>",

    // Spell the magnitude out (`2.35 Million`) instead of suffixing it (`2.35M`)
    val longForm: Boolean = false,

    // Null for no ranking. See [Leaderboard]
    val leaderboard: Leaderboard? = null,
) {
    init {
        require(id.isNotBlank()) { "A currency id cannot be blank" }
        require(id == id.lowercase()) { "Currency id '$id' must be lowercase" }
        require(starting.signum() >= 0 || allowNegative) {
            "Currency '$id' starts negative but does not allow negative balances"
        }
    }

    /**
     * [amount] written the way this currency reads. The one place a balance becomes text.
     */
    fun format(amount: PackedDecimal): String = pattern
        .replace("<amount>", NumberUtils.formatBalance(amount, longForm))
        .replace("<name>", displayName)
}

