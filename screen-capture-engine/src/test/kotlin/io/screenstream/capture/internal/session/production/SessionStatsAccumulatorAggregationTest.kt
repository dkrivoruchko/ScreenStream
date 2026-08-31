package io.screenstream.capture.internal.session.production

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

internal class SessionStatsAccumulatorAggregationTest {
    // Verification: SES-07
    @Test
    fun recordsOnlyTheOwnedStatsMembershipAndMaintainsFiniteAverages() {
        val accumulator = SessionStatsAccumulator()
        assertFalse(accumulator.hasUnpublishedChanges)

        accumulator.recordReadback(durationNanos = 2_000_000L)
        accumulator.recordEncodeSuccess(durationNanos = 4_000_000L, encodedByteCount = 100)
        accumulator.recordEncodeSuccess(durationNanos = 5_000_000L, encodedByteCount = 100)
        accumulator.recordEncodeSuccess(durationNanos = 6_000_000L, encodedByteCount = 102)
        accumulator.recordProducedFrame(timestampNanos = 10L)
        accumulator.recordProducedFrame(timestampNanos = 1_000_000_010L)
        accumulator.recordStaleWork()
        accumulator.recordProductionFailure()
        accumulator.recordConsumerBusy()
        accumulator.recordCallbackFailure()

        val stats = accumulator.snapshot()
        assertEquals(3L, stats.encodedFrameCount)
        assertEquals(2L, stats.producedFrameCount)
        assertEquals(1L, stats.droppedFrames.byStaleWork)
        assertEquals(1L, stats.droppedFrames.byFailure)
        assertEquals(1L, stats.droppedDeliveries.byConsumerBusy)
        assertEquals(1L, stats.droppedDeliveries.byCallbackFailure)
        assertEquals(5.milliseconds, stats.averageEncodingDuration)
        assertEquals(2.milliseconds, stats.averageReadbackDuration)
        assertEquals(102, stats.lastEncodedByteCount)
        assertEquals(101, stats.averageEncodedByteCount)
        assertEquals(1.0, stats.averageProducedFps, 0.0)
        assertTrue(accumulator.hasUnpublishedChanges)

        accumulator.markPublished()
        assertFalse(accumulator.hasUnpublishedChanges)
    }

    // Verification: SES-07
    @Test
    fun equalProductionTimestampsKeepAverageFpsAtZero() {
        val accumulator = SessionStatsAccumulator()

        accumulator.recordProducedFrame(timestampNanos = 123L)
        accumulator.recordProducedFrame(timestampNanos = 123L)

        val stats = accumulator.snapshot()
        assertEquals(2L, stats.producedFrameCount)
        assertEquals(0.0, stats.averageProducedFps, 0.0)
    }
}
