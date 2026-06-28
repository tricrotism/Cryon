package com.tricrotism.cryon.common.number

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.*

/**
 * Number formatting: grouped (`1,234,567`), suffixed balances (`1.5k`, `2.35M`), Roman numerals,
 * and balance parsing. Formatters are `DecimalFormat`/`NumberFormat`, which are **not** thread-safe,
 * so each is held per-thread.
 */
object NumberUtils {

    // index 0 is the no-suffix slot (values < 1000 never reach it); index n applies 10^(3n).
    private const val SUFFIX_CHARS = " kMBTqQsSONDUdt"
    private val LONG_SUFFIXES = arrayOf(
        "", " Thousand", " Million", " Billion", " Trillion", " Quadrillion", " Quintillion",
        " Sextillion", " Septillion", " Octillion", " Nonillion", " Decillion", " Undecillion",
        " Duodecillion", " Tredecillion",
    )
    private val THOUSAND = BigDecimal.valueOf(1000)

    private fun symbols() = DecimalFormatSymbols(Locale.US)
    private val grouped = ThreadLocal.withInitial { NumberFormat.getNumberInstance(Locale.US) }
    private val grouped0 = ThreadLocal.withInitial { DecimalFormat("#,##0", symbols()) }
    private val grouped2 = ThreadLocal.withInitial { DecimalFormat("#,##0.00", symbols()) }
    private val commas = ThreadLocal.withInitial {
        NumberFormat.getInstance(Locale.US).apply { maximumFractionDigits = 10 }
    }

    fun format(value: Int): String = grouped.get().format(value.toLong())
    fun format(value: Long): String = grouped.get().format(value)
    fun format(value: Double): String = grouped2.get().format(value)
    fun format(value: BigDecimal): String = grouped0.get().format(value)

    /** Grouped with exactly two decimals. */
    fun formatDouble(value: BigDecimal): String = grouped2.get().format(value)
    fun formatDouble(value: Double): String = grouped2.get().format(value)

    /** Grouped with up to ten decimals; `NaN` renders as `"NaN"`. */
    fun formatCommas(value: Int): String = commas.get().format(value.toLong())
    fun formatCommas(value: Long): String = commas.get().format(value)
    fun formatCommas(value: Double): String = if (value.isNaN()) "NaN" else commas.get().format(value)
    fun formatCommas(value: BigDecimal): String = commas.get().format(value)

    fun formatBalance(number: Long, isLong: Boolean = false): String =
        formatBalance(BigDecimal.valueOf(number), isLong)

    /**
     * Compact balance with a magnitude suffix: `999` → `999`, `1500` → `1.5k`, `2_350_000` → `2.35M`.
     * [isLong] swaps the single-letter suffix for the full word (` Million`). Values past the suffix
     * table fall back to engineering notation rather than throwing.
     */
    fun formatBalance(number: BigDecimal?, isLong: Boolean = false): String {
        if (number == null || number.signum() == 0) return "0"
        val sign = if (number.signum() < 0) "-" else ""
        val abs = number.abs()
        if (abs < THOUSAND) {
            return sign + abs.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
        }
        val power = BigDecimalUtils.magnitude(abs) / 3
        if (power >= SUFFIX_CHARS.length) {
            return sign + abs.round(MathContext(4)).toEngineeringString()
        }
        val scaled = abs.divide(BigDecimal.TEN.pow(power * 3), 2, RoundingMode.DOWN)
        val body = scaled.stripTrailingZeros().toPlainString()
        return sign + body + (if (isLong) LONG_SUFFIXES[power] else SUFFIX_CHARS[power].toString())
    }

    /** Alias for the compact (single-letter) balance form. */
    fun formatBalanceCompact(number: BigDecimal?): String = formatBalance(number, isLong = false)

    /**
     * Suffixed balance for a [PackedDecimal]. Within `BigDecimal` range it reuses the suffix table;
     * beyond it (where no named suffix exists) it falls back to scientific notation (`1.23e1500`).
     */
    fun formatBalance(number: PackedDecimal, isLong: Boolean = false): String = when {
        number.signum() == 0 -> "0"
        number.magnitude in -30..41 -> formatBalance(number.toBigDecimal(), isLong)
        else -> number.toScientificString()
    }

    private val ROMAN_NUMBERS = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    private val ROMAN_LETTERS =
        arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")

    fun roman(arabic: Int): String {
        var n = arabic
        val sb = StringBuilder()
        for (i in ROMAN_NUMBERS.indices) {
            while (n >= ROMAN_NUMBERS[i]) {
                sb.append(ROMAN_LETTERS[i])
                n -= ROMAN_NUMBERS[i]
            }
        }
        return sb.toString()
    }

    /**
     * Parse a balance string back to a `BigDecimal`, honouring a trailing suffix (`1.5k`, `2.35M`).
     * `k/m/b/t` are case-insensitive; higher tiers (`q Q s S O N D U d`) are case-sensitive. Strips
     * commas. Throws `NumberFormatException` on blank input or an unknown suffix.
     */
    fun parseBalance(input: String?): BigDecimal {
        require(!input.isNullOrBlank()) { "Input string is null or blank" }
        var s = input.trim().replace(",", "")
        val last = s[s.length - 1]
        if (last.isDigit()) return BigDecimal(s)

        val power = when (last) {
            'k', 'K' -> 1
            'm', 'M' -> 2
            'b', 'B' -> 3
            't', 'T' -> 4
            'q' -> 5
            'Q' -> 6
            's' -> 7
            'S' -> 8
            'O' -> 9
            'N' -> 10
            'D' -> 11
            'U' -> 12
            'd' -> 13
            else -> throw NumberFormatException("Unknown balance suffix '$last'")
        }
        s = s.substring(0, s.length - 1).trim()
        return BigDecimal(s).multiply(BigDecimal.TEN.pow(power * 3))
    }
}
