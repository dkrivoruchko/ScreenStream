package io.screenstream.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.screenstream.capture.internal.runtime.ProductionRuntime
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        val context: Context = mockk(relaxed = false)
        val projection: MediaProjection = mockk(relaxed = true)

        val session = ScreenCaptureEngine.createSession(context, projection, config)
        val otherSession = ScreenCaptureEngine.createSession(context, mockk(relaxed = true), config)
        verify { context wasNot Called }

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

        session.stop()
        otherSession.stop()
    }

    // Verification: SES-01
    @Test
    fun successfulFactoryTransfersIdleProjectionAndHostStopRetiresItOnce() = runTest {
        val dispatcher = ControlledNonInlineDispatcher()
        mockkObject(ProductionRuntime)
        every { ProductionRuntime.workerDispatcher } returns dispatcher
        every { ProductionRuntime.handlerThreadPlatform } answers { callOriginal() }
        every { ProductionRuntime.handlerTaskPoster } answers { callOriginal() }
        every { ProductionRuntime.delayedEntryScheduler } answers { callOriginal() }
        every { ProductionRuntime.elapsedRealtimeClock } answers { callOriginal() }
        every { ProductionRuntime.currentEpochMillis } answers { callOriginal() }
        val context: Context = mockk(relaxed = false)
        val projection: MediaProjection = mockk(relaxed = true)
        val source = CaptureMetricsSource { AutoCloseable {} }
        try {
            val session = ScreenCaptureEngine.createSession(
                context,
                projection,
                ScreenCaptureConfig(captureMetricsSource = source),
            )
            val caller = Job().apply { cancel() }
            val start = CoroutineScope(caller).async(start = CoroutineStart.DEFAULT) { session.start() }

            assertSame(ScreenCaptureState.NotStarted, session.state.value)
            session.stop()
            try {
                start.await()
                throw AssertionError("cancelled-before-entry start completed normally")
            } catch (_: kotlinx.coroutines.CancellationException) {
            }

            val retirement = dispatcher.enterNext() ?: error("Projection retirement was not queued")
            retirement.awaitSuccessfulCompletion()
            assertTrue(session.state.value is ScreenCaptureState.Stopped)
            assertZeroStats(session.stats.value)
            session.stop()
            assertEquals(0, dispatcher.pendingCount())
            verify(exactly = 1) { projection.stop() }
        } finally {
            unmockkObject(ProductionRuntime)
            dispatcher.close()
        }
    }

    // Verification: SES-01
    @Test
    fun defaultSourceFactoryFailureLeavesProjectionWithCaller() = runTest {
        val context: Context = mockk(relaxed = false)
        every { context.applicationContext } returns context
        every { context.getSystemService(DisplayManager::class.java) } returns null
        val projection: MediaProjection = mockk(relaxed = true)

        assertThrows(IllegalArgumentException::class.java) {
            ScreenCaptureEngine.createSession(context, projection)
        }

        verify { projection wasNot Called }
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
