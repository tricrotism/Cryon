package com.tricrotism.cryon.common.number

import java.math.BigDecimal
import kotlin.math.*

/**
 * An effectively-unbounded number for incremental-game scale, stored as a normalized double
 * [mantissa] (`1 ≤ |mantissa| < 10`, or `0`) and a `long` [exponent] (the power of ten). All
 * hot-path math is plain `double` arithmetic, so it stays fast and allocation-free far past
 * `BigDecimal`'s practical ceiling. Carries ~15–16 significant figures; the magnitude ceiling
 * (~10^9.2e18) is astronomically beyond any game value.
 *
 * Immutable; use the [Companion.of] factories. Operators ([plus], [times], …) and the
 * `Int/Long/Double/BigDecimal` extensions in `…common.extension` make it ergonomic.
 */
class CryonNumber private constructor(
    val mantissa: Double,
    val exponent: Long,
) : Comparable<CryonNumber> {

    fun signum(): Int = when {
        mantissa > 0.0 -> 1
        mantissa < 0.0 -> -1
        else -> 0
    }

    val isZero: Boolean get() = mantissa == 0.0

    operator fun unaryMinus(): CryonNumber = CryonNumber(-mantissa, exponent)

    operator fun plus(other: CryonNumber): CryonNumber {
        if (isZero) return other
        if (other.isZero) return this
        val big: CryonNumber
        val small: CryonNumber
        if (exponent >= other.exponent) {
            big = this; small = other
        } else {
            big = other; small = this
        }
        val diff = big.exponent - small.exponent
        if (diff > ALIGN_LIMIT) return big // smaller term vanishes within double precision
        return of(big.mantissa + small.mantissa / TEN.pow(diff.toDouble()), big.exponent)
    }

    operator fun minus(other: CryonNumber): CryonNumber = this + (-other)

    operator fun times(other: CryonNumber): CryonNumber =
        of(mantissa * other.mantissa, exponent + other.exponent)

    operator fun div(other: CryonNumber): CryonNumber {
        if (other.isZero) throw ArithmeticException("division by zero")
        return of(mantissa / other.mantissa, exponent - other.exponent)
    }

    /** Integer power. [exponent]`* power` can overflow `long` only at absurd inputs. */
    fun pow(power: Int): CryonNumber = when {
        power == 0 -> ONE
        isZero -> ZERO
        else -> of(mantissa.pow(power), exponent * power)
    }

    /** Real power via logs: `x^y = 10^(y · log10 x)`. Negative bases allow only integer powers. */
    fun pow(power: Double): CryonNumber {
        if (power == 0.0) return ONE
        if (isZero) return ZERO
        if (signum() < 0) {
            if (power.isFinite() && power == floor(power)) return pow(power.toInt())
            throw ArithmeticException("fractional power of a negative CryonNumber")
        }
        val log = power * log10()
        val exp = floor(log).toLong()
        return of(TEN.pow(log - exp), exp)
    }

    /** Square root. Splits the exponent by parity so it stays exact to mantissa precision. */
    fun sqrt(): CryonNumber {
        if (signum() < 0) throw ArithmeticException("sqrt of a negative CryonNumber")
        if (isZero) return ZERO
        return if (exponent % 2 == 0L) of(sqrt(mantissa), exponent / 2)
        else of(sqrt(mantissa * TEN), (exponent - 1) / 2)
    }

    /** Cube root (defined for negative values). */
    fun cbrt(): CryonNumber {
        if (isZero) return ZERO
        val q = Math.floorDiv(exponent, 3L)
        val r = Math.floorMod(exponent, 3L).toInt()
        return of(Math.cbrt(mantissa * TEN.pow(r.toDouble())), q)
    }

    /** Base-10 logarithm — small enough to return as a `Double`. Requires a positive value. */
    fun log10(): Double {
        if (signum() <= 0) throw ArithmeticException("log10 of a non-positive CryonNumber")
        return exponent.toDouble() + log10(mantissa)
    }

    /** Natural logarithm. */
    fun ln(): Double = log10() * LN_10

    /** Base-2 logarithm. */
    fun log2(): Double = log10() / LOG10_2

    /** Logarithm in an arbitrary [base]. */
    fun log(base: Double): Double = log10() / log10(base)

    fun abs(): CryonNumber = if (signum() < 0) -this else this

    fun reciprocal(): CryonNumber = ONE / this

    fun max(other: CryonNumber): CryonNumber = if (this >= other) this else other

    fun min(other: CryonNumber): CryonNumber = if (this <= other) this else other

    override fun compareTo(other: CryonNumber): Int {
        val s = signum()
        val os = other.signum()
        if (s != os) return s.compareTo(os)
        if (s == 0) return 0
        val magCmp = if (exponent != other.exponent) exponent.compareTo(other.exponent)
        else abs(mantissa).compareTo(abs(other.mantissa))
        return if (s > 0) magCmp else -magCmp
    }

    /** Lossy outside `double` range: `±Infinity` above ~10^308, `0` below ~10^-308. */
    fun toDouble(): Double = mantissa * TEN.pow(exponent.toDouble())

    /** Requires [exponent] to fit an `Int`; for larger values use [toScientificString]. */
    fun toBigDecimal(): BigDecimal {
        require(exponent in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "exponent $exponent out of BigDecimal range; use toScientificString()"
        }
        return BigDecimal.valueOf(mantissa).scaleByPowerOfTen(exponent.toInt())
    }

    fun toScientificString(): String = if (isZero) "0" else "${trimMantissa(mantissa)}e$exponent"

    override fun toString(): String = toScientificString()

    override fun equals(other: Any?): Boolean =
        other is CryonNumber && mantissa == other.mantissa && exponent == other.exponent

    override fun hashCode(): Int = 31 * mantissa.hashCode() + exponent.hashCode()

    companion object {
        private const val ALIGN_LIMIT = 17L
        private const val TEN = 10.0
        private const val LN_10 = 2.302585092994046
        private const val LOG10_2 = 0.3010299956639812

        val ZERO = CryonNumber(0.0, 0)
        val ONE = CryonNumber(1.0, 0)

        /** `10^power`. */
        fun tenPow(power: Long): CryonNumber = of(1.0, power)

        fun of(value: Int): CryonNumber = of(value.toDouble())
        fun of(value: Long): CryonNumber = of(value.toDouble())
        fun of(value: Double): CryonNumber = of(value, 0)

        fun of(value: BigDecimal): CryonNumber {
            if (value.signum() == 0) return ZERO
            val mag = BigDecimalUtils.magnitude(value.abs())
            return CryonNumber(value.movePointLeft(mag).toDouble(), mag.toLong())
        }

        /** Build from a raw mantissa/exponent pair and normalize to `1 ≤ |mantissa| < 10`. */
        fun of(mantissa: Double, exponent: Long): CryonNumber {
            if (mantissa == 0.0 || !mantissa.isFinite()) {
                return if (mantissa == 0.0) ZERO else CryonNumber(mantissa, exponent)
            }
            var mant = mantissa
            var exp = exponent
            val shift = floor(log10(abs(mant))).toLong()
            if (shift != 0L) {
                mant /= TEN.pow(shift.toDouble())
                exp += shift
            }
            // correct any floating-point boundary drift
            if (abs(mant) >= TEN) {
                mant /= TEN; exp += 1
            } else if (abs(mant) < 1.0) {
                mant *= TEN; exp -= 1
            }
            return CryonNumber(mant, exp)
        }

        private fun trimMantissa(m: Double): String {
            val s = m.toString()
            return if (s.endsWith(".0")) s.dropLast(2) else s
        }
    }
}
