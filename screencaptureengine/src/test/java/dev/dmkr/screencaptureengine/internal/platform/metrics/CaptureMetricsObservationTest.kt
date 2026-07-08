package dev.dmkr.screencaptureengine.internal.platform.metrics

import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.internal.startup.TestMetricsProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureMetricsObservationTest {
    @Test
    fun closeDoesNotCancelCallerScopeJob() = runTest {
        val callerJob = checkNotNull(coroutineContext[Job])
        val provider = TestMetricsProvider(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 440))
        val observation = CaptureMetricsObservation.start(provider = provider, coroutineContext = coroutineContext)
        runCurrent()

        observation.close()
        runCurrent()

        assertTrue(callerJob.isActive)
        assertEquals(0, provider.activeCollectorCount)
        assertEquals(1, provider.attachmentDisposeCount)
    }
}
