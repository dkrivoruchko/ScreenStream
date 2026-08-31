package io.screenstream.capture.internal.session.production

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class SessionProductionStatsTest {
    // Verification: SES-07
    @Test
    fun ordinaryStatsWaitForNaturalOneSecondCadenceAndClearDirtyStateOnCommit() {
        val production = SessionProduction(creationElapsedRealtimeNanos = 0L)
        production.recordConsumerBusy()

        assertNull(production.prepareStats(publicationNanos = 999_999_999L))
        val first = production.prepareStats(publicationNanos = 1_000_000_000L)
            ?: error("dirty Stats were not cadence eligible")
        assertEquals(1L, first.stats.droppedDeliveries.byConsumerBusy)
        production.commitStatsPublication(first)
        assertNull(production.prepareStats(publicationNanos = 1_000_000_000L))

        production.recordCallbackFailure()
        assertNull(production.prepareStats(publicationNanos = 1_999_999_999L))
        val second = production.prepareStats(publicationNanos = 2_000_000_000L)
            ?: error("successor Stats were not cadence eligible")
        assertEquals(1L, second.stats.droppedDeliveries.byCallbackFailure)
    }

    // Verification: SES-07
    @Test
    fun terminalSnapshotFreezesFinalStatsAndIgnoresLaterAccounting() {
        val production = SessionProduction(creationElapsedRealtimeNanos = 0L)
        production.recordReadback(durationNanos = 3L)
        production.recordEncodeSuccess(durationNanos = 5L, encodedByteCount = 7)
        production.recordProductionFailure()

        val terminal = production.prepareTerminal()
        assertEquals(1L, terminal.finalStats.encodedFrameCount)
        assertEquals(1L, terminal.finalStats.droppedFrames.byFailure)
        assertEquals(0.0, terminal.finalStats.averageProducedFps, 0.0)
        production.commitTerminal(terminal)

        production.recordReadback(durationNanos = 30L)
        production.recordEncodeSuccess(durationNanos = 50L, encodedByteCount = 70)
        production.recordProductionFailure()
        production.recordConsumerBusy()
        assertNull(production.prepareStats(publicationNanos = Long.MAX_VALUE))
        assertEquals(1L, terminal.finalStats.encodedFrameCount)
        assertEquals(1L, terminal.finalStats.droppedFrames.byFailure)
        assertEquals(0L, terminal.finalStats.droppedDeliveries.byConsumerBusy)
    }
}
