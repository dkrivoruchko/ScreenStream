package io.screenstream.mjpeg

import com.elvishew.xlog.XLog
import io.screenstream.capture.ColorMode
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.FrameRate
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.Mirror
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureConfig
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.capture.ScreenCaptureStopReason
import io.screenstream.capture.SourceRegion
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.MjpegCaptureSession.State.SuspensionProblem
import io.screenstream.mjpeg.MjpegEngineState.Phase
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.core.capture.ScreenCaptureAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Adapter policy through lawful Engine observations, not graphics or physical projection evidence.
 * These decisions have no SDK branch; SDK 34 represents the supported 24–37 policy equivalence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegCaptureSessionStatesTest {
    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CAP-05
    @Test
    fun initialParametersMapBothSelectionModesAndPreserveInactiveSavedChoices() = runTest {
        val variants = listOf(
            ImageSettings(
                sourceRegion = SourceRegion.LeftHalf,
                cropEnabled = false,
                cropInsets = CropInsetsPx(2, 3, 5, 7),
                outputSelection = ImageSettings.OutputSelection.Scale,
                scalePercent = 37.5,
                targetSize = OutputSize.TargetSize(901, 607, OutputSize.ContentMode.Stretch),
                rotation = Rotation.Degrees90,
                mirror = Mirror.Horizontal,
                colorMode = ColorMode.Grayscale,
                frameRate = FrameRate.MaxFps(17),
                jpegQuality = 63,
                jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly,
            ) to ScreenCaptureParameters(
                sourceRegion = SourceRegion.LeftHalf,
                crop = CropInsetsPx.ZERO,
                outputSize = OutputSize.ScaleFactor(0.375),
                rotation = Rotation.Degrees90,
                mirror = Mirror.Horizontal,
                colorMode = ColorMode.Grayscale,
                frameRate = FrameRate.MaxFps(17),
                jpegQuality = 63,
            ),
            ImageSettings(
                sourceRegion = SourceRegion.RightHalf,
                cropEnabled = true,
                cropInsets = CropInsetsPx(11, 13, 17, 19),
                outputSelection = ImageSettings.OutputSelection.TargetSize,
                scalePercent = 82.0,
                targetSize = OutputSize.TargetSize(733, 419, OutputSize.ContentMode.AspectFit),
                rotation = Rotation.Degrees270,
                mirror = Mirror.Vertical,
                colorMode = ColorMode.Color,
                frameRate = FrameRate.SamplingInterval(2.seconds),
                jpegQuality = 91,
                jpegBackendPolicy = JpegBackendPolicy.Auto,
            ) to ScreenCaptureParameters(
                sourceRegion = SourceRegion.RightHalf,
                crop = CropInsetsPx(11, 13, 17, 19),
                outputSize = OutputSize.TargetSize(733, 419, OutputSize.ContentMode.AspectFit),
                rotation = Rotation.Degrees270,
                mirror = Mirror.Vertical,
                colorMode = ColorMode.Color,
                frameRate = FrameRate.SamplingInterval(2.seconds),
                jpegQuality = 91,
            ),
        )

        for ((settings, expected) in variants) {
            val inputs = MjpegCaptureSessionInputs()
            val engine = ControlledEngine()
            val session = inputs.session(backgroundScope, engine.factory(inputs))
            try {
                session.runCapture(settings)
                runCurrent()

                assertCapture(session, Status.Starting, streaming = false)
                assertTrue(engine.startEntered.isCompleted)
                assertEquals(expected, engine.initialParameters)
                assertEquals(expected, engine.acceptedParameters)
                assertEquals(settings.jpegBackendPolicy, engine.config?.jpegBackendPolicy)
                assertNull(engine.config?.captureMetricsSource)
            } finally {
                finishCapture(session, engine)
            }
        }
    }

    // Cases: MJPEG-CAP-05, MJPEG-CAP-06
    @Test
    fun latestDesireSurvivesConsentAndForegroundWhileBackendStaysFixed() = runTest {
        val grant = object : ScreenCaptureAccess.Grant {}
        val consent = CompletableDeferred<ScreenCaptureAccess.Grant?>()
        val promotionEntered = CompletableDeferred<Unit>()
        val promotion = CompletableDeferred<Unit>()
        val inputs = MjpegCaptureSessionInputs(
            grant = grant,
            consent = { consent.await() },
            promotion = {
                promotionEntered.complete(Unit)
                promotion.await()
            },
        )
        val engine = ControlledEngine()
        val session = inputs.session(backgroundScope, engine.factory(inputs))
        val latest = ImageSettings(
            sourceRegion = SourceRegion.RightHalf,
            cropEnabled = true,
            cropInsets = CropInsetsPx(4, 6, 8, 10),
            outputSelection = ImageSettings.OutputSelection.TargetSize,
            scalePercent = 21.0,
            targetSize = OutputSize.TargetSize(811, 457, OutputSize.ContentMode.Stretch),
            rotation = Rotation.Degrees180,
            mirror = Mirror.Vertical,
            colorMode = ColorMode.Grayscale,
            frameRate = FrameRate.MaxFps(29),
            jpegQuality = 73,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
        )
        val expected = ScreenCaptureParameters(
            sourceRegion = SourceRegion.RightHalf,
            crop = CropInsetsPx(4, 6, 8, 10),
            outputSize = OutputSize.TargetSize(811, 457, OutputSize.ContentMode.Stretch),
            rotation = Rotation.Degrees180,
            mirror = Mirror.Vertical,
            colorMode = ColorMode.Grayscale,
            frameRate = FrameRate.MaxFps(29),
            jpegQuality = 73,
        )
        try {
            session.runCapture(ImageSettings(jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly))
            runCurrent()
            assertCapture(session, Status.WaitingForPermission, streaming = false)

            session.updateImageSettings(ImageSettings(jpegQuality = 21))
            session.updateImageSettings(ImageSettings(jpegQuality = 39))
            consent.complete(grant)
            runCurrent()
            assertTrue(promotionEntered.isCompleted)
            assertFalse(engine.startEntered.isCompleted)

            session.updateImageSettings(latest.copy(jpegQuality = 57))
            session.updateImageSettings(latest)
            promotion.complete(Unit)
            runCurrent()

            assertCapture(session, Status.Starting, streaming = false)
            assertEquals(expected, engine.initialParameters)
            assertEquals(JpegBackendPolicy.FrameworkOnly, engine.config?.jpegBackendPolicy)
            engine.publish(MjpegEngineState(Phase.Active))
            engine.completeStart()
            runCurrent()
            assertCapture(session, Status.Active, streaming = true)

            session.updateImageSettings(latest.copy(jpegQuality = 87))
            runCurrent()
            assertEquals(expected.copy(jpegQuality = 87), engine.acceptedParameters)
            assertEquals(JpegBackendPolicy.FrameworkOnly, engine.config?.jpegBackendPolicy)
        } finally {
            consent.complete(grant)
            promotion.complete(Unit)
            finishCapture(session, engine)
        }
    }

    // Cases: MJPEG-CAP-07
    @Test
    fun availabilityCanChangeBeforeStartupSettlesAndSuccessNeedsNoJpeg() = runTest {
        val inputs = MjpegCaptureSessionInputs()
        val engine = ControlledEngine()
        val session = inputs.session(backgroundScope, engine.factory(inputs))
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            assertTrue(engine.startEntered.isCompleted)
            assertCapture(session, Status.Starting, streaming = false)

            engine.publish(MjpegEngineState(Phase.Starting))
            runCurrent()
            assertCapture(session, Status.Starting, streaming = false)
            engine.publish(MjpegEngineState(Phase.Active))
            runCurrent()
            assertCapture(session, Status.Active, streaming = false)

            engine.publish(MjpegEngineState(Phase.Reconfiguring))
            runCurrent()
            assertCapture(session, Status.Reconfiguring, streaming = false)
            engine.publish(MjpegEngineState(Phase.Suspended, problem = ScreenCaptureProblem.CaptureUnavailable))
            runCurrent()
            assertCapture(session, Status.Suspended, streaming = false, suspension = SuspensionProblem.CaptureUnavailable)

            engine.publish(MjpegEngineState(Phase.Active))
            runCurrent()
            assertCapture(session, Status.Active, streaming = false)
            engine.completeStart()
            runCurrent()
            assertCapture(session, Status.Active, streaming = true)
            assertNull(session.state.value.captureFailure)
            // The shared input callback rejects any JPEG; this success history delivers none.
        } finally {
            finishCapture(session, engine)
        }
    }

    // Cases: MJPEG-CAP-07
    @Test
    fun confirmedCaptureStaysStreamingThroughReconfigurationAndEachRecoverableProblem() = runTest {
        val inputs = MjpegCaptureSessionInputs()
        val engine = ControlledEngine()
        val session = inputs.session(backgroundScope, engine.factory(inputs))
        val problems = listOf(
            ScreenCaptureProblem.InvalidRequest to SuspensionProblem.InvalidRequest,
            ScreenCaptureProblem.CaptureUnavailable to SuspensionProblem.CaptureUnavailable,
            ScreenCaptureProblem.ResourceExhausted to SuspensionProblem.ResourceExhausted,
        )
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            engine.publish(MjpegEngineState(Phase.Active))
            engine.completeStart()
            runCurrent()
            assertCapture(session, Status.Active, streaming = true)

            for ((engineProblem, adapterProblem) in problems) {
                engine.publish(MjpegEngineState(Phase.Reconfiguring))
                runCurrent()
                assertCapture(session, Status.Reconfiguring, streaming = true)
                engine.publish(MjpegEngineState(Phase.Suspended, problem = engineProblem))
                runCurrent()
                assertCapture(session, Status.Suspended, streaming = true, suspension = adapterProblem)
                assertNull(session.state.value.captureFailure)

                engine.publish(MjpegEngineState(Phase.Active))
                runCurrent()
                assertCapture(session, Status.Active, streaming = true)
            }
        } finally {
            finishCapture(session, engine)
        }
    }

    // Cases: MJPEG-CAP-05, MJPEG-CAP-06, MJPEG-CAP-07
    @Test
    fun latestParametersWaitForRunningAndRemainEditableWhileReconfiguringOrSuspended() = runTest {
        val inputs = MjpegCaptureSessionInputs()
        val engine = ControlledEngine()
        val session = inputs.session(backgroundScope, engine.factory(inputs))
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            assertCapture(session, Status.Starting, streaming = false)
            assertEquals(ScreenCaptureParameters.DEFAULT, engine.acceptedParameters)

            session.updateImageSettings(ImageSettings(jpegQuality = 11))
            session.updateImageSettings(ImageSettings(jpegQuality = 27))
            runCurrent()
            assertEquals(ScreenCaptureParameters.DEFAULT, engine.acceptedParameters)
            engine.publish(MjpegEngineState(Phase.Starting))
            runCurrent()
            session.updateImageSettings(ImageSettings(jpegQuality = 43))
            runCurrent()
            assertEquals(ScreenCaptureParameters.DEFAULT, engine.acceptedParameters)

            engine.publish(MjpegEngineState(Phase.Active))
            runCurrent()
            assertEquals(ScreenCaptureParameters(jpegQuality = 43), engine.acceptedParameters)
            engine.completeStart()
            runCurrent()

            engine.publish(MjpegEngineState(Phase.Reconfiguring))
            runCurrent()
            session.updateImageSettings(
                ImageSettings(
                    sourceRegion = SourceRegion.LeftHalf,
                    cropEnabled = true,
                    cropInsets = CropInsetsPx(3, 5, 7, 9),
                    scalePercent = 62.5,
                    rotation = Rotation.Degrees90,
                    mirror = Mirror.Horizontal,
                    colorMode = ColorMode.Grayscale,
                    frameRate = FrameRate.MaxFps(23),
                    jpegQuality = 59,
                ),
            )
            runCurrent()
            val reconfiguredParameters = ScreenCaptureParameters(
                sourceRegion = SourceRegion.LeftHalf,
                crop = CropInsetsPx(3, 5, 7, 9),
                outputSize = OutputSize.ScaleFactor(0.625),
                rotation = Rotation.Degrees90,
                mirror = Mirror.Horizontal,
                colorMode = ColorMode.Grayscale,
                frameRate = FrameRate.MaxFps(23),
                jpegQuality = 59,
            )
            assertEquals(reconfiguredParameters, engine.acceptedParameters)
            assertCapture(session, Status.Reconfiguring, streaming = true)

            engine.publish(MjpegEngineState(Phase.Suspended, problem = ScreenCaptureProblem.InvalidRequest))
            runCurrent()
            val suspendedSettings = ImageSettings(
                sourceRegion = SourceRegion.RightHalf,
                cropEnabled = false,
                cropInsets = CropInsetsPx(10, 20, 30, 40),
                outputSelection = ImageSettings.OutputSelection.TargetSize,
                targetSize = OutputSize.TargetSize(641, 359, OutputSize.ContentMode.Stretch),
                rotation = Rotation.Degrees270,
                mirror = Mirror.Vertical,
                colorMode = ColorMode.Color,
                frameRate = FrameRate.SamplingInterval(3.seconds),
                jpegQuality = 97,
            )
            session.updateImageSettings(suspendedSettings)
            runCurrent()
            val suspendedParameters = ScreenCaptureParameters(
                sourceRegion = SourceRegion.RightHalf,
                crop = CropInsetsPx.ZERO,
                outputSize = OutputSize.TargetSize(641, 359, OutputSize.ContentMode.Stretch),
                rotation = Rotation.Degrees270,
                mirror = Mirror.Vertical,
                colorMode = ColorMode.Color,
                frameRate = FrameRate.SamplingInterval(3.seconds),
                jpegQuality = 97,
            )
            assertEquals(suspendedParameters, engine.acceptedParameters)
            val expectedSubmissions = listOf(
                ScreenCaptureParameters(jpegQuality = 43),
                reconfiguredParameters,
                suspendedParameters,
            )
            assertEquals(expectedSubmissions, engine.submittedParameters)
            assertCapture(session, Status.Suspended, streaming = true, suspension = SuspensionProblem.InvalidRequest)

            // Equal desires are not an Engine reevaluation request, even while capture is suspended.
            session.updateImageSettings(suspendedSettings)
            runCurrent()
            assertEquals(expectedSubmissions, engine.submittedParameters)
            session.updateImageSettings(suspendedSettings.copy(jpegBackendPolicy = JpegBackendPolicy.FrameworkOnly))
            runCurrent()
            assertEquals(expectedSubmissions, engine.submittedParameters)
            assertEquals(JpegBackendPolicy.Auto, engine.config?.jpegBackendPolicy)
            assertCapture(session, Status.Suspended, streaming = true, suspension = SuspensionProblem.InvalidRequest)
        } finally {
            finishCapture(session, engine)
        }
    }

    // Cases: MJPEG-CAP-06, MJPEG-CAP-07
    @Test
    fun updateAdmissionCanCloseBeforeTheRunningObservationChanges() = runTest {
        val inputs = MjpegCaptureSessionInputs()
        val engine = ControlledEngine(holdStop = true)
        val session = inputs.session(backgroundScope, engine.factory(inputs))
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            engine.publish(MjpegEngineState(Phase.Active))
            engine.completeStart()
            runCurrent()

            engine.closeAdmission()
            session.updateImageSettings(ImageSettings(jpegQuality = 31))
            runCurrent()
            assertTrue(engine.rejectedUpdate.isCompleted)
            assertEquals(ScreenCaptureParameters(jpegQuality = 31), engine.rejectedUpdate.await())
            assertEquals(ScreenCaptureParameters.DEFAULT, engine.acceptedParameters)
            assertCapture(session, Status.Active, streaming = true)
            assertNull(session.state.value.captureFailure)

            engine.publish(MjpegEngineState(Phase.Stopped, stopReason = ScreenCaptureStopReason.Requested))
            runCurrent()
            assertCapture(session, Status.Stopping, streaming = false)
            assertTrue(engine.stopEntered.isCompleted)
            engine.releaseStop()
            runCurrent()
            assertTrue(withTimeout(5.seconds) { session.awaitCleanup() })
            assertCapture(session, Status.Stopped, streaming = false)
            assertNull(session.state.value.captureFailure)
            assertNull(session.state.value.cleanupFailure)
        } finally {
            finishCapture(session, engine)
        }
    }

    // Cases: MJPEG-CAP-02, MJPEG-CAP-06, MJPEG-CAP-07
    @Test
    fun acceptedStopCancelsPendingOrConfirmedStartupAndIgnoresLaterDesires() = runTest {
        for (settleStart in listOf(false, true)) {
            val inputs = MjpegCaptureSessionInputs()
            val engine = ControlledEngine(holdStop = true)
            val session = inputs.session(backgroundScope, engine.factory(inputs))
            try {
                session.runCapture(ImageSettings())
                runCurrent()
                engine.publish(MjpegEngineState(Phase.Active))
                if (settleStart) engine.completeStart()
                runCurrent()
                assertCapture(session, Status.Active, streaming = settleStart)

                session.stop(MjpegCaptureSession.StopReason.User)
                session.updateImageSettings(ImageSettings(jpegQuality = 19))
                runCurrent()
                assertTrue(engine.stopEntered.isCompleted)
                assertEquals(ScreenCaptureParameters.DEFAULT, engine.acceptedParameters)
                assertCapture(session, Status.Stopping, streaming = false)
                assertNull(session.state.value.captureFailure)
                assertNull(session.state.value.cleanupSucceeded)

                engine.releaseStop()
                runCurrent()
                assertTrue(withTimeout(5.seconds) { session.awaitCleanup() })
                assertCapture(session, Status.Stopped, streaming = false)
                session.updateImageSettings(ImageSettings(jpegQuality = 7))
                runCurrent()
                assertEquals(ScreenCaptureParameters.DEFAULT, engine.acceptedParameters)
                assertCapture(session, Status.Stopped, streaming = false)
            } finally {
                finishCapture(session, engine)
            }
        }
    }

    // Cases: MJPEG-CAP-04
    @Test
    fun terminalAdmissionBeforeRegistrationRetainsFailureAndSettlesOwnedCleanupWithoutStarting() = runTest {
        val engine = ControlledEngine()
        val terminal = MjpegEngineState(Phase.Stopped, stopReason = ScreenCaptureStopReason.Requested)
        var handoffFinished = false
        val inputs = MjpegCaptureSessionInputs(guardRelease = {
            if (!handoffFinished) {
                handoffFinished = true
                // A public owner Stop may close registration after creation transfers projection ownership.
                engine.publish(terminal)
            }
        })
        val session = inputs.session(backgroundScope, engine.factory(inputs))
        try {
            session.runCapture(ImageSettings())
            runCurrent()

            assertTrue(withTimeout(5.seconds) { session.awaitCleanup() })
            assertCapture(session, Status.Failed, streaming = false)
            assertEquals("Engine.registration", session.state.value.captureFailure?.stage)
            assertTrue(session.state.value.captureFailure?.cause is IllegalStateException)
            assertFalse(engine.startEntered.isCompleted)
            assertNull(engine.initialParameters)
            assertTrue(engine.stopEntered.isCompleted)
            assertEquals(terminal, engine.currentState)
            assertNull(session.state.value.cleanupFailure)
            assertEquals(listOf(inputs.grant), inputs.projections)
            assertEquals(listOf("enterForeground", "leaveForeground"), inputs.foregroundEvents)
            assertEquals(listOf(session.id, session.id), inputs.finished)
            assertTrue(inputs.saved.isEmpty())
        } finally {
            finishCapture(session, engine)
        }
    }

    // Cases: MJPEG-CAP-04, MJPEG-CAP-09
    @Test
    fun startupThrowableRemainsCaptureFailureAfterSuccessfulCleanup() = runTest {
        val startFailure = IllegalArgumentException("Startup boundary failed")
        val inputs = MjpegCaptureSessionInputs()
        val engine = ControlledEngine(holdStop = true, startFailure = startFailure)
        val session = inputs.session(backgroundScope, engine.factory(inputs))
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            assertTrue(engine.startEntered.isCompleted)
            assertTrue(engine.stopEntered.isCompleted)
            assertCapture(session, Status.Failed, streaming = false)
            val retainedFailure = checkNotNull(session.state.value.captureFailure)
            assertEquals("Engine.start", retainedFailure.stage)
            // Coroutine stack recovery may copy the throwable while preserving its original cause.
            assertTrue(
                "Original startup failure must remain in the cause chain",
                generateSequence(retainedFailure.cause) { it.cause }.any { it === startFailure },
            )
            assertNull(session.state.value.cleanupSucceeded)

            engine.releaseStop()
            runCurrent()
            assertTrue(withTimeout(5.seconds) { session.awaitCleanup() })
            assertSame(retainedFailure, session.state.value.captureFailure)
            assertNull(session.state.value.cleanupFailure)
            assertCapture(session, Status.Failed, streaming = false)
        } finally {
            finishCapture(session, engine)
        }
    }

    // Cases: MJPEG-CAP-02, MJPEG-CAP-07
    @Test
    fun normalEngineStopEndsPendingOrConfirmedStartupWithoutCaptureFailure() = runTest {
        for (settleStart in listOf(false, true)) {
            for (reason in ScreenCaptureStopReason.entries) {
                val inputs = MjpegCaptureSessionInputs()
                val engine = ControlledEngine(holdStop = true)
                val session = inputs.session(backgroundScope, engine.factory(inputs))
                try {
                    session.runCapture(ImageSettings())
                    runCurrent()
                    engine.publish(MjpegEngineState(Phase.Active))
                    if (settleStart) engine.completeStart()
                    runCurrent()
                    assertCapture(session, Status.Active, streaming = settleStart)

                    engine.publish(MjpegEngineState(Phase.Stopped, stopReason = reason))
                    runCurrent()
                    assertTrue(engine.stopEntered.isCompleted)
                    assertCapture(session, Status.Stopping, streaming = false)
                    assertNull(session.state.value.captureFailure)
                    assertNull(session.state.value.cleanupSucceeded)

                    engine.releaseStop()
                    runCurrent()
                    assertTrue(withTimeout(5.seconds) { session.awaitCleanup() })
                    assertCapture(session, Status.Stopped, streaming = false)
                    assertNull(session.state.value.captureFailure)
                    assertNull(session.state.value.cleanupFailure)
                } finally {
                    finishCapture(session, engine)
                }
            }
        }
    }

    // Cases: MJPEG-CAP-07, MJPEG-CAP-09
    @Test
    fun engineFailureEndsPendingOrConfirmedStartupAndOutranksSuccessfulCleanup() = runTest {
        for (settleStart in listOf(false, true)) {
            val inputs = MjpegCaptureSessionInputs()
            val engine = ControlledEngine(holdStop = true)
            val session = inputs.session(backgroundScope, engine.factory(inputs))
            try {
                session.runCapture(ImageSettings())
                runCurrent()
                engine.publish(MjpegEngineState(Phase.Active))
                if (settleStart) engine.completeStart()
                runCurrent()

                engine.publish(MjpegEngineState(Phase.Failed, problem = ScreenCaptureProblem.InternalFailure))
                runCurrent()
                assertTrue(engine.stopEntered.isCompleted)
                assertCapture(session, Status.Failed, streaming = false)
                val retainedFailure = checkNotNull(session.state.value.captureFailure)
                assertEquals("Engine.state", retainedFailure.stage)
                assertTrue(retainedFailure.cause is IllegalStateException)
                assertNull(session.state.value.cleanupSucceeded)

                engine.releaseStop()
                runCurrent()
                assertTrue(withTimeout(5.seconds) { session.awaitCleanup() })
                assertSame(retainedFailure, session.state.value.captureFailure)
                assertNull(session.state.value.cleanupFailure)
                assertCapture(session, Status.Failed, streaming = false)
            } finally {
                finishCapture(session, engine)
            }
        }
    }

    private fun assertCapture(
        session: MjpegCaptureSession,
        status: Status,
        streaming: Boolean,
        suspension: SuspensionProblem? = null,
    ) {
        val snapshot = session.state.value
        if (status != Status.Failed) {
            assertNull("Capture failed while expecting $status: ${snapshot.captureFailure}", snapshot.captureFailure)
            assertNull("Cleanup failed while expecting $status: ${snapshot.cleanupFailure}", snapshot.cleanupFailure)
        }
        assertEquals(status, snapshot.status)
        assertEquals(streaming, snapshot.isStreaming)
        assertEquals(suspension, snapshot.suspensionProblem)
    }

    private suspend fun TestScope.finishCapture(session: MjpegCaptureSession, engine: ControlledEngine) {
        // Release all test gates before waiting for NonCancellable adapter cleanup or leaving the scope.
        session.stop(MjpegCaptureSession.StopReason.Shutdown)
        engine.releaseAll()
        runCurrent()
        withTimeout(5.seconds) { session.awaitCleanup() }
    }

    private class ControlledEngine(
        holdStop: Boolean = false,
        private val startFailure: Throwable? = null,
    ) : MjpegEngineSession {
        private val decision = MutableStateFlow(MjpegEngineState(Phase.NotStarted))
        private val startup = CompletableDeferred<Unit>()
        private val mediaStop = CompletableDeferred<Unit>().apply { if (!holdStop) complete(Unit) }
        private var updateAdmissionOpen = false
        private var admissionClosed = false

        val startEntered: CompletableDeferred<Unit> = CompletableDeferred()
        val stopEntered: CompletableDeferred<Unit> = CompletableDeferred()
        val rejectedUpdate: CompletableDeferred<ScreenCaptureParameters> = CompletableDeferred()
        var initialParameters: ScreenCaptureParameters? = null
            private set
        var acceptedParameters: ScreenCaptureParameters? = null
            private set
        val submittedParameters: MutableList<ScreenCaptureParameters> = mutableListOf()
        var config: ScreenCaptureConfig? = null
            private set

        override val currentState: MjpegEngineState
            get() = decision.value
        override val states: Flow<MjpegEngineState> = decision

        // No statistics assertion needs a privately constructed Engine snapshot. Keep this Flow open and inert.
        override val stats: Flow<ScreenCaptureStats> = flow { awaitCancellation() }

        fun factory(inputs: MjpegCaptureSessionInputs): MjpegEngineFactory = MjpegEngineFactory { context, projection, config ->
            assertSame(inputs.context, context)
            assertSame(inputs.projection, projection)
            this.config = config
            this
        }

        override suspend fun start(parameters: ScreenCaptureParameters) {
            initialParameters = parameters
            acceptedParameters = parameters
            startEntered.complete(Unit)
            startFailure?.let { throw it }
            try {
                startup.await()
            } catch (cause: CancellationException) {
                closeAdmission()
                throw cause
            }
            check(decision.value.phase == Phase.Active) { "Successful startup requires a usable Active observation" }
        }

        override fun updateParameters(parameters: ScreenCaptureParameters) {
            if (!updateAdmissionOpen) {
                rejectedUpdate.complete(parameters)
                throw IllegalStateException("Parameter admission is closed")
            }
            submittedParameters += parameters
            acceptedParameters = parameters
        }

        override fun registerFrameConsumer(consumer: (MjpegBorrowedJpeg) -> Unit): MjpegFrameRegistration {
            check(decision.value.phase != Phase.Stopped && decision.value.phase != Phase.Failed) {
                "Terminal capture cannot register a frame consumer"
            }
            return MjpegFrameRegistration {}
        }

        override suspend fun stop() {
            closeAdmission()
            stopEntered.complete(Unit)
            mediaStop.await()
            if (decision.value.phase != Phase.Stopped && decision.value.phase != Phase.Failed) {
                decision.value = MjpegEngineState(Phase.Stopped, stopReason = ScreenCaptureStopReason.Requested)
            }
        }

        fun publish(snapshot: MjpegEngineState) {
            check(decision.value.phase != Phase.Stopped && decision.value.phase != Phase.Failed) { "Terminal observations are frozen" }
            when (snapshot.phase) {
                Phase.Active -> if (!admissionClosed) updateAdmissionOpen = true
                Phase.Stopped, Phase.Failed -> closeAdmission()
                else -> Unit
            }
            decision.value = snapshot
        }

        fun closeAdmission() {
            admissionClosed = true
            updateAdmissionOpen = false
        }

        fun completeStart() {
            startup.complete(Unit)
        }

        fun releaseStop() {
            mediaStop.complete(Unit)
        }

        fun releaseAll() {
            startup.complete(Unit)
            mediaStop.complete(Unit)
        }
    }
}
