package com.tricrotism.cryon.common.currency

import com.tricrotism.cryon.common.number.PackedDecimal
import java.math.BigDecimal

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
