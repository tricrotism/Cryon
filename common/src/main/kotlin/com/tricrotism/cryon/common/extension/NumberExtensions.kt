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

/**
 * Compact duration from a whole number of seconds — `90L.formatDuration()` -> "1m 30s". The two
 * most-significant units only: a countdown is glanced at, and `1h 2m 5s` is three numbers where two
 * would do. Non-positive input renders `"0s"`.
 */
fun Long.formatDuration(): String {
    if (this <= 0L) return "0s"
    val days = this / 86_400
    val hours = this % 86_400 / 3_600
    val minutes = this % 3_600 / 60
    val secs = this % 60
    return when {
        days > 0L -> if (hours > 0L) "${days}d ${hours}h" else "${days}d"
        hours > 0L -> if (minutes > 0L) "${hours}h ${minutes}m" else "${hours}h"
        minutes > 0L -> if (secs > 0L) "${minutes}m ${secs}s" else "${minutes}m"
        else -> "${secs}s"
    }
}

/**
 * Full duration from a whole number of seconds — every non-zero unit down to seconds, matching
 * vanilla's `%statistic_time_played%`: `90L.formatDurationFull()` -> "1m 30s",
 * `93_784L.formatDurationFull()` -> "1d 2h 3m 4s". Zero units are dropped (`3_600L` -> "1h"), and
 * non-positive input renders `"0s"`. Use [formatDuration] instead for the two-unit compact form a
 * countdown wants.
 */
fun Long.formatDurationFull(): String {
    if (this <= 0L) return "0s"
    val days = this / 86_400
    val hours = this % 86_400 / 3_600
    val minutes = this % 3_600 / 60
    val secs = this % 60
    return buildList {
        if (days > 0L) add("${days}d")
        if (hours > 0L) add("${hours}h")
        if (minutes > 0L) add("${minutes}m")
        if (secs > 0L) add("${secs}s")
    }.joinToString(" ")
}

// Parsing.
fun String.parseLongShorthand(): LongUtils.LongParseResult = LongUtils.parseLongShorthand(this)
fun String.parseBalance(): BigDecimal = NumberUtils.parseBalance(this)
