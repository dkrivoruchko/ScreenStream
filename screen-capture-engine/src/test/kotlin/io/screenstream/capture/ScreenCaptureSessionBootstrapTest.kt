package io.screenstream.capture

import android.media.projection.MediaProjection
import android.os.Build
import io.mockk.Called
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.screenstream.capture.internal.capture.EglPlatform
import io.screenstream.capture.internal.capture.GlesPlatform
import io.screenstream.capture.internal.capture.ProjectionPlatform
import io.screenstream.capture.internal.capture.TargetPlatform
import io.screenstream.capture.testutil.DispatchOutcome
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.CapturePlatformFixture
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.requestStopAndDrainSession
import io.screenstream.capture.testutil.SessionHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/*
 * Public Bootstrap contract evidence through the real Session Coordinator.
 *
 * Injected platform outcomes and manual task entry only arrange the scenario. The oracles are public start/state
 * outcomes and exact projection ownership settlement, never private Bootstrap checkpoints, queue shape, or call
 * ordering. HandlerThread quit calls prove request cardinality only, never thread termination or cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionBootstrapTest {
    // Verification: SES-08
    // Verification: SES-01
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelledEnteredStopStillStopsNeverStartedSessionAndAnotherWaiterCompletes() = runTest {
        SessionHarness().use { harness ->
            every { harness.projection().stop() } just Runs
            val cancellation = AtomicReference<Throwable?>()
            val cancelled = launch(UnconfinedTestDispatcher(testScheduler)) {
                currentCoroutineContext().cancel()
                cancellation.set(runCatching { harness.session.stop() }.exceptionOrNull())
            }
            cancelled.join()
            assertTrue(cancellation.get() is CancellationException)
            assertTrue(harness.session.state.value is ScreenCaptureState.Stopped)
            val stopped = async(UnconfinedTestDispatcher(testScheduler)) { harness.session.stop() }
            assertFalse(stopped.isCompleted)
            check(harness.enterNextWorkerSuccessfully())
            stopped.await()
            harness.session.stop()
            assertTrue(runCatching { harness.session.start() }.exceptionOrNull() is IllegalStateException)
            verify(exactly = 1) { harness.projection().stop() }
        }
    }

    // Verification: SES-08
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun bootstrapStopFailureIsDurableAndSeparateFromRequestedTerminalState() = runTest {
        SessionHarness().use { harness ->
            val cause = IllegalStateException("bootstrap projection stop failed")
            every { harness.projection().stop() } throws cause
            val stopped = async(UnconfinedTestDispatcher(testScheduler)) { runCatching { harness.session.stop() } }
            assertFalse(stopped.isCompleted)
            check(harness.enterNextWorkerSuccessfully())
            val failure = stopped.await().exceptionOrNull() as ScreenCaptureException
            assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
            assertSame(cause, failure.cause)
            assertSame(failure, runCatching { harness.session.stop() }.exceptionOrNull())
            assertTrue(harness.session.state.value is ScreenCaptureState.Stopped)
            verify(exactly = 1) { harness.projection().stop() }
        }
    }

    // Verification: SES-08
    @Test
    fun rejectedBootstrapStopDispatchFailsWithoutClaimingProjectionCompletion() = runTest {
        SessionHarness(workerOutcome = DispatchOutcome.Reject).use { harness ->
            val failure = runCatching { harness.session.stop() }.exceptionOrNull() as ScreenCaptureException
            assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
            assertSame(failure, runCatching { harness.session.stop() }.exceptionOrNull())
            assertTrue(harness.session.state.value is ScreenCaptureState.Stopped)
            verify(exactly = 0) { harness.projection().stop() }
        }
    }

    // Verification: SES-08
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun failedStartupDoesNotMakeSuccessfulStopFail() = runTest {
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            bootstrapFault = SessionHarness.BootstrapFault.ControlThreadStartThrows,
        ).use { harness ->
            every { harness.projection().stop() } just Runs
            val startup = async(UnconfinedTestDispatcher(testScheduler)) { runCatching { harness.session.start() } }
            check(harness.enterNextWorkerSuccessfully())
            assertTrue(startup.await().exceptionOrNull() is ScreenCaptureException)
            assertTrue(harness.session.state.value is ScreenCaptureState.Failed)
            val terminal = harness.session.state.value
            harness.session.stop()
            assertSame(terminal, harness.session.state.value)
            verify(exactly = 1) { harness.projection().stop() }
            harness.drainWorkerTasks()
        }
    }

    // Verification: SES-01
    // Verification: BSP-01
    @Test
    fun controlThreadStartFailureFailsPublicStartAndReleasesProjectionOnce() = runTest {
        assertFatalBootstrapFault(
            fault = SessionHarness.BootstrapFault.ControlThreadStartThrows,
            expectedControlQuitRequests = 1,
            expectedCaptureQuitRequests = 0,
        )
    }

    // Verification: SES-01
    // Verification: BSP-02
    @Test
    fun missingControlLooperFailsPublicStartAndReleasesProjectionOnce() = runTest {
        assertFatalBootstrapFault(
            fault = SessionHarness.BootstrapFault.ControlLooperReturnsNull,
            expectedControlQuitRequests = 1,
            expectedCaptureQuitRequests = 0,
        )
    }

    // Verification: SES-01
    // Verification: BSP-03
    @Test
    fun controlHandlerConstructionFailureFailsPublicStartAndReleasesProjectionOnce() = runTest {
        assertFatalBootstrapFault(
            fault = SessionHarness.BootstrapFault.ControlHandlerConstructionThrows,
            expectedControlQuitRequests = 1,
            expectedCaptureQuitRequests = 0,
        )
    }

    // Verification: SES-01
    // Verification: BSP-04
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rejectedFirstControlPostFailsStartAndRetiresProjectionWithoutSeparateRequestStop() = runTest {
        val platforms = CapturePlatformProbes()
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            bootstrapFault = SessionHarness.BootstrapFault.FirstControlPostReturnsFalse,
            projectionPlatform = platforms.projection,
            eglPlatform = platforms.egl,
            glesPlatform = platforms.gles,
            targetPlatform = platforms.target,
        ).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val initialStats = harness.session.stats.value
            val start = async {
                runCatching { harness.session.start() }.exceptionOrNull()
            }

            try {
                testScheduler.runCurrent()
                val bootstrapWorker = harness.enterNextWorker() ?: error("Accepted Bootstrap work was not retained")
                bootstrapWorker.awaitSuccessfulCompletion()
                testScheduler.runCurrent()

                // Test-seam receipt only: it validates that the arranged post(false) outcome was consumed.
                assertSame(
                    SessionHarness.BootstrapFault.FirstControlPostReturnsFalse,
                    harness.consumedBootstrapFault(),
                )
                val terminal = harness.session.state.value as ScreenCaptureState.Failed
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureProblem.InternalFailure, terminal.problem)
                assertEquals(initialStats, terminalStats)
                val startFailure = start.await() as ScreenCaptureException
                assertSame(ScreenCaptureProblem.InternalFailure, startFailure.problem)
                assertEquals(initialStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify(exactly = 1) { projection.stop() }
                harness.drainWorkerTasks()

                assertEquals(terminal, harness.session.state.value)
                assertEquals(terminalStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify(exactly = 1) { harness.controlThread().quitSafely() }
                verify(exactly = 1) { harness.captureThread().quitSafely() }
                confirmVerified(projection)
            } finally {
                try {
                    requestStopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    // Verification: SES-01
    // Verification: BSP-04
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun acceptedFirstControlPostMayEnterDuringCallAndRetiresProjectionOnce() = runTest {
        val platform = CapturePlatformFixture()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val projectionStopReturned = AtomicBoolean(false)
        every { platform.projection.stop() } just Runs
        every { platform.projectionPlatform.unregisterCallback(refEq(platform.projection), any()) } just Runs
        every { platform.projectionPlatform.stop(refEq(platform.projection)) } answers {
            projectionStopReturned.set(true)
        }
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            bootstrapFault = SessionHarness.BootstrapFault.FirstControlPostEntersDuringCall,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val initialStats = harness.session.stats.value
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                runCatching { harness.session.start(parameters) }.exceptionOrNull()
            }

            try {
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }

                assertSame(
                    SessionHarness.BootstrapFault.FirstControlPostEntersDuringCall,
                    harness.consumedBootstrapFault(),
                )
                assertTrue(start.isCompleted)
                assertNull(start.await())
                assertTrue(harness.session.state.value is ScreenCaptureState.Active)
                assertEquals(initialStats, harness.session.stats.value)

                harness.session.requestStop()
                requestStopAndDrainSession(harness)

                val terminal = harness.session.state.value as ScreenCaptureState.Stopped
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, terminal.reason)
                assertEquals(initialStats, terminalStats)
                assertTrue("Projection stop did not return before capture retirement completed", projectionStopReturned.get())
                verify(exactly = 1) { platform.projectionPlatform.stop(refEq(platform.projection)) }
                verify(exactly = 1) { platform.projectionPlatform.unregisterCallback(refEq(platform.projection), any()) }
                verify(exactly = 0) { platform.projection.stop() }
            } finally {
                try {
                    requestStopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    // Verification: SES-01
    // Verification: BSP-05
    @Test
    fun thrownFirstControlPostFailsPublicStartAndReleasesProjectionOnce() = runTest {
        assertFatalBootstrapFault(
            fault = SessionHarness.BootstrapFault.FirstControlPostThrows,
            expectedControlQuitRequests = 1,
            expectedCaptureQuitRequests = 1,
        )
    }

    // Verification: SES-01
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun requestStopBeforeBootstrapWorkerEntryMakesLateWorkerCleanupOnly() = runTest {
        val platforms = CapturePlatformProbes()
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            projectionPlatform = platforms.projection,
            eglPlatform = platforms.egl,
            glesPlatform = platforms.gles,
            targetPlatform = platforms.target,
        ).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val initialStats = harness.session.stats.value
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                runCatching { harness.session.start() }.exceptionOrNull()
            }

            try {
                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertFalse(start.isCompleted)
                assertEquals(initialStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                harness.session.requestStop()

                val terminal = harness.session.state.value as ScreenCaptureState.Stopped
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, terminal.reason)
                assertEquals(initialStats, terminalStats)
                assertTrue(start.await() is CancellationException)
                platforms.verifyUntouched()

                val lateWorker = harness.enterNextWorker() ?: error("Accepted bootstrap worker was not retained")
                lateWorker.awaitSuccessfulCompletion()

                assertEquals(terminal, harness.session.state.value)
                assertEquals(terminalStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify(exactly = 1) { projection.stop() }
                confirmVerified(projection)
            } finally {
                try {
                    requestStopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    // Verification: SES-01
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun requestStopBeforeAcceptedFirstControlEntryMakesLateEntryCleanupOnly() = runTest {
        val platforms = CapturePlatformProbes()
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            projectionPlatform = platforms.projection,
            eglPlatform = platforms.egl,
            glesPlatform = platforms.gles,
            targetPlatform = platforms.target,
        ).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val initialStats = harness.session.stats.value
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                runCatching { harness.session.start() }.exceptionOrNull()
            }

            try {
                val bootstrapWorker = harness.enterNextWorker() ?: error("Accepted bootstrap worker was not retained")
                bootstrapWorker.awaitSuccessfulCompletion()
                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertFalse(start.isCompleted)
                assertEquals(initialStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                harness.session.requestStop()

                val terminal = harness.session.state.value as ScreenCaptureState.Stopped
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, terminal.reason)
                assertEquals(initialStats, terminalStats)
                assertTrue(start.await() is CancellationException)
                platforms.verifyUntouched()

                check(harness.enterNextControlTask()) { "Accepted first Control work was not retained" }
                val lateWorker = harness.enterNextWorker() ?: error("Accepted projection retirement was not retained")
                lateWorker.awaitSuccessfulCompletion()

                assertEquals(terminal, harness.session.state.value)
                assertEquals(terminalStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify(exactly = 1) { projection.stop() }
                confirmVerified(projection)
            } finally {
                try {
                    requestStopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    // Verification: SES-01
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun requestStopDuringBootstrapSkipsQueuedPlatformOpen() = runTest {
        val platforms = CapturePlatformProbes()
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            projectionPlatform = platforms.projection,
            eglPlatform = platforms.egl,
            glesPlatform = platforms.gles,
            targetPlatform = platforms.target,
        ).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            platforms.allowProjectionCleanup(projection)
            val initialStats = harness.session.stats.value
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                runCatching { harness.session.start() }.exceptionOrNull()
            }

            try {
                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertFalse(start.isCompleted)
                assertEquals(initialStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                driveWorkerAndControlUntilCaptureBoundary(harness)

                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertFalse(start.isCompleted)
                assertEquals(initialStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                harness.session.requestStop()
                driveControlUntilStopped(harness)

                val terminal = harness.session.state.value as ScreenCaptureState.Stopped
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, terminal.reason)
                assertEquals(initialStats, terminalStats)
                assertTrue(start.await() is CancellationException)
                platforms.verifyUntouched()

                check(harness.enterNextCaptureTask()) { "Accepted Capture work was not retained" }
                val lateWorker = harness.enterNextWorker() ?: error("Accepted projection retirement was not retained")
                lateWorker.awaitSuccessfulCompletion()

                assertEquals(terminal, harness.session.state.value)
                assertEquals(terminalStats, harness.session.stats.value)
                platforms.verifyProjectionCleanupOnly(projection)
                verify { projection wasNot Called }
                confirmVerified(projection)
            } finally {
                try {
                    requestStopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    // Verification: SES-01
    // Verification: MET-01
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun requestStopDuringMetricsSubscribeClosesLateHandleOnce() = runTest {
        SessionHarness(bootstrapMode = SessionHarness.BootstrapMode.BlockingMetrics).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val callbackCount = AtomicInteger()
            harness.session.registerFrameConsumer { callbackCount.incrementAndGet() }
            val initialStats = harness.session.stats.value
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                runCatching { harness.session.start() }.exceptionOrNull()
            }

            try {
                check(harness.enterNextWorkerSuccessfully()) { "Bootstrap worker did not complete" }
                check(harness.enterNextControlTask()) { "First Control work was not retained" }

                val metricsTask = harness.enterNextWorker() ?: error("Metrics attachment work was not retained")
                assertTrue(metricsTask.awaitEntered())
                assertTrue(harness.awaitMetricsSubscribeEntered())
                assertEquals(1, harness.metricsSubscriptionCount())
                assertEquals(0, harness.metricsHandleCloseCount())
                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertEquals(initialStats, harness.session.stats.value)
                assertFalse(start.isCompleted)
                verify { projection wasNot Called }

                harness.session.requestStop()
                driveControlUntilStopped(harness)

                val terminal = harness.session.state.value as ScreenCaptureState.Stopped
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, terminal.reason)
                assertEquals(initialStats, terminalStats)
                assertEquals(0, harness.metricsHandleCloseCount())
                assertEquals(0, callbackCount.get())
                assertTrue(start.await() is CancellationException)

                harness.releaseMetricsSubscribeReturn()
                metricsTask.awaitSuccessfulCompletion()
                driveWorkersUntil(harness) { harness.metricsHandleCloseCount() == 1 }

                check(harness.enterNextCaptureTask()) { "Accepted Capture cleanup was not retained" }

                assertSame(terminal, harness.session.state.value)
                assertEquals(terminalStats, harness.session.stats.value)
                assertEquals(1, harness.metricsHandleCloseCount())
                assertEquals(0, callbackCount.get())
                verify(exactly = 1) { projection.stop() }
                confirmVerified(projection)
            } finally {
                try {
                    harness.releaseMetricsSubscribeReturn()
                    requestStopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.assertFatalBootstrapFault(
        fault: SessionHarness.BootstrapFault,
        expectedControlQuitRequests: Int,
        expectedCaptureQuitRequests: Int,
    ) {
        val platforms = CapturePlatformProbes()
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            bootstrapFault = fault,
            projectionPlatform = platforms.projection,
            eglPlatform = platforms.egl,
            glesPlatform = platforms.gles,
            targetPlatform = platforms.target,
        ).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val initialStats = harness.session.stats.value
            val start = async {
                runCatching { harness.session.start() }.exceptionOrNull()
            }

            try {
                testScheduler.runCurrent()
                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertFalse(start.isCompleted)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                val bootstrapWorker = harness.enterNextWorker() ?: error("Accepted Bootstrap work was not retained")
                bootstrapWorker.awaitSuccessfulCompletion()
                harness.drainWorkerTasks()
                testScheduler.runCurrent()

                assertSame(fault, harness.consumedBootstrapFault())
                val terminal = harness.session.state.value as ScreenCaptureState.Failed
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureProblem.InternalFailure, terminal.problem)
                assertEquals(initialStats, terminalStats)
                val startFailure = start.await() as ScreenCaptureException
                assertSame(ScreenCaptureProblem.InternalFailure, startFailure.problem)
                platforms.verifyUntouched()
                verify(exactly = expectedControlQuitRequests) { harness.controlThread().quitSafely() }
                verify(exactly = expectedCaptureQuitRequests) { harness.captureThread().quitSafely() }
                verify(exactly = 1) { projection.stop() }
                confirmVerified(projection)
            } finally {
                try {
                    requestStopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    private fun driveWorkerAndControlUntilCaptureBoundary(harness: SessionHarness) {
        repeat(ACCEPTED_WORK_LIMIT) {
            val worker = harness.enterNextWorker()
            if (worker != null) {
                worker.awaitSuccessfulCompletion()
                return@repeat
            }
            if (harness.enterNextControlTask()) return@repeat
            return
        }
        error("Worker and Control work did not reach the bounded Capture boundary")
    }

    private fun driveControlUntilStopped(harness: SessionHarness) {
        repeat(ACCEPTED_WORK_LIMIT) {
            if (harness.session.state.value is ScreenCaptureState.Stopped) return
            check(harness.enterNextControlTask()) { "Control work became idle before terminal publication" }
        }
        check(harness.session.state.value is ScreenCaptureState.Stopped) {
            "Control work did not reach terminal publication within the bounded drive"
        }
    }

    private fun driveWorkersUntil(harness: SessionHarness, condition: () -> Boolean) {
        repeat(ACCEPTED_WORK_LIMIT) {
            if (condition()) return
            val worker = harness.enterNextWorker() ?: error("Worker work became idle before the owner boundary settled")
            worker.awaitSuccessfulCompletion()
        }
        check(condition()) { "Worker work did not settle the owner boundary within the bounded drive" }
    }

    private fun requestStopAndDrainAcceptedWork(harness: SessionHarness) {
        harness.session.requestStop()
        repeat(ACCEPTED_WORK_LIMIT) {
            var progressed = false
            harness.enterNextWorker()?.let { worker ->
                worker.awaitSuccessfulCompletion()
                progressed = true
            }
            progressed = harness.enterNextControlTask() || progressed
            progressed = harness.enterNextCaptureTask() || progressed
            if (!progressed) return
        }
        error("Accepted cleanup work did not drain within the bounded limit")
    }

    private class CapturePlatformProbes {
        val projection: ProjectionPlatform = mockk()
        val egl: EglPlatform = mockk()
        val gles: GlesPlatform = mockk()
        val target: TargetPlatform = mockk()

        fun allowProjectionCleanup(mediaProjection: MediaProjection) {
            every { projection.stop(refEq(mediaProjection)) } just Runs
        }

        fun verifyUntouched() {
            verify { projection wasNot Called }
            verify { egl wasNot Called }
            verify { gles wasNot Called }
            verify { target wasNot Called }
        }

        fun verifyProjectionCleanupOnly(mediaProjection: MediaProjection) {
            verify(exactly = 1) { projection.stop(refEq(mediaProjection)) }
            confirmVerified(projection)
            verify { egl wasNot Called }
            verify { gles wasNot Called }
            verify { target wasNot Called }
        }
    }

    private companion object {
        private const val ACCEPTED_WORK_LIMIT = 32
    }
}
