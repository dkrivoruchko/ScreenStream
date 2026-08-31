package io.screenstream.capture

import android.media.projection.MediaProjection
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
import io.screenstream.capture.testutil.SessionStartHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicInteger

/*
 * Public Bootstrap contract evidence through the real Session Coordinator.
 *
 * Injected platform outcomes and manual task entry only arrange the scenario. The oracles are public start/state
 * outcomes and exact projection ownership settlement, never private Bootstrap checkpoints, queue shape, or call
 * ordering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionBootstrapTest {
    // Verification: SES-01
    // Verification: BSP-01
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun controlThreadStartFailureFailsPublicStartAndReleasesProjectionOnce() = runTest {
        assertFatalBootstrapFault(SessionStartHarness.BootstrapFault.ControlThreadStartThrows)
    }

    // Verification: SES-01
    // Verification: BSP-02
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun missingControlLooperFailsPublicStartAndReleasesProjectionOnce() = runTest {
        assertFatalBootstrapFault(SessionStartHarness.BootstrapFault.ControlLooperReturnsNull)
    }

    // Verification: SES-01
    // Verification: BSP-03
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun controlHandlerConstructionFailureFailsPublicStartAndReleasesProjectionOnce() = runTest {
        assertFatalBootstrapFault(SessionStartHarness.BootstrapFault.ControlHandlerConstructionThrows)
    }

    // Verification: SES-01
    // Verification: BSP-04
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rejectedFirstControlPostKeepsStartAndProjectionPendingUntilSeparateStop() = runTest {
        val platforms = CapturePlatformProbes()
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            bootstrapFault = SessionStartHarness.BootstrapFault.FirstControlPostReturnsFalse,
            projectionPlatform = platforms.projection,
            eglPlatform = platforms.egl,
            glesPlatform = platforms.gles,
            targetPlatform = platforms.target,
        ).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val initialStats = harness.session.stats.value
            val start = async {
                runCatching { harness.session.start(projection) }.exceptionOrNull()
            }

            try {
                testScheduler.runCurrent()
                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertFalse(start.isCompleted)

                val bootstrapWorker = harness.enterNextWorker() ?: error("Accepted Bootstrap work was not retained")
                bootstrapWorker.awaitSuccessfulCompletion()
                testScheduler.runCurrent()

                // Test-seam receipt only: it validates that the arranged post(false) outcome was consumed.
                assertSame(
                    SessionStartHarness.BootstrapFault.FirstControlPostReturnsFalse,
                    harness.consumedBootstrapFault(),
                )
                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertFalse(start.isCompleted)
                assertEquals(initialStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                harness.session.stop()
                testScheduler.runCurrent()

                val terminal = harness.session.state.value as ScreenCaptureState.Stopped
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, terminal.reason)
                assertEquals(initialStats, terminalStats)
                val startFailure = start.await() as ScreenCaptureException
                assertSame(ScreenCaptureProblem.CaptureUnavailable, startFailure.problem)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                val retirementWorker = harness.enterNextWorker() ?: error("Bootstrap retirement work was not retained")
                retirementWorker.awaitSuccessfulCompletion()
                harness.drainWorkerTasks()

                assertEquals(terminal, harness.session.state.value)
                assertEquals(terminalStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify(exactly = 1) { projection.stop() }
                confirmVerified(projection)
            } finally {
                try {
                    stopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    // Verification: SES-01
    // Verification: BSP-05
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun thrownFirstControlPostFailsPublicStartAndReleasesProjectionOnce() = runTest {
        assertFatalBootstrapFault(SessionStartHarness.BootstrapFault.FirstControlPostThrows)
    }

    // Verification: SES-01
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopBeforeBootstrapWorkerEntryMakesLateWorkerCleanupOnly() = runTest {
        val platforms = CapturePlatformProbes()
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            projectionPlatform = platforms.projection,
            eglPlatform = platforms.egl,
            glesPlatform = platforms.gles,
            targetPlatform = platforms.target,
        ).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val initialStats = harness.session.stats.value
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                runCatching { harness.session.start(projection) }.exceptionOrNull()
            }

            try {
                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertFalse(start.isCompleted)
                assertEquals(initialStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                harness.session.stop()

                val terminal = harness.session.state.value as ScreenCaptureState.Stopped
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, terminal.reason)
                assertEquals(initialStats, terminalStats)
                val startFailure = start.await() as ScreenCaptureException
                assertSame(ScreenCaptureProblem.CaptureUnavailable, startFailure.problem)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                val lateWorker = harness.enterNextWorker() ?: error("Accepted bootstrap worker was not retained")
                lateWorker.awaitSuccessfulCompletion()

                assertEquals(terminal, harness.session.state.value)
                assertEquals(terminalStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify(exactly = 1) { projection.stop() }
                confirmVerified(projection)
            } finally {
                try {
                    stopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    // Verification: SES-01
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopBeforeAcceptedFirstControlEntryMakesLateEntryCleanupOnly() = runTest {
        val platforms = CapturePlatformProbes()
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            projectionPlatform = platforms.projection,
            eglPlatform = platforms.egl,
            glesPlatform = platforms.gles,
            targetPlatform = platforms.target,
        ).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val initialStats = harness.session.stats.value
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                runCatching { harness.session.start(projection) }.exceptionOrNull()
            }

            try {
                val bootstrapWorker = harness.enterNextWorker() ?: error("Accepted bootstrap worker was not retained")
                bootstrapWorker.awaitSuccessfulCompletion()
                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                assertFalse(start.isCompleted)
                assertEquals(initialStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                harness.session.stop()

                val terminal = harness.session.state.value as ScreenCaptureState.Stopped
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, terminal.reason)
                assertEquals(initialStats, terminalStats)
                val startFailure = start.await() as ScreenCaptureException
                assertSame(ScreenCaptureProblem.CaptureUnavailable, startFailure.problem)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                check(harness.enterNextControlTask()) { "Accepted first Control work was not retained" }

                assertEquals(terminal, harness.session.state.value)
                assertEquals(terminalStats, harness.session.stats.value)
                platforms.verifyUntouched()
                verify(exactly = 1) { projection.stop() }
                confirmVerified(projection)
            } finally {
                try {
                    stopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    // Verification: SES-01
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopDuringBootstrapSkipsQueuedPlatformOpen() = runTest {
        val platforms = CapturePlatformProbes()
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
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
                runCatching { harness.session.start(projection) }.exceptionOrNull()
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

                harness.session.stop()
                driveControlUntilStopped(harness)

                val terminal = harness.session.state.value as ScreenCaptureState.Stopped
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, terminal.reason)
                assertEquals(initialStats, terminalStats)
                val startFailure = start.await() as ScreenCaptureException
                assertSame(ScreenCaptureProblem.CaptureUnavailable, startFailure.problem)
                platforms.verifyUntouched()
                verify { projection wasNot Called }

                check(harness.enterNextCaptureTask()) { "Accepted Capture work was not retained" }

                assertEquals(terminal, harness.session.state.value)
                assertEquals(terminalStats, harness.session.stats.value)
                platforms.verifyProjectionCleanupOnly(projection)
                verify { projection wasNot Called }
                confirmVerified(projection)
            } finally {
                try {
                    stopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    // Verification: SES-01
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopDuringMetricsSubscribeClosesLateHandleOnce() = runTest {
        SessionStartHarness(bootstrapMode = SessionStartHarness.BootstrapMode.BlockingMetrics).use { harness ->
            val projection = harness.projection()
            every { projection.stop() } just Runs
            val callbackCount = AtomicInteger()
            harness.session.registerFrameConsumer { callbackCount.incrementAndGet() }
            val initialStats = harness.session.stats.value
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                runCatching { harness.session.start(projection) }.exceptionOrNull()
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

                harness.session.stop()
                driveControlUntilStopped(harness)

                val terminal = harness.session.state.value as ScreenCaptureState.Stopped
                val terminalStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, terminal.reason)
                assertEquals(initialStats, terminalStats)
                assertEquals(0, harness.metricsHandleCloseCount())
                assertEquals(0, callbackCount.get())
                val startFailure = start.await() as ScreenCaptureException
                assertSame(ScreenCaptureProblem.CaptureUnavailable, startFailure.problem)
                verify { projection wasNot Called }

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
                    stopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.assertFatalBootstrapFault(fault: SessionStartHarness.BootstrapFault) {
        val platforms = CapturePlatformProbes()
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
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
                runCatching { harness.session.start(projection) }.exceptionOrNull()
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
                verify(exactly = 1) { projection.stop() }
                confirmVerified(projection)
            } finally {
                try {
                    stopAndDrainAcceptedWork(harness)
                } finally {
                    start.cancelAndJoin()
                }
            }
        }
    }

    private fun driveWorkerAndControlUntilCaptureBoundary(harness: SessionStartHarness) {
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

    private fun driveControlUntilStopped(harness: SessionStartHarness) {
        repeat(ACCEPTED_WORK_LIMIT) {
            if (harness.session.state.value is ScreenCaptureState.Stopped) return
            check(harness.enterNextControlTask()) { "Control work became idle before terminal publication" }
        }
        check(harness.session.state.value is ScreenCaptureState.Stopped) {
            "Control work did not reach terminal publication within the bounded drive"
        }
    }

    private fun driveWorkersUntil(harness: SessionStartHarness, condition: () -> Boolean) {
        repeat(ACCEPTED_WORK_LIMIT) {
            if (condition()) return
            val worker = harness.enterNextWorker() ?: error("Worker work became idle before the owner boundary settled")
            worker.awaitSuccessfulCompletion()
        }
        check(condition()) { "Worker work did not settle the owner boundary within the bounded drive" }
    }

    private fun stopAndDrainAcceptedWork(harness: SessionStartHarness) {
        harness.session.stop()
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
