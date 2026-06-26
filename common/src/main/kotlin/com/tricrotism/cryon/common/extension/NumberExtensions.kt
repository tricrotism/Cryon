package com.tricrotism.cryon.common.extension

import com.tricrotism.cryon.common.number.CryonNumber
import com.tricrotism.cryon.common.number.LongUtils
import com.tricrotism.cryon.common.number.NumberUtils
import java.math.BigDecimal

// Conversions to the scaling number type.
fun Int.toCryonNumber(): CryonNumber = CryonNumber.of(this)
fun Long.toCryonNumber(): CryonNumber = CryonNumber.of(this)
fun Double.toCryonNumber(): CryonNumber = CryonNumber.of(this)
fun BigDecimal.toCryonNumber(): CryonNumber = CryonNumber.of(this)

/** Terse alias for [toCryonNumber] — `5.cn + price`. */
val Int.cn: CryonNumber get() = toCryonNumber()
val Long.cn: CryonNumber get() = toCryonNumber()
val Double.cn: CryonNumber get() = toCryonNumber()
val BigDecimal.cn: CryonNumber get() = toCryonNumber()

// Grouped formatting — `1234567L.formatCommas()` -> "1,234,567".
fun Int.formatCommas(): String = NumberUtils.formatCommas(this)
fun Long.formatCommas(): String = NumberUtils.formatCommas(this)
fun Double.formatCommas(): String = NumberUtils.formatCommas(this)
fun BigDecimal.formatCommas(): String = NumberUtils.formatCommas(this)

// Suffixed balances — `1500L.formatBalance()` -> "1.5k".
fun Long.formatBalance(isLong: Boolean = false): String = NumberUtils.formatBalance(this, isLong)
fun BigDecimal.formatBalance(isLong: Boolean = false): String = NumberUtils.formatBalance(this, isLong)
fun CryonNumber.formatBalance(isLong: Boolean = false): String = NumberUtils.formatBalance(this, isLong)

fun Int.roman(): String = NumberUtils.roman(this)

// Parsing.
fun String.parseLongShorthand(): LongUtils.LongParseResult = LongUtils.parseLongShorthand(this)
fun String.parseBalance(): BigDecimal = NumberUtils.parseBalance(this)
