package com.tricrotism.cryon.common.number

import java.math.BigDecimal
import java.math.MathContext

/**
 * `BigDecimal` helpers that stay correct and allocation-light even far beyond `Double` range — the
 * magnitude/log routines never call `toDouble()` on the whole value, so they don't overflow.
 */
object BigDecimalUtils {

    /** `floor(log10(value))` for `value > 0`, computed exactly from precision/scale (no math, no overflow). */
    fun magnitude(value: BigDecimal): Int {
        require(value.signum() > 0) { "magnitude requires a positive value" }
        return value.precision() - value.scale() - 1
    }

    /**
     * Base-10 logarithm. Splits the exact integer magnitude from a double-precision mantissa in
     * `[1, 10)`, so it stays accurate for values far past `Double.MAX_VALUE`.
     */
    fun log10(value: BigDecimal, mc: MathContext = MathContext.DECIMAL128): BigDecimal {
        require(value.signum() > 0) { "log10 requires a positive value" }
        val intPart = value.precision() - value.scale() - 1
        // normalized into [1, 10)
        val mantissa = value.movePointLeft(intPart)
        return BigDecimal(intPart).add(BigDecimal(Math.log10(mantissa.toDouble())), mc)
    }
}
