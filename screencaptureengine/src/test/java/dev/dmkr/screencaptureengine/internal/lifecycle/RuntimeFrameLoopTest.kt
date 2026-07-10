package dev.dmkr.screencaptureengine.internal.lifecycle

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionCapturedContentResize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFrameLoopTest {
    @Test
    fun sourceFrameSignalRemainsPendingDuringProductionAdmissionPauseAndAdmitsAfterResume() {
        var runtimeWakeCount = 0
        val loop = runtimeFrameLoop(onRuntimeSignalRecorded = { runtimeWakeCount += 1 })
        loop.drainPendingSignalsForCommit(latestMetrics = startupMetrics, projectionStopObserved = false)

        loop.pauseProductionAdmission()
        loop.recordSourceFrameAvailable(generation = 1L)
        loop.recordSourceFrameAvailable(generation = 2L)

        assertFalse(loop.hasPendingRuntimeWork(latestMetrics = startupMetrics, projectionStopObserved = false))
        assertNull(loop.admitLatestFrameSignal())
        assertEquals(0, runtimeWakeCount)
        loop.snapshot().let { snapshot ->
            assertTrue(snapshot.productionAdmissionPaused)
            assertTrue(snapshot.sourceFrameSignalPending)
            assertEquals(2L, snapshot.latestSourceFrameGeneration)
            assertEquals(0L, snapshot.admittedProductionAttempts)
        }

        loop.resumeProductionAdmission()
        val signal = loop.admitLatestFrameSignal()

        assertEquals(1, runtimeWakeCount)
        assertEquals(2L, signal?.generation)
        loop.snapshot().let { snapshot ->
            assertFalse(snapshot.productionAdmissionPaused)
            assertFalse(snapshot.sourceFrameSignalPending)
            assertNull(snapshot.latestSourceFrameGeneration)
            assertEquals(1L, snapshot.admittedProductionAttempts)
        }
    }

    @Test
    fun periodicRefreshWakeRemainsPendingDuringProductionAdmissionPauseAndConsumesAfterResume() {
        var runtimeWakeCount = 0
        val loop = runtimeFrameLoop(onRuntimeSignalRecorded = { runtimeWakeCount += 1 })
        loop.drainPendingSignalsForCommit(latestMetrics = startupMetrics, projectionStopObserved = false)

        loop.pauseProductionAdmission()
        loop.recordPeriodicRefreshWake()

        assertFalse(loop.hasPendingRuntimeWork(latestMetrics = startupMetrics, projectionStopObserved = false))
        assertFalse(loop.consumePeriodicRefreshWake())
        assertEquals(0, runtimeWakeCount)
        loop.snapshot().let { snapshot ->
            assertTrue(snapshot.productionAdmissionPaused)
            assertTrue(snapshot.periodicRefreshWakePending)
        }

        loop.resumeProductionAdmission()

        assertEquals(1, runtimeWakeCount)
        assertTrue(loop.consumePeriodicRefreshWake())
        assertFalse(loop.snapshot().periodicRefreshWakePending)
    }

    @Test
    fun terminalGeometryVisibilityAndMetricsSignalsDrainWhileProductionAdmissionPaused() {
        val loop = runtimeFrameLoop(startupGeometrySource = CaptureGeometrySource.CapturedContentResize)
        loop.drainPendingSignalsForCommit(latestMetrics = startupMetrics, projectionStopObserved = false)
        loop.pauseProductionAdmission()
        loop.recordSourceFrameAvailable(generation = 1L)
        loop.recordPeriodicRefreshWake()
        loop.recordCapturedContentResize(ProjectionCapturedContentResize(id = 9L, width = 801, height = 601))
        loop.recordCapturedContentVisibility(isVisible = false)
        loop.recordMetricsObservationChanged()

        assertTrue(loop.hasPendingRuntimeWork(latestMetrics = startupMetrics, projectionStopObserved = false))
        val signals = loop.drainPendingSignalsForRuntime(latestMetrics = startupMetrics, projectionStopObserved = false)

        assertFalse(signals.projectionStopObserved)
        assertEquals(ProjectionCapturedContentResize(id = 9L, width = 801, height = 601), signals.pendingCapturedContentResize)
        assertEquals(
            CaptureGeometry(widthPx = 801, heightPx = 601, densityDpi = 320, source = CaptureGeometrySource.CapturedContentResize),
            signals.pendingCaptureGeometry,
        )
        assertEquals(false, signals.latestCapturedContentVisible)
        assertTrue(signals.metricsObservationChanged)
        assertTrue(loop.snapshot().sourceFrameSignalPending)
        assertTrue(loop.snapshot().periodicRefreshWakePending)

        loop.recordProjectionStopped()
        assertTrue(loop.hasPendingRuntimeWork(latestMetrics = startupMetrics, projectionStopObserved = false))
        val terminalSignals = loop.drainPendingSignalsForRuntime(latestMetrics = startupMetrics, projectionStopObserved = false)

        assertTrue(terminalSignals.projectionStopObserved)
        assertNull(terminalSignals.pendingCapturedContentResize)
        assertNull(terminalSignals.pendingCaptureGeometry)
        assertNull(terminalSignals.latestCapturedContentVisible)
        assertFalse(terminalSignals.metricsObservationChanged)
        assertTrue(loop.snapshot().sourceFrameSignalPending)
        assertTrue(loop.snapshot().periodicRefreshWakePending)
        assertNull(loop.admitLatestFrameSignal())
        assertFalse(loop.consumePeriodicRefreshWake())
    }

    private fun runtimeFrameLoop(
        startupGeometrySource: CaptureGeometrySource = CaptureGeometrySource.MetricsProvider,
        onRuntimeSignalRecorded: () -> Unit = {},
    ): RuntimeFrameLoop =
        RuntimeFrameLoop(
            startupGeometry = CaptureGeometry(
                widthPx = startupMetrics.widthPx,
                heightPx = startupMetrics.heightPx,
                densityDpi = startupMetrics.densityDpi,
                source = startupGeometrySource,
            ),
            onRuntimeSignalRecorded = onRuntimeSignalRecorded,
        )

    private companion object {
        val startupMetrics = CaptureMetrics(widthPx = 800, heightPx = 600, densityDpi = 320)
    }
}
