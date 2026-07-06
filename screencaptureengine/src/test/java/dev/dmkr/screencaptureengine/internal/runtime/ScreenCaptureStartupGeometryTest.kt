package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenCaptureStartupGeometryTest {
    @Test
    fun startupGeometryGateChoosesProjectionStopBeforeCallerCancellationResizeAndTimeout() {
        val decision = StartupGeometryGate.decide(
            StartupGeometryGateSnapshot(
                projectionStopped = true,
                callerActive = false,
                firstValidResize = StartupCapturedContentResize.unidentified(width = 800, height = 600),
                timeoutObserved = true,
            ),
        )

        assertEquals(StartupGeometryDecision.ProjectionStopped, decision)
    }

    @Test
    fun startupGeometryGateChoosesCallerCancellationBeforeResizeAndTimeout() {
        val decision = StartupGeometryGate.decide(
            StartupGeometryGateSnapshot(
                projectionStopped = false,
                callerActive = false,
                firstValidResize = StartupCapturedContentResize.unidentified(width = 800, height = 600),
                timeoutObserved = true,
            ),
        )

        assertEquals(StartupGeometryDecision.CallerCancelled, decision)
    }

    @Test
    fun startupGeometryGateChoosesResizeBeforeTimeout() {
        val decision = StartupGeometryGate.decide(
            StartupGeometryGateSnapshot(
                projectionStopped = false,
                callerActive = true,
                firstValidResize = StartupCapturedContentResize.unidentified(width = 800, height = 600),
                timeoutObserved = true,
            ),
        )

        val resizeDecision = decision as StartupGeometryDecision.FirstValidResize
        assertEquals(800, resizeDecision.resize.width)
        assertEquals(600, resizeDecision.resize.height)
    }

    @Test
    fun startupGeometryAwaitDecisionChoosesCallerCancellationBeforeAlreadyRecordedResize() = runTest {
        val arbiter = StartupGeometryArbiter(StartupProjectionLifecycle(FakeProjectionHandle(), cleanupFailureSink = {}))
        arbiter.recordCapturedContentResize(StartupCapturedContentResize.unidentified(width = 800, height = 600))
        val callerJob = Job().also { it.cancel() }

        val decision = arbiter.awaitDecision(callerJob)

        assertEquals(StartupGeometryDecision.CallerCancelled, decision)
    }

    @Test
    fun startupGeometryAwaitDecisionChoosesProjectionStopBeforeCallerCancellation() = runTest {
        val arbiter = StartupGeometryArbiter(StartupProjectionLifecycle(FakeProjectionHandle(), cleanupFailureSink = {}))
        arbiter.recordProjectionStopped()
        val callerJob = Job().also { it.cancel() }

        val decision = arbiter.awaitDecision(callerJob)

        assertEquals(StartupGeometryDecision.ProjectionStopped, decision)
    }


    @Test
    fun api33SuccessUsesMetricsGeometry() = runTest {
        val runtime = TestRuntime(apiLevel = 33)

        val resources = runtime.start()
        val owner = resources.transferToPreActiveRuntimeOwner()
        try {
            runCurrent()

            assertEquals(CaptureGeometrySource.MetricsProvider, owner.startupGeometry.source)
            assertEquals(1080, owner.startupGeometry.widthPx)
            assertEquals(1920, owner.startupGeometry.heightPx)
            assertEquals(440, owner.startupGeometry.densityDpi)
            assertMilestonesInOrder(
                owner.milestones,
                ScreenCaptureStartupMilestone.ValidatedInputs,
                ScreenCaptureStartupMilestone.ProjectionTargetReady,
                ScreenCaptureStartupMilestone.ProjectionCallbackAttached,
                ScreenCaptureStartupMilestone.VirtualDisplayAttempted,
                ScreenCaptureStartupMilestone.VirtualDisplayOwned,
                ScreenCaptureStartupMilestone.AuthoritativeStartupGeometryReady,
            )
            assertEquals(1, runtime.virtualDisplayCreateCount)
            assertEquals(1, runtime.metricsProvider.activeCollectorCount)
            assertEquals(listOf(TargetCreation(width = 1080, height = 1920, densityDpi = 440)), runtime.targetOwner.createdTargets)

            runtime.metricsProvider.update(CaptureMetrics(widthPx = 1440, heightPx = 2560, densityDpi = 560))
            runCurrent()

            assertEquals(CaptureMetrics(widthPx = 1440, heightPx = 2560, densityDpi = 560), owner.latestCaptureMetrics)
        } finally {
            owner.close()
            resources.close()
            runCurrent()
        }

        assertEquals(0, runtime.metricsProvider.activeCollectorCount)
        assertEquals(1, runtime.projection.stopCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }


    @Test
    fun callbackRegistrationFailureBeforeConsumptionDoesNotRequireFreshProjection() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val failure = IllegalStateException("register failed")
        runtime.callbackRegistration.registerFailure = failure

        val exception = expectStartException { runtime.start() }
        runCurrent()

        assertFalse(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(failure, exception.problem.cause)
        assertEquals(0, runtime.virtualDisplayCreateCount)
        assertEquals(0, runtime.projection.stopCount)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(0, runtime.metricsProvider.activeCollectorCount)
    }


    @Test
    fun adapterStopObservedBeforeCreateFactorySkipsVirtualDisplay() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        runtime.callbackRegistration.emitStopDuringRegister = true

        val exception = expectStartException { runtime.start() }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(0, runtime.virtualDisplayCreateCount)
        assertEquals(0, runtime.projection.stopCount)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }


    @Test
    fun virtualDisplayCreateFailureAfterAttemptRequiresFreshProjection() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val failure = IllegalStateException("create failed")
        runtime.virtualDisplayCreateFailure = failure

        val exception = expectStartException { runtime.start() }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.VirtualDisplayCreateFailed, exception.problem.kind)
        assertEquals(failure, exception.problem.cause)
        assertEquals(1, runtime.virtualDisplayCreateCount)
        assertEquals(1, runtime.projection.stopCount)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(0, runtime.virtualDisplayOwner.closeCount)
    }


    @Test
    fun api34InvalidResizeThenValidResizeUsesFirstValidResize() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()

        runtime.callbackRegistration.emitResize(width = 0, height = 1280)
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)

        val resources = start.await()
        try {
            assertEquals(CaptureGeometrySource.CapturedContentResize, resources.startupGeometry.source)
            assertEquals(720, resources.startupGeometry.widthPx)
            assertEquals(1280, resources.startupGeometry.heightPx)
            assertEquals(440, resources.startupGeometry.densityDpi)
        } finally {
            resources.close()
            runCurrent()
        }
    }


    @Test
    fun api34ProjectionStopBeforeDeadlineTasksRunWinsOverResizeAndTimeout() = runTest {
        val runtime = TestRuntime(apiLevel = 34, startupResizeTimeoutMillis = 3_000L)
        val start = async { runCatching { runtime.start() } }
        runCurrent()
        assertEquals(1, runtime.virtualDisplayCreateCount)

        advanceTimeBy(3_000.milliseconds)
        runtime.callbackRegistration.emitResize(width = 800, height = 600)
        runtime.callbackRegistration.emitStop()
        runCurrent()

        val exception = start.await().screenCaptureStartException()
        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(0, runtime.projection.stopCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(0, runtime.metricsProvider.activeCollectorCount)
    }


    @Test
    fun api34CancelledWhileWaitingForGeometryRollsBackConsumedProjection() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()

        assertEquals(1, runtime.virtualDisplayCreateCount)
        assertEquals(1, runtime.metricsProvider.activeCollectorCount)

        start.cancelAndJoin()
        runCurrent()

        assertTrue(start.isCancelled)
        assertEquals(0, runtime.metricsProvider.activeCollectorCount)
        assertEquals(1, runtime.metricsProvider.attachmentDisposeCount)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun api34TimeoutRollsBackAndRequiresFreshProjection() = runTest {
        val runtime = TestRuntime(apiLevel = 34, startupResizeTimeoutMillis = 3_000L)
        val start = async { runCatching { runtime.start() } }
        runCurrent()

        advanceTimeBy(3_000.milliseconds)
        runCurrent()

        val exception = start.await().screenCaptureStartException()
        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.StartupGeometryUnavailable, exception.problem.kind)
        assertEquals(1, runtime.projection.stopCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }


    @Test
    fun rollbackRunsCleanupLocallyWhenCleanupSchedulerThrows() = runTest {
        val runtime = TestRuntime(apiLevel = 34, startupResizeTimeoutMillis = 3_000L)
        val schedulerFailure = IllegalStateException("scheduler rejected cleanup")
        val closeFailure = IllegalStateException("virtual display close failed")
        runtime.failOnCleanupFailure = false
        runtime.cleanupSchedulerFailure = schedulerFailure
        runtime.virtualDisplayOwner.closeFailure = closeFailure
        val start = async { runCatching { runtime.start() } }
        runCurrent()

        advanceTimeBy(3_000.milliseconds)
        runCurrent()

        val exception = start.await().screenCaptureStartException()
        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.StartupGeometryUnavailable, exception.problem.kind)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(listOf(schedulerFailure, closeFailure), runtime.cleanupFailures)
    }


    @Test
    fun api34ResizeAlreadyRecordedAtDeadlineWinsWhenTimeoutTaskHasNotRunYet() = runTest {
        val runtime = TestRuntime(apiLevel = 34, startupResizeTimeoutMillis = 3_000L)
        val start = async { runtime.start() }
        runCurrent()

        // advanceTimeBy reaches the deadline without running tasks scheduled exactly at that time.
        advanceTimeBy(3_000.milliseconds)
        runtime.callbackRegistration.emitResize(width = 800, height = 600)

        val resources = start.await()
        try {
            assertEquals(CaptureGeometrySource.CapturedContentResize, resources.startupGeometry.source)
            assertEquals(800, resources.startupGeometry.widthPx)
            assertEquals(600, resources.startupGeometry.heightPx)
        } finally {
            resources.close()
            runCurrent()
        }
    }


    @Test
    fun preActivePrepareFailsWhenProjectionStopObservedAfterTransfer() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val resources = runtime.start()
        val owner = resources.transferToPreActiveRuntimeOwner()
        try {
            runtime.callbackRegistration.emitStop()

            val exception = expectStartException { owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider)) }
            assertTrue(exception.requiresFreshProjection)
            assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
            runtime.callbackRegistration.emitStop()
        } finally {
            owner.close()
            resources.close()
            runCurrent()
        }

        assertEquals(0, runtime.projection.stopCount)
    }


    @Test
    fun startupResourcesTransferIsMoveOnlyAndCloseDoesNotCleanupTransferredOwners() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val resources = runtime.start()
        val owner = resources.transferToPreActiveRuntimeOwner()

        resources.close()
        runCurrent()

        assertEquals(0, runtime.projection.stopCount)
        assertEquals(0, runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, runtime.targetOwner.closeCount)

        val transferFailure = runCatching { resources.transferToPreActiveRuntimeOwner() }.exceptionOrNull()
        assertTrue(transferFailure is IllegalStateException)

        owner.close()
        runCurrent()

        assertEquals(1, runtime.projection.stopCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }

}
