package dev.dmkr.screencaptureengine.internal.platform.metrics

import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CaptureMetricsState
import dev.dmkr.screencaptureengine.CaptureMetricsUnavailableReason
import dev.dmkr.screencaptureengine.internal.startup.TestMetricsProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun unavailableRuntimeStatePreservesLatestValidMetrics() = runTest {
        val initialMetrics = CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 440)
        val provider = TestMetricsProvider(initialMetrics)
        val observation = CaptureMetricsObservation.start(provider = provider, coroutineContext = coroutineContext)
        runCurrent()

        provider.updateUnavailable(
            reason = CaptureMetricsUnavailableReason.SourceNoLongerAvailable,
            message = "source removed",
        )
        runCurrent()

        assertEquals(initialMetrics, observation.latestMetrics)
        assertEquals(initialMetrics, observation.latestMetricsOrNull)
        assertNull(observation.latestAvailableMetricsOrNull)
        assertEquals(
            CaptureMetricsState.Unavailable(
                reason = CaptureMetricsUnavailableReason.SourceNoLongerAvailable,
                message = "source removed",
            ),
            observation.latestProviderState,
        )

        observation.close()
    }

    @Test
    fun laterAvailableStateReplacesLatestValidMetrics() = runTest {
        val provider = TestMetricsProvider(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 440))
        val observation = CaptureMetricsObservation.start(provider = provider, coroutineContext = coroutineContext)
        val laterMetrics = CaptureMetrics(widthPx = 720, heightPx = 1280, densityDpi = 320)

        provider.updateUnavailable()
        provider.update(laterMetrics)
        runCurrent()

        assertEquals(laterMetrics, observation.latestMetrics)
        assertEquals(CaptureMetricsState.Available(laterMetrics), observation.latestProviderState)

        observation.close()
    }

    @Test
    fun unavailableInitialStateHasNoStartupMetrics() = runTest {
        val provider = TestMetricsProvider(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 440))
        provider.updateUnavailable()

        val observation = CaptureMetricsObservation.start(provider = provider, coroutineContext = coroutineContext)
        runCurrent()

        assertNull(observation.latestMetricsOrNull)
        assertEquals(
            CaptureMetricsState.Unavailable(reason = CaptureMetricsUnavailableReason.SourceNotReady),
            observation.latestProviderState,
        )

        observation.close()
    }

    @Test
    fun startPassesAttachmentCallbackToProvider() = runTest {
        val provider = TestMetricsProvider(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 440))
        var callbackCount = 0

        val observation = CaptureMetricsObservation.start(
            provider = provider,
            coroutineContext = coroutineContext,
            onMetricsChanged = { callbackCount++ },
        )

        assertNotNull(provider.attachmentChangedCallback)
        provider.update(CaptureMetrics(widthPx = 720, heightPx = 1280, densityDpi = 320))
        provider.attachmentChangedCallback?.invoke()

        assertEquals(1, callbackCount)
        observation.close()
        assertNull(provider.attachmentChangedCallback)
    }

    @Test
    fun providerStateUpdateNotifiesInstalledRuntimeListenerAfterLatestStateIsUpdated() = runTest {
        val provider = TestMetricsProvider(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 440))
        val observation = CaptureMetricsObservation.start(provider = provider, coroutineContext = coroutineContext)
        var callbackState: CaptureMetricsState? = null
        observation.installRuntimeMetricsChangedListener {
            callbackState = observation.latestProviderState
        }
        val laterMetrics = CaptureMetrics(widthPx = 720, heightPx = 1280, densityDpi = 320)

        provider.update(laterMetrics)
        runCurrent()

        assertEquals(CaptureMetricsState.Available(laterMetrics), callbackState)
        observation.close()
    }

    @Test
    fun providerStateUpdateAfterCloseIsIgnored() = runTest {
        val provider = TestMetricsProvider(CaptureMetrics(widthPx = 1080, heightPx = 1920, densityDpi = 440))
        val observation = CaptureMetricsObservation.start(provider = provider, coroutineContext = coroutineContext)
        var callbackCount = 0
        observation.installRuntimeMetricsChangedListener { callbackCount++ }
        runCurrent()

        observation.close()
        provider.update(CaptureMetrics(widthPx = 720, heightPx = 1280, densityDpi = 320))
        runCurrent()

        assertEquals(0, callbackCount)
    }
}
