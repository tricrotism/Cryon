package com.tricrotism.cryon.common.number

import java.math.BigDecimal

/**
 * Safe parsing of 64-bit longs from strings, including overflow-checked digit accumulation and a
 * shorthand parser (`1.5k`, `2M`, `3B`, …).
 */
object LongUtils {

    val FAILURE = LongParseResult(false, 0)

    /** Parse a plain (optionally negative) integer string, failing on non-digits or overflow. */
    fun parseLong(string: String?): LongParseResult {
        if (string.isNullOrEmpty()) return FAILURE
        val length = string.length
        var previous: Long
        var number = 0L

        if (string[0] == '-') {
            if (length == 1) return FAILURE
            for (i in 1 until length) {
                val c = string[i]
                if (c < '0' || c > '9') return FAILURE
                previous = number
                number *= 10
                number -= (c - '0')
                if (previous < number) return FAILURE // overflowed past Long.MIN_VALUE
            }
        } else {
            for (i in 0 until length) {
                val c = string[i]
                if (c < '0' || c > '9') return FAILURE
                previous = number
                number *= 10
                number += (c - '0')
                if (previous > number) return FAILURE // overflowed past Long.MAX_VALUE
            }
        }
        return LongParseResult(true, number)
    }

    /**
     * Parse a string with an optional trailing shorthand suffix into a long. Suffixes:
     * `k`=1e3, `m`=1e6, `b`=1e9, `t`=1e12, `q`=1e15, `Q`=1e18 (`k/m/b/t` are case-insensitive;
     * `q`/`Q` are distinct). Accepts a leading `-`, one decimal point, and commas. Fails on multiple
     * dots, unknown/embedded letters, fractional results that aren't whole, and overflow.
     */
    fun parseLongShorthand(input: String?): LongParseResult {
        if (input == null) return FAILURE
        val raw = input.replace(",", "")
        if (raw.isEmpty()) return FAILURE

        var multiplier: BigDecimal? = when (raw[raw.length - 1]) {
            'k', 'K' -> BigDecimal.valueOf(1_000L)
            'm', 'M' -> BigDecimal.valueOf(1_000_000L)
            'b', 'B' -> BigDecimal.valueOf(1_000_000_000L)
            't', 'T' -> BigDecimal.valueOf(1_000_000_000_000L)
            'q' -> BigDecimal.valueOf(1_000_000_000_000_000L)
            'Q' -> BigDecimal("1000000000000000000")
            else -> null
        }

        val numericPart: String
        if (multiplier != null) {
            numericPart = raw.substring(0, raw.length - 1)
            if (numericPart.isEmpty()) return FAILURE
        } else {
            numericPart = raw
            multiplier = BigDecimal.ONE
        }

        var seenDot = false
        val start = if (numericPart[0] == '-') {
            if (numericPart.length == 1) return FAILURE
            1
        } else 0
        for (i in start until numericPart.length) {
            val c = numericPart[i]
            if (c == '.') {
                if (seenDot) return FAILURE
                seenDot = true
            } else if (c < '0' || c > '9') {
                return FAILURE
            }
        }

        val value = try {
            BigDecimal(numericPart)
        } catch (e: NumberFormatException) {
            return FAILURE
        }
        return try {
            LongParseResult(true, value.multiply(multiplier).toBigIntegerExact().longValueExact())
        } catch (e: ArithmeticException) {
            FAILURE
        }
    }

    /** Parse without validation — assumes a well-formed integer string. Use only on trusted input. */
    fun unsafelyParseLong(string: String): Long {
        val length = string.length
        var number = 0L
        if (string[0] == '-') {
            for (i in 1 until length) {
                number *= 10
                number -= (string[i] - '0')
            }
        } else {
            for (i in 0 until length) {
                number *= 10
                number += (string[i] - '0')
            }
        }
        return number
    }

    data class LongParseResult(val successful: Boolean, val value: Long) {
        val isPositive: Boolean get() = successful && value > 0
    }
}
