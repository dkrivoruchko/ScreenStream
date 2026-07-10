package dev.dmkr.screencaptureengine.internal.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.nextDown
import kotlin.math.nextUp

class CheckedArithmeticTest {
    @Test
    fun nonNegativeAdditionReturnsValueInvalidInputOrOverflowWithoutWrapping() {
        assertEquals(CheckedLongFact.Value(Long.MAX_VALUE), CheckedArithmetic.addNonNegative(Long.MAX_VALUE, 0L))
        assertSame(CheckedLongFact.Overflow, CheckedArithmetic.addNonNegative(Long.MAX_VALUE, 1L))
        assertSame(CheckedLongFact.InvalidInput, CheckedArithmetic.addNonNegative(-1L, 1L))
    }

    @Test
    fun nonNegativeMultiplicationReturnsValueInvalidInputOrOverflowWithoutWrapping() {
        assertEquals(CheckedLongFact.Value(Long.MAX_VALUE), CheckedArithmetic.multiplyNonNegative(Long.MAX_VALUE, 1L))
        assertSame(CheckedLongFact.Overflow, CheckedArithmetic.multiplyNonNegative(Long.MAX_VALUE, 2L))
        assertEquals(CheckedLongFact.Value(0L), CheckedArithmetic.multiplyNonNegative(0L, Long.MAX_VALUE))
        assertSame(CheckedLongFact.InvalidInput, CheckedArithmetic.multiplyNonNegative(1L, -1L))
    }

    @Test
    fun peakSumChecksEveryComponentAndTheAccumulatedTotal() {
        assertEquals(
            CheckedLongFact.Value(1_000L),
            CheckedArithmetic.sumNonNegative(listOf(100L, 200L, 300L, 400L)),
        )
        assertSame(
            CheckedLongFact.Overflow,
            CheckedArithmetic.sumNonNegative(listOf(Long.MAX_VALUE - 1L, 1L, 1L)),
        )
        assertSame(
            CheckedLongFact.InvalidInput,
            CheckedArithmetic.sumNonNegative(listOf(1L, -1L)),
        )
    }

    @Test
    fun headroomDifferenceClampsAtZeroWithoutUnderflow() {
        assertEquals(CheckedLongFact.Value(40L), CheckedArithmetic.nonNegativeDifference(total = 100L, used = 60L))
        assertEquals(CheckedLongFact.Value(0L), CheckedArithmetic.nonNegativeDifference(total = 100L, used = 100L))
        assertEquals(CheckedLongFact.Value(0L), CheckedArithmetic.nonNegativeDifference(total = 100L, used = Long.MAX_VALUE))
        assertSame(CheckedLongFact.InvalidInput, CheckedArithmetic.nonNegativeDifference(total = -1L, used = 0L))
    }

    @Test
    fun requiredRgbaBytesUsesLastRowSpanAndAcceptsPaddedRows() {
        assertEquals(CheckedLongFact.Value(12L), CheckedArithmetic.rgbaRowByteCount(width = 3))
        assertEquals(
            CheckedLongFact.Value(44L),
            CheckedArithmetic.requiredRgbaByteCount(width = 3, height = 3, rowStrideBytes = 16),
        )
        assertEquals(
            CheckedLongFact.Value(12L),
            CheckedArithmetic.requiredRgbaByteCount(width = 3, height = 1, rowStrideBytes = 16),
        )
    }

    @Test
    fun requiredRgbaBytesRejectsInvalidShapeBeforeAnyNarrowing() {
        assertSame(
            CheckedLongFact.InvalidInput,
            CheckedArithmetic.requiredRgbaByteCount(width = 3, height = 3, rowStrideBytes = 11),
        )
        assertSame(
            CheckedLongFact.InvalidInput,
            CheckedArithmetic.requiredRgbaByteCount(width = 0, height = 3, rowStrideBytes = 12),
        )
        assertSame(CheckedLongFact.InvalidInput, CheckedArithmetic.rgbaRowByteCount(width = 0))
    }

    @Test
    fun publicIntAddressabilityIsAnExplicitCheckedFact() {
        assertEquals(
            CheckedIntFact.Value(Int.MAX_VALUE),
            CheckedArithmetic.narrowToPositiveInt(Int.MAX_VALUE.toLong()),
        )
        assertSame(
            CheckedIntFact.Overflow,
            CheckedArithmetic.narrowToPositiveInt(Int.MAX_VALUE.toLong() + 1L),
        )
        assertSame(CheckedIntFact.InvalidInput, CheckedArithmetic.narrowToPositiveInt(0L))
    }

    @Test
    fun subtractionAndHalfUpRatioRemainExactAtLongBoundaries() {
        assertEquals(CheckedLongFact.Value(1L), CheckedArithmetic.subtractNonNegative(Long.MAX_VALUE, Long.MAX_VALUE - 1L))
        assertSame(CheckedLongFact.InvalidInput, CheckedArithmetic.subtractNonNegative(1L, 2L))

        val vectors = listOf(
            0L to 3L,
            1L to 2L,
            2L to 3L,
            5L to 2L,
            Long.MAX_VALUE to Long.MAX_VALUE,
            Long.MAX_VALUE - 1L to Long.MAX_VALUE,
        )
        vectors.forEach { (numerator, denominator) ->
            val expected = BigInteger.valueOf(numerator)
                .multiply(BigInteger.TWO)
                .add(BigInteger.valueOf(denominator))
                .divide(BigInteger.valueOf(denominator).multiply(BigInteger.TWO))
                .longValueExact()
            assertEquals(
                CheckedLongFact.Value(expected),
                CheckedArithmetic.roundPositiveRatio(numerator, denominator),
            )
        }
    }

    @Test
    fun positiveProductUsesHalfUpThenMinimumOneAndChecksIntRepresentation() {
        assertEquals(CheckedIntFact.Value(1), CheckedArithmetic.roundPositiveProductToInt(1, Double.MIN_VALUE))
        assertEquals(CheckedIntFact.Value(2), CheckedArithmetic.roundPositiveProductToInt(3, 0.5))
        assertEquals(CheckedIntFact.Value(Int.MAX_VALUE), CheckedArithmetic.roundPositiveProductToInt(Int.MAX_VALUE, 1.0))
        assertSame(CheckedIntFact.Overflow, CheckedArithmetic.roundPositiveProductToInt(Int.MAX_VALUE, 1.000000001))
        assertSame(CheckedIntFact.Overflow, CheckedArithmetic.roundPositiveProductToInt(1, Double.MAX_VALUE))
        assertSame(CheckedIntFact.InvalidInput, CheckedArithmetic.roundPositiveProductToInt(1, Double.NaN))
        assertSame(CheckedIntFact.InvalidInput, CheckedArithmetic.roundPositiveProductToInt(1, 0.0))
    }

    @Test
    fun positiveProductRoundsTheExactRepresentedDoubleWithoutIntermediateRounding() {
        val vectors = listOf(
            Int.MAX_VALUE to 1.0000000002328306,
            Int.MAX_VALUE to 0.5068958248043879,
            3 to 0.5.nextDown(),
            3 to 0.5,
            3 to 0.5.nextUp(),
            1 to Double.MIN_VALUE,
            1 to Double.MAX_VALUE,
        )

        vectors.forEach { (multiplier, factor) ->
            assertEquals(
                "multiplier=$multiplier factor=$factor",
                exactProductOracle(multiplier, factor),
                CheckedArithmetic.roundPositiveProductToInt(multiplier, factor),
            )
        }
        assertEquals(
            CheckedIntFact.Value(Int.MAX_VALUE),
            CheckedArithmetic.roundPositiveProductToInt(Int.MAX_VALUE, 1.0000000002328306),
        )
        assertEquals(
            CheckedIntFact.Value(1_088_550_494),
            CheckedArithmetic.roundPositiveProductToInt(Int.MAX_VALUE, 0.5068958248043879),
        )
    }

    private fun exactProductOracle(multiplier: Int, factor: Double): CheckedIntFact {
        val rounded = BigDecimal.valueOf(multiplier.toLong())
            .multiply(BigDecimal(factor))
            .add(BigDecimal("0.5"))
            .setScale(0, RoundingMode.FLOOR)
            .max(BigDecimal.ONE)
        return if (rounded > BigDecimal.valueOf(Int.MAX_VALUE.toLong())) {
            CheckedIntFact.Overflow
        } else {
            CheckedIntFact.Value(rounded.intValueExact())
        }
    }
}
