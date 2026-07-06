package dev.dmkr.screencaptureengine.internal.session

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CaptureMetricsProvider
import dev.dmkr.screencaptureengine.CaptureTarget
import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.FrameRate
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageRect
import dev.dmkr.screencaptureengine.Mirror
import dev.dmkr.screencaptureengine.OutputSize
import dev.dmkr.screencaptureengine.ReadbackMode
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureEffectiveParameters
import dev.dmkr.screencaptureengine.ScreenCaptureOutputState
import dev.dmkr.screencaptureengine.ScreenCaptureParameterUpdateResult
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblem
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.ScreenCaptureSessionState
import dev.dmkr.screencaptureengine.ScreenCaptureStopReason
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.SourceRegion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class ScreenCaptureSessionCoreTest {
    @Test
    fun publishEncodedFrame_staleGenerationCountsEncodedAndDropWithoutPublishing() {
        val session = sessionCore()
        val deliveredSequences = LinkedBlockingQueue<Long>()
        session.onFrame { frame -> deliveredSequences.put(frame.sequence) }
        assertEquals(0L, session.currentOutputGeneration())
        session.updateOutputActive(effectiveParameters(), generation = 1L)

        val published = session.publishEncodedFrame(
            generation = 0L,
            format = EncodedImageFormats.Jpeg,
            bytes = byteArrayOf(1, 2, 3),
        )

        assertFalse(published)
        deliveredSequences.assertNoValue("stale-generation frame callback")
        val stats = session.stats.value
        assertEquals(1L, stats.framesEncoded)
        assertEquals(0L, stats.framesPublished)
        assertEquals(1L, stats.droppedFrames.total)
        assertEquals(1L, stats.droppedFrames.byStaleGeneration)
        assertEquals(0L, stats.droppedDeliveries.total)
        assertEquals(3, stats.lastEncodedByteCount)
        assertEquals(3, stats.averageEncodedByteCount)
        assertEquals(1, stats.activeFrameSubscriptions)
        assertEquals(0, stats.slowConsumers)
    }

    @Test
    fun publishEncodedFrame_outputSuspendedCountsEncodedAndOutputSuspendedDropWithoutPublishing() {
        val session = sessionCore()
        val deliveredSequences = LinkedBlockingQueue<Long>()
        session.onFrame { frame -> deliveredSequences.put(frame.sequence) }
        val previousParameters = effectiveParameters()
        val suspendedProblem = outputPlanInvalidProblem()
        val currentGeometry = CaptureGeometry(
            widthPx = 320,
            heightPx = 240,
            densityDpi = 320,
            source = CaptureGeometrySource.CapturedContentResize,
        )
        session.updateOutputSuspended(
            problem = suspendedProblem,
            previousEffectiveParameters = previousParameters,
            currentCaptureGeometry = currentGeometry,
            generation = 1L,
        )

        val published = session.publishEncodedFrame(
            generation = 1L,
            format = EncodedImageFormats.Jpeg,
            bytes = byteArrayOf(4, 5, 6, 7),
        )

        assertFalse(published)
        deliveredSequences.assertNoValue("suspended-output frame callback")
        val stats = session.stats.value
        assertEquals(1L, stats.framesEncoded)
        assertEquals(0L, stats.framesPublished)
        assertEquals(1L, stats.droppedFrames.total)
        assertEquals(1L, stats.droppedFrames.byOutputSuspended)
        assertEquals(0L, stats.droppedDeliveries.total)
        assertEquals(4, stats.lastEncodedByteCount)
        assertEquals(4, stats.averageEncodedByteCount)
        assertEquals(1, stats.activeFrameSubscriptions)
        assertEquals(0, stats.slowConsumers)
    }

    @Test
    fun updateCapturedContentVisibility_falseKeepsActiveOutputAndPublishedFrameDelivery() {
        val callbackDispatcher = QueuingDispatcher()
        val session = sessionCore(frameCallbackDispatcher = callbackDispatcher)
        try {
            val deliveredSequences = LinkedBlockingQueue<Long>()
            session.onFrame { frame -> deliveredSequences.put(frame.sequence) }

            val updated = session.updateCapturedContentVisibility(isVisible = false)

            assertEquals(true, updated)
            val state = session.state.value as ScreenCaptureSessionState.Running
            assertEquals(false, state.capturedContentVisible)
            assertEquals(true, state.output is ScreenCaptureOutputState.Active)

            val published = session.publishEncodedFrame(
                generation = 0L,
                format = EncodedImageFormats.Jpeg,
                bytes = byteArrayOf(11, 12, 13),
            )
            assertEquals(true, published)

            val callbackTask = callbackDispatcher.awaitDispatched("frame callback dispatch after visibility hidden")
            callbackTask.run()

            assertEquals(1L, deliveredSequences.awaitValue("frame callback after visibility hidden"))
            val statsAfterDelivery = session.stats.value
            assertEquals(1L, statsAfterDelivery.framesEncoded)
            assertEquals(1L, statsAfterDelivery.framesPublished)
            assertEquals(0L, statsAfterDelivery.droppedFrames.total)
            assertEquals(0L, statsAfterDelivery.droppedDeliveries.total)
            assertEquals(3, statsAfterDelivery.lastEncodedByteCount)
            assertEquals(3, statsAfterDelivery.averageEncodedByteCount)
            assertEquals(1, statsAfterDelivery.activeFrameSubscriptions)
            assertEquals(0, statsAfterDelivery.slowConsumers)
        } finally {
            session.stop()
        }
    }

    @Test
    fun setParameters_stopBeforeCommitGateRejectsAndDoesNotRunVisibleCommit() = runTest {
        var visibleCommitRan = false
        lateinit var session: ScreenCaptureSessionCore
        session = sessionCore { _, commitGate ->
            session.stop()
            commitGate.commit {
                visibleCommitRan = true
                ScreenCaptureParameterUpdateResult.Applied
            }
        }

        val result = session.setParameters(ScreenCaptureParameters.defaults())

        assertFalse(visibleCommitRan)
        val rejection = result as ScreenCaptureParameterUpdateResult.Rejected
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, rejection.problem.kind)
        val state = session.state.value as ScreenCaptureSessionState.Stopped
        assertEquals(ScreenCaptureStopReason.OwnerStop, state.reason)
        assertSame(null, state.problem)
    }

    @Test
    fun setParameters_stopAfterCommitGateReturnsApplied() = runTest {
        var visibleCommitRan = false
        lateinit var session: ScreenCaptureSessionCore
        session = sessionCore { _, commitGate ->
            val result = commitGate.commit {
                visibleCommitRan = true
                ScreenCaptureParameterUpdateResult.Applied
            }
            session.stop()
            result
        }

        val result = session.setParameters(ScreenCaptureParameters.defaults())

        assertSame(ScreenCaptureParameterUpdateResult.Applied, result)
        assertEquals(true, visibleCommitRan)
        val state = session.state.value as ScreenCaptureSessionState.Stopped
        assertEquals(ScreenCaptureStopReason.OwnerStop, state.reason)
        assertSame(null, state.problem)
    }

    @Test
    fun updateOutputSuspended_invalidatesMaterializedLatestDeliveryBeforeCallbackAdmission() {
        val callbackDispatcher = QueuingDispatcher()
        val session = sessionCore(frameCallbackDispatcher = callbackDispatcher)
        try {
            val callbackCount = AtomicInteger()
            session.onFrame { callbackCount.incrementAndGet() }

            val published = session.publishEncodedFrame(
                generation = 0L,
                format = EncodedImageFormats.Jpeg,
                bytes = byteArrayOf(8, 9, 10),
            )

            assertEquals(true, published)
            val callbackTask = callbackDispatcher.awaitDispatched("frame callback dispatch")
            val previousParameters = effectiveParameters()
            session.updateOutputSuspended(
                problem = outputPlanInvalidProblem(),
                previousEffectiveParameters = previousParameters,
                currentCaptureGeometry = CaptureGeometry(
                    widthPx = 320,
                    heightPx = 240,
                    densityDpi = 320,
                    source = CaptureGeometrySource.CapturedContentResize,
                ),
                generation = 1L,
            )

            callbackTask.run()

            assertEquals(0, callbackCount.get())
            val state = session.state.value as ScreenCaptureSessionState.Running
            assertEquals(true, state.output is ScreenCaptureOutputState.Suspended)
            val stats = session.stats.value
            assertEquals(1L, stats.framesEncoded)
            assertEquals(1L, stats.framesPublished)
            assertEquals(1L, stats.droppedDeliveries.total)
            assertEquals(1L, stats.droppedDeliveries.byStaleSession)
        } finally {
            session.stop()
        }
    }

    private fun sessionCore(
        frameCallbackDispatcher: CoroutineDispatcher? = null,
        parameterUpdater: suspend (ScreenCaptureParameters, ScreenCaptureParameterCommitGate) -> ScreenCaptureParameterUpdateResult = { _, commitGate ->
            commitGate.commit { ScreenCaptureParameterUpdateResult.Applied }
        },
    ): ScreenCaptureSessionCore {
        var nowNanos = 1_000_000_000L
        return ScreenCaptureSessionCore(
            config = ScreenCaptureConfig(metricsProvider = TestMetricsProvider(), frameCallbackDispatcher = frameCallbackDispatcher),
            initialState = ScreenCaptureSessionState.Running(
                output = ScreenCaptureOutputState.Active(effectiveParameters()),
                capturedContentVisible = null,
            ),
            parameterUpdater = parameterUpdater,
        ) {
            nowNanos += 1_000_000L
            nowNanos
        }
    }

    private class TestMetricsProvider : CaptureMetricsProvider {
        override val metrics: StateFlow<CaptureMetrics> = MutableStateFlow(
            CaptureMetrics(widthPx = 640, heightPx = 480, densityDpi = 320),
        )
    }

    private fun effectiveParameters(): ScreenCaptureEffectiveParameters =
        ScreenCaptureEffectiveParameters(
            captureGeometry = CaptureGeometry(
                widthPx = 640,
                heightPx = 480,
                densityDpi = 320,
                source = CaptureGeometrySource.MetricsProvider,
            ),
            captureTarget = CaptureTarget(
                width = 640,
                height = 480,
                scaleFromLogicalCapture = 1.0,
                isEarlyDownscaled = false,
            ),
            sourceRegion = SourceRegion.Full,
            crop = CropInsetsPx.Zero,
            appliedSourceRect = ImageRect(left = 0, top = 0, right = 640, bottom = 480),
            orientedContentSize = Size(width = 640, height = 480),
            outputSize = OutputSize.TargetSize(width = 640, height = 480, contentMode = ContentMode.Stretch),
            finalImageSize = Size(width = 640, height = 480),
            rotation = Rotation.Degrees0,
            mirror = Mirror.None,
            colorMode = ColorMode.Original,
            readbackMode = ReadbackMode.Es2,
            encoderInfo = ImageEncoderInfo(
                providerId = "test",
                outputFormat = EncodedImageFormats.Jpeg,
                backendName = "test",
            ),
            frameRate = FrameRate.MaxFps(30),
        )

    private fun outputPlanInvalidProblem(): ScreenCaptureProblem =
        ScreenCaptureProblem(
            sequence = 1L,
            kind = ScreenCaptureProblemKind.OutputPlanInvalid,
            message = "Test problem.",
            cause = null,
        )

    private fun <T : Any> BlockingQueue<T>.assertNoValue(description: String) {
        val value = poll(NO_DELIVERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        assertEquals("$description was observed", null, value)
    }

    private fun <T : Any> BlockingQueue<T>.awaitValue(description: String): T =
        poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: throw AssertionError("$description was not observed")

    private class QueuingDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.put(block)
        }

        fun awaitDispatched(description: String): Runnable =
            tasks.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: throw AssertionError("$description was not observed")
    }

    private companion object {
        const val NO_DELIVERY_TIMEOUT_MILLIS: Long = 100L
        const val TIMEOUT_MILLIS: Long = 2_000L
    }
}
