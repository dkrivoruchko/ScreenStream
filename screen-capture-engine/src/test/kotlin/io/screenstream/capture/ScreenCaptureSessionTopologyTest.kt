package io.screenstream.capture

import android.os.Build
import io.mockk.every
import io.mockk.verify
import io.screenstream.capture.testutil.ControllableMetricsSource
import io.screenstream.capture.testutil.CoordinatorMetricsHarness
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.CapturePlatformFixture
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.SafeRejectingNativeJpegFacade
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.requestStopAndDrainSession
import io.screenstream.capture.testutil.SessionHarness
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionTopologyTest {
    // Verification: SES-01
    // Verification: SES-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publicStartTraversesCaptureBoundariesAndReturnsAfterFirstActive() = runTest {
        val platform = CapturePlatformFixture()
        val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = metrics,
            platformSdkInt = Build.VERSION_CODES.N,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(parameters)
                harness.session.state.value
            }

            assertSame(ScreenCaptureState.Starting, harness.session.state.value)
            assertFalse(start.isCompleted)
            platform.verifyUntouched()

            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val stateAtStartReturn = start.await()

            val active = stateAtStartReturn as ScreenCaptureState.Active
            val outputInfo = active.outputInfo
            assertEquals(parameters, outputInfo.parameters)
            assertEquals(8, outputInfo.captureGeometry.widthPx)
            assertEquals(6, outputInfo.captureGeometry.heightPx)
            assertEquals(320, outputInfo.captureGeometry.densityDpi)
            assertEquals(0, outputInfo.appliedSourceRect.leftPx)
            assertEquals(0, outputInfo.appliedSourceRect.topPx)
            assertEquals(8, outputInfo.appliedSourceRect.rightPx)
            assertEquals(6, outputInfo.appliedSourceRect.bottomPx)
            assertEquals(8, outputInfo.finalImageSize.widthPx)
            assertEquals(6, outputInfo.finalImageSize.heightPx)
            assertNull(active.isCapturedContentVisible)

            platform.verifyOpenBoundaries(widthPx = 8, heightPx = 6, densityDpi = 320)
        }
    }

    // Verification: SES-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun authoritativeInitialResizeKeepsStartPendingUntilResizedCaptureIsActive() = runTest {
        val provisionalMetrics = CaptureMetrics(widthPx = 2, heightPx = 2, densityDpi = 320)
        val parameters = ScreenCaptureParameters(
            crop = CropInsetsPx(left = 2, top = 0, right = 0, bottom = 0),
            outputSize = OutputSize.ScaleFactor(1.0),
        )
        val platform = CapturePlatformFixture()

        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = provisionalMetrics,
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(parameters)
                harness.session.state.value
            }

            assertSame(ScreenCaptureState.Starting, harness.session.state.value)
            assertFalse(start.isCompleted)
            platform.verifyUntouched()

            harness.driveUntil(platform::initialVirtualDisplayReturned)

            assertSame(ScreenCaptureState.Starting, harness.session.state.value)
            assertFalse(start.isCompleted)
            platform.deliverCapturedContentResize(widthPx = 6, heightPx = 4)

            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val stateAtStartReturn = start.await()

            val active = stateAtStartReturn as ScreenCaptureState.Active
            val outputInfo = active.outputInfo
            assertEquals(parameters, active.requestedParameters)
            assertEquals(parameters, outputInfo.parameters)
            assertEquals(6, outputInfo.captureGeometry.widthPx)
            assertEquals(4, outputInfo.captureGeometry.heightPx)
            assertEquals(320, outputInfo.captureGeometry.densityDpi)
            assertEquals(2, outputInfo.appliedSourceRect.leftPx)
            assertEquals(0, outputInfo.appliedSourceRect.topPx)
            assertEquals(6, outputInfo.appliedSourceRect.rightPx)
            assertEquals(4, outputInfo.appliedSourceRect.bottomPx)
            assertEquals(4, outputInfo.finalImageSize.widthPx)
            assertEquals(4, outputInfo.finalImageSize.heightPx)
            assertNull(active.isCapturedContentVisible)

            platform.verifyInitialProjectionBoundaries(widthPx = 2, heightPx = 2, densityDpi = 320)
            platform.verifyAuthoritativeResizeBoundaries(widthPx = 6, heightPx = 4, densityDpi = 320)
        }
    }

    // Verification: SES-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun provisionalOpenUsesBoundedNeutralOutputAndDefersEncodingPreparation() = runTest {
        val platform = CapturePlatformFixture()
        val nativeJpeg = SafeRejectingNativeJpegFacade()
        val parameters = ScreenCaptureParameters(
            outputSize = OutputSize.TargetSize(64, 32, OutputSize.ContentMode.Stretch),
        )
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                val start = async(UnconfinedTestDispatcher(testScheduler)) {
                    harness.session.start(parameters)
                }

                harness.driveUntil(platform::initialVirtualDisplayReturned)
                drainAcceptedSessionWork(harness)

                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertFalse(start.isCompleted)
                verify(exactly = 1) { platform.glesPlatform.texImage2D(1, 1) }
                verify(exactly = 1) { platform.glesPlatform.texImage2D(any(), any()) }
                assertEquals(0, nativeJpeg.carrierSnapshot().allocationCount)
                platform.verifyNoProjectionTopologyChanges()

                platform.deliverCapturedContentResize(widthPx = 10, heightPx = 8)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
                start.await()

                val active = harness.session.state.value as ScreenCaptureState.Active
                assertEquals(parameters, active.requestedParameters)
                assertEquals(parameters, active.outputInfo.parameters)
                assertEquals(10, active.outputInfo.captureGeometry.widthPx)
                assertEquals(8, active.outputInfo.captureGeometry.heightPx)
                assertEquals(64, active.outputInfo.finalImageSize.widthPx)
                assertEquals(32, active.outputInfo.finalImageSize.heightPx)
                verify(exactly = 1) { platform.glesPlatform.texImage2D(64, 32) }
                verify(exactly = 2) { platform.glesPlatform.texImage2D(any(), any()) }
                assertEquals(1, nativeJpeg.carrierSnapshot().allocationCount)
            } finally {
                requestStopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }

    // Verification: SES-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun authoritativeInvalidGeometryFailsStartupAfterNeutralOpen() = runTest {
        val platform = CapturePlatformFixture()
        val nativeJpeg = SafeRejectingNativeJpegFacade()
        val parameters = ScreenCaptureParameters(
            crop = CropInsetsPx(left = 2, top = 0, right = 0, bottom = 0),
            outputSize = OutputSize.ScaleFactor(1.0),
        )
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 2, heightPx = 2, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                val start = async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { harness.session.start(parameters) }.exceptionOrNull()
                }
                harness.driveUntil(platform::initialVirtualDisplayReturned)
                platform.deliverCapturedContentResize(widthPx = 2, heightPx = 2)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }

                val failed = harness.session.state.value as ScreenCaptureState.Failed
                assertSame(ScreenCaptureProblem.InvalidRequest, failed.problem)
                assertEquals(parameters, failed.requestedParameters)
                assertNull(failed.lastOutputInfo)
                assertEquals(0, nativeJpeg.carrierSnapshot().allocationCount)
                verify(exactly = 1) { platform.glesPlatform.texImage2D(1, 1) }
                val startFailure = start.await() as ScreenCaptureException
                assertSame(ScreenCaptureProblem.InvalidRequest, startFailure.problem)
            } finally {
                requestStopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }

    // Verification: SES-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun genuineNeutralOpenFailureStillFailsStartup() = runTest {
        val platform = CapturePlatformFixture()
        every { platform.glesPlatform.texImage2D(1, 1) } throws IllegalStateException("Injected neutral output setup failure")
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.TargetSize(64, 32, OutputSize.ContentMode.Stretch))
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            try {
                val start = async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { harness.session.start(parameters) }.exceptionOrNull()
                }
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }

                val failed = harness.session.state.value as ScreenCaptureState.Failed
                assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
                assertNull(failed.lastOutputInfo)
                val startFailure = start.await() as ScreenCaptureException
                assertSame(ScreenCaptureProblem.InternalFailure, startFailure.problem)
            } finally {
                requestStopAndDrainSession(harness)
            }
        }
    }

    // Verification: SES-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun authoritativeResizeAfterActivePublishesCompatibleResizedCapture() = runTest {
        val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val platform = CapturePlatformFixture()
        val metricsSource = ControllableMetricsSource()

        CoordinatorMetricsHarness(
            source = metricsSource,
            platform = platform,
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(parameters)
                harness.session.state.value
            }

            harness.driveUntil(metricsSource::isSubscribed)
            metricsSource.emit(metrics)
            harness.driveUntil(platform::initialVirtualDisplayReturned)
            platform.deliverCapturedContentResize(widthPx = 8, heightPx = 6)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val initialActive = start.await() as ScreenCaptureState.Active

            platform.deliverCapturedContentResize(widthPx = 6, heightPx = 4)
            harness.driveUntil {
                val state = harness.session.state.value
                (state is ScreenCaptureState.Active) &&
                        (state.outputInfo.captureGeometry.widthPx == 6) &&
                        (state.outputInfo.captureGeometry.heightPx == 4)
            }

            val resizedActive = harness.session.state.value as ScreenCaptureState.Active
            val outputInfo = resizedActive.outputInfo
            assertEquals(parameters, resizedActive.requestedParameters)
            assertEquals(parameters, outputInfo.parameters)
            assertEquals(6, outputInfo.captureGeometry.widthPx)
            assertEquals(4, outputInfo.captureGeometry.heightPx)
            assertEquals(320, outputInfo.captureGeometry.densityDpi)
            assertEquals(0, outputInfo.appliedSourceRect.leftPx)
            assertEquals(0, outputInfo.appliedSourceRect.topPx)
            assertEquals(6, outputInfo.appliedSourceRect.rightPx)
            assertEquals(4, outputInfo.appliedSourceRect.bottomPx)
            assertEquals(6, outputInfo.finalImageSize.widthPx)
            assertEquals(4, outputInfo.finalImageSize.heightPx)
            assertEquals(initialActive.isCapturedContentVisible, resizedActive.isCapturedContentVisible)

            metricsSource.emit(CaptureMetrics(widthPx = 10, heightPx = 7, densityDpi = 320))
            harness.settleNextMetricsChange()

            assertEquals(resizedActive, harness.session.state.value)
            verify(exactly = 1) {
                platform.projectionPlatform.resize(any(), any(), any(), any())
                platform.projectionPlatform.setSurface(any(), any())
            }
            platform.verifyAuthoritativeResizeBoundaries(widthPx = 6, heightPx = 4, densityDpi = 320)

            metricsSource.emit(CaptureMetrics(widthPx = 10, heightPx = 7, densityDpi = 480))
            harness.driveUntil {
                val state = harness.session.state.value
                (state is ScreenCaptureState.Active) &&
                        (state.outputInfo.captureGeometry.densityDpi == 480)
            }

            val densityUpdated = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(6, densityUpdated.outputInfo.captureGeometry.widthPx)
            assertEquals(4, densityUpdated.outputInfo.captureGeometry.heightPx)
            assertEquals(480, densityUpdated.outputInfo.captureGeometry.densityDpi)
            verify(exactly = 1) {
                platform.projectionPlatform.resize(any(), 6, 4, 480)
            }
            verify(exactly = 2) {
                platform.projectionPlatform.resize(any(), any(), any(), any())
            }

            platform.verifyInitialProjectionBoundaries(widthPx = 8, heightPx = 6, densityDpi = 320)
        }
    }

    // Verification: SES-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun capturedContentVisibilityRepublishesActiveWithoutChangingCaptureTopology() = runTest {
        val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val platform = CapturePlatformFixture()

        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = metrics,
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(parameters)
                harness.session.state.value
            }

            harness.driveUntil(platform::initialVirtualDisplayReturned)
            platform.deliverCapturedContentResize(widthPx = 8, heightPx = 6)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val initialActive = start.await() as ScreenCaptureState.Active
            val initialStats = harness.session.stats.value

            platform.deliverCapturedContentVisibilityChanged(isVisible = true)
            harness.driveUntil {
                (harness.session.state.value as? ScreenCaptureState.Active)?.isCapturedContentVisible == true
            }
            val visibleActive = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(initialActive.outputInfo, visibleActive.outputInfo)
            assertEquals(initialActive.requestedParameters, visibleActive.requestedParameters)
            assertEquals(initialStats, harness.session.stats.value)
            assertEquals(true, visibleActive.isCapturedContentVisible)

            platform.deliverCapturedContentVisibilityChanged(isVisible = false)
            harness.driveUntil {
                (harness.session.state.value as? ScreenCaptureState.Active)?.isCapturedContentVisible == false
            }
            val hiddenActive = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(initialActive.outputInfo, hiddenActive.outputInfo)
            assertEquals(initialActive.requestedParameters, hiddenActive.requestedParameters)
            assertEquals(initialStats, harness.session.stats.value)
            assertEquals(false, hiddenActive.isCapturedContentVisible)

            platform.verifyInitialProjectionBoundaries(widthPx = 8, heightPx = 6, densityDpi = 320)
            platform.verifyNoProjectionTopologyChanges()
        }
    }

}
