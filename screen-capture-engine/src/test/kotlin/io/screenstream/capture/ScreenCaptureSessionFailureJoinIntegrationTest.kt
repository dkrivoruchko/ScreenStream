package io.screenstream.capture

import android.os.Build
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.FrameSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.copyFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.driveControlUntil
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.startActiveSession
import io.screenstream.capture.testutil.SessionStartHarness
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

/*
 * Public failure/suspension joins through the real Session Coordinator and Capture/Metrics owners.
 * Controlled task entry and injected platform/source outcomes only arrange the boundary. Public State/Stats,
 * start settlement, requested/last-effective facts, and terminal immutability are the verdicts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionFailureJoinIntegrationTest {
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun exactFirstActiveCutoffFailsStartAndRetiresAcceptedProjection() = runTest {
        SessionStartHarness(bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val parameters = ScreenCaptureParameters(jpegQuality = 81)
            val initialStats = harness.session.stats.value
            harness.clock.enqueueValue(0L)
            harness.clock.enqueueValue(0L)
            val start = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { harness.session.start(projection, parameters) }.exceptionOrNull()
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
            assertNull(failed.lastEffectiveParameters)
            assertZeroStats(harness.session.stats.value)
            assertEquals(initialStats, harness.session.stats.value)

            harness.drainWorkerTasks()
            assertEquals(failed, harness.session.state.value)
            assertZeroStats(harness.session.stats.value)
            assertEquals(initialStats, harness.session.stats.value)
            verify(exactly = 1) { projection.stop() }
        }
    }

    // Audit item: P2-02
    // Verification: SES-07
    // Audit item: P3-04
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun invalidRequestSuspendsWithDirtyStatsWithheldUntilActiveRecovery() = runTest {
        val platform = HappyCapturePlatform()
        val initialParameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val invalidParameters = initialParameters.copy(
            crop = CropInsetsPx(left = 8, top = 0, right = 0, bottom = 0),
        )
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
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
                expectedLastEffective = initialActive.effectiveParameters,
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
                        state.effectiveParameters.appliedParameters == initialParameters &&
                        harness.session.stats.value.producedFrameCount == 1L
            }

            val recovered = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(initialParameters, recovered.requestedParameters)
            assertEquals(initialParameters, recovered.effectiveParameters.appliedParameters)
            assertEquals(1L, harness.session.stats.value.encodedFrameCount)
            assertEquals(1L, harness.session.stats.value.producedFrameCount)
        }
    }

    // Audit item: P2-02
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun unavailableMetricsSuspendRecoverAndAdjacentSourceFailureTerminates() = runTest {
        val platform = HappyCapturePlatform()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.N,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            startActiveSession(harness, platform, parameters)
            val initialActive = harness.session.state.value as ScreenCaptureState.Active

            harness.emitMetrics(null)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Suspended }
            assertSuspended(
                state = harness.session.state.value,
                expectedProblem = ScreenCaptureProblem.CaptureUnavailable,
                expectedRequested = parameters,
                expectedLastEffective = initialActive.effectiveParameters,
            )

            harness.emitMetrics(CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320))
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val recovered = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(parameters, recovered.requestedParameters)
            assertEquals(initialActive.effectiveParameters, recovered.effectiveParameters)

            harness.failMetrics(IllegalStateException("Injected metrics-source failure"))
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }
            val failed = harness.session.state.value as ScreenCaptureState.Failed
            assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
            assertEquals(parameters, failed.requestedParameters)
            assertEquals(recovered.effectiveParameters, failed.lastEffectiveParameters)

            drainAcceptedSessionWork(harness)
            harness.session.stop()
            assertEquals(failed, harness.session.state.value)
        }
    }

    // Audit item: P2-02
    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun rollbackSafeResourceExhaustionSuspendsAndRecovers() = runTest {
        val platform = HappyCapturePlatform()
        val initialParameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val downscaledParameters = initialParameters.copy(outputSize = OutputSize.ScaleFactor(0.5))
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.S_V2,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            startActiveSession(harness, platform, initialParameters)
            val initialActive = harness.session.state.value as ScreenCaptureState.Active
            platform.denyNextReplacementSurfaceTextureAllocation()

            harness.session.updateParameters(downscaledParameters)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Suspended }
            assertSuspended(
                state = harness.session.state.value,
                expectedProblem = ScreenCaptureProblem.ResourceExhausted,
                expectedRequested = downscaledParameters,
                expectedLastEffective = initialActive.effectiveParameters,
            )

            harness.session.updateParameters(initialParameters)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val recovered = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(initialParameters, recovered.requestedParameters)
            assertEquals(initialActive.effectiveParameters, recovered.effectiveParameters)
        }
    }

    // Audit item: P2-02
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun maintainableProblemsBeforeFirstActiveFailWithoutSuspension() = runTest {
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

    // Audit item: P2-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun currentProjectionStopBeatsRequestedContenderAndFreezesFinalState() = runTest {
        val platform = HappyCapturePlatform()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            startActiveSessionWithAuthoritativeResize(harness, platform, parameters)
            val active = harness.session.state.value as ScreenCaptureState.Active

            platform.deliverProjectionStopped()
            assertEquals(active, harness.session.state.value)
            harness.session.stop()
            driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Stopped }

            val stopped = harness.session.state.value as ScreenCaptureState.Stopped
            assertSame(ScreenCaptureStopReason.ProjectionStopped, stopped.reason)
            assertEquals(parameters, stopped.requestedParameters)
            assertEquals(active.effectiveParameters, stopped.lastEffectiveParameters)

            drainAcceptedSessionWork(harness)
            harness.session.stop()
            platform.deliverCapturedContentVisibilityChanged(isVisible = true)
            drainAcceptedSessionWork(harness)
            assertEquals(stopped, harness.session.state.value)
        }
    }

    // Audit item: P2-05
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun staleOwnerInvalidatedReadFailsExactlyWhileOrdinaryAdmissionRemainsOpen() = runTest {
        val platform = HappyCapturePlatform()
        val initialParameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            frameRate = FrameRate.Auto,
        )
        val staleRevisionParameters = initialParameters.copy(frameRate = FrameRate.MaxFps(15))
        val latestParameters = initialParameters.copy(frameRate = FrameRate.MaxFps(30))
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            startActiveSession(harness, platform, initialParameters)
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
            assertEquals(active.effectiveParameters, failed.lastEffectiveParameters)
            assertEquals(1L, harness.session.stats.value.droppedFrames.byFailure)

            drainAcceptedSessionWork(harness)
            harness.session.stop()
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
        val platform = HappyCapturePlatform()
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = metrics,
            platformSdkInt = Build.VERSION_CODES.N,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val initialStats = harness.session.stats.value
            // Serialized eager collection is deterministic instrumentation for transient Suspended only; it is
            // not a receipt for product-publication history or ordering.
            val transientStates = CopyOnWriteArrayList<ScreenCaptureState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.state.collect(transientStates::add)
            }
            val start = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { harness.session.start(platform.projection, parameters) }.exceptionOrNull()
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
                assertNull(failed.lastEffectiveParameters)
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
        expectedLastEffective: ScreenCaptureEffectiveParameters,
    ) {
        val suspended = state as ScreenCaptureState.Suspended
        assertSame(expectedProblem, suspended.problem)
        assertEquals(expectedRequested, suspended.requestedParameters)
        assertEquals(expectedLastEffective, suspended.lastEffectiveParameters)
    }

    private fun assertZeroStats(stats: ScreenCaptureStats) {
        assertEquals(0L, stats.encodedFrameCount)
        assertEquals(0L, stats.producedFrameCount)
        assertEquals(0L, stats.droppedFrames.total)
        assertEquals(0L, stats.droppedDeliveries.total)
    }

    private suspend fun startActiveSessionWithAuthoritativeResize(
        harness: SessionStartHarness,
        platform: HappyCapturePlatform,
        parameters: ScreenCaptureParameters,
    ) = coroutineScope {
        val start = async(start = CoroutineStart.UNDISPATCHED) {
            harness.session.start(platform.projection, parameters)
        }
        harness.driveUntil(platform::initialVirtualDisplayReturned)
        platform.deliverCapturedContentResize(widthPx = 8, heightPx = 6)
        harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
        start.await()
    }

    private fun claimNextCaptureTask(harness: SessionStartHarness): Runnable {
        repeat(32) {
            harness.claimNextCaptureTask()?.let { return it }
            harness.enterNextWorkerSuccessfully()
            harness.enterNextControlTask()
        }
        error("Controlled Session work did not expose the accepted Capture read")
    }
}
