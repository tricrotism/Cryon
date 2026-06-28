package com.tricrotism.cryon.common.extension

import com.tricrotism.cryon.common.number.LongUtils
import com.tricrotism.cryon.common.number.NumberUtils
import com.tricrotism.cryon.common.number.PackedDecimal
import java.math.BigDecimal

// Conversions to the scaling number type.
fun Int.toPackedDecimal(): PackedDecimal = PackedDecimal.of(this)
fun Long.toPackedDecimal(): PackedDecimal = PackedDecimal.of(this)
fun Double.toPackedDecimal(): PackedDecimal = PackedDecimal.of(this)
fun BigDecimal.toPackedDecimal(): PackedDecimal = PackedDecimal.of(this)

/** Terse alias for [toPackedDecimal], as in `5.pd + price`. */
val Int.pd: PackedDecimal get() = toPackedDecimal()
val Long.pd: PackedDecimal get() = toPackedDecimal()
val Double.pd: PackedDecimal get() = toPackedDecimal()
val BigDecimal.pd: PackedDecimal get() = toPackedDecimal()

// Grouped formatting — `1234567L.formatCommas()` -> "1,234,567".
fun Int.formatCommas(): String = NumberUtils.formatCommas(this)
fun Long.formatCommas(): String = NumberUtils.formatCommas(this)
fun Double.formatCommas(): String = NumberUtils.formatCommas(this)
fun BigDecimal.formatCommas(): String = NumberUtils.formatCommas(this)

// Suffixed balances — `1500L.formatBalance()` -> "1.5k".
fun Long.formatBalance(isLong: Boolean = false): String = NumberUtils.formatBalance(this, isLong)
fun BigDecimal.formatBalance(isLong: Boolean = false): String = NumberUtils.formatBalance(this, isLong)
fun PackedDecimal.formatBalance(isLong: Boolean = false): String = NumberUtils.formatBalance(this, isLong)

fun Int.roman(): String = NumberUtils.roman(this)

// Parsing.
fun String.parseLongShorthand(): LongUtils.LongParseResult = LongUtils.parseLongShorthand(this)
fun String.parseBalance(): BigDecimal = NumberUtils.parseBalance(this)
