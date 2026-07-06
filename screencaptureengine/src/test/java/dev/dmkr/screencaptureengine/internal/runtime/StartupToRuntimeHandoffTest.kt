package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupToRuntimeHandoffTest {
    @Test
    fun initialRuntimeHandoffGateChoosesProjectionStopWhenProjectionStoppedAndCallerInactive() {
        val decision = InitialRuntimeHandoffGate.decide(
            handoffGateSnapshot(projectionStopped = true, callerActive = false),
        )

        assertEquals(InitialRuntimeHandoffDecision.ProjectionStopped, decision)
    }


    @Test
    fun initialRuntimeHandoffGateChoosesCallerCancellationWhenCallerInactiveWithoutProjectionStop() {
        val decision = InitialRuntimeHandoffGate.decide(
            handoffGateSnapshot(projectionStopped = false, callerActive = false),
        )

        assertEquals(InitialRuntimeHandoffDecision.CallerCancelled, decision)
    }


    @Test
    fun initialRuntimeHandoffGateReadyAllowsTransferToProceed() {
        val decision = InitialRuntimeHandoffGate.decide(
            handoffGateSnapshot(projectionStopped = false, callerActive = true),
        )

        assertEquals(InitialRuntimeHandoffDecision.Ready, decision)
    }


    @Test
    fun initialRuntimeTransferMovesCleanupOwnershipAndKeepsCallbackRegistered() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))

        val runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)
        owner.close()
        runCurrent()

        assertEquals(0, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.metricsProvider.activeCollectorCount)
        assertEquals(0, runtime.virtualDisplayOwner.closeCount)
        assertEquals(0, runtime.targetOwner.closeCount)
        assertEquals(0, runtime.projection.stopCount)

        runtimeOwner.close()
        runCurrent()

        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(0, runtime.metricsProvider.activeCollectorCount)
        assertEquals(1, runtime.metricsProvider.attachmentDisposeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun initialRuntimeTransferIsOneShot() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)

        val exception = expectStartException {
            owner.transferToInitialRuntimeResourceOwner(prepared)
        }
        runtimeOwner.close()
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun initialRuntimeTransferRejectsPlanFromDifferentOwner() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val otherRuntime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val otherOwner = otherRuntime.start().transferToPreActiveRuntimeOwner()
        val otherPrepared = otherOwner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = otherRuntime.metricsProvider))

        val exception = expectStartException {
            owner.transferToInitialRuntimeResourceOwner(otherPrepared)
        }
        runCurrent()
        otherOwner.close()
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
        assertEquals(1, otherRuntime.callbackRegistration.closeCount)
        assertEquals(1, otherRuntime.virtualDisplayOwner.closeCount)
        assertEquals(1, otherRuntime.targetOwner.closeCount)
    }


    @Test
    fun initialRuntimeTransferRejectsStaleTargetGenerationFromSameOwner() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val stalePrepared = PreActiveInitialRuntimePlan(
            ownerToken = prepared.ownerToken,
            planToken = prepared.planToken,
            outputPlan = prepared.outputPlan,
            projectionTarget = prepared.projectionTarget.copy(generation = prepared.projectionTarget.generation + 1L),
        )

        val exception = expectStartException {
            owner.transferToInitialRuntimeResourceOwner(stalePrepared)
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun initialRuntimeTransferRejectsStalePlanTokenFromSameOwner() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val stalePrepared = PreActiveInitialRuntimePlan(
            ownerToken = prepared.ownerToken,
            planToken = prepared.planToken - 1L,
            outputPlan = prepared.outputPlan,
            projectionTarget = prepared.projectionTarget,
        )

        val exception = expectStartException {
            owner.transferToInitialRuntimeResourceOwner(stalePrepared)
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun initialRuntimeTransferAfterPreActiveCloseRollsBackWithoutMovingOwnership() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))

        owner.close()
        runCurrent()
        val exception = expectStartException {
            owner.transferToInitialRuntimeResourceOwner(prepared)
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun initialRuntimeTransferCancellationBeforeLocalHandoffRollsBackPreActiveOwner() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))

        val handoff = async {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.cancel()
            owner.transferToInitialRuntimeResourceOwner(prepared)
        }
        runCurrent()

        assertTrue(handoff.isCancelled)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(0, runtime.metricsProvider.activeCollectorCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun initialRuntimeTransferProjectionStopBeforeLocalHandoffWinsAndRollsBack() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        runtime.callbackRegistration.emitStop()

        val exception = expectStartException {
            owner.transferToInitialRuntimeResourceOwner(prepared)
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(0, runtime.projection.stopCount)
    }


    @Test
    fun initialRuntimeOwnerReceivesPostHandoffSignalsOnce() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        runtime.callbackRegistration.emitResize(width = 640, height = 360)

        val runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)
        runtime.callbackRegistration.emitResize(width = 320, height = 240)
        runtime.callbackRegistration.emitVisibility(true)
        runtime.metricsProvider.update(CaptureMetrics(widthPx = 1440, heightPx = 2560, densityDpi = 560))
        runCurrent()

        val initialSignals = runtimeOwner.initialPendingSignals
        val laterSignals = runtimeOwner.pendingSignalsSnapshot()
        runtimeOwner.close()
        runCurrent()

        assertEquals(640, initialSignals.pendingCapturedContentResize?.width)
        assertEquals(360, initialSignals.pendingCapturedContentResize?.height)
        assertEquals(320, laterSignals.pendingCapturedContentResize?.width)
        assertEquals(240, laterSignals.pendingCapturedContentResize?.height)
        assertEquals(560, laterSignals.latestCaptureMetrics.densityDpi)
        assertEquals(320, laterSignals.pendingCaptureGeometry?.widthPx)
        assertEquals(240, laterSignals.pendingCaptureGeometry?.heightPx)
        assertEquals(560, laterSignals.pendingCaptureGeometry?.densityDpi)
        assertEquals(true, laterSignals.latestCapturedContentVisible)
    }


    @Test
    fun selectedOldPreActiveResizeAndVisibilityAfterTransferReachRuntimeMailbox() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        val selectedOldListener = owner as StartupProjectionCallbackRouter.SelectedRuntimeListener
        val selectedResize = {
            selectedOldListener.onRouterSelectedCapturedContentResized(
                ProjectionCapturedContentResize(id = 99L, width = 640, height = 360),
            )
        }
        val selectedVisibility = {
            selectedOldListener.onRouterSelectedCapturedContentVisibilityChanged(isVisible = false)
        }
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))

        val runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)
        selectedResize()
        selectedVisibility()

        val initialSignals = runtimeOwner.initialPendingSignals
        val laterSignals = runtimeOwner.pendingSignalsSnapshot()
        runtimeOwner.close()
        runCurrent()

        assertNull(initialSignals.pendingCapturedContentResize)
        assertNull(initialSignals.pendingCaptureGeometry)
        assertNull(initialSignals.latestCapturedContentVisible)
        assertEquals(640, laterSignals.pendingCapturedContentResize?.width)
        assertEquals(360, laterSignals.pendingCapturedContentResize?.height)
        assertEquals(640, laterSignals.pendingCaptureGeometry?.widthPx)
        assertEquals(360, laterSignals.pendingCaptureGeometry?.heightPx)
        assertEquals(false, laterSignals.latestCapturedContentVisible)
    }


    @Test
    fun selectedOldPreActiveStopAfterTransferReachesRuntimeMailbox() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val selectedOldListener = owner as StartupProjectionCallbackRouter.SelectedRuntimeListener
        val selectedStop = selectedOldListener::onRouterSelectedProjectionStopped
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))

        val runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)
        selectedStop()

        val initialSignals = runtimeOwner.initialPendingSignals
        val laterSignals = runtimeOwner.pendingSignalsSnapshot()
        runtimeOwner.close()
        runCurrent()

        assertFalse(initialSignals.projectionStopObserved)
        assertTrue(laterSignals.projectionStopObserved)
        assertNull(laterSignals.pendingCapturedContentResize)
        assertNull(laterSignals.pendingCaptureGeometry)
        assertNull(laterSignals.latestCapturedContentVisible)
    }


    @Test
    fun initialRuntimeOwnerCloseIsIdempotentAndCallbacksAfterCloseAreInert() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)

        runtimeOwner.close()
        runtimeOwner.onCapturedContentResized(ProjectionCapturedContentResize(id = 1L, width = 800, height = 600))
        runtimeOwner.onCapturedContentVisibilityChanged(isVisible = false)
        runtimeOwner.onProjectionStopped()
        runtimeOwner.close()
        runCurrent()

        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(0, runtime.metricsProvider.activeCollectorCount)
        assertEquals(1, runtime.metricsProvider.attachmentDisposeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun stalePreActiveCallbacksAfterTransferDoNotMutateRuntimeMailbox() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)

        owner.onCapturedContentResized(ProjectionCapturedContentResize(id = 1L, width = 800, height = 600))
        owner.onCapturedContentVisibilityChanged(isVisible = false)
        owner.onProjectionStopped()

        val signals = runtimeOwner.pendingSignalsSnapshot()
        runtimeOwner.close()
        runCurrent()

        assertFalse(signals.projectionStopObserved)
        assertNull(signals.pendingCapturedContentResize)
        assertNull(signals.pendingCaptureGeometry)
        assertNull(signals.latestCapturedContentVisible)
    }


    @Test
    fun initialVisibilityHandoffIsNotReplayedWithoutNewPostHandoffVisibility() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val resources = start.await()
        runtime.callbackRegistration.emitVisibility(false)
        val owner = resources.transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val runtimeOwner = owner.transferToInitialRuntimeResourceOwner(prepared)

        val beforeNewVisibility = runtimeOwner.pendingSignalsSnapshot()
        runtime.callbackRegistration.emitVisibility(true)
        val afterNewVisibility = runtimeOwner.pendingSignalsSnapshot()
        val initialVisible = runtimeOwner.initialPendingSignals.latestCapturedContentVisible
        runtimeOwner.close()
        runCurrent()

        assertEquals(false, initialVisible)
        assertNull(beforeNewVisibility.latestCapturedContentVisible)
        assertEquals(true, afterNewVisibility.latestCapturedContentVisible)
    }

    private fun handoffGateSnapshot(
        projectionStopped: Boolean,
        callerActive: Boolean,
    ): InitialRuntimeHandoffGateSnapshot =
        InitialRuntimeHandoffGateSnapshot(
            ownerOpen = true,
            ownerStateDescription = "Open",
            planOwnerMatches = true,
            planTokenMatches = true,
            targetGenerationMatches = true,
            projectionStopped = projectionStopped,
            callerActive = callerActive,
        )

}
