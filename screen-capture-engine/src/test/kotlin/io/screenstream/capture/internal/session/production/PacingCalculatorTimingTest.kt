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
    fun repeatOutputDefersUntilExactRepeatBoundary() {
        val early = PacingCalculator.repeatOutput(
            frameRate = FrameRate.Auto,
            repeatInterval = 1_000.milliseconds,
            nowNanos = 1_000_000_009L,
            lastOutputGrantNanos = 10L,
            outputHistory = null,
        )
        assertTrue(early is PacingDecision.Deferred)
        assertEquals(1_000_000_010L, (early as PacingDecision.Deferred).eligibleAtNanos)

        val boundary = PacingCalculator.repeatOutput(
            frameRate = FrameRate.Auto,
            repeatInterval = 1_000.milliseconds,
            nowNanos = 1_000_000_010L,
            lastOutputGrantNanos = 10L,
            outputHistory = null,
        )
        assertTrue(boundary is PacingDecision.Eligible)
        boundary as PacingDecision.Eligible
        assertNull(boundary.nextPhase)
        assertEquals(0L, boundary.nextRequiredGapNanos)
    }

    // Verification: SES-05
    @Test
    fun maxFpsRepeatDefersToRepeatBoundaryAfterWrappedCadenceIsEligible() {
        val history = CadenceHistory(
            lastGrantNanos = 666_666_666L,
            phase = 0,
            requiredGapNanos = 333_333_334L,
        )

        val early = PacingCalculator.repeatOutput(
            frameRate = FrameRate.MaxFps(3),
            repeatInterval = 1_000.milliseconds,
            nowNanos = 1_000_000_000L,
            lastOutputGrantNanos = 666_666_666L,
            outputHistory = history,
        )
        assertTrue(early is PacingDecision.Deferred)
        assertEquals(1_666_666_666L, (early as PacingDecision.Deferred).eligibleAtNanos)

        val successor = PacingCalculator.repeatOutput(
            frameRate = FrameRate.MaxFps(3),
            repeatInterval = 1_000.milliseconds,
            nowNanos = 1_666_666_666L,
            lastOutputGrantNanos = 666_666_666L,
            outputHistory = history,
        )
        assertTrue(successor is PacingDecision.Eligible)
        successor as PacingDecision.Eligible
        assertEquals(1, successor.nextPhase)
        assertEquals(333_333_333L, successor.nextRequiredGapNanos)
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
    fun samplingIsImmediateOnceThenRetainsUntilExactInterval() {
        val frameRate = FrameRate.SamplingInterval(1_001.milliseconds)
        val first = PacingCalculator.freshCapture(frameRate, nowNanos = 5L, lastFreshGrantNanos = null, history = null)
        assertTrue(first is PacingDecision.Eligible)
        assertEquals(1_001_000_000L, (first as PacingDecision.Eligible).nextRequiredGapNanos)

        val early = PacingCalculator.freshCapture(
            frameRate,
            nowNanos = 1_001_000_004L,
            lastFreshGrantNanos = 5L,
            history = null,
        )
        assertTrue(early is PacingDecision.RetainOpportunity)
        assertEquals(1_001_000_005L, (early as PacingDecision.RetainOpportunity).eligibleAtNanos)

        assertTrue(
            PacingCalculator.freshCapture(
                frameRate,
                nowNanos = 10_000_000_000L,
                lastFreshGrantNanos = 5L,
                history = null,
            ) is PacingDecision.Eligible,
        )
        assertTrue(
            PacingCalculator.freshCapture(frameRate, nowNanos = 4L, lastFreshGrantNanos = 5L, history = null) ===
                    PacingDecision.InvalidEvidence,
        )
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
        assertTrue(early is PacingDecision.RetainOpportunity)
        assertEquals(333_333_333L, (early as PacingDecision.RetainOpportunity).eligibleAtNanos)

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
