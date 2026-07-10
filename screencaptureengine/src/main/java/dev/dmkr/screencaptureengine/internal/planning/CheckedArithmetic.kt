@file:Suppress("unused") // Intentionally dormant until clean-spine planner integration.

package dev.dmkr.screencaptureengine.internal.planning

import java.math.BigInteger

internal sealed interface CheckedLongFact {
    data class Value(
        val value: Long,
    ) : CheckedLongFact

    data object InvalidInput : CheckedLongFact

    data object Overflow : CheckedLongFact
}

internal sealed interface CheckedIntFact {
    data class Value(
        val value: Int,
    ) : CheckedIntFact

    data object InvalidInput : CheckedIntFact

    data object Overflow : CheckedIntFact
}

/** Pure non-negative size and peak arithmetic that never narrows or wraps implicitly. */
internal object CheckedArithmetic {
    internal fun addNonNegative(left: Long, right: Long): CheckedLongFact {
        if (left < 0L || right < 0L) return CheckedLongFact.InvalidInput
        if (left > Long.MAX_VALUE - right) return CheckedLongFact.Overflow
        return CheckedLongFact.Value(left + right)
    }

    internal fun multiplyNonNegative(left: Long, right: Long): CheckedLongFact {
        if (left < 0L || right < 0L) return CheckedLongFact.InvalidInput
        if (left != 0L && right > Long.MAX_VALUE / left) return CheckedLongFact.Overflow
        return CheckedLongFact.Value(left * right)
    }

    internal fun sumNonNegative(components: Iterable<Long>): CheckedLongFact {
        var total = 0L
        for (component in components) {
            when (val next = addNonNegative(total, component)) {
                is CheckedLongFact.Value -> total = next.value
                CheckedLongFact.InvalidInput -> return CheckedLongFact.InvalidInput
                CheckedLongFact.Overflow -> return CheckedLongFact.Overflow
            }
        }
        return CheckedLongFact.Value(total)
    }

    internal fun nonNegativeDifference(total: Long, used: Long): CheckedLongFact {
        if (total < 0L || used < 0L) return CheckedLongFact.InvalidInput
        return CheckedLongFact.Value(if (used >= total) 0L else total - used)
    }

    internal fun subtractNonNegative(total: Long, part: Long): CheckedLongFact {
        if (total < 0L || part < 0L || part > total) return CheckedLongFact.InvalidInput
        return CheckedLongFact.Value(total - part)
    }

    internal fun roundPositiveRatio(numerator: Long, denominator: Long): CheckedLongFact {
        if (numerator < 0L || denominator <= 0L) return CheckedLongFact.InvalidInput
        val quotient = numerator / denominator
        val remainder = numerator % denominator
        val roundsUp = remainder >= denominator / 2L + denominator % 2L
        return if (roundsUp) addNonNegative(quotient, 1L) else CheckedLongFact.Value(quotient)
    }

    internal fun roundPositiveProductToInt(multiplier: Int, factor: Double): CheckedIntFact {
        if (multiplier <= 0 || !factor.isFinite() || factor <= 0.0) return CheckedIntFact.InvalidInput

        val bits = factor.toRawBits()
        val encodedExponent = ((bits ushr DOUBLE_SIGNIFICAND_BITS) and DOUBLE_EXPONENT_MASK).toInt()
        val encodedSignificand = bits and DOUBLE_SIGNIFICAND_MASK
        val significand = if (encodedExponent == 0) {
            encodedSignificand
        } else {
            encodedSignificand or DOUBLE_IMPLICIT_BIT
        }
        val binaryExponent = if (encodedExponent == 0) {
            DOUBLE_SUBNORMAL_EXPONENT
        } else {
            encodedExponent - DOUBLE_EXPONENT_BIAS - DOUBLE_SIGNIFICAND_BITS
        }
        val exactProduct = BigInteger.valueOf(multiplier.toLong()) * BigInteger.valueOf(significand)
        val rounded = if (binaryExponent >= 0) {
            exactProduct.shiftLeft(binaryExponent)
        } else {
            val denominator = BigInteger.ONE.shiftLeft(-binaryExponent)
            val (quotient, remainder) = exactProduct.divideAndRemainder(denominator)
            quotient + if (remainder.shiftLeft(1) >= denominator) BigInteger.ONE else BigInteger.ZERO
        }.max(BigInteger.ONE)

        return if (rounded > BIG_INTEGER_INT_MAX) {
            CheckedIntFact.Overflow
        } else {
            CheckedIntFact.Value(rounded.toInt())
        }
    }

    internal fun rgbaRowByteCount(width: Int): CheckedLongFact = if (width > 0) {
        multiplyNonNegative(width.toLong(), RGBA_BYTES_PER_PIXEL)
    } else {
        CheckedLongFact.InvalidInput
    }

    internal fun requiredRgbaByteCount(
        width: Int,
        height: Int,
        rowStrideBytes: Int,
    ): CheckedLongFact {
        if (width <= 0 || height <= 0 || rowStrideBytes <= 0) return CheckedLongFact.InvalidInput
        val rowBytesFact = rgbaRowByteCount(width)
        if (rowBytesFact !is CheckedLongFact.Value) return rowBytesFact
        val rowBytes = rowBytesFact.value
        if (rowStrideBytes.toLong() < rowBytes) return CheckedLongFact.InvalidInput
        val precedingRows = multiplyNonNegative((height - 1).toLong(), rowStrideBytes.toLong())
        if (precedingRows !is CheckedLongFact.Value) return precedingRows
        return addNonNegative(precedingRows.value, rowBytes)
    }

    internal fun narrowToPositiveInt(value: Long): CheckedIntFact = when {
        value <= 0L -> CheckedIntFact.InvalidInput
        value > Int.MAX_VALUE.toLong() -> CheckedIntFact.Overflow
        else -> CheckedIntFact.Value(value.toInt())
    }
}

private const val RGBA_BYTES_PER_PIXEL: Long = 4L
private const val DOUBLE_SIGNIFICAND_BITS: Int = 52
private const val DOUBLE_EXPONENT_BIAS: Int = 1023
private const val DOUBLE_SUBNORMAL_EXPONENT: Int = -1074
private const val DOUBLE_EXPONENT_MASK: Long = 0x7ffL
private const val DOUBLE_SIGNIFICAND_MASK: Long = 0x000f_ffff_ffff_ffffL
private const val DOUBLE_IMPLICIT_BIT: Long = 0x0010_0000_0000_0000L
private val BIG_INTEGER_INT_MAX: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong())
