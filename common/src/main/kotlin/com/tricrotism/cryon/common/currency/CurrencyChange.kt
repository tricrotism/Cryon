package com.tricrotism.cryon.common.currency

import com.tricrotism.cryon.common.number.PackedDecimal
import java.util.*

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
