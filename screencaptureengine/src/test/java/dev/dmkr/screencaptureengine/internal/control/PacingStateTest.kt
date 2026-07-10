package dev.dmkr.screencaptureengine.internal.control

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingStateTest {
    @Test
    fun maxFpsMatchesIndependentRationalPhaseVectors() {
        val frameRates = listOf(1, 2, 3, 60, 1_000_000_000, 1_000_000_001, 1_500_000_000, Int.MAX_VALUE)

        for (fps in frameRates) {
            val state = MaxFpsPacingState(fps = fps, commitSampleNanos = 0L)
            var now = 0L
            for (grantNumber in 1L..12L) {
                assertTrue("fps=$fps grant=$grantNumber", state.select(now) is MaxFpsPacingFact.Granted)

                val expectedCumulative = BigInteger.valueOf(grantNumber)
                    .multiply(BigInteger.valueOf(NANOS_PER_SECOND))
                    .divide(BigInteger.valueOf(fps.toLong()))
                    .longValueExact()
                val expectedPhase = BigInteger.valueOf(grantNumber)
                    .multiply(BigInteger.valueOf(NANOS_PER_SECOND % fps.toLong()))
                    .mod(BigInteger.valueOf(fps.toLong()))
                    .longValueExact()
                val deadline = state.nextEligible as MonotonicDeadline.Finite

                assertEquals("fps=$fps grant=$grantNumber", expectedCumulative, deadline.elapsedRealtimeNanos)
                assertEquals("fps=$fps grant=$grantNumber", expectedPhase, state.phase)
                now = deadline.elapsedRealtimeNanos
            }
        }
    }

    @Test
    fun subNanosecondRatesAllowOnlyTheirExactSameTimestampGrantCount() {
        val expectedGrants = mapOf(
            1_000_000_001 to 2,
            1_500_000_000 to 2,
            Int.MAX_VALUE to 3,
        )

        for ((fps, grantCount) in expectedGrants) {
            val state = MaxFpsPacingState(fps = fps, commitSampleNanos = 7L)

            repeat(grantCount) {
                assertTrue("fps=$fps grant=$it", state.select(7L) is MaxFpsPacingFact.Granted)
            }
            assertTrue(state.select(7L) is MaxFpsPacingFact.RateLimited)
        }
    }

    @Test
    fun maxFpsClampsLowerClockAndConsumesGrantedSlotBeforeLaterBusyClassification() {
        val state = MaxFpsPacingState(fps = 1, commitSampleNanos = 10L)
        assertTrue(state.select(10L) is MaxFpsPacingFact.Granted)
        val equal = state.select(10L) as MaxFpsPacingFact.RateLimited

        val lower = state.select(0L) as MaxFpsPacingFact.RateLimited

        assertFalse(equal.clockMovedBackward)
        assertEquals(10L, lower.effectiveNowNanos)
        assertTrue(lower.clockMovedBackward)
        assertEquals(1_000_000_010L, (state.nextEligible as MonotonicDeadline.Finite).elapsedRealtimeNanos)
    }

    @Test
    fun maxFpsLargeJumpAnchorsNextSlotToCurrentSampleAndNewPolicyResetsPhase() {
        val state = MaxFpsPacingState(fps = 2, commitSampleNanos = 0L)
        assertTrue(state.select(0L) is MaxFpsPacingFact.Granted)
        assertTrue(state.select(10_000_000_000L) is MaxFpsPacingFact.Granted)
        assertEquals(
            10_500_000_000L,
            (state.nextEligible as MonotonicDeadline.Finite).elapsedRealtimeNanos,
        )

        val reset = MaxFpsPacingState(fps = 3, commitSampleNanos = 10_000_000_000L)
        assertEquals(0L, reset.phase)
        assertTrue(reset.select(10_000_000_000L) is MaxFpsPacingFact.Granted)
    }

    @Test
    fun maxFpsOverflowUsesTaggedInfiniteFutureRatherThanFiniteMaxValue() {
        val state = MaxFpsPacingState(fps = 1, commitSampleNanos = Long.MAX_VALUE)

        assertTrue(state.select(Long.MAX_VALUE) is MaxFpsPacingFact.Granted)
        assertSame(MonotonicDeadline.InfiniteFuture, state.nextEligible)
        assertTrue(state.select(Long.MAX_VALUE) is MaxFpsPacingFact.RateLimited)
    }

    @Test
    fun periodicIntervalConversionUsesExactMultiplicationBoundary() {
        val boundaryMillis = Long.MAX_VALUE / NANOS_PER_MILLISECOND
        val finite = PeriodicRefreshPacingState(boundaryMillis, commitSampleNanos = 0L, hasCurrentSource = true)
        val infinite = PeriodicRefreshPacingState(boundaryMillis + 1L, commitSampleNanos = 0L, hasCurrentSource = true)

        assertEquals(
            PeriodicRefreshInterval.Finite(boundaryMillis * NANOS_PER_MILLISECOND),
            finite.interval,
        )
        assertEquals(
            MonotonicDeadline.Finite(boundaryMillis * NANOS_PER_MILLISECOND),
            finite.deadline,
        )
        assertSame(PeriodicRefreshInterval.InfiniteFuture, infinite.interval)
        assertSame(MonotonicDeadline.InfiniteFuture, infinite.deadline)
    }

    @Test
    fun periodicSchedulingHandlesExactLongMaxAndAdditionOverflow() {
        val intervalNanos = 2L * NANOS_PER_MILLISECOND
        val exact = PeriodicRefreshPacingState(
            intervalMillis = 2L,
            commitSampleNanos = Long.MAX_VALUE - intervalNanos,
            hasCurrentSource = true,
        )
        val overflow = PeriodicRefreshPacingState(
            intervalMillis = 2L,
            commitSampleNanos = Long.MAX_VALUE - intervalNanos + 1L,
            hasCurrentSource = true,
        )

        assertEquals(MonotonicDeadline.Finite(Long.MAX_VALUE), exact.deadline)
        assertSame(MonotonicDeadline.InfiniteFuture, overflow.deadline)
    }

    @Test
    fun periodicHasNoDeadlineBeforeFirstSourceAndFreshnessFenceClearsEverything() {
        val state = PeriodicRefreshPacingState(intervalMillis = 10L, commitSampleNanos = 100L, hasCurrentSource = false)
        assertEquals(null, state.deadline)
        assertFalse(state.hasCurrentSource)
        assertTrue(state.observeClock(100L) is PeriodicRefreshClockFact.NotDue)

        state.onFirstCurrentSourceAcquired(200L)
        assertTrue(state.hasCurrentSource)
        assertEquals(MonotonicDeadline.Finite(10_000_200L), state.deadline)

        state.observeClock(10_000_200L)
        assertTrue(state.refreshPending)
        state.clearForFreshSourceFence()
        assertFalse(state.hasCurrentSource)
        assertFalse(state.refreshPending)
        assertEquals(null, state.deadline)
    }

    @Test
    fun periodicDueObservationsCoalesceAndRescheduleFromNormalizedNow() {
        val state = PeriodicRefreshPacingState(intervalMillis = 1L, commitSampleNanos = 10L, hasCurrentSource = true)
        assertTrue(state.observeClock(1_000_010L) is PeriodicRefreshClockFact.BecamePending)
        assertEquals(MonotonicDeadline.Finite(2_000_010L), state.deadline)

        val lower = state.observeClock(5L) as PeriodicRefreshClockFact.NotDue
        assertEquals(1_000_010L, lower.effectiveNowNanos)
        assertTrue(lower.clockMovedBackward)
        assertTrue(state.observeClock(2_000_010L) is PeriodicRefreshClockFact.CoalescedWhilePending)
        assertEquals(MonotonicDeadline.Finite(3_000_010L), state.deadline)
        assertTrue(state.takePendingRefresh())
        assertFalse(state.takePendingRefresh())
    }

    @Test
    fun invalidPacingInputsAreRejectedBeforeStateExists() {
        assertThrows(IllegalArgumentException::class.java) {
            MaxFpsPacingState(fps = 0, commitSampleNanos = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeriodicRefreshPacingState(intervalMillis = 0L, commitSampleNanos = 0L, hasCurrentSource = false)
        }
    }
}

private const val NANOS_PER_SECOND: Long = 1_000_000_000L
private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
