package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.JpegImageEncoderProvider
import dev.dmkr.screencaptureengine.ScreenCaptureConfig
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
    fun preActivePlanCarriesInitialEncoderProviderWithoutOwningProviderLifecycle() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val encoderProvider = JpegImageEncoderProvider(quality = 73)
        val preparer = TestRenderingPipelinePreparer()

        val prepared = owner.prepareInitialActivePlan(
            config = ScreenCaptureConfig(metricsProvider = runtime.metricsProvider),
            initialParameters = ScreenCaptureParameters(encoderProvider = encoderProvider),
        )
        val preparedResources = owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)

        owner.transferToInitialRuntimeResourceOwner(preparedPlan = prepared, preparedResources = preparedResources).close()
        runCurrent()

        assertEquals(encoderProvider, prepared.encoderProvider)
        assertEquals(encoderProvider, preparer.requests.single().encoderProvider)
        assertTrue(preparer.requests.single().projectionTargetHandle === prepared.projectionTargetHandle)
        assertTrue(preparer.requests.single().startupRenderingGlAccess === prepared.startupRenderingGlAccess)
    }


    @Test
    fun preActiveRenderingPipelinePreparationClosesPreparedResourcesWhenProjectionStopWinsAfterPrepare() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        preparer.afterPrepare = { runtime.callbackRegistration.emitStop() }

        val exception = expectStartException {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(1, preparer.preparedResources.single().closeCount)
        assertEquals(1, preparer.preparedEncoders.single().closeCount)
        assertEquals(0, runtime.projection.stopCount)
    }


    @Test
    fun preActiveRenderingPipelinePreparationProjectionStopWinsWhilePreparerIsSuspended() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparerEntered = CompletableDeferred<Unit>()
        val releasePreparer = CompletableDeferred<Unit>()
        val preparer = TestRenderingPipelinePreparer()
        preparer.beforeReturn = {
            preparerEntered.complete(Unit)
            releasePreparer.await()
        }

        val pending = async {
            runCatching {
                owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
            }
        }
        runCurrent()
        preparerEntered.await()

        runtime.callbackRegistration.emitStop()
        assertFalse(preparer.requests.single().planPreparationToken.isCurrent)

        releasePreparer.complete(Unit)
        val exception = pending.await().screenCaptureStartException()
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(1, preparer.preparedResources.single().closeCount)
        assertEquals(1, preparer.preparedEncoders.single().closeCount)
        assertEquals(0, runtime.projection.stopCount)
    }


    @Test
    fun preActiveRenderingPipelinePreparationCallerCancellationWinsWhilePreparerIsSuspended() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparerEntered = CompletableDeferred<Unit>()
        val preparer = TestRenderingPipelinePreparer()
        preparer.beforePrepare = {
            preparerEntered.complete(Unit)
            CompletableDeferred<Unit>().await()
        }

        val pending = async {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()
        preparerEntered.await()

        pending.cancel()
        runCurrent()

        assertTrue(pending.isCancelled)
        assertFalse(preparer.requests.single().planPreparationToken.isCurrent)
        assertTrue(preparer.preparedResources.isEmpty())
        assertTrue(preparer.preparedEncoders.isEmpty())
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }


    @Test
    fun preActiveRenderingPipelinePreparationCallerCancellationInvalidatesTokenBeforeNonCooperativePreparerReturns() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparerEntered = CompletableDeferred<Unit>()
        lateinit var request: RenderingPipelinePrepareRequest
        lateinit var continuation: Continuation<RenderingPipelinePreparationResult>
        val readbackResources = TestPreparedRenderingPipelineResource()
        val encoder = FakeImageEncoder()
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
            RenderingPipelinePreparationResult.Success(
                PreparedRenderingPipelineComponents(
                    readbackResources = readbackResources,
                    renderTransformPackage = testRenderTransformPackage(request),
                    encoderResources = PreparedImageEncoderResources(
                        encoder = encoder,
                        info = encoder.info,
                        request = request.outputPlan.encoderRequest,
                        cleanup = ImmediateProviderEncoderCleanup,
                    ),
                ),
            ),
        )
        runCurrent()

        assertTrue(pending.isCancelled)
        assertEquals(1, readbackResources.closeCount)
        assertEquals(1, encoder.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }


    @Test
    fun preActiveCloseWhileRenderingPipelinePreparationIsSuspendedInvalidatesTokenAndRetiresLateResources() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparerEntered = CompletableDeferred<Unit>()
        lateinit var request: RenderingPipelinePrepareRequest
        lateinit var continuation: Continuation<RenderingPipelinePreparationResult>
        val readbackResources = TestPreparedRenderingPipelineResource()
        val encoder = FakeImageEncoder()
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
            RenderingPipelinePreparationResult.Success(
                PreparedRenderingPipelineComponents(
                    readbackResources = readbackResources,
                    renderTransformPackage = testRenderTransformPackage(request),
                    encoderResources = PreparedImageEncoderResources(
                        encoder = encoder,
                        info = encoder.info,
                        request = request.outputPlan.encoderRequest,
                        cleanup = ImmediateProviderEncoderCleanup,
                    ),
                ),
            ),
        )
        val result = pending.await()
        runCurrent()

        assertTrue(result.isFailure)
        assertEquals(1, readbackResources.closeCount)
        assertEquals(1, encoder.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }


    @Test
    fun preActiveRenderingPipelinePreparationCallerCancellationWinsOverPreparerResourceFailure() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        lateinit var pending: Deferred<PreparedRenderingPipelineResources>
        preparer.prepareFailure = IllegalStateException("resource validation failed")
        preparer.beforeFailure = { pending.cancel() }

        pending = async {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()

        assertTrue(pending.isCancelled)
        assertFalse(preparer.requests.single().planPreparationToken.isCurrent)
        assertTrue(preparer.preparedResources.isEmpty())
        assertTrue(preparer.preparedEncoders.isEmpty())
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }


    @Test
    fun preActiveRenderingPipelinePreparationPreservesClassifiedStartExceptionFromPreparer() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        preparer.preparationFailure = RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.EncoderUnavailable,
            message = "classified encoder failure",
        )

        val exception = expectStartException {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.EncoderUnavailable, exception.problem.kind)
        assertEquals(1, preparer.requests.size)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }


    @Test
    fun preActiveRenderingPipelinePreparationProjectionStopWinsOverClassifiedPreparerException() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        preparer.preparationFailure = RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.EncoderUnavailable,
            message = "classified encoder failure",
        )
        preparer.beforeFailure = { runtime.callbackRegistration.emitStop() }

        val exception = expectStartException {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()

        assertTrue(exception.requiresFreshProjection)
        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(1, preparer.requests.size)
        assertEquals(0, runtime.projection.stopCount)
    }


    @Test
    fun preActiveRenderingPipelinePreparationRejectsSecondCallBeforePreparerSideEffects() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()

        owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        val exception = expectStartException {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertEquals(1, preparer.requests.size)
        assertEquals(1, preparer.preparedResources.single().closeCount)
        assertEquals(1, preparer.preparedEncoders.single().closeCount)
    }


    @Test
    fun preActiveRenderingPipelineRollbackSchedulesPreparedResourceCleanup() = runTest {
        val runtime = TestRuntime(apiLevel = 33, runCleanupSynchronously = false)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        preparer.afterPrepare = { runtime.callbackRegistration.emitStop() }

        val exception = expectStartException {
            owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        }

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(0, preparer.preparedResources.single().closeCount)

        runtime.runScheduledCleanup()

        assertEquals(1, preparer.preparedResources.single().closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }


    @Test
    fun initialRuntimeTransferMovesPreparedPipelineCleanupOwnership() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        val preparedResources = owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        val resource = preparer.preparedResources.single()
        val token = preparedResources.planPreparationToken
        val renderTransformPackage = preparedResources.renderTransformPackage

        val runtimeOwner = owner.transferToInitialRuntimeResourceOwner(
            preparedPlan = prepared,
            preparedResources = preparedResources,
        )
        owner.close()
        runCurrent()

        assertFalse(token.isCurrent)
        assertEquals(0, resource.closeCount)
        assertTrue(renderTransformPackage === runtimeOwner.initialRenderTransformPackage)

        runtimeOwner.close()
        runCurrent()

        assertEquals(1, resource.closeCount)
        assertEquals(1, preparer.preparedEncoders.single().closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
    }


    @Test
    fun initialRuntimeTransferProjectionStopAfterResourcesPreparedClosesPreparedResourcesWithoutMoving() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        val preparedResources = owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        val resource = preparer.preparedResources.single()
        val encoder = preparer.preparedEncoders.single()

        runtime.callbackRegistration.emitStop()
        val exception = expectStartException {
            owner.transferToInitialRuntimeResourceOwner(
                preparedPlan = prepared,
                preparedResources = preparedResources,
            )
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.ProjectionInvalidOrStopped, exception.problem.kind)
        assertEquals(1, resource.closeCount)
        assertEquals(1, encoder.closeCount)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(0, runtime.projection.stopCount)
    }


    @Test
    fun initialRuntimeTransferCallerCancellationAfterResourcesPreparedClosesPreparedResourcesWithoutMoving() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        val preparedResources = owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        val resource = preparer.preparedResources.single()
        val encoder = preparer.preparedEncoders.single()

        val pending = async {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.cancel()
            owner.transferToInitialRuntimeResourceOwner(
                preparedPlan = prepared,
                preparedResources = preparedResources,
            )
        }
        runCurrent()

        assertTrue(pending.isCancelled)
        assertEquals(1, resource.closeCount)
        assertEquals(1, encoder.closeCount)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun preparedRenderingPipelineResourcesRemainCloseableWhenTransformHandoffValidationFails() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val readbackResources = TestPreparedRenderingPipelineResource()
        val encoder = FakeImageEncoder()
        val resources = PreparedRenderingPipelineResources(
            planPreparationToken = PlanPreparationToken(
                ownerToken = prepared.ownerToken,
                planToken = prepared.planToken,
                projectionTargetGeneration = prepared.projectionTarget.generation,
            ),
            ownerToken = prepared.ownerToken,
            planToken = prepared.planToken,
            outputPlan = prepared.outputPlan,
            projectionTarget = prepared.projectionTarget,
            projectionTargetGeneration = prepared.projectionTarget.generation,
            startupRenderingGlAccess = prepared.startupRenderingGlAccess,
            readbackResources = readbackResources,
            renderTransformPackage = testRenderTransformPackage(
                outputPlan = prepared.outputPlan,
                projectionTarget = prepared.projectionTarget.copy(generation = prepared.projectionTarget.generation + 1L),
            ),
            encoderResources = PreparedImageEncoderResources(
                encoder = encoder,
                info = encoder.info,
                request = prepared.outputPlan.encoderRequest,
                cleanup = ImmediateProviderEncoderCleanup,
            ),
        )

        val result = runCatching { resources.moveToInitialRuntimeOwner() }
        resources.close()
        owner.close()
        runCurrent()

        assertTrue(result.isFailure)
        assertEquals(1, readbackResources.closeCount)
        assertEquals(1, encoder.closeCount)
    }


    @Test
    fun initialRuntimeTransferRejectsStalePreparedTargetGenerationFromSameOwner() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = TestRenderingPipelinePreparer())
        val stalePreparedEncoder = FakeImageEncoder()
        val stalePreparedResources = PreparedRenderingPipelineResources(
            planPreparationToken = PlanPreparationToken(
                ownerToken = prepared.ownerToken,
                planToken = prepared.planToken,
                projectionTargetGeneration = prepared.projectionTarget.generation,
            ),
            ownerToken = prepared.ownerToken,
            planToken = prepared.planToken,
            outputPlan = prepared.outputPlan,
            projectionTarget = prepared.projectionTarget,
            projectionTargetGeneration = prepared.projectionTarget.generation + 1L,
            startupRenderingGlAccess = prepared.startupRenderingGlAccess,
            readbackResources = TestPreparedRenderingPipelineResource(),
            renderTransformPackage = testRenderTransformPackage(
                outputPlan = prepared.outputPlan,
                projectionTarget = prepared.projectionTarget,
            ),
            encoderResources = PreparedImageEncoderResources(
                encoder = stalePreparedEncoder,
                info = stalePreparedEncoder.info,
                request = prepared.outputPlan.encoderRequest,
                cleanup = ImmediateProviderEncoderCleanup,
            ),
        )

        val exception = expectStartException {
            owner.transferToInitialRuntimeResourceOwner(
                preparedPlan = prepared,
                preparedResources = stalePreparedResources,
            )
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
        assertEquals(1, runtime.projection.stopCount)
    }


    @Test
    fun initialRuntimeTransferRejectsStalePlanPreparationTokenFromSameOwner() = runTest {
        val runtime = TestRuntime(apiLevel = 33)
        val owner = runtime.start().transferToPreActiveRuntimeOwner()
        val prepared = owner.prepareInitialActivePlan(ScreenCaptureConfig(metricsProvider = runtime.metricsProvider))
        val preparer = TestRenderingPipelinePreparer()
        val preparedResources = owner.prepareInitialRenderingPipeline(preparedPlan = prepared, preparer = preparer)
        preparedResources.planPreparationToken.invalidate()

        val exception = expectStartException {
            owner.transferToInitialRuntimeResourceOwner(
                preparedPlan = prepared,
                preparedResources = preparedResources,
            )
        }
        runCurrent()

        assertEquals(ScreenCaptureProblemKind.InternalInvariantViolation, exception.problem.kind)
        assertEquals(1, preparer.preparedResources.single().closeCount)
        assertEquals(1, preparer.preparedEncoders.single().closeCount)
        assertEquals(1, runtime.callbackRegistration.closeCount)
        assertEquals(1, runtime.virtualDisplayOwner.closeCount)
        assertEquals(1, runtime.targetOwner.closeCount)
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
            projectionTargetHandle = prepared.projectionTargetHandle,
            startupRenderingGlAccess = prepared.startupRenderingGlAccess,
            encoderProvider = prepared.encoderProvider,
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
            projectionTargetHandle = prepared.projectionTargetHandle,
            startupRenderingGlAccess = prepared.startupRenderingGlAccess,
            encoderProvider = prepared.encoderProvider,
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
            targetHandleMatches = true,
            targetGenerationMatches = true,
            startupRenderingGlAccessMatches = true,
            preparedResourcesOwnerMatches = true,
            preparedResourcesPlanTokenMatches = true,
            preparedResourcesTargetGenerationMatches = true,
            preparedResourcesStartupRenderingGlAccessMatches = true,
            preparedResourcesPlanPreparationTokenMatches = true,
            preparedResourcesPlanPreparationTokenCurrent = true,
            preparedResourcesOpen = true,
            projectionStopped = projectionStopped,
            callerActive = callerActive,
        )

}
