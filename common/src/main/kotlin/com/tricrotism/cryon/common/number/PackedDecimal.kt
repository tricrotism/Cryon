package com.tricrotism.cryon.common.number

import com.tricrotism.cryon.common.number.PackedDecimal.Companion.MAX_VALUE
import com.tricrotism.cryon.common.number.PackedDecimal.Companion.ZERO
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.*

/**
 * The project's effectively-unbounded number for incremental-game scale, packed into a **single
 * `Long`** so that as a `@JvmInline value class` it stays an unboxed primitive on the stack, with
 * **zero allocation per op**. The low 48 bits hold a signed base-10 mantissa normalized to exactly 14
 * significant digits (`1e13 <= |m| < 1e14`, or `0`); the high 16 bits hold a signed power-of-ten
 * [exponent]. The value is `mantissa * 10^exponent`, carries ~14 significant figures, and ranges to
 * ~10^±32767. The exponent **saturates** rather than wrapping (overflow goes to [MAX_VALUE], underflow
 * to [ZERO]).
 *
 * Note [exponent] is the power of ten under the 14-digit mantissa, not the value's decimal magnitude;
 * use [magnitude] for `floor(log10|value|)`. Base-10 keeps formatting and parsing exact and cheap.
 * Multiply renormalizes a `double` product kept to 14 figures; add aligns with an integer divide.
 * About 5x the throughput of a boxed mantissa/exponent class, with no GC churn.
 */
@JvmInline
value class PackedDecimal private constructor(private val bits: Long) : Comparable<PackedDecimal> {

    private val m: Long get() = (bits shl 16) shr 16
    private val e: Int get() = (bits shr 48).toInt()

    val exponent: Int get() = e
    val isZero: Boolean get() = bits == 0L

    /** Decimal order of magnitude, `floor(log10|value|)` (0 for zero). The mantissa sits at `10^13`. */
    val magnitude: Int get() = if (isZero) 0 else e + DIGITS - 1

    fun signum(): Int = when {
        bits == 0L -> 0
        m > 0 -> 1
        else -> -1
    }

    operator fun unaryMinus(): PackedDecimal =
        if (isZero) this else PackedDecimal((e.toLong() shl 48) or ((-m) and MASK48))

    operator fun plus(other: PackedDecimal): PackedDecimal {
        if (isZero) return other
        if (other.isZero) return this
        return if (e >= other.e) {
            val de = e - other.e
            if (de >= DIGITS) this else packed(m + other.m / POW10[de], e)
        } else {
            val de = other.e - e
            if (de >= DIGITS) other else packed(other.m + m / POW10[de], other.e)
        }
    }

    operator fun minus(other: PackedDecimal): PackedDecimal = this + (-other)

    operator fun times(other: PackedDecimal): PackedDecimal {
        if (isZero || other.isZero) return ZERO
        // m1,m2 ∈ [1e13,1e14) → product ∈ [1e26,1e28): always 27 or 28 digits, so shedding 13 or 14
        // (one compare, no log/pow) lands back in range. The double product holds ~15.9 figs; keep 14.
        val prod = m.toDouble() * other.m.toDouble()
        val drop = if (abs(prod) >= 1e27) 14 else 13
        return normalized(Math.round(prod / POW10D[drop]), e + other.e + drop)
    }

    operator fun div(other: PackedDecimal): PackedDecimal {
        if (other.isZero) throw ArithmeticException("division by zero")
        if (isZero) return ZERO
        val q = m.toDouble() / other.m.toDouble() * 1e14
        val drop = if (abs(q) >= 1e14) 1 else 0
        return normalized(Math.round(q / POW10D[drop]), e - other.e - 14 + drop)
    }

    operator fun plus(other: Number): PackedDecimal = this + of(other)
    operator fun minus(other: Number): PackedDecimal = this - of(other)
    operator fun times(other: Number): PackedDecimal = this * of(other)
    operator fun div(other: Number): PackedDecimal = this / of(other)

    /** Integer power by squaring. Negative powers go through [reciprocal]. */
    fun pow(power: Int): PackedDecimal {
        if (power == 0) return ONE
        if (isZero) return ZERO
        if (power < 0) return reciprocal().pow(-power)
        var result = ONE
        var base = this
        var p = power
        while (p > 0) {
            if (p and 1 == 1) result *= base
            base *= base
            p = p shr 1
        }
        return result
    }

    /** Real power via logs: `x^y = 10^(y · log10 x)`. Negative bases allow only integer powers. */
    fun pow(power: Double): PackedDecimal {
        if (power == 0.0) return ONE
        if (isZero) return ZERO
        if (signum() < 0) {
            if (power.isFinite() && power == floor(power)) return pow(power.toInt())
            throw ArithmeticException("fractional power of a negative PackedDecimal")
        }
        val log = power * log10()
        val whole = floor(log)
        return scaled(10.0.pow(log - whole), whole.toInt())
    }

    fun sqrt(): PackedDecimal {
        if (isZero) return ZERO
        if (signum() < 0) throw ArithmeticException("sqrt of a negative PackedDecimal")
        val half = Math.floorDiv(e, 2)
        val odd = e - 2 * half
        return scaled(sqrt(m.toDouble() * if (odd == 1) 10.0 else 1.0), half)
    }

    fun cbrt(): PackedDecimal {
        if (isZero) return ZERO
        val q = Math.floorDiv(e, 3)
        return scaled(Math.cbrt(m.toDouble() * POW10D[e - 3 * q]), q)
    }

    /** Base-10 logarithm; requires a positive value. `m ∈ [1e13,1e14)` so `log10(m) ∈ [13,14)`. */
    fun log10(): Double {
        if (signum() <= 0) throw ArithmeticException("log10 of a non-positive PackedDecimal")
        return e + log10(m.toDouble())
    }

    fun ln(): Double = log10() * LN_10
    fun log2(): Double = log10() / LOG10_2
    fun log(base: Double): Double = log10() / log10(base)

    fun abs(): PackedDecimal = if (signum() < 0) -this else this
    fun reciprocal(): PackedDecimal = ONE / this
    fun max(other: PackedDecimal): PackedDecimal = if (this >= other) this else other
    fun min(other: PackedDecimal): PackedDecimal = if (this <= other) this else other

    override fun compareTo(other: PackedDecimal): Int {
        val s = signum()
        val os = other.signum()
        if (s != os) return s.compareTo(os)
        if (s == 0) return 0
        val mag = if (e != other.e) e.compareTo(other.e) else abs(m).compareTo(abs(other.m))
        return if (s > 0) mag else -mag
    }

    operator fun compareTo(other: Number): Int = compareTo(of(other))

    /** Lossy outside `double` range: `±Infinity` above ~10^308, `0` below ~10^-308. */
    fun toDouble(): Double = m.toDouble() * 10.0.pow(e)

    fun toLong(): Long = toBigDecimal().toLong()
    fun toInt(): Int = toLong().toInt()

    fun toBigDecimal(): BigDecimal =
        if (isZero) BigDecimal.ZERO else BigDecimal.valueOf(m).scaleByPowerOfTen(e)

    /** Scientific form, e.g. `1.2345e7`. Cheap and exact — the exponent is already base-10. */
    fun toScientificString(): String {
        if (isZero) return "0"
        val digits = abs(m).toString()
        val frac = digits.substring(1).trimEnd('0')
        val sign = if (m < 0) "-" else ""
        val decExp = e + DIGITS - 1
        return if (frac.isEmpty()) "$sign${digits[0]}e$decExp" else "$sign${digits[0]}.$frac" + "e$decExp"
    }

    override fun toString(): String = toScientificString()

    internal fun raw(): Long = bits

    companion object {
        private const val DIGITS = 14
        private const val TEN13 = 10_000_000_000_000L
        private const val TEN14 = 100_000_000_000_000L
        private const val MASK48 = (1L shl 48) - 1L
        private const val EXP_MAX = 32_767
        private const val EXP_MIN = -32_768
        private const val LN_10 = 2.302585092994046
        private const val LOG10_2 = 0.3010299956639812

        private val POW10 = LongArray(DIGITS + 1) { var p = 1L; repeat(it) { p *= 10 }; p }
        private val POW10D = DoubleArray(16) { 10.0.pow(it) }

        val ZERO = PackedDecimal(0L)
        val ONE = of(1L)
        val MAX_VALUE = PackedDecimal((EXP_MAX.toLong() shl 48) or ((TEN14 - 1) and MASK48))
        val MIN_VALUE = -MAX_VALUE

        /** `1 × 10^power`. */
        fun tenPow(power: Int): PackedDecimal = packed(TEN13, power - DIGITS + 1)

        fun of(value: Long): PackedDecimal = if (value == 0L) ZERO else packed(value, 0)
        fun of(value: Int): PackedDecimal = of(value.toLong())
        fun of(value: Double): PackedDecimal = scaled(value, 0)

        fun of(value: BigDecimal): PackedDecimal {
            val r = value.round(MathContext(DIGITS, RoundingMode.HALF_UP))
            if (r.signum() == 0) return ZERO
            return packed(r.unscaledValue().toLong(), -r.scale())
        }

        fun of(value: Number): PackedDecimal = when (value) {
            is Int -> of(value)
            is Long -> of(value)
            is BigDecimal -> of(value)
            else -> of(value.toDouble())
        }

        /** Exact to 14 significant figures via [BigDecimal]; accepts `e`-notation. */
        fun parse(s: String): PackedDecimal = of(BigDecimal(s.trim()))

        internal fun fromRaw(bits: Long): PackedDecimal = PackedDecimal(bits)

        /** Build from a `double` mantissa × 10^[extraExp] — for of(Double)/sqrt/cbrt/pow. */
        private fun scaled(value: Double, extraExp: Int): PackedDecimal {
            if (value == 0.0 || !value.isFinite()) return ZERO
            val neg = value < 0
            val a = if (neg) -value else value
            val d = floor(log10(a)).toInt()
            val mant = Math.round(a / 10.0.pow(d - 13))
            return packed(if (neg) -mant else mant, d - 13 + extraExp)
        }

        /** Fast normalize for times/div: the input is in [1e13,1e14] bar one carry from rounding up. */
        private fun normalized(signedM: Long, rawE: Int): PackedDecimal {
            val neg = signedM < 0
            var mAbs = if (neg) -signedM else signedM
            var e = rawE
            if (mAbs >= TEN14) {
                mAbs = (mAbs + 5) / 10
                e++
            }
            return finish(neg, mAbs, e)
        }

        /** General normalize: bring any `rawM × 10^rawE` to canonical 14-digit form. */
        private fun packed(rawM: Long, rawE: Int): PackedDecimal {
            if (rawM == 0L) return ZERO
            val neg = rawM < 0
            var mAbs = if (neg) -rawM else rawM
            var e = rawE
            while (mAbs >= TEN14) {
                mAbs = (mAbs + 5) / 10
                e++
            }
            while (mAbs < TEN13) {
                mAbs *= 10
                e--
            }
            return finish(neg, mAbs, e)
        }

        private fun finish(neg: Boolean, mAbs: Long, e: Int): PackedDecimal {
            if (e > EXP_MAX) return if (neg) MIN_VALUE else MAX_VALUE
            if (e < EXP_MIN) return ZERO
            val m = if (neg) -mAbs else mAbs
            return PackedDecimal((e.toLong() shl 48) or (m and MASK48))
        }
    }
}
