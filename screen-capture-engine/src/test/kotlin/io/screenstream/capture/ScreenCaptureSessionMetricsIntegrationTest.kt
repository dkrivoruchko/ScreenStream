package io.screenstream.capture

import android.os.Build
import io.screenstream.capture.testutil.ControllableMetricsSource
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.CoordinatorMetricsHarness
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.CapturePlatformFixture
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.N])
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionMetricsIntegrationTest {
    // Verification: MET-03
    // Verification: SES-02
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun sourceFailureDuringOpenAdmissionFailsStartWithInternalFailure() = runTest {
        val source = ControllableMetricsSource()
        val platform = CapturePlatformFixture()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                try {
                    harness.session.start(parameters)
                    null
                } catch (failure: ScreenCaptureException) {
                    failure
                }
            }
            harness.driveUntil(source::isSubscribed)

            source.fail(IllegalStateException("expected Metrics failure"))
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }
            harness.driveUntil { source.handleCloseCount() == 1 }

            val failed = harness.session.state.value as ScreenCaptureState.Failed
            val startFailure = checkNotNull(start.await())
            assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
            assertEquals(parameters, failed.requestedParameters)
            assertNull(failed.lastOutputInfo)
            assertSame(ScreenCaptureProblem.InternalFailure, startFailure.problem)
            assertEquals(1, source.handleCloseCount())
        }
    }

    // Verification: MET-03
    // Verification: SES-02
    // Verification: SES-03
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun preActiveCompletionSettlesFromFrozenAvailabilityBeforeExactHandleCloseReturns() = runTest {
        // Completed unavailable metrics fail startup while the exact handle close remains entered.
        run {
            val closeEntered = CountDownLatch(1)
            val allowCloseReturn = CountDownLatch(1)
            val source = ControllableMetricsSource {
                closeEntered.countDown()
                allowCloseReturn.await()
            }
            val platform = CapturePlatformFixture()
            val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
            var closeTask: ControlledNonInlineDispatcher.TaskHandle? = null

            CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
                try {
                    val start = async(UnconfinedTestDispatcher(testScheduler)) {
                        try {
                            harness.session.start(parameters)
                            null
                        } catch (failure: ScreenCaptureException) {
                            failure
                        }
                    }
                    harness.driveUntil(source::isSubscribed)

                    source.complete()
                    harness.settleNextMetricsChange()
                    closeTask = harness.enterNextWorker()
                    assertTrue(closeEntered.await(5L, TimeUnit.SECONDS))
                    harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }

                    val failed = harness.session.state.value as ScreenCaptureState.Failed
                    val startFailure = checkNotNull(start.await())
                    assertSame(ScreenCaptureProblem.CaptureUnavailable, failed.problem)
                    assertEquals(parameters, failed.requestedParameters)
                    assertNull(failed.lastOutputInfo)
                    assertSame(ScreenCaptureProblem.CaptureUnavailable, startFailure.problem)
                    assertEquals(1, source.handleCloseCount())
                    assertFalse(closeTask.completed)
                } finally {
                    allowCloseReturn.countDown()
                    closeTask?.awaitSuccessfulCompletion()
                }
            }
            assertEquals(1, source.handleCloseCount())
        }

        // Completed positive metrics can produce first Active while the exact handle close remains entered.
        run {
            val closeEntered = CountDownLatch(1)
            val allowCloseReturn = CountDownLatch(1)
            val source = ControllableMetricsSource {
                closeEntered.countDown()
                allowCloseReturn.await()
            }
            val platform = CapturePlatformFixture()
            val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
            val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
            var closeTask: ControlledNonInlineDispatcher.TaskHandle? = null

            CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
                try {
                    val startReturned = AtomicBoolean()
                    val start = async(UnconfinedTestDispatcher(testScheduler)) {
                        harness.session.start(parameters)
                        startReturned.set(true)
                        harness.session.state.value
                    }
                    harness.driveUntil(source::isSubscribed)

                    source.emit(metrics)
                    source.complete()
                    harness.settleNextMetricsChange()
                    closeTask = harness.enterNextWorker()
                    assertTrue(closeEntered.await(5L, TimeUnit.SECONDS))
                    harness.driveUntil { startReturned.get() }

                    val active = start.await() as ScreenCaptureState.Active
                    assertSame(active, harness.session.state.value)
                    assertEquals(parameters, active.requestedParameters)
                    assertEquals(metrics.widthPx, active.outputInfo.captureGeometry.widthPx)
                    assertEquals(metrics.heightPx, active.outputInfo.captureGeometry.heightPx)
                    assertEquals(metrics.densityDpi, active.outputInfo.captureGeometry.densityDpi)
                    assertEquals(1, source.handleCloseCount())
                    assertFalse(closeTask.completed)
                } finally {
                    allowCloseReturn.countDown()
                    closeTask?.awaitSuccessfulCompletion()
                }
            }
            assertEquals(1, source.handleCloseCount())
        }
    }

    // Verification: MET-03
    // Verification: SES-03
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun postActiveCompletionPreservesPositiveOrUnavailableNonterminalState() = runTest {
        // Completion retains the positive metrics and current Active state after handle close.
        run {
            val source = ControllableMetricsSource()
            val platform = CapturePlatformFixture()
            val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
            val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

            CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
                val start = async(UnconfinedTestDispatcher(testScheduler)) {
                    harness.session.start(parameters)
                    harness.session.state.value
                }
                harness.driveUntil(source::isSubscribed)
                source.emit(metrics)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
                val active = start.await() as ScreenCaptureState.Active

                source.complete()
                harness.driveUntil { source.handleCloseCount() == 1 }
                harness.drainAcceptedWork()

                assertEquals(active, harness.session.state.value)
                assertEquals(1, source.handleCloseCount())
            }
        }

        // Completion after unavailable metrics retains the current Suspended state.
        run {
            val source = ControllableMetricsSource()
            val platform = CapturePlatformFixture()
            val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
            val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

            CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
                val start = async(UnconfinedTestDispatcher(testScheduler)) {
                    harness.session.start(parameters)
                    harness.session.state.value
                }
                harness.driveUntil(source::isSubscribed)
                source.emit(metrics)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
                start.await() as ScreenCaptureState.Active

                source.emit(null)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Suspended }
                val suspended = harness.session.state.value as ScreenCaptureState.Suspended
                assertSame(ScreenCaptureProblem.CaptureUnavailable, suspended.problem)

                source.complete()
                harness.driveUntil { source.handleCloseCount() == 1 }
                harness.drainAcceptedWork()

                assertEquals(suspended, harness.session.state.value)
                assertEquals(1, source.handleCloseCount())
            }
        }
    }

    // Verification: MET-03
    // Verification: SES-02
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun ordinaryCompletionCloseFailureAfterActiveFailsTheStillOpenSession() = runTest {
        val closeFailure = IllegalStateException("expected Metrics close failure")
        val source = ControllableMetricsSource { throw closeFailure }
        val platform = CapturePlatformFixture()
        val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(parameters)
                harness.session.state.value
            }
            harness.driveUntil(source::isSubscribed)
            source.emit(metrics)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val active = start.await() as ScreenCaptureState.Active

            source.complete()
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }

            val failed = harness.session.state.value as ScreenCaptureState.Failed
            assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
            assertEquals(active.outputInfo, failed.lastOutputInfo)
            assertEquals(1, source.handleCloseCount())
        }
    }

    // Verification: MET-03
    // Verification: SES-03
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun reentrantActiveInvalidationCannotSettleStaleStartAndCurrentRecoverySucceeds() = runTest {
        val source = ControllableMetricsSource()
        val platform = CapturePlatformFixture()
        val initialMetrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val recoveredMetrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        CoordinatorMetricsHarness(source, platform, Build.VERSION_CODES.N).use { harness ->
            val invalidated = AtomicBoolean()
            val startReturned = AtomicBoolean()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.state.collect { state ->
                    if (state is ScreenCaptureState.Active && invalidated.compareAndSet(false, true)) {
                        // Make a real source opportunity available before the reentrant Metrics invalidation.
                        // The stale Active settlement must keep Capture production closed despite that opportunity.
                        platform.deliverSourceFrame(rgbaSeed = 41)
                        source.emit(null)
                    }
                }
            }
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(parameters)
                startReturned.set(true)
                harness.session.state.value
            }
            harness.driveUntil(source::isSubscribed)

            source.emit(initialMetrics)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Suspended }
            harness.drainAcceptedWork()

            val suspended = harness.session.state.value as ScreenCaptureState.Suspended
            assertTrue(invalidated.get())
            assertFalse(startReturned.get())
            assertSame(ScreenCaptureProblem.CaptureUnavailable, suspended.problem)
            assertEquals(0, platform.sourceUpdateCount())

            source.emit(recoveredMetrics)
            harness.driveUntil { startReturned.get() }

            val active = start.await() as ScreenCaptureState.Active
            assertSame(active, harness.session.state.value)
            assertEquals(recoveredMetrics.widthPx, active.outputInfo.captureGeometry.widthPx)
            assertEquals(recoveredMetrics.heightPx, active.outputInfo.captureGeometry.heightPx)
            assertEquals(recoveredMetrics.densityDpi, active.outputInfo.captureGeometry.densityDpi)

            platform.deliverSourceFrame(rgbaSeed = 83)
            harness.driveUntil { platform.sourceUpdateCount() > 0 }
            assertEquals(1, platform.sourceUpdateCount())
        }
    }
}
