package dev.dmkr.screencaptureengine.internal.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeEncodeHardFailureTrackerTest {
    @Test
    fun thresholdMustBePositive() {
        listOf(0, -1).forEach { threshold ->
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeEncodeHardFailureTracker<HealthKey>(threshold = threshold)
            }
        }
    }

    @Test
    fun hardFailuresReportBelowThresholdUntilTheExactThresholdFailure() {
        val tracker = RuntimeEncodeHardFailureTracker<HealthKey>(threshold = 3)

        assertEquals(RuntimeEncodeHardFailureResult.BelowThreshold, tracker.recordHardFailure(currentKey))
        assertEquals(RuntimeEncodeHardFailureResult.BelowThreshold, tracker.recordHardFailure(currentKey))
        assertEquals(RuntimeEncodeHardFailureResult.ThresholdReached, tracker.recordHardFailure(currentKey))
    }

    @Test
    fun thresholdOneIsReachedByTheFirstHardFailure() {
        val tracker = RuntimeEncodeHardFailureTracker<HealthKey>(threshold = 1)

        assertEquals(RuntimeEncodeHardFailureResult.ThresholdReached, tracker.recordHardFailure(currentKey))
    }

    @Test
    fun differentFailureKeyStartsANewConsecutiveStreak() {
        val tracker = RuntimeEncodeHardFailureTracker<HealthKey>(threshold = 2)

        assertEquals(RuntimeEncodeHardFailureResult.BelowThreshold, tracker.recordHardFailure(staleKey))
        assertEquals(RuntimeEncodeHardFailureResult.BelowThreshold, tracker.recordHardFailure(currentKey))
        assertEquals(RuntimeEncodeHardFailureResult.ThresholdReached, tracker.recordHardFailure(currentKey))
    }

    @Test
    fun matchingSuccessResetsTheConsecutiveStreak() {
        val tracker = RuntimeEncodeHardFailureTracker<HealthKey>(threshold = 2)
        tracker.recordHardFailure(currentKey)

        tracker.recordSuccess(currentKey)

        assertEquals(RuntimeEncodeHardFailureResult.BelowThreshold, tracker.recordHardFailure(currentKey))
    }

    @Test
    fun mismatchedStaleSuccessDoesNotResetTheCurrentStreak() {
        val tracker = RuntimeEncodeHardFailureTracker<HealthKey>(threshold = 2)
        tracker.recordHardFailure(currentKey)

        tracker.recordSuccess(staleKey)

        assertEquals(RuntimeEncodeHardFailureResult.ThresholdReached, tracker.recordHardFailure(currentKey))
    }

    @Test
    fun explicitResetClearsTheCurrentStreak() {
        val tracker = RuntimeEncodeHardFailureTracker<HealthKey>(threshold = 2)
        tracker.recordHardFailure(currentKey)

        tracker.reset()

        assertEquals(RuntimeEncodeHardFailureResult.BelowThreshold, tracker.recordHardFailure(currentKey))
    }

    @Test
    fun sameKeyCannotAdvancePastTheExactThresholdWithoutReset() {
        val tracker = RuntimeEncodeHardFailureTracker<HealthKey>(threshold = 1)
        tracker.recordHardFailure(currentKey)

        assertThrows(IllegalStateException::class.java) {
            tracker.recordHardFailure(currentKey)
        }

        tracker.reset()
        assertEquals(RuntimeEncodeHardFailureResult.ThresholdReached, tracker.recordHardFailure(currentKey))
    }

    private data class HealthKey(
        val outputGeneration: Long,
        val providerIdentity: String,
    )

    private companion object {
        val currentKey = HealthKey(outputGeneration = 2L, providerIdentity = "current")
        val staleKey = HealthKey(outputGeneration = 1L, providerIdentity = "stale")
    }
}
