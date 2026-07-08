package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class RenderingPipelinePreparationBoundaryTest {
    @Test
    fun preparationFailureAllowsExactlyClassifiedBoundaryKinds() {
        val expectedAllowedKinds = setOf(
            ScreenCaptureProblemKind.OutputPlanInvalid,
            ScreenCaptureProblemKind.OutputLimitsExceeded,
            ScreenCaptureProblemKind.GlInitializationFailed,
            ScreenCaptureProblemKind.GlResourceFailure,
            ScreenCaptureProblemKind.GlInvariantViolation,
            ScreenCaptureProblemKind.ReadbackUnavailable,
            ScreenCaptureProblemKind.EncoderUnavailable,
            ScreenCaptureProblemKind.EncoderValidationFailed,
            ScreenCaptureProblemKind.AllocationFailed,
            ScreenCaptureProblemKind.InternalInvariantViolation,
        )

        assertEquals(expectedAllowedKinds, RenderingPipelinePreparationFailure.AllowedKinds)
        ScreenCaptureProblemKind.entries.forEach { kind ->
            val result = runCatching {
                RenderingPipelinePreparationFailure(kind = kind, message = "classified failure")
            }

            if (kind in expectedAllowedKinds) {
                assertTrue("Expected $kind to be allowed.", result.isSuccess)
            } else {
                assertTrue("Expected $kind to be rejected.", result.isFailure)
            }
        }
    }

    @Test
    fun preActiveRenderingPipelinePreparationLifecycleStaleWithoutStaleCauseIsInvariantViolation() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        lateinit var request: RenderingPipelinePrepareRequest
        val preparer = RenderingPipelinePreparer { renderingRequest ->
            request = renderingRequest
            renderingRequest.planPreparationToken.invalidate()
            RenderingPipelinePreparationResult.LifecycleStale
        }

        val exception = expectStartException {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertFalse(request.planPreparationToken.isCurrent)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }

    @Test
    fun preActiveRenderingPipelinePreparationOwnerCloseLifecycleStaleIsNotResourceFailure() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparerEntered = CompletableDeferred<Unit>()
        lateinit var request: RenderingPipelinePrepareRequest
        lateinit var continuation: Continuation<RenderingPipelinePreparationResult>
        val preparer = RenderingPipelinePreparer { renderingRequest ->
            request = renderingRequest
            suspendCoroutine { suspendedContinuation ->
                continuation = suspendedContinuation
                preparerEntered.complete(Unit)
            }
        }

        val pending = async {
            runCatching {
                owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
            }
        }
        runCurrent()
        preparerEntered.await()

        owner.close()
        runCurrent()

        assertFalse(request.planPreparationToken.isCurrent)

        continuation.resume(RenderingPipelinePreparationResult.LifecycleStale)
        val failure = pending.await().exceptionOrNull()
        runCurrent()

        assertTrue(failure is CancellationException)
        assertEquals("Initial rendering pipeline preparation became stale.", failure?.message)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }

    @Test
    fun preActiveRenderingPipelinePreparationProjectionStopOutranksLifecycleStaleReturnedAfterSuspension() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparerEntered = CompletableDeferred<Unit>()
        lateinit var request: RenderingPipelinePrepareRequest
        lateinit var continuation: Continuation<RenderingPipelinePreparationResult>
        val preparer = RenderingPipelinePreparer { renderingRequest ->
            request = renderingRequest
            suspendCoroutine { suspendedContinuation ->
                continuation = suspendedContinuation
                preparerEntered.complete(Unit)
            }
        }

        val pending = async {
            runCatching {
                owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
            }
        }
        runCurrent()
        preparerEntered.await()

        runtime.callbackRegistration.emitStop()
        runCurrent()

        assertFalse(request.planPreparationToken.isCurrent)

        continuation.resume(RenderingPipelinePreparationResult.LifecycleStale)
        val exception = pending.await().screenCaptureStartException()
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(0, runtime.projection.stopCount)
    }

    @Test
    fun preActiveRenderingPipelinePreparationCallerCancellationOutranksLifecycleStaleReturnedAfterSuspension() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparerEntered = CompletableDeferred<Unit>()
        lateinit var request: RenderingPipelinePrepareRequest
        lateinit var continuation: Continuation<RenderingPipelinePreparationResult>
        val preparer = RenderingPipelinePreparer { renderingRequest ->
            request = renderingRequest
            suspendCoroutine { suspendedContinuation ->
                continuation = suspendedContinuation
                preparerEntered.complete(Unit)
            }
        }

        val pending = async {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()
        preparerEntered.await()

        pending.cancel()
        runCurrent()

        assertFalse(request.planPreparationToken.isCurrent)

        continuation.resume(RenderingPipelinePreparationResult.LifecycleStale)
        runCurrent()

        assertTrue(pending.isCancelled)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }

    @Test
    fun preActiveRenderingPipelinePreparationPreservesEveryTypedFailureKind() = runTest {
        RenderingPipelinePreparationFailure.AllowedKinds.forEach { kind ->
            val runtime = TestRuntime(apiLevel = 33)
            val owner = runtime.start().transferToPreActiveRuntimeOwner()
            val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
            val preparer = TestRenderingPipelinePreparer()
            preparer.preparationFailure = RenderingPipelinePreparationFailure(
                kind = kind,
                message = "$kind failure",
            )

            val exception = expectStartException {
                owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
            }
            runCurrent()

            assertEquals(kind, exception.problem.kind)
            assertEquals(1, preparer.requests.size)
            assertTrue(preparer.preparedResources.isEmpty())
            assertTrue(preparer.preparedEncoders.isEmpty())
            assertEquals(1, runtime.virtualDisplayOwner.closeCount)
            assertEquals(1, runtime.targetOwner.closeCount)
        }
    }

    @Test
    fun preActiveRenderingPipelinePreparationCallerCancellationOutranksTypedFailure() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        preparer.preparationFailure = RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.EncoderUnavailable,
            message = "classified encoder failure",
        )
        preparer.beforePrepare = {
            currentCoroutineContext()[Job]?.cancel()
        }

        val pending = async {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()

        assertTrue(pending.isCancelled)
        assertEquals(1, preparer.requests.size)
        assertTrue(preparer.preparedResources.isEmpty())
        assertTrue(preparer.preparedEncoders.isEmpty())
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }

    @Test
    fun preActiveRenderingPipelinePreparationPreservesTypedFailureReturnedAfterClose() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparerEntered = CompletableDeferred<Unit>()
        lateinit var request: RenderingPipelinePrepareRequest
        lateinit var continuation: Continuation<RenderingPipelinePreparationResult>
        val typedCause = IllegalStateException("typed cause")
        val preparer = RenderingPipelinePreparer { renderingRequest ->
            request = renderingRequest
            suspendCoroutine { suspendedContinuation ->
                continuation = suspendedContinuation
                preparerEntered.complete(Unit)
            }
        }

        val pending = async {
            runCatching {
                owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
            }
        }
        runCurrent()
        preparerEntered.await()

        owner.close()
        runCurrent()

        assertFalse(request.planPreparationToken.isCurrent)

        continuation.resume(
            RenderingPipelinePreparationResult.Failure(
                RenderingPipelinePreparationFailure(
                    kind = ScreenCaptureProblemKind.EncoderValidationFailed,
                    message = "typed validation failure",
                    cause = typedCause,
                ),
            ),
        )
        val exception = pending.await().screenCaptureStartException()
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.EncoderValidationFailed, exception.problem.kind)
        assertEquals("typed validation failure", exception.problem.message)
        assertSame(typedCause, exception.problem.cause)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }

    @Test
    fun preActiveRenderingPipelinePreparationProjectionStopOutranksTypedFailureReturnedAfterSuspension() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparerEntered = CompletableDeferred<Unit>()
        lateinit var request: RenderingPipelinePrepareRequest
        lateinit var continuation: Continuation<RenderingPipelinePreparationResult>
        val preparer = RenderingPipelinePreparer { renderingRequest ->
            request = renderingRequest
            suspendCoroutine { suspendedContinuation ->
                continuation = suspendedContinuation
                preparerEntered.complete(Unit)
            }
        }

        val pending = async {
            runCatching {
                owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
            }
        }
        runCurrent()
        preparerEntered.await()

        runtime.callbackRegistration.emitStop()
        runCurrent()

        assertFalse(request.planPreparationToken.isCurrent)

        continuation.resume(
            RenderingPipelinePreparationResult.Failure(
                RenderingPipelinePreparationFailure(
                    kind = ScreenCaptureProblemKind.EncoderValidationFailed,
                    message = "typed validation failure",
                ),
            ),
        )
        val exception = pending.await().screenCaptureStartException()
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(0, runtime.projection.stopCount)
    }

    @Test
    fun preActiveRenderingPipelinePreparationCallerCancellationOutranksTypedFailureReturnedAfterSuspension() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparerEntered = CompletableDeferred<Unit>()
        lateinit var request: RenderingPipelinePrepareRequest
        lateinit var continuation: Continuation<RenderingPipelinePreparationResult>
        val preparer = RenderingPipelinePreparer { renderingRequest ->
            request = renderingRequest
            suspendCoroutine { suspendedContinuation ->
                continuation = suspendedContinuation
                preparerEntered.complete(Unit)
            }
        }

        val pending = async {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()
        preparerEntered.await()

        pending.cancel()
        runCurrent()

        assertFalse(request.planPreparationToken.isCurrent)

        continuation.resume(
            RenderingPipelinePreparationResult.Failure(
                RenderingPipelinePreparationFailure(
                    kind = ScreenCaptureProblemKind.EncoderValidationFailed,
                    message = "typed validation failure",
                ),
            ),
        )
        runCurrent()

        assertTrue(pending.isCancelled)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }

    @Test
    fun preActiveRenderingPipelinePreparationCloseAfterMoveBeforeCommitDoesNotBecomeInvariantViolation() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        owner.afterInitialRenderingPipelineResourcesMovedForTesting = {
            owner.close()
        }

        val exception = expectStartException {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, exception.problem.kind)
        assertFalse(preparer.requests.single().planPreparationToken.isCurrent)
        assertEquals(1, preparer.preparedResources.single().closeCount)
        assertEquals(1, preparer.preparedEncoders.single().closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }

    @Test
    fun preActiveRenderingPipelinePreparationCleansStaleLateSuccessBeforeAcceptance() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        preparer.afterPrepare = {
            preparer.requests.single().planPreparationToken.invalidate()
        }

        val exception = expectStartException {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, exception.problem.kind)
        assertEquals(1, preparer.preparedResources.single().closeCount)
        assertEquals(1, preparer.preparedEncoders.single().closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }
}
