package io.screenstream.capture

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
internal class ScreenCaptureEngineLifecycleTest {
    // Verification: API-02
    // Verification: OBS-01
    @Test
    fun createSessionStartsColdWithoutMetricsSubscription() = runTest {
        val subscriptionCount = AtomicInteger()
        val metricsSource = CaptureMetricsSource {
            subscriptionCount.incrementAndGet()
            AutoCloseable {}
        }
        val config = ScreenCaptureConfig(captureMetricsSource = metricsSource)
        val application: Application = RuntimeEnvironment.getApplication()

        val session = ScreenCaptureEngine.createSession(application, config)
        val otherSession = ScreenCaptureEngine.createSession(application, config)

        assertNotSame(session, otherSession)
        assertEquals(0, subscriptionCount.get())

        val state = session.state
        val stats = session.stats
        val diagnosticEvents = session.diagnosticEvents
        assertSame(state, session.state)
        assertSame(stats, session.stats)
        assertSame(diagnosticEvents, session.diagnosticEvents)
        assertSame(ScreenCaptureState.NotStarted, state.value)
        assertZeroStats(stats.value)
        assertEquals(0, subscriptionCount.get())

        assertSame(ScreenCaptureState.NotStarted, state.first())
        assertZeroStats(stats.first())
        assertEquals(0, subscriptionCount.get())
    }

    private fun assertZeroStats(stats: ScreenCaptureStats) {
        assertEquals(0L, stats.encodedFrameCount)
        assertEquals(0L, stats.producedFrameCount)
        assertEquals(0L, stats.droppedFrames.byStaleWork)
        assertEquals(0L, stats.droppedFrames.byFailure)
        assertEquals(0L, stats.droppedFrames.total)
        assertEquals(0L, stats.droppedDeliveries.byConsumerBusy)
        assertEquals(0L, stats.droppedDeliveries.byCallbackFailure)
        assertEquals(0L, stats.droppedDeliveries.total)
        assertEquals(0.0, stats.averageProducedFps, 0.0)
        assertEquals(Duration.ZERO, stats.averageEncodingDuration)
        assertEquals(Duration.ZERO, stats.averageReadbackDuration)
        assertEquals(0, stats.lastEncodedByteCount)
        assertEquals(0, stats.averageEncodedByteCount)
    }
}
