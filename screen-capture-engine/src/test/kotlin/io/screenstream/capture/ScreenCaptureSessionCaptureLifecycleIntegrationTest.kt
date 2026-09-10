package io.screenstream.capture

import android.hardware.display.VirtualDisplay
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.capture.CaptureApplyResult
import io.screenstream.capture.internal.capture.CaptureOpenResult
import io.screenstream.capture.internal.capture.CapturePhysicalException
import io.screenstream.capture.internal.capture.CapturePlan
import io.screenstream.capture.internal.capture.CaptureProjectionIdentity
import io.screenstream.capture.internal.capture.CaptureSourceIdentity
import io.screenstream.capture.internal.capture.CaptureTargetMode
import io.screenstream.capture.internal.capture.ProjectionStopCompletion
import io.screenstream.capture.internal.capture.SessionCaptureFactPort
import io.screenstream.capture.internal.capture.SessionCaptureOwner
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.CapturePlatformFixture
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.startActiveSession
import io.screenstream.capture.testutil.SessionHarness
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/*
 * Capture lifecycle evidence spans direct SessionCaptureOwner boundaries and full-session Coordinator composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionCaptureLifecycleIntegrationTest {
    private var savedNoDisplay: EGLDisplay? = null
    private var savedNoContext: EGLContext? = null
    private var savedNoSurface: EGLSurface? = null

    @Before
    fun installOpaqueEglSentinels() {
        savedNoDisplay = EGL14.EGL_NO_DISPLAY
        savedNoContext = EGL14.EGL_NO_CONTEXT
        savedNoSurface = EGL14.EGL_NO_SURFACE
        EGL14.EGL_NO_DISPLAY = mockk()
        EGL14.EGL_NO_CONTEXT = mockk()
        EGL14.EGL_NO_SURFACE = mockk()
    }

    @After
    fun restoreOpaqueEglSentinels() {
        EGL14.EGL_NO_DISPLAY = savedNoDisplay
        EGL14.EGL_NO_CONTEXT = savedNoContext
        EGL14.EGL_NO_SURFACE = savedNoSurface
    }

    // Verification: CAP-01
    @Test
    fun nullVirtualDisplayPreservesRootProblemOnlyWhenRetirementIsProvedClean() {
        val cases = listOf(
            NullDisplayCase(
                name = "clean retirement",
                projectionRetirementFailure = null,
                expectedProblem = ScreenCaptureProblem.CaptureUnavailable,
            ),
            NullDisplayCase(
                name = "unproved projection retirement",
                projectionRetirementFailure = IllegalStateException("Injected callback unregister failure"),
                expectedProblem = ScreenCaptureProblem.InternalFailure,
            ),
        )

        cases.forEach { case ->
            val platform = CapturePlatformFixture()
            every {
                platform.projectionPlatform.createVirtualDisplay(
                    refEq(platform.projection),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns null
            every {
                platform.projectionPlatform.unregisterCallback(refEq(platform.projection), any())
            } just Runs
            every { platform.projectionPlatform.stop(refEq(platform.projection)) } just Runs
            case.projectionRetirementFailure?.let { failure ->
                every {
                    platform.projectionPlatform.unregisterCallback(refEq(platform.projection), any())
                } throws failure
            }

            val captureHandler = Handler(Looper.getMainLooper())
            val controlHandler = Handler(Looper.getMainLooper())
            val captureThread = mockk<HandlerThread>()
            every { captureThread.quitSafely() } returns true
            val poster = SingleCaptureTaskPoster(captureHandler)
            val facts = RecordingCaptureFacts()
            val owner = SessionCaptureOwner(
                captureThread = captureThread,
                captureHandler = captureHandler,
                controlHandler = controlHandler,
                handlerTaskPoster = poster,
                factPort = facts,
                readbackClock = { 0L },
                platformSdkInt = Build.VERSION_CODES.TIRAMISU,
                projectionPlatform = platform.projectionPlatform,
                eglPlatform = platform.eglPlatform,
                glesPlatform = platform.glesPlatform,
                targetPlatform = platform.targetPlatform,
                projectionStopCompletion = ProjectionStopCompletion(),
            )

            owner.adoptProjection(platform.projection)
            check(owner.open(capturePlan()))
            check(poster.enterNext()) { "Capture Open did not enter" }
            val failed = facts.openResult as CaptureOpenResult.Failed

            assertSame(case.name, case.expectedProblem, failed.problem)
            assertTrue(case.name, failed.cause is CapturePhysicalException)
            assertEquals(case.name, "MediaProjection.createVirtualDisplay returned null", failed.cause.message)
            verify(exactly = 1) {
                platform.projectionPlatform.unregisterCallback(refEq(platform.projection), any())
                platform.projectionPlatform.stop(refEq(platform.projection))
                platform.eglPlatform.destroyContext(any(), any())
                platform.eglPlatform.destroySurface(any(), any())
                platform.eglPlatform.releaseThread()
            }
            verify(exactly = 0) {
                platform.projectionPlatform.setSurface(any(), any())
                platform.projectionPlatform.release(any())
            }

            owner.retire()
            check(poster.enterNext()) { "Capture retirement did not enter" }
            verify(exactly = 1) {
                platform.projectionPlatform.unregisterCallback(refEq(platform.projection), any())
                platform.projectionPlatform.stop(refEq(platform.projection))
            }
        }
    }

    // Verification: CAP-01
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun registeredProjectionDoubleStopRequestsOneDeferredCaptureRetirement() = runTest {
        val platform = CapturePlatformFixture()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            startActiveSession(harness, parameters)
            val active = harness.session.state.value as ScreenCaptureState.Active
            drainAcceptedSessionWork(harness)

            platform.deliverProjectionStopped()
            platform.deliverProjectionStopped()
            assertEquals(active, harness.session.state.value)

            check(harness.enterNextControlTask()) { "Current projection stop did not enter Coordinator Control" }
            val stopped = harness.session.state.value as ScreenCaptureState.Stopped
            assertSame(ScreenCaptureStopReason.ProjectionStopped, stopped.reason)
            assertEquals(parameters, stopped.requestedParameters)
            assertEquals(active.outputInfo, stopped.lastOutputInfo)

            verifyNoCaptureRetirementEffects(platform)
            check(harness.enterNextCaptureTask()) { "Projection stop did not request Capture retirement" }

            verify(exactly = 1) {
                platform.projectionPlatform.setSurface(any(), null)
                platform.projectionPlatform.release(any())
                platform.projectionPlatform.unregisterCallback(refEq(platform.projection), any())
                platform.projectionPlatform.stop(refEq(platform.projection))
                platform.targetPlatform.clearFrameListener(any())
                platform.targetPlatform.releaseSurface(any())
                platform.targetPlatform.releaseSurfaceTexture(any())
                platform.glesPlatform.deleteFramebuffers(any())
                platform.glesPlatform.deleteProgram(any())
            }
            verify(exactly = 2) { platform.glesPlatform.deleteShader(any()) }

            platform.verifySuccessfulRetirement()
            platform.deliverProjectionStopped()
            drainAcceptedSessionWork(harness)
            assertEquals(stopped, harness.session.state.value)
            verify(exactly = 1) {
                platform.projectionPlatform.unregisterCallback(refEq(platform.projection), any())
                platform.projectionPlatform.stop(refEq(platform.projection))
            }
        }
    }

    // Verification: SES-02
    // Verification: CAP-03
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun returnedRequestStopPrecedesExactCaptureRetirementIncludingReplacement() = runTest {
        listOf(false, true).forEach { replace ->
            val platform = CapturePlatformFixture()
            SessionHarness(
                bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
                metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
                platformSdkInt = Build.VERSION_CODES.TIRAMISU,
                projection = platform.projection,
                projectionPlatform = platform.projectionPlatform,
                eglPlatform = platform.eglPlatform,
                glesPlatform = platform.glesPlatform,
                targetPlatform = platform.targetPlatform,
            ).use { harness ->
                startActiveSession(harness, ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0)))
                if (replace) {
                    val updated = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(0.5))
                    harness.session.updateParameters(updated)
                    harness.driveUntil {
                        (harness.session.state.value as? ScreenCaptureState.Active)?.outputInfo?.parameters == updated
                    }
                }
                drainAcceptedSessionWork(harness)
                harness.session.requestStop()
                // Explicit entry arranges the interval; exact physical-resource receipts below are the oracle.
                verify(exactly = 0) {
                    platform.eglPlatform.destroyContext(any(), any())
                    platform.eglPlatform.releaseDisplayInitialization(any())
                    platform.projectionPlatform.release(any())
                }
                drainAcceptedSessionWork(harness)
                assertTrue(harness.session.state.value is ScreenCaptureState.Stopped)
                platform.verifySuccessfulRetirement()
                harness.session.requestStop()
                drainAcceptedSessionWork(harness)
                platform.verifySuccessfulRetirement()
            }
        }
    }

    // Verification: CAP-03
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun poisonedCaptureReleasesDisplayOnlyWhenExternalSurfaceTextureRetires() = runTest {
        listOf(false, true).forEach { retainSurfaceTexture ->
            val platform = CapturePlatformFixture()
            SessionHarness(
                bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
                metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
                platformSdkInt = Build.VERSION_CODES.TIRAMISU,
                projection = platform.projection,
                projectionPlatform = platform.projectionPlatform,
                eglPlatform = platform.eglPlatform,
                glesPlatform = platform.glesPlatform,
                targetPlatform = platform.targetPlatform,
            ).use { harness ->
                startActiveSession(harness, ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0)))
                drainAcceptedSessionWork(harness)
                val releaseFailure = IllegalStateException("SurfaceTexture retained")
                if (retainSurfaceTexture) platform.failInitialSurfaceTextureRelease(releaseFailure)
                every { platform.glesPlatform.getError() } returns android.opengl.GLES20.GL_INVALID_OPERATION
                platform.deliverSourceFrame(17)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }
                drainAcceptedSessionWork(harness)
                verify(exactly = 1) {
                    platform.targetPlatform.releaseSurfaceTexture(any())
                    platform.eglPlatform.destroyContext(any(), any())
                    platform.eglPlatform.destroySurface(any(), any())
                    platform.eglPlatform.releaseThread()
                }
                verify(exactly = if (retainSurfaceTexture) 0 else 1) { platform.eglPlatform.releaseDisplayInitialization(any()) }
                harness.session.requestStop()
                drainAcceptedSessionWork(harness)
                verify(exactly = 1) { platform.targetPlatform.releaseSurfaceTexture(any()) }
                verify(exactly = if (retainSurfaceTexture) 0 else 1) { platform.eglPlatform.releaseDisplayInitialization(any()) }
            }
        }
    }

    private fun verifyNoCaptureRetirementEffects(platform: CapturePlatformFixture) {
        verify(exactly = 0) {
            platform.projectionPlatform.setSurface(any<VirtualDisplay>(), null)
            platform.projectionPlatform.release(any())
            platform.projectionPlatform.unregisterCallback(refEq(platform.projection), any())
            platform.projectionPlatform.stop(refEq(platform.projection))
            platform.targetPlatform.clearFrameListener(any())
            platform.targetPlatform.releaseSurface(any<Surface>())
            platform.targetPlatform.releaseSurfaceTexture(any())
            platform.eglPlatform.destroyContext(any(), any())
            platform.eglPlatform.destroySurface(any(), any())
            platform.eglPlatform.releaseThread()
            platform.glesPlatform.deleteTextures(any())
            platform.glesPlatform.deleteFramebuffers(any())
            platform.glesPlatform.deleteShader(any())
            platform.glesPlatform.deleteProgram(any())
        }
    }

    private fun capturePlan(): CapturePlan = CapturePlan(
        appliedSourceRect = ImageRect.create(leftPx = 0, topPx = 0, rightPx = 8, bottomPx = 6),
        rotation = Rotation.Degrees0,
        mirror = Mirror.None,
        colorMode = ColorMode.Color,
        sourceWidthPx = 8,
        sourceHeightPx = 6,
        densityDpi = 320,
        targetMode = CaptureTargetMode.Full,
        targetWidthPx = 8,
        targetHeightPx = 6,
        rgbaLayout = Rgba8888Layout.create(widthPx = 8, heightPx = 6),
    )

    private class SingleCaptureTaskPoster(
        private val captureHandler: Handler,
    ) : HandlerTaskPoster {
        private val tasks = ArrayDeque<Runnable>()

        override fun post(handler: Handler, task: Runnable): Boolean {
            if (handler !== captureHandler) throw AssertionError("Task escaped the Capture Handler")
            tasks.addLast(task)
            return true
        }

        override fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean =
            throw AssertionError("Delayed post was not expected")

        override fun removeCallbacks(handler: Handler, task: Runnable) {
            throw AssertionError("Callback removal was not expected")
        }

        fun enterNext(): Boolean = tasks.removeFirstOrNull()?.let { task ->
            task.run()
            true
        } ?: false
    }

    private class RecordingCaptureFacts : SessionCaptureFactPort {
        var openResult: CaptureOpenResult? = null
            private set

        override fun onOpenReturned(result: CaptureOpenResult) {
            check(openResult == null)
            openResult = result
        }

        override fun onApplyReturned(result: CaptureApplyResult) = error("Unexpected Capture Apply")
        override fun onSourceAvailable(sourceIdentity: CaptureSourceIdentity) = error("Unexpected source")
        override fun onProjectionStopped(projectionIdentity: CaptureProjectionIdentity) = error("Unexpected projection stop")

        override fun onCapturedContentResize(projectionIdentity: CaptureProjectionIdentity, widthPx: Int, heightPx: Int) =
            error("Unexpected captured-content resize")

        override fun onCapturedContentVisibilityChanged(projectionIdentity: CaptureProjectionIdentity, isVisible: Boolean) =
            error("Unexpected captured-content visibility")

        override fun onCaptureFailure(failure: Exception) {
            throw AssertionError("Unexpected Capture callback failure", failure)
        }
    }

    private class NullDisplayCase(
        val name: String,
        val projectionRetirementFailure: Exception?,
        val expectedProblem: ScreenCaptureProblem,
    )
}
