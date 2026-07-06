package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreActiveRuntimeTargetRebindTest {
    @Test
    fun preActiveRebindsProvisionalTargetToAuthoritativePlanUsingSingleVirtualDisplay() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()

        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()

        try {
            val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))

            assertEquals(1, runtime.virtualDisplayCreateCount)
            assertEquals(2, runtime.targetOwner.createCount)
            assertEquals(2, runtime.virtualDisplayOwner.bindCount)
            assertEquals(720, prepared.projectionTarget.width)
            assertEquals(1280, prepared.projectionTarget.height)
            assertEquals(1, runtime.targetOwner.createdHandles.first().closeCount)
        } finally {
            owner.close()
            runCurrent()
        }
    }


    @Test
    fun preActiveDoesNotRebindWhenCurrentTargetAlreadyMatchesPlan() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()

        try {
            val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))

            assertEquals(1, runtime.targetOwner.createCount)
            assertEquals(1, runtime.virtualDisplayOwner.bindCount)
            assertEquals(1L, prepared.projectionTarget.generation)
        } finally {
            owner.close()
            runCurrent()
        }
    }


    @Test
    fun preActiveRetriesOnceWithFreshGenerationAfterBindFailure() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        val bindFailure = IllegalStateException("bind failed")
        runtime.virtualDisplayOwner.bindFailures += bindFailure

        try {
            val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))

            assertEquals(3, runtime.targetOwner.createCount)
            assertEquals(3, runtime.virtualDisplayOwner.bindCount)
            assertEquals(3L, prepared.projectionTarget.generation)
            assertEquals(1, runtime.targetOwner.createdHandles[1].directCloseCount)
            assertEquals(1, runtime.targetOwner.createdHandles[0].directCloseCount)
        } finally {
            owner.close()
            runCurrent()
        }
    }


    @Test
    fun preActiveUnsafeBindFailureFailsWithoutRetryingClosedVirtualDisplay() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        runtime.virtualDisplayOwner.unsafeBindFailures += IllegalStateException("setSurface failed")

        val exception = expectStartException {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.SurfaceCreateOrResizeFailed, exception.problem.kind)
        assertEquals(2, runtime.targetOwner.createCount)
        assertEquals(2, runtime.virtualDisplayOwner.bindCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].directCloseCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun preActiveProjectionStopAfterTargetCreateBeforeBindWinsAndRetiresCandidate() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        runtime.targetOwner.afterCreateTarget = { runtime.callbackRegistration.emitStop() }

        val exception = expectStartException {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(2, runtime.targetOwner.createCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].directCloseCount)
        assertEquals(1, runtime.virtualDisplayOwner.bindCount)
        assertEquals(0, runtime.projection.stopCount)
    }


    @Test
    fun preActiveProjectionStopAfterSuccessfulBindBeforeReturnWins() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        runtime.virtualDisplayOwner.afterSuccessfulBind = {
            if (runtime.virtualDisplayOwner.bindCount > 1) runtime.callbackRegistration.emitStop()
        }

        val exception = expectStartException {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(2, runtime.virtualDisplayOwner.bindCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(0, runtime.projection.stopCount)
    }


    @Test
    fun preActiveCancellationAfterTargetCreateRollsBackConsumedProjection() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        lateinit var prepare: kotlinx.coroutines.Deferred<PreActiveInitialRuntimePlan>
        runtime.targetOwner.afterCreateTarget = { prepare.cancel() }

        prepare = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        prepare.start()
        runCurrent()

        assertTrue(prepare.isCancelled)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].directCloseCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun preActiveOwnerCloseAfterTargetCreateBeforeBindRollsBackCandidate() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        runtime.targetOwner.afterCreateTarget = { owner.close() }

        val exception = expectStartException {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertEquals(2, runtime.targetOwner.createCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].ownerCloseCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].directCloseCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].ownerCloseCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.bindCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun preActiveOwnerCloseAfterSuccessfulBindBeforeReturnRollsBackBoundTarget() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        runtime.virtualDisplayOwner.afterSuccessfulBind = {
            if (runtime.virtualDisplayOwner.bindCount > 1) owner.close()
        }

        val exception = expectStartException {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertEquals(2, runtime.virtualDisplayOwner.bindCount)
        assertEquals(0, runtime.targetOwner.createdHandles[0].directCloseCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].ownerCloseCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].closeCount)
        assertEquals(0, runtime.targetOwner.createdHandles[1].directCloseCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].ownerCloseCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun preActiveTargetCreationFailureRetriesOnceThenFailsWithoutMismatchedFallback() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        runtime.targetOwner.createFailures += IllegalStateException("create failed 1")
        runtime.targetOwner.createFailures += IllegalStateException("create failed 2")

        val exception = expectStartException {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.SurfaceCreateOrResizeFailed, exception.problem.kind)
        assertEquals(3, runtime.targetOwner.createCount)
        assertEquals(1, runtime.virtualDisplayOwner.bindCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun preActiveRecoverableBindFailuresExhaustRetryWithoutMismatchedFallback() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        runtime.virtualDisplayOwner.bindFailures += IllegalStateException("bind failed 1")
        runtime.virtualDisplayOwner.bindFailures += IllegalStateException("bind failed 2")

        val exception = expectStartException {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.SurfaceCreateOrResizeFailed, exception.problem.kind)
        assertEquals(3, runtime.targetOwner.createCount)
        assertEquals(3, runtime.virtualDisplayOwner.bindCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].directCloseCount)
        assertEquals(1, runtime.targetOwner.createdHandles[2].directCloseCount)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun preActiveCancellationAfterSuccessfulBindRollsBackConsumedProjection() = runTest {
        val runtime = TestRuntime(apiLevel = 34)
        val start = async { runtime.start() }
        runCurrent()
        runtime.callbackRegistration.emitResize(width = 720, height = 1280)
        val owner = start.await().transferToPreActiveRuntimeOwner()
        lateinit var prepare: kotlinx.coroutines.Deferred<PreActiveInitialRuntimePlan>
        runtime.virtualDisplayOwner.afterSuccessfulBind = {
            if (runtime.virtualDisplayOwner.bindCount > 1) prepare.cancel()
        }

        prepare = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        }
        prepare.start()
        runCurrent()

        assertTrue(prepare.isCancelled)
        assertEquals(1, runtime.targetOwner.createdHandles[0].closeCount)
        assertEquals(1, runtime.targetOwner.createdHandles[1].closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }

}
