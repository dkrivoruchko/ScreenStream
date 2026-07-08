package dev.dmkr.screencaptureengine.internal.session.core

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class ScreenCaptureSessionCoreTest {
    @Test
    fun beginProductionAttempt_staleBeforeMaterializationReturnsNullWithoutDrop() {
        val session = sessionCore()
        session.updateOutputActive(effectiveParameters(), generation = 1L)

        val attempt = session.beginProductionAttempt(generation = 0L)

        assertNull(attempt)
        val stats = session.stats.value
        assertEquals(0L, stats.framesEncoded)
        assertEquals(0L, stats.framesPublished)
        assertEquals(0L, stats.droppedFrames.total)
        assertEquals(0.0, stats.averageEncodeMs, 0.0)
        assertEquals(0.0, stats.averageReadbackMs, 0.0)
    }

    @Test
    fun productionAttempt_successPublishesAndRecordsReadbackAndEncodeAveragesOnce() {
        val session = sessionCore()
        val attempt = session.beginProductionAttempt(generation = 0L) ?: throw AssertionError("production attempt was not materialized")

        attempt.recordReadbackSuccess(durationNanos = 2_000_000L)
        attempt.recordReadbackSuccess(durationNanos = 8_000_000L)
        val published = attempt.completeEncodedSuccess(
            format = EncodedImageFormats.Jpeg,
            bytes = byteArrayOf(1, 2, 3, 4),
            encodeDurationNanos = 3_000_000L,
        )
        val completedAgain = attempt.completeEncodeFailedDrop()

        assertEquals(true, published)
        assertEquals(false, completedAgain)
        val stats = session.stats.value
        assertEquals(1L, stats.framesEncoded)
        assertEquals(1L, stats.framesPublished)
        assertEquals(0L, stats.droppedFrames.total)
        assertEquals(3.0, stats.averageEncodeMs, 0.0)
        assertEquals(2.0, stats.averageReadbackMs, 0.0)
        assertEquals(4, stats.lastEncodedByteCount)
        assertEquals(4, stats.averageEncodedByteCount)
    }

    @Test
    fun productionAttempt_successAfterGenerationChangeCountsEncodedAndStaleDropWithoutDelivery() {
        val callbackDispatcher = QueuingDispatcher()
        val session = sessionCore(frameCallbackDispatcher = callbackDispatcher)
        try {
            val deliveredSequences = LinkedBlockingQueue<Long>()
            session.onFrame { frame -> deliveredSequences.put(frame.sequence) }
            val attempt = session.beginProductionAttempt(generation = 0L) ?: throw AssertionError("production attempt was not materialized")
            attempt.recordReadbackSuccess(durationNanos = 4_000_000L)
            session.updateOutputActive(effectiveParameters(), generation = 1L)

            val published = attempt.completeEncodedSuccess(
                format = EncodedImageFormats.Jpeg,
                bytes = byteArrayOf(5, 6, 7, 8, 9),
                encodeDurationNanos = 6_000_000L,
            )

            assertFalse(published)
            deliveredSequences.assertNoValue("stale materialized attempt callback", callbackDispatcher)
            val stats = session.stats.value
            assertEquals(1L, stats.framesEncoded)
            assertEquals(0L, stats.framesPublished)
            assertEquals(1L, stats.droppedFrames.total)
            assertEquals(1L, stats.droppedFrames.byStaleGeneration)
            assertEquals(6.0, stats.averageEncodeMs, 0.0)
            assertEquals(4.0, stats.averageReadbackMs, 0.0)
            assertEquals(5, stats.lastEncodedByteCount)
            assertEquals(5, stats.averageEncodedByteCount)
        } finally {
            session.stop()
        }
    }

    @Test
    fun productionAttempt_encodedSizeLimitDropDoesNotCountEncodedOrEncodeAverage() {
        val session = sessionCore()
        val attempt = session.beginProductionAttempt(generation = 0L) ?: throw AssertionError("production attempt was not materialized")
        attempt.recordReadbackSuccess(durationNanos = 1_000_000L)

        val completed = attempt.completeEncodedSizeLimitDrop()

        assertEquals(true, completed)
        val stats = session.stats.value
        assertEquals(0L, stats.framesEncoded)
        assertEquals(0L, stats.framesPublished)
        assertEquals(1L, stats.droppedFrames.total)
        assertEquals(1L, stats.droppedFrames.byEncodedSizeLimit)
        assertEquals(0.0, stats.averageEncodeMs, 0.0)
        assertEquals(1.0, stats.averageReadbackMs, 0.0)
        assertEquals(0, stats.lastEncodedByteCount)
        assertEquals(0, stats.averageEncodedByteCount)
    }

    @Test
    fun productionAttempt_oversizeSuccessDoesNotCountEncodedStatsOrPublish() {
        val session = sessionCore(maxEncodedBytes = 1_024)
        val attempt = session.beginProductionAttempt(generation = 0L) ?: throw AssertionError("production attempt was not materialized")
        attempt.recordReadbackSuccess(durationNanos = 2_000_000L)

        val published = attempt.completeEncodedSuccess(
            format = EncodedImageFormats.Jpeg,
            bytes = ByteArray(1_025) { 7 },
            encodeDurationNanos = 4_000_000L,
        )

        assertFalse(published)
        val stats = session.stats.value
        assertEquals(0L, stats.framesEncoded)
        assertEquals(0L, stats.framesPublished)
        assertEquals(1L, stats.droppedFrames.total)
        assertEquals(1L, stats.droppedFrames.byEncodedSizeLimit)
        assertEquals(0.0, stats.averageEncodeMs, 0.0)
        assertEquals(2.0, stats.averageReadbackMs, 0.0)
        assertEquals(0, stats.lastEncodedByteCount)
        assertEquals(0, stats.averageEncodedByteCount)
    }

    @Test
    fun productionAttempt_providerFailureAndThrowDropTransientWithoutEncodedStats() {
        val session = sessionCore()
        val failedAttempt = session.beginProductionAttempt(generation = 0L) ?: throw AssertionError("failed attempt was not materialized")
        val threwAttempt = session.beginProductionAttempt(generation = 0L) ?: throw AssertionError("throw attempt was not materialized")

        assertEquals(true, failedAttempt.completeEncodeFailedDrop())
        assertEquals(true, threwAttempt.completeEncodeThrewDrop())

        val stats = session.stats.value
        assertEquals(0L, stats.framesEncoded)
        assertEquals(0L, stats.framesPublished)
        assertEquals(2L, stats.droppedFrames.total)
        assertEquals(2L, stats.droppedFrames.byTransientFailure)
        assertEquals(0.0, stats.averageEncodeMs, 0.0)
        assertEquals(0, stats.lastEncodedByteCount)
    }

    @Test
    fun productionAttempt_providerFailureAfterStopCountsStaleDrop() {
        val session = sessionCore()
        val attempt = session.beginProductionAttempt(generation = 0L) ?: throw AssertionError("production attempt was not materialized")

        session.stop()
        val completed = attempt.completeEncodeFailedDrop()

        assertEquals(true, completed)
        val stats = session.stats.value
        assertEquals(0L, stats.framesEncoded)
        assertEquals(0L, stats.framesPublished)
        assertEquals(1L, stats.droppedFrames.total)
        assertEquals(1L, stats.droppedFrames.byStaleGeneration)
        assertEquals(0L, stats.droppedFrames.byTransientFailure)
    }

    @Test
    fun currentUnmaterializedProductionFrameDropDoesNotAccountAfterTerminal() {
        val session = sessionCore()

        session.recordCurrentUnmaterializedProductionFrameDrop(ProductionFrameDropKind.FrameRatePolicy)
        session.stop()
        session.recordCurrentUnmaterializedProductionFrameDrop(ProductionFrameDropKind.FrameRatePolicy)

        val stats = session.stats.value
        assertEquals(1L, stats.droppedFrames.total)
        assertEquals(1L, stats.droppedFrames.byFrameRatePolicy)
        assertEquals(0L, stats.droppedFrames.byStaleGeneration)
    }

    @Test
    fun productionAttempt_successAfterStopCountsStaleDropWithoutPublishing() {
        val session = sessionCore()
        val attempt = session.beginProductionAttempt(generation = 0L) ?: throw AssertionError("production attempt was not materialized")

        session.stop()
        val published = attempt.completeEncodedSuccess(
            format = EncodedImageFormats.Jpeg,
            bytes = byteArrayOf(1, 2, 3),
            encodeDurationNanos = 5_000_000L,
        )

        assertFalse(published)
        val stats = session.stats.value
        assertEquals(1L, stats.framesEncoded)
        assertEquals(0L, stats.framesPublished)
        assertEquals(1L, stats.droppedFrames.total)
        assertEquals(1L, stats.droppedFrames.byStaleGeneration)
        assertEquals(5.0, stats.averageEncodeMs, 0.0)
    }

    @Test
    fun terminalCommitHandlerRunsOnceForWinningStopAndFailure() {
        val stoppedCommits = mutableListOf<ScreenCaptureSessionTerminalCommit>()
        val stoppedSession = sessionCore(terminalCommitHandler = stoppedCommits::add)

        stoppedSession.stop()
        stoppedSession.stop()

        assertEquals(1, stoppedCommits.size)
        val stoppedCommit = stoppedCommits.single() as ScreenCaptureSessionTerminalCommit.Stopped
        assertEquals(ScreenCaptureStopReason.OwnerStop, stoppedCommit.reason)
        assertSame(null, stoppedCommit.problem)

        val failedCommits = mutableListOf<ScreenCaptureSessionTerminalCommit>()
        val failedSession = sessionCore(terminalCommitHandler = failedCommits::add)
        val problem = failedSession.newProblem(ScreenCaptureProblemKind.GlResourceFailure, "GL failed.", null)

        failedSession.finishFailed(problem)
        failedSession.finishFailed(problem)

        assertEquals(1, failedCommits.size)
        val failedCommit = failedCommits.single() as ScreenCaptureSessionTerminalCommit.Failed
        assertSame(problem, failedCommit.problem)
    }

    @Test
    fun terminalCommitHandlerThrowDoesNotBlockCoordinatorCloseOrTerminalStatePublication() {
        val session = sessionCore(terminalCommitHandler = {
            throw IllegalStateException("terminal hook failed")
        })
        val subscription = session.onFrame { }

        session.stop()

        val state = session.state.value as ScreenCaptureSessionState.Stopped
        assertEquals(ScreenCaptureStopReason.OwnerStop, state.reason)
        assertEquals(0L, session.stats.value.activeFrameSubscriptions.toLong())
        subscription.cancel()
    }

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
    fun publishEncodedFrame_oversizeDoesNotCountEncodedStatsOrPublish() {
        val session = sessionCore(maxEncodedBytes = 1_024)
        val deliveredSequences = LinkedBlockingQueue<Long>()
        session.onFrame { frame -> deliveredSequences.put(frame.sequence) }

        val published = session.publishEncodedFrame(
            generation = 0L,
            format = EncodedImageFormats.Jpeg,
            bytes = ByteArray(1_025) { 9 },
        )

        assertFalse(published)
        deliveredSequences.assertNoValue("oversize frame callback")
        val stats = session.stats.value
        assertEquals(0L, stats.framesEncoded)
        assertEquals(0L, stats.framesPublished)
        assertEquals(1L, stats.droppedFrames.total)
        assertEquals(1L, stats.droppedFrames.byEncodedSizeLimit)
        assertEquals(0.0, stats.averageEncodeMs, 0.0)
        assertEquals(0, stats.lastEncodedByteCount)
        assertEquals(0, stats.averageEncodedByteCount)
        assertEquals(1, stats.activeFrameSubscriptions)
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
        terminalCommitHandler: (ScreenCaptureSessionTerminalCommit) -> Unit = {},
        maxEncodedBytes: Int = DEFAULT_TEST_MAX_ENCODED_BYTES,
        parameterUpdater: suspend (ScreenCaptureParameters, ScreenCaptureParameterCommitGate) -> ScreenCaptureParameterUpdateResult = { _, commitGate ->
            commitGate.commit { ScreenCaptureParameterUpdateResult.Applied }
        },
    ): ScreenCaptureSessionCore {
        var nowNanos = 1_000_000_000L
        return ScreenCaptureSessionCore(
            config = ScreenCaptureConfig(
                metricsProvider = TestMetricsProvider(),
                maxEncodedBytes = maxEncodedBytes,
                frameCallbackDispatcher = frameCallbackDispatcher,
            ),
            initialState = ScreenCaptureSessionState.Running(
                output = ScreenCaptureOutputState.Active(effectiveParameters()),
                capturedContentVisible = null,
            ),
            parameterUpdater = parameterUpdater,
            terminalCommitHandler = terminalCommitHandler,
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

    private fun <T : Any> BlockingQueue<T>.assertNoValue(description: String, callbackDispatcher: QueuingDispatcher? = null) {
        callbackDispatcher?.assertNoQueuedTasks(description)
        val value = if (callbackDispatcher == null) {
            poll(NO_DELIVERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } else {
            poll()
        }
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

        fun assertNoQueuedTasks(description: String) {
            assertEquals("$description queued a callback task", null, tasks.poll())
        }
    }

    private companion object {
        const val NO_DELIVERY_TIMEOUT_MILLIS: Long = 100L
        const val TIMEOUT_MILLIS: Long = 2_000L
        const val DEFAULT_TEST_MAX_ENCODED_BYTES: Int = 8 * 1024 * 1024
    }
}
