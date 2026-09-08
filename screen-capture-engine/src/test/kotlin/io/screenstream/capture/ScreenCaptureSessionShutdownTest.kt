package io.screenstream.capture

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.mockk.mockk
import io.screenstream.capture.internal.metrics.SessionMetricsSourceSelection
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import io.screenstream.capture.internal.runtime.HandlerThreadPlatform
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.session.SessionCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration

internal class ScreenCaptureSessionShutdownTest {
    // Verification: UNR-01
    @Test
    fun preStartConsumerAdmissionUnregisterReplacementAndUpdateArePlatformFree() = runTest {
        val (session, dispatcher) = sessionWithDispatcher()
        val initialState = session.state.value
        val initialStats = session.stats.value
        val first = session.registerFrameConsumer { fail("pre-start consumer was invoked") }

        assertThrows(IllegalStateException::class.java) {
            session.registerFrameConsumer { fail("occupied consumer was invoked") }
        }
        assertThrows(IllegalStateException::class.java) {
            session.updateParameters(ScreenCaptureParameters(jpegQuality = 81))
        }
        assertSame(initialState, session.state.value)
        assertSame(initialStats, session.stats.value)
        dispatcher.runAll()

        first.unregister()
        val replacement = session.registerFrameConsumer { fail("replacement pre-start consumer was invoked") }
        replacement.unregister()

        assertSame(initialState, session.state.value)
        assertSame(initialStats, session.stats.value)
    }

    // Verification: API-04
    // Verification: SES-02
    @Test
    fun preStartStopSettlesRegistrationWithoutCancellingCallerAndRejectsLaterWork() = runTest {
        val (session, dispatcher) = sessionWithDispatcher()
        val registration = session.registerFrameConsumer { fail("terminal consumer was invoked") }

        session.stop()
        val terminalState = session.state.value
        assertTrue(terminalState is ScreenCaptureState.Stopped)

        registration.unregister()

        assertThrows(IllegalStateException::class.java) {
            session.registerFrameConsumer { fail("post-terminal consumer was invoked") }
        }
        assertThrows(IllegalStateException::class.java) {
            session.updateParameters(ScreenCaptureParameters.DEFAULT)
        }
        val restartFailure = runCatching { session.start() }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, restartFailure?.javaClass)
        assertSame(terminalState, session.state.value)
        dispatcher.runAll()
    }

    // Verification: SES-02
    // Verification: OBS-01
    @Test
    fun stopBeforeStartPublishesRequestedTerminalDefaultsAndIsIdempotent() {
        val (session, dispatcher) = sessionWithDispatcher()
        val initialStats = session.stats.value

        assertSame(ScreenCaptureState.NotStarted, session.state.value)
        assertZeroStats(initialStats)

        session.stop()

        val stopped = session.state.value as ScreenCaptureState.Stopped
        val finalStats = session.stats.value
        assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
        assertEquals(ScreenCaptureParameters.DEFAULT, stopped.requestedParameters)
        assertNull(stopped.lastEffectiveParameters)
        assertZeroStats(finalStats)

        session.stop()

        assertEquals(stopped, session.state.value)
        assertEquals(finalStats, session.stats.value)
        dispatcher.runAll()
    }

    private fun sessionWithDispatcher(): Pair<ScreenCaptureSession, QueuedDispatcher> {
        val dispatcher = QueuedDispatcher()
        return ScreenCaptureSession.create(coordinator(dispatcher)) to dispatcher
    }

    private fun coordinator(workerDispatcher: NonInlineDispatcher): SessionCoordinator = SessionCoordinator(
        metricsSourceSelection = SessionMetricsSourceSelection.Explicit {
            throw AssertionError("Metrics subscription was not expected")
        },
        jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly,
        workerDispatcher = workerDispatcher,
        handlerThreadPlatform = FailFastHandlerThreadPlatform,
        handlerTaskPoster = FailFastHandlerTaskPoster,
        delayedEntryScheduler = { _, _ ->
            throw AssertionError("Delayed scheduling was not expected")
        },
        executionClock = { 0L },
        currentEpochMillis = { 0L },
        platformSdkInt = 36,
    ).also { it.adoptProjection(mockk(relaxed = true)) }

    private class QueuedDispatcher : NonInlineDispatcher {
        private val tasks = ArrayDeque<Runnable>()

        override fun tryDispatch(task: Runnable): Boolean {
            tasks.addLast(task)
            return true
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
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

    private object FailFastHandlerThreadPlatform : HandlerThreadPlatform {
        override fun newThread(name: String): HandlerThread =
            throw AssertionError("HandlerThread creation was not expected")

        override fun start(thread: HandlerThread): Unit =
            throw AssertionError("HandlerThread start was not expected")

        @Suppress("RedundantNullableReturnType")
        override fun looper(thread: HandlerThread): Looper? =
            throw AssertionError("Looper access was not expected")

        override fun handler(looper: Looper): Handler =
            throw AssertionError("Handler creation was not expected")
    }

    private object FailFastHandlerTaskPoster : HandlerTaskPoster {
        override fun post(handler: Handler, task: Runnable): Boolean =
            throw AssertionError("Handler post was not expected")

        override fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean =
            throw AssertionError("Handler delayed post was not expected")

        override fun removeCallbacks(handler: Handler, task: Runnable): Unit =
            throw AssertionError("Handler callback removal was not expected")
    }
}
