package io.screenstream.capture

import android.os.Build
import android.view.Surface
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.CapturePlatformFixture
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.FrameSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.copyFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.driveControlUntil
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.startActiveSession
import io.screenstream.capture.testutil.SessionHarness
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionFailureAndRecoveryIntegrationTest {
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun exactFirstActiveCutoffFailsStartAndRetiresAcceptedProjection() = runTest {
        SessionHarness(bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val parameters = ScreenCaptureParameters(jpegQuality = 81)
            val initialStats = harness.session.stats.value
            harness.clock.enqueueValue(0L)
            harness.clock.enqueueValue(0L)
            val start = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { harness.session.start(parameters) }.exceptionOrNull()
            }
            harness.clock.setDefaultNanos(10L * 1_000_000_000L)

            val deadlineEntry = checkNotNull(harness.enterNextDelayedEntry()) {
                "Accepted startup cutoff did not enter"
            }
            deadlineEntry.awaitSuccessfulCompletion()
            runCurrent()

            val startFailure = start.await() as ScreenCaptureException
            assertSame(ScreenCaptureProblem.CaptureUnavailable, startFailure.problem)
            val failed = harness.session.state.value as ScreenCaptureState.Failed
            assertSame(ScreenCaptureProblem.CaptureUnavailable, failed.problem)
            assertEquals(parameters, failed.requestedParameters)
            assertNull(failed.lastOutputInfo)
            assertZeroStats(harness.session.stats.value)
            assertEquals(initialStats, harness.session.stats.value)

            harness.drainWorkerTasks()
            assertEquals(failed, harness.session.state.value)
            assertZeroStats(harness.session.stats.value)
            assertEquals(initialStats, harness.session.stats.value)
            verify(exactly = 1) { projection.stop() }
        }
    }

    // Verification: SES-07
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun invalidRequestSuspendsWithDirtyStatsWithheldUntilActiveRecovery() = runTest {
        val platform = CapturePlatformFixture()
        val initialParameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val invalidParameters = initialParameters.copy(
            crop = CropInsetsPx(left = 8, top = 0, right = 0, bottom = 0),
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
        ).use { harness ->
            startActiveSessionWithAuthoritativeResize(harness, platform, initialParameters)
            val initialActive = harness.session.state.value as ScreenCaptureState.Active
            val publishedBeforeFrame = harness.session.stats.value
            val delivered = CopyOnWriteArrayList<FrameSnapshot>()
            harness.session.registerFrameConsumer { frame -> delivered += copyFrame(frame) }

            platform.deliverSourceFrame(rgbaSeed = 31)
            harness.driveUntil { delivered.isNotEmpty() }
            assertEquals(publishedBeforeFrame, harness.session.stats.value)

            harness.session.updateParameters(invalidParameters)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Suspended }
            assertSuspended(
                state = harness.session.state.value,
                expectedProblem = ScreenCaptureProblem.InvalidRequest,
                expectedRequested = invalidParameters,
                expectedLastOutputInfo = initialActive.outputInfo,
            )

            harness.clock.setDefaultNanos(1_000_000_000L)
            platform.deliverCapturedContentVisibilityChanged(isVisible = true)
            harness.driveUntil {
                (harness.session.state.value as? ScreenCaptureState.Suspended)?.isCapturedContentVisible == true
            }
            assertEquals(publishedBeforeFrame, harness.session.stats.value)

            harness.session.updateParameters(initialParameters)
            harness.driveUntil {
                val state = harness.session.state.value
                state is ScreenCaptureState.Active &&
                        state.outputInfo.parameters == initialParameters &&
                        harness.session.stats.value.producedFrameCount == 1L
            }

            val recovered = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(initialParameters, recovered.requestedParameters)
            assertEquals(initialParameters, recovered.outputInfo.parameters)
            assertEquals(1L, harness.session.stats.value.encodedFrameCount)
            assertEquals(1L, harness.session.stats.value.producedFrameCount)
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun unavailableMetricsSuspendRecoverAndAdjacentSourceFailureTerminates() = runTest {
        val platform = CapturePlatformFixture()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.N,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            startActiveSession(harness, parameters)
            val initialActive = harness.session.state.value as ScreenCaptureState.Active

            harness.emitMetrics(null)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Suspended }
            assertSuspended(
                state = harness.session.state.value,
                expectedProblem = ScreenCaptureProblem.CaptureUnavailable,
                expectedRequested = parameters,
                expectedLastOutputInfo = initialActive.outputInfo,
            )

            harness.emitMetrics(CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320))
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val recovered = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(parameters, recovered.requestedParameters)
            assertEquals(initialActive.outputInfo, recovered.outputInfo)

            harness.failMetrics(IllegalStateException("Injected metrics-source failure"))
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }
            val failed = harness.session.state.value as ScreenCaptureState.Failed
            assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
            assertEquals(parameters, failed.requestedParameters)
            assertEquals(recovered.outputInfo, failed.lastOutputInfo)

            drainAcceptedSessionWork(harness)
            harness.session.requestStop()
            assertEquals(failed, harness.session.state.value)
        }
    }

    // Verification: API-04
    // Verification: SES-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun equalResubmissionReevaluatesRollbackSafeResourceExhaustionAndRecovers() = runTest {
        val platform = CapturePlatformFixture()
        val initialParameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val downscaledParameters = initialParameters.copy(outputSize = OutputSize.ScaleFactor(0.5))
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.S_V2,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            startActiveSession(harness, initialParameters)
            val initialActive = harness.session.state.value as ScreenCaptureState.Active
            platform.failNextReplacementSurfaceTextureCreation(
                Surface.OutOfResourcesException("Injected replacement SurfaceTexture allocation denial"),
            )

            harness.session.updateParameters(downscaledParameters)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Suspended }
            drainAcceptedSessionWork(harness)
            val suspended = harness.session.state.value
            assertSuspended(
                state = suspended,
                expectedProblem = ScreenCaptureProblem.ResourceExhausted,
                expectedRequested = downscaledParameters,
                expectedLastOutputInfo = initialActive.outputInfo,
            )
            assertSame(downscaledParameters, (suspended as ScreenCaptureState.Suspended).requestedParameters)
            verify(exactly = 2) { platform.targetPlatform.createSurfaceTexture(any()) }
            verify(exactly = 1) {
                platform.targetPlatform.setDefaultBufferSize(any(), any(), any())
                platform.targetPlatform.createSurface(any())
                platform.targetPlatform.setFrameListener(any(), any(), any())
            }
            verify(exactly = 0) { platform.projectionPlatform.setSurface(any(), any()) }

            drainAcceptedSessionWork(harness)
            assertSame(suspended, harness.session.state.value)

            platform.failNextReplacementSurfaceTextureCreation(
                Surface.OutOfResourcesException("Injected repeated replacement SurfaceTexture allocation denial"),
            )
            harness.session.updateParameters(downscaledParameters.copy())
            harness.session.updateParameters(downscaledParameters.copy())
            drainAcceptedSessionWork(harness)
            val renewedSuspension = harness.session.state.value
            assertSuspended(
                state = renewedSuspension,
                expectedProblem = ScreenCaptureProblem.ResourceExhausted,
                expectedRequested = downscaledParameters,
                expectedLastOutputInfo = initialActive.outputInfo,
            )
            assertSame(
                downscaledParameters,
                (renewedSuspension as ScreenCaptureState.Suspended).requestedParameters,
            )
            assertSame(initialActive.outputInfo, renewedSuspension.lastOutputInfo)
            verify(exactly = 3) { platform.targetPlatform.createSurfaceTexture(any()) }

            drainAcceptedSessionWork(harness)
            assertSame(renewedSuspension, harness.session.state.value)
            verify(exactly = 3) { platform.targetPlatform.createSurfaceTexture(any()) }

            harness.session.updateParameters(downscaledParameters.copy())
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val recovered = harness.session.state.value as ScreenCaptureState.Active
            assertSame(downscaledParameters, recovered.requestedParameters)
            assertSame(downscaledParameters, recovered.outputInfo.parameters)
            assertEquals(4, recovered.outputInfo.finalImageSize.widthPx)
            assertEquals(3, recovered.outputInfo.finalImageSize.heightPx)
            verify(exactly = 4) { platform.targetPlatform.createSurfaceTexture(any()) }
            verify(exactly = 1) {
                platform.projectionPlatform.createVirtualDisplay(any(), any(), any(), any(), any())
                platform.projectionPlatform.setSurface(any(), any())
            }

            harness.session.updateParameters(downscaledParameters.copy())
            drainAcceptedSessionWork(harness)
            assertSame(recovered, harness.session.state.value)
            verify(exactly = 4) { platform.targetPlatform.createSurfaceTexture(any()) }

            val delivered = CopyOnWriteArrayList<FrameSnapshot>()
            harness.session.registerFrameConsumer { frame -> delivered += copyFrame(frame) }
            platform.deliverSourceFrame(rgbaSeed = 61)
            harness.driveUntil { delivered.isNotEmpty() }
            assertEquals(1, platform.sourceUpdateCount())
            assertSame(downscaledParameters, delivered.single().outputInfo.parameters)
            assertTrue(delivered.single().bytes.isNotEmpty())
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun recoverableProblemsBeforeFirstActiveFailWithoutSuspension() = runTest {
        assertStartupFailure(
            expectedProblem = ScreenCaptureProblem.InvalidRequest,
            parameters = ScreenCaptureParameters(crop = CropInsetsPx(left = 8, top = 0, right = 0, bottom = 0)),
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
        )
        assertStartupFailure(
            expectedProblem = ScreenCaptureProblem.ResourceExhausted,
            parameters = ScreenCaptureParameters(
                outputSize = OutputSize.TargetSize(
                    widthPx = Int.MAX_VALUE,
                    heightPx = 1,
                    contentMode = OutputSize.ContentMode.Stretch,
                ),
            ),
            metrics = CaptureMetrics(widthPx = 1, heightPx = 1, densityDpi = 320),
        )
        assertStartupFailure(
            expectedProblem = ScreenCaptureProblem.CaptureUnavailable,
            parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0)),
            metrics = null,
            completeUnavailableMetrics = true,
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun currentProjectionStopBeatsRequestedContenderAndFreezesFinalState() = runTest {
        val platform = CapturePlatformFixture()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
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
            startActiveSessionWithAuthoritativeResize(harness, platform, parameters)
            val active = harness.session.state.value as ScreenCaptureState.Active

            platform.deliverProjectionStopped()
            assertEquals(active, harness.session.state.value)
            harness.session.requestStop()
            driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Stopped }

            val stopped = harness.session.state.value as ScreenCaptureState.Stopped
            assertSame(ScreenCaptureStopReason.ProjectionStopped, stopped.reason)
            assertEquals(parameters, stopped.requestedParameters)
            assertEquals(active.outputInfo, stopped.lastOutputInfo)

            drainAcceptedSessionWork(harness)
            harness.session.requestStop()
            platform.deliverCapturedContentVisibilityChanged(isVisible = true)
            drainAcceptedSessionWork(harness)
            assertEquals(stopped, harness.session.state.value)
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun staleOwnerInvalidatedReadFailsExactlyWhileOrdinaryAdmissionRemainsOpen() = runTest {
        val platform = CapturePlatformFixture()
        val initialParameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            frameRate = FrameRate.Auto,
        )
        val staleRevisionParameters = initialParameters.copy(frameRate = FrameRate.MaxFps(15))
        val latestParameters = initialParameters.copy(frameRate = FrameRate.MaxFps(30))
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
            startActiveSession(harness, initialParameters)
            val active = harness.session.state.value as ScreenCaptureState.Active
            harness.session.registerFrameConsumer { }
            platform.failNextSourceUpdate(IllegalStateException("Injected stale read owner invalidation"))
            platform.deliverSourceFrame(rgbaSeed = 43)
            val staleRead = claimNextCaptureTask(harness)

            harness.session.updateParameters(staleRevisionParameters)
            staleRead.run()
            assertEquals(active, harness.session.state.value)

            harness.session.updateParameters(latestParameters)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }

            val failed = harness.session.state.value as ScreenCaptureState.Failed
            assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
            assertEquals(latestParameters, failed.requestedParameters)
            assertEquals(active.outputInfo, failed.lastOutputInfo)
            assertEquals(1L, harness.session.stats.value.frameProductionDrops.byFailure)

            drainAcceptedSessionWork(harness)
            harness.session.requestStop()
            assertEquals(failed, harness.session.state.value)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.assertStartupFailure(
        expectedProblem: ScreenCaptureProblem,
        parameters: ScreenCaptureParameters,
        metrics: CaptureMetrics?,
        completeUnavailableMetrics: Boolean = false,
    ) {
        val platform = CapturePlatformFixture()
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
            val initialStats = harness.session.stats.value
            // In this serialized arrangement the collector body does not suspend, so eager collection can observe
            // transient Suspended. It is not a general receipt for publication history or ordering.
            val transientStates = CopyOnWriteArrayList<ScreenCaptureState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.state.collect(transientStates::add)
            }
            val start = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { harness.session.start(parameters) }.exceptionOrNull()
            }
            try {
                if (completeUnavailableMetrics) {
                    harness.driveUntil { harness.metricsSubscriptionCount() == 1 }
                    harness.completeMetrics()
                }
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }

                val startFailure = start.await() as ScreenCaptureException
                assertSame(expectedProblem, startFailure.problem)
                val failed = harness.session.state.value as ScreenCaptureState.Failed
                assertSame(expectedProblem, failed.problem)
                assertEquals(parameters, failed.requestedParameters)
                assertNull(failed.lastOutputInfo)
                assertTrue(transientStates.none { it is ScreenCaptureState.Suspended })
                assertEquals(initialStats, harness.session.stats.value)
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    private fun assertSuspended(
        state: ScreenCaptureState,
        expectedProblem: ScreenCaptureProblem,
        expectedRequested: ScreenCaptureParameters,
        expectedLastOutputInfo: CaptureOutputInfo,
    ) {
        val suspended = state as ScreenCaptureState.Suspended
        assertSame(expectedProblem, suspended.problem)
        assertEquals(expectedRequested, suspended.requestedParameters)
        assertEquals(expectedLastOutputInfo, suspended.lastOutputInfo)
    }

    private fun assertZeroStats(stats: ScreenCaptureStats) {
        assertEquals(0L, stats.encodedFrameCount)
        assertEquals(0L, stats.producedFrameCount)
        assertEquals(0L, stats.frameProductionDrops.total)
        assertEquals(0L, stats.droppedDeliveries.total)
    }

    private suspend fun startActiveSessionWithAuthoritativeResize(
        harness: SessionHarness,
        platform: CapturePlatformFixture,
        parameters: ScreenCaptureParameters,
    ) = coroutineScope {
        val start = async(start = CoroutineStart.UNDISPATCHED) {
            harness.session.start(parameters)
        }
        harness.driveUntil(platform::initialVirtualDisplayReturned)
        platform.deliverCapturedContentResize(widthPx = 8, heightPx = 6)
        harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
        start.await()
    }

    private fun claimNextCaptureTask(harness: SessionHarness): Runnable {
        repeat(32) {
            harness.claimNextCaptureTask()?.let { return it }
            harness.enterNextWorkerSuccessfully()
            harness.enterNextControlTask()
        }
        error("Controlled Session work did not expose the accepted Capture read")
    }
}
