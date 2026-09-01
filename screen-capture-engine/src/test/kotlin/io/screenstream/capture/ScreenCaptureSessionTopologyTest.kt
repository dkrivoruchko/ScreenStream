package io.screenstream.capture

import android.os.Build
import io.mockk.verify
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
import io.screenstream.capture.testutil.SessionStartHarness
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

/*
 * Public capture-topology integration evidence through the real Coordinator and Capture owners.
 *
 * Projection callbacks and accepted non-inline work only arrange activation, resize, and visibility changes.
 * Queue shape, turn count, private phase, handler identity, and incidental platform-call ordering are not oracles;
 * public State/effective geometry and maintained platform effects decide these scenarios.
 */
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
        val platform = HappyCapturePlatform()
        val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = metrics,
            platformSdkInt = Build.VERSION_CODES.N,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(platform.projection, parameters)
                harness.session.state.value
            }

            assertSame(ScreenCaptureState.Starting, harness.session.state.value)
            assertFalse(start.isCompleted)
            platform.verifyUntouched()

            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val stateAtStartReturn = start.await()

            val active = stateAtStartReturn as ScreenCaptureState.Active
            val effective = active.effectiveParameters
            assertEquals(parameters, effective.appliedParameters)
            assertEquals(8, effective.captureGeometry.widthPx)
            assertEquals(6, effective.captureGeometry.heightPx)
            assertEquals(320, effective.captureGeometry.densityDpi)
            assertEquals(0, effective.appliedSourceRect.leftPx)
            assertEquals(0, effective.appliedSourceRect.topPx)
            assertEquals(8, effective.appliedSourceRect.rightPx)
            assertEquals(6, effective.appliedSourceRect.bottomPx)
            assertEquals(8, effective.finalImageSize.widthPx)
            assertEquals(6, effective.finalImageSize.heightPx)
            assertNull(active.isCapturedContentVisible)

            platform.verifyOpenBoundaries(widthPx = 8, heightPx = 6, densityDpi = 320)
        }
    }

    // Verification: SES-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun authoritativeInitialResizeKeepsStartPendingUntilResizedCaptureIsActive() = runTest {
        val provisionalMetrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val platform = HappyCapturePlatform()

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = provisionalMetrics,
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(platform.projection, parameters)
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
            val effective = active.effectiveParameters
            assertEquals(parameters, active.requestedParameters)
            assertEquals(parameters, effective.appliedParameters)
            assertEquals(6, effective.captureGeometry.widthPx)
            assertEquals(4, effective.captureGeometry.heightPx)
            assertEquals(320, effective.captureGeometry.densityDpi)
            assertEquals(0, effective.appliedSourceRect.leftPx)
            assertEquals(0, effective.appliedSourceRect.topPx)
            assertEquals(6, effective.appliedSourceRect.rightPx)
            assertEquals(4, effective.appliedSourceRect.bottomPx)
            assertEquals(6, effective.finalImageSize.widthPx)
            assertEquals(4, effective.finalImageSize.heightPx)
            assertNull(active.isCapturedContentVisible)

            platform.verifyInitialProjectionBoundaries(widthPx = 8, heightPx = 6, densityDpi = 320)
            platform.verifyAuthoritativeResizeBoundaries(widthPx = 6, heightPx = 4, densityDpi = 320)
        }
    }

    // Verification: SES-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun authoritativeResizeAfterActivePublishesCompatibleResizedCapture() = runTest {
        val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val platform = HappyCapturePlatform()
        val metricsSource = ControllableMetricsSource()

        CoordinatorMetricsHarness(
            source = metricsSource,
            platform = platform,
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(platform.projection, parameters)
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
                        (state.effectiveParameters.captureGeometry.widthPx == 6) &&
                        (state.effectiveParameters.captureGeometry.heightPx == 4)
            }

            val resizedActive = harness.session.state.value as ScreenCaptureState.Active
            val effective = resizedActive.effectiveParameters
            assertEquals(parameters, resizedActive.requestedParameters)
            assertEquals(parameters, effective.appliedParameters)
            assertEquals(6, effective.captureGeometry.widthPx)
            assertEquals(4, effective.captureGeometry.heightPx)
            assertEquals(320, effective.captureGeometry.densityDpi)
            assertEquals(0, effective.appliedSourceRect.leftPx)
            assertEquals(0, effective.appliedSourceRect.topPx)
            assertEquals(6, effective.appliedSourceRect.rightPx)
            assertEquals(4, effective.appliedSourceRect.bottomPx)
            assertEquals(6, effective.finalImageSize.widthPx)
            assertEquals(4, effective.finalImageSize.heightPx)
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
                        (state.effectiveParameters.captureGeometry.densityDpi == 480)
            }

            val densityUpdated = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(6, densityUpdated.effectiveParameters.captureGeometry.widthPx)
            assertEquals(4, densityUpdated.effectiveParameters.captureGeometry.heightPx)
            assertEquals(480, densityUpdated.effectiveParameters.captureGeometry.densityDpi)
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
        val platform = HappyCapturePlatform()

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = metrics,
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(platform.projection, parameters)
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
            assertEquals(initialActive.effectiveParameters, visibleActive.effectiveParameters)
            assertEquals(initialActive.requestedParameters, visibleActive.requestedParameters)
            assertEquals(initialStats, harness.session.stats.value)
            assertEquals(true, visibleActive.isCapturedContentVisible)

            platform.deliverCapturedContentVisibilityChanged(isVisible = false)
            harness.driveUntil {
                (harness.session.state.value as? ScreenCaptureState.Active)?.isCapturedContentVisible == false
            }
            val hiddenActive = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(initialActive.effectiveParameters, hiddenActive.effectiveParameters)
            assertEquals(initialActive.requestedParameters, hiddenActive.requestedParameters)
            assertEquals(initialStats, harness.session.stats.value)
            assertEquals(false, hiddenActive.isCapturedContentVisible)

            platform.verifyInitialProjectionBoundaries(widthPx = 8, heightPx = 6, densityDpi = 320)
            platform.verifyNoProjectionTopologyChanges()
        }
    }

}
