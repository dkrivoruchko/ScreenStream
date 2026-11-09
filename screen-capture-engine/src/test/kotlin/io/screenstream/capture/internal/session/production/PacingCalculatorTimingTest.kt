package io.screenstream.capture.internal.session.production

import io.screenstream.capture.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

internal class PacingCalculatorTimingTest {
    // Verification: SES-05
    @Test
    fun freshOutputDefersUntilExactMaxFpsBoundary() {
        val history = CadenceHistory(
            lastGrantNanos = 100L,
            phase = 0,
            requiredGapNanos = 500_000_000L,
        )

        val early = PacingCalculator.freshOutput(
            frameRate = FrameRate.MaxFps(2),
            nowNanos = 500_000_099L,
            history = history,
        )
        assertTrue(early is PacingDecision.Deferred)
        assertEquals(500_000_100L, (early as PacingDecision.Deferred).eligibleAtNanos)

        val boundary = PacingCalculator.freshOutput(
            frameRate = FrameRate.MaxFps(2),
            nowNanos = 500_000_100L,
            history = history,
        )
        assertTrue(boundary is PacingDecision.Eligible)
        boundary as PacingDecision.Eligible
        assertEquals(0, boundary.nextPhase)
        assertEquals(500_000_000L, boundary.nextRequiredGapNanos)
    }

    // Verification: SES-05
    @Test
    fun autoIsImmediateAndNegativeClockIsInvalid() {
        val eligible = PacingCalculator.freshCapture(FrameRate.Auto, nowNanos = 0L, lastFreshGrantNanos = null, history = null)
        assertTrue(eligible is PacingDecision.Eligible)
        eligible as PacingDecision.Eligible
        assertNull(eligible.nextPhase)
        assertEquals(0L, eligible.nextRequiredGapNanos)

        assertTrue(
            PacingCalculator.freshCapture(FrameRate.Auto, nowNanos = -1L, lastFreshGrantNanos = null, history = null) ===
                    PacingDecision.InvalidEvidence,
        )
    }

    // Verification: SES-05
    @Test
    fun samplingIntervalDefersFreshCaptureWithoutDelayingFreshOutput() {
        val sampling = FrameRate.SamplingInterval(1_000.milliseconds)
        val first = PacingCalculator.freshCapture(sampling, nowNanos = 0L, lastFreshGrantNanos = null, history = null)
        assertTrue(first is PacingDecision.Eligible)
        assertEquals(1_000_000_000L, (first as PacingDecision.Eligible).nextRequiredGapNanos)

        val early = PacingCalculator.freshCapture(
            sampling,
            nowNanos = 999_999_999L,
            lastFreshGrantNanos = 0L,
            history = null,
        )
        assertTrue(early is PacingDecision.Deferred)
        assertEquals(1_000_000_000L, (early as PacingDecision.Deferred).eligibleAtNanos)

        val boundary = PacingCalculator.freshCapture(
            frameRate = sampling,
            nowNanos = 1_000_000_000L,
            lastFreshGrantNanos = 0L,
            history = null,
        )
        assertTrue(boundary is PacingDecision.Eligible)

        val output = PacingCalculator.freshOutput(
            frameRate = sampling,
            nowNanos = 999_999_999L,
            history = null,
        )
        assertTrue(output is PacingDecision.Eligible)
    }

    // Verification: SES-05
    @Test
    fun rationalMaxFpsAdvancesOnePhasePerGrantWithoutCatchUp() {
        val frameRate = FrameRate.MaxFps(3)
        val first = PacingCalculator.freshCapture(frameRate, nowNanos = 0L, lastFreshGrantNanos = null, history = null)
        assertTrue(first is PacingDecision.Eligible)
        first as PacingDecision.Eligible
        assertEquals(1, first.nextPhase)
        assertEquals(333_333_333L, first.nextRequiredGapNanos)
        val firstHistory = CadenceHistory(0L, first.nextPhase ?: error("missing phase"), first.nextRequiredGapNanos)

        val early = PacingCalculator.freshCapture(frameRate, 333_333_332L, 0L, firstHistory)
        assertTrue(early is PacingDecision.Deferred)
        assertEquals(333_333_333L, (early as PacingDecision.Deferred).eligibleAtNanos)

        val exact = PacingCalculator.freshCapture(frameRate, 333_333_333L, 0L, firstHistory)
        assertTrue(exact is PacingDecision.Eligible)
        exact as PacingDecision.Eligible
        assertEquals(2, exact.nextPhase)
        assertEquals(333_333_333L, exact.nextRequiredGapNanos)
        val exactHistory = CadenceHistory(
            lastGrantNanos = 333_333_333L,
            phase = exact.nextPhase ?: error("missing phase"),
            requiredGapNanos = exact.nextRequiredGapNanos,
        )

        val deepSleep = PacingCalculator.freshCapture(frameRate, 10_000_000_000L, 333_333_333L, exactHistory)
        assertTrue(deepSleep is PacingDecision.Eligible)
        deepSleep as PacingDecision.Eligible
        assertEquals(0, deepSleep.nextPhase)
        assertEquals(333_333_334L, deepSleep.nextRequiredGapNanos)

        assertTrue(
            PacingCalculator.freshCapture(frameRate, 333_333_332L, 333_333_333L, exactHistory) ===
                    PacingDecision.InvalidEvidence,
        )
    }
}
