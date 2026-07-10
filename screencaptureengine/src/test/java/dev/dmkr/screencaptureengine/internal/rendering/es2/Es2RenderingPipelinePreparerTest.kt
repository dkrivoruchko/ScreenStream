package dev.dmkr.screencaptureengine.internal.rendering.es2

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.ImageRect
import dev.dmkr.screencaptureengine.OutputSize
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.SourceRegion
import dev.dmkr.screencaptureengine.internal.encoding.provider.FakeImageEncoder
import dev.dmkr.screencaptureengine.internal.encoding.provider.FakeImageEncoderProvider
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImageEncoderPreparationResult
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImageEncoderPreparer
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImmediateProviderEncoderCleanup
import dev.dmkr.screencaptureengine.internal.encoding.provider.PreparedImageEncoderResources
import dev.dmkr.screencaptureengine.internal.encoding.provider.ProviderPreparationContext
import dev.dmkr.screencaptureengine.internal.gl.GlLaneAbandonment
import dev.dmkr.screencaptureengine.internal.gl.GlLaneScope
import dev.dmkr.screencaptureengine.internal.gl.GlResourceRetirementLane
import dev.dmkr.screencaptureengine.internal.lifecycle.PlanPreparationToken
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanResult
import dev.dmkr.screencaptureengine.internal.planning.OutputPlanningLimits
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlanner
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionSurfaceHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSizeLimits
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.OutputPlanPrepareRequest
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.OutputPlanPreparer
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationFailure
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationResult
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePrepareRequest
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.asPlanRenderingAccess
import dev.dmkr.screencaptureengine.internal.startup.FakeProjectionSurfaceHandle
import dev.dmkr.screencaptureengine.internal.target.ProjectionTargetGlScope
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlAccess
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlAccessException
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlAccessFailureReason
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

class Es2RenderingPipelinePreparerTest {
    @Test
    fun prepareSuccessSequencesOperationsAndUsesOneReadbackSpec() = runTest {
        val plan = nonTrivialOutputPlan()
        val events = mutableListOf<String>()
        val request = request(
            plan = plan,
            startupRenderingGlAccess = RecordingStartupRenderingGlAccess(events = events),
        )
        val glAccess = request.startupRenderingGlAccess as RecordingStartupRenderingGlAccess
        var es2ReadbackSpec: Es2ReadbackSpec? = null
        var transformReadbackSpec: Es2ReadbackSpec? = null
        var encoderPrepareCount = 0

        val result = preparer(
            events = events,
            es2Prepare = { es2Request ->
                events += "es2"
                assertEquals(ColorMode.Original, es2Request.selectedColorMode)
                es2ReadbackSpec = es2Request.readbackSpec
                Es2RenderingReadbackPreparationResult.Success(preparedEs2Resources(plan = plan))
            },
            transformBuild = { transformRequest ->
                events += "transform"
                transformReadbackSpec = transformRequest.readbackSpec
                FirstPlanRenderTransformPackageBuilder.build(transformRequest)
            },
            encoderPrepare = { token, provider, encoderRequest ->
                events += "encoder"
                encoderPrepareCount++
                assertSame(request.planPreparationToken, token)
                assertSame(request.encoderProvider, provider)
                assertSame(plan.encoderRequest, encoderRequest)
                ImageEncoderPreparationResult.Success(preparedEncoder(request = encoderRequest))
            },
        ).prepareInitialRenderingPipeline(request)

        assertTrue(result is RenderingPipelinePreparationResult.Success)
        assertEquals(listOf("preflight", "glAccess", "es2", "transform", "encoder"), events)
        assertSame(es2ReadbackSpec, transformReadbackSpec)
        assertEquals(1, glAccess.accessCount)
        assertEquals(1, encoderPrepareCount)
        val success = result as RenderingPipelinePreparationResult.Success
        assertSame(plan.encoderRequest, success.components.encoderResources.request)
        success.components.close()
    }

    @Test
    fun prepareOutputPlanUsesNeutralPlanRenderingAccess() = runTest {
        val events = mutableListOf<String>()
        val glAccess = RecordingStartupRenderingGlAccess(events = events)
        val request = outputPlanRequest(
            startupRenderingGlAccess = glAccess,
        )
        val outputPlanPreparer: OutputPlanPreparer = preparer(events = events)

        val result = outputPlanPreparer.prepareOutputPlan(request)

        assertTrue(result is RenderingPipelinePreparationResult.Success)
        assertEquals(listOf("preflight", "glAccess", "es2", "transform", "encoder"), events)
        assertEquals(1, glAccess.accessCount)
        val success = result as RenderingPipelinePreparationResult.Success
        assertSame(request.outputPlan.encoderRequest, success.components.encoderResources.request)
        success.components.close()
    }

    @Test
    fun firstCheckStaleSkipsGlEs2TransformAndProvider() = runTest {
        val events = mutableListOf<String>()
        val request = request()
        request.planPreparationToken.invalidate()

        val result = preparer(events = events).prepareInitialRenderingPipeline(request)

        assertSame(RenderingPipelinePreparationResult.LifecycleStale, result)
        assertTrue(events.isEmpty())
        assertEquals(0, (request.startupRenderingGlAccess as RecordingStartupRenderingGlAccess).accessCount)
    }

    @Test
    fun staticPreflightFailureSkipsEs2AndProvider() = runTest {
        val events = mutableListOf<String>()
        val request = request()
        val failure = RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.OutputPlanInvalid,
            message = "preflight failed",
            cause = null,
        )

        val result = preparer(
            events = events,
            preflight = {
                events += "preflight"
                failure
            },
        ).prepareInitialRenderingPipeline(request)

        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, result.failure().kind)
        assertEquals(listOf("preflight"), events)
        assertEquals(0, (request.startupRenderingGlAccess as RecordingStartupRenderingGlAccess).accessCount)
    }

    @Test
    fun defaultStaticPreflightRejectsPaddedReadbackStrideBeforeGlAccess() = runTest {
        val plan = nonTrivialOutputPlan()
        val paddedRowStrideBytes = plan.rowStrideBytes + 4
        val paddedPlan = plan.withReadbackShape(
            encoderRequest = ImageEncoderRequest(
                width = plan.encoderRequest.width,
                height = plan.encoderRequest.height,
                rowStrideBytes = paddedRowStrideBytes,
                maxEncodedBytes = plan.encoderRequest.maxEncodedBytes,
                inputFormat = plan.encoderRequest.inputFormat,
            ),
            rowStrideBytes = paddedRowStrideBytes,
            rgbaByteCount = paddedRowStrideBytes.toLong() * plan.finalImageSize.height.toLong(),
        )
        val glAccess = RecordingStartupRenderingGlAccess()

        val result = defaultPreflightPreparer().prepareInitialRenderingPipeline(
            request(
                plan = paddedPlan,
                startupRenderingGlAccess = glAccess,
            ),
        )

        assertEquals(ScreenCaptureProblemKind.ReadbackUnavailable, result.failure().kind)
        assertEquals(0, glAccess.accessCount)
    }

    @Test
    fun defaultStaticPreflightRejectsEmptySourceRectBeforeGlAccess() = runTest {
        val plan = nonTrivialOutputPlan().withAppliedSourceRect(
            ImageRect(left = 0, top = 0, right = 0, bottom = 10),
        )
        val glAccess = RecordingStartupRenderingGlAccess()

        val result = defaultPreflightPreparer().prepareInitialRenderingPipeline(
            request(
                plan = plan,
                startupRenderingGlAccess = glAccess,
            ),
        )

        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, result.failure().kind)
        assertEquals(0, glAccess.accessCount)
    }

    @Test
    fun grayscalePlanSelectsGrayscaleEs2ReadinessAndTransformBinding() = runTest {
        val plan = nonTrivialOutputPlan(parameters = ScreenCaptureParameters(colorMode = ColorMode.Grayscale))
        val request = request(plan = plan)
        var selectedColorMode: ColorMode? = null

        val result = preparer(
            es2Prepare = { es2Request ->
                selectedColorMode = es2Request.selectedColorMode
                Es2RenderingReadbackPreparationResult.Success(
                    preparedEs2Resources(
                        plan = plan,
                        shaderVariant = Es2RenderingShaderVariant.GrayscaleExternalOes,
                    ),
                )
            },
        ).prepareInitialRenderingPipeline(request)

        val success = result as RenderingPipelinePreparationResult.Success
        assertEquals(ColorMode.Grayscale, selectedColorMode)
        assertEquals(ColorMode.Grayscale, success.components.renderTransformPackage.colorMode)
        assertEquals(FirstPlanEs2ShaderVariant.Grayscale, success.components.renderTransformPackage.programBinding.shaderVariant)
        success.components.close()
    }

    @Test
    fun startupGlInvariantFailureMapsToGlInvariantViolation() = runTest {
        StartupRenderingGlAccessFailureReason.entries.forEach { reason ->
            val request = request(
                startupRenderingGlAccess = RecordingStartupRenderingGlAccess(
                    failure = StartupRenderingGlAccessException(
                        reason = reason,
                        message = "startup access failed",
                    ),
                ),
            )

            val result = preparer().prepareInitialRenderingPipeline(request)

            val failure = result.failure()
            assertEquals(ScreenCaptureProblemKind.GlInvariantViolation, failure.kind)
            assertTrue(failure.cause !is StartupRenderingGlAccessException)
            assertEquals("startup access failed", failure.cause?.message)
        }
    }

    @Test
    fun es2FailurePreservesFailureAndSkipsProvider() = runTest {
        val events = mutableListOf<String>()
        val request = request(startupRenderingGlAccess = RecordingStartupRenderingGlAccess(events = events))
        val failure = IllegalStateException("readback")

        val result = preparer(
            events = events,
            es2Prepare = {
                events += "es2"
                Es2RenderingReadbackPreparationResult.Failure(
                    kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                    message = "readback unavailable",
                    cause = failure,
                )
            },
        ).prepareInitialRenderingPipeline(request)

        val preparationFailure = result.failure()
        assertEquals(ScreenCaptureProblemKind.ReadbackUnavailable, preparationFailure.kind)
        assertSame(failure, preparationFailure.cause)
        assertEquals(listOf("preflight", "glAccess", "es2"), events)
    }

    @Test
    fun es2FailureAfterTokenInvalidationReturnsLifecycleStale() = runTest {
        val plan = nonTrivialOutputPlan()
        val request = request(plan = plan)

        val result = preparer(
            es2Prepare = {
                request.planPreparationToken.invalidate()
                Es2RenderingReadbackPreparationResult.Failure(
                    kind = ScreenCaptureProblemKind.GlResourceFailure,
                    message = "stale es2 failure",
                    cause = null,
                )
            },
        ).prepareInitialRenderingPipeline(request)

        assertSame(RenderingPipelinePreparationResult.LifecycleStale, result)
    }

    @Test
    fun staleAfterEs2SuccessClosesEs2AndReturnsLifecycleStale() = runTest {
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)
        val request = request(plan = plan)

        val result = preparer(
            es2Prepare = {
                request.planPreparationToken.invalidate()
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
        ).prepareInitialRenderingPipeline(request)

        assertSame(RenderingPipelinePreparationResult.LifecycleStale, result)
        assertEquals(1, es2Fixture.retirement.retireCount)
    }

    @Test
    fun transformFailureClosesEs2AndSkipsProvider() = runTest {
        val events = mutableListOf<String>()
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)
        val request = request(
            plan = plan,
            startupRenderingGlAccess = RecordingStartupRenderingGlAccess(events = events),
        )

        val result = preparer(
            events = events,
            es2Prepare = {
                events += "es2"
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
            transformBuild = {
                events += "transform"
                FirstPlanRenderTransformPackageBuildResult.Failure(
                    kind = ScreenCaptureProblemKind.GlResourceFailure,
                    message = "transform failed",
                    cause = null,
                )
            },
        ).prepareInitialRenderingPipeline(request)

        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, result.failure().kind)
        assertEquals(1, es2Fixture.retirement.retireCount)
        assertEquals(listOf("preflight", "glAccess", "es2", "transform"), events)
    }

    @Test
    fun transformFailureAfterTokenInvalidationClosesEs2AndReturnsLifecycleStale() = runTest {
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)
        val request = request(plan = plan)

        val result = preparer(
            es2Prepare = {
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
            transformBuild = {
                request.planPreparationToken.invalidate()
                FirstPlanRenderTransformPackageBuildResult.Failure(
                    kind = ScreenCaptureProblemKind.GlResourceFailure,
                    message = "stale transform failure",
                    cause = null,
                )
            },
        ).prepareInitialRenderingPipeline(request)

        assertSame(RenderingPipelinePreparationResult.LifecycleStale, result)
        assertEquals(1, es2Fixture.retirement.retireCount)
    }

    @Test
    fun staleAfterTransformSuccessClosesEs2AndReturnsLifecycleStale() = runTest {
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)
        val request = request(plan = plan)

        val result = preparer(
            es2Prepare = {
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
            transformBuild = { transformRequest ->
                val transformResult = FirstPlanRenderTransformPackageBuilder.build(transformRequest)
                request.planPreparationToken.invalidate()
                transformResult
            },
        ).prepareInitialRenderingPipeline(request)

        assertSame(RenderingPipelinePreparationResult.LifecycleStale, result)
        assertEquals(1, es2Fixture.retirement.retireCount)
    }

    @Test
    fun cleanupExceptionDuringTransformFailureDoesNotReplacePrimaryFailure() = runTest {
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(
            plan = plan,
            retirement = ThrowingRetirementLane(IllegalStateException("close failed")),
        )

        val result = preparer(
            es2Prepare = {
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
            transformBuild = {
                FirstPlanRenderTransformPackageBuildResult.Failure(
                    kind = ScreenCaptureProblemKind.GlResourceFailure,
                    message = "transform failed",
                    cause = null,
                )
            },
        ).prepareInitialRenderingPipeline(request(plan = plan))

        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, result.failure().kind)
        assertEquals("transform failed", result.failure().message)
    }

    @Test
    fun encoderFailureClosesEs2AndPreservesEncoderClassification() = runTest {
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)

        val result = preparer(
            es2Prepare = {
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
            encoderPrepare = { _, _, _ ->
                ImageEncoderPreparationResult.Failure(
                    kind = ScreenCaptureProblemKind.EncoderValidationFailed,
                    message = "encoder failed",
                    cause = null,
                )
            },
        ).prepareInitialRenderingPipeline(request(plan = plan))

        assertEquals(ScreenCaptureProblemKind.EncoderValidationFailed, result.failure().kind)
        assertEquals(1, es2Fixture.retirement.retireCount)
    }

    @Test
    fun encoderAllocationFailureClosesEs2AndPreservesAllocationClassification() = runTest {
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)

        val result = preparer(
            es2Prepare = {
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
            encoderPrepare = { _, _, _ ->
                ImageEncoderPreparationResult.Failure(
                    kind = ScreenCaptureProblemKind.AllocationFailed,
                    message = "encoder allocation failed",
                    cause = null,
                )
            },
        ).prepareInitialRenderingPipeline(request(plan = plan))

        assertEquals(ScreenCaptureProblemKind.AllocationFailed, result.failure().kind)
        assertEquals(1, es2Fixture.retirement.retireCount)
    }

    @Test
    fun fakeStaleEncoderFailureClosesEs2AndReturnsLifecycleStale() = runTest {
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)
        val request = request(plan = plan)

        val result = preparer(
            es2Prepare = {
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
            encoderPrepare = { token, _, _ ->
                token.invalidate()
                ImageEncoderPreparationResult.Failure(
                    kind = ScreenCaptureProblemKind.EncoderUnavailable,
                    message = "stale provider",
                    cause = null,
                )
            },
        ).prepareInitialRenderingPipeline(request)

        assertSame(RenderingPipelinePreparationResult.LifecycleStale, result)
        assertEquals(1, es2Fixture.retirement.retireCount)
    }

    @Test
    fun realStaleProviderFailureClosesEs2AndReturnsLifecycleStale() = runBlocking {
        val plan = nonTrivialOutputPlan()
        val request = request(plan = plan)
        val es2Fixture = preparedEs2Fixture(plan = plan)
        val providerContext = ProviderPreparationContext(
            beforeClaim = {
                request.planPreparationToken.invalidate()
            },
        )
        try {
            val result = Es2RenderingPipelinePreparer(
                encoderPrepare = ImageEncoderPrepareOperation(ImageEncoderPreparer(providerContext)::prepare),
                staticTransformPreflight = Es2StaticTransformPreflightOperation { null },
                es2ReadbackPrepare = Es2ReadbackPrepareOperation {
                    Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
                },
            ).prepareInitialRenderingPipeline(request)

            assertSame(RenderingPipelinePreparationResult.LifecycleStale, result)
            assertEquals(1, es2Fixture.retirement.retireCount)
        } finally {
            providerContext.close()
        }
    }

    @Test
    fun staleEncoderCleanupThrowClosesEs2AndReturnsLifecycleStale() = runTest {
        val plan = nonTrivialOutputPlan()
        val request = request(plan = plan)
        val es2Fixture = preparedEs2Fixture(plan = plan)

        val result = preparer(
            es2Prepare = {
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
            encoderPrepare = { token, _, _ ->
                token.invalidate()
                throw IllegalStateException("stale encoder cleanup failed")
            },
        ).prepareInitialRenderingPipeline(request)

        assertSame(RenderingPipelinePreparationResult.LifecycleStale, result)
        assertEquals(1, es2Fixture.retirement.retireCount)
    }

    @Test
    fun callerCancellationDuringSuspendedEncoderPrepareClosesEs2AndPreservesCancellation() = runTest {
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)
        val encoderStarted = CompletableDeferred<Unit>()
        val deferred = async {
            preparer(
                es2Prepare = {
                    Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
                },
                encoderPrepare = { _, _, _ ->
                    encoderStarted.complete(Unit)
                    delay(Duration.INFINITE)
                    ImageEncoderPreparationResult.Success(preparedEncoder(request = plan.encoderRequest))
                },
            ).prepareInitialRenderingPipeline(request(plan = plan))
        }

        encoderStarted.await()
        deferred.cancelAndJoin()

        assertTrue(deferred.isCancelled)
        assertEquals(1, es2Fixture.retirement.retireCount)
    }

    @Test
    fun staleAfterEncoderSuccessClosesEs2AndEncoderResources() = runTest {
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)
        val encoder = FakeImageEncoder()
        val request = request(plan = plan)

        val result = preparer(
            es2Prepare = {
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
            encoderPrepare = { token, _, encoderRequest ->
                token.invalidate()
                ImageEncoderPreparationResult.Success(
                    preparedEncoder(
                        request = encoderRequest,
                        encoder = encoder,
                    ),
                )
            },
        ).prepareInitialRenderingPipeline(request)

        assertSame(RenderingPipelinePreparationResult.LifecycleStale, result)
        assertEquals(1, es2Fixture.retirement.retireCount)
        assertEquals(1, encoder.closeCount)
    }

    @Test
    fun startupGlTimeoutInvalidatesTokenAbandonsLaneAndSkipsProvider() = runTest {
        val events = mutableListOf<String>()
        val glAccess = RecordingStartupRenderingGlAccess(suspendBeforeBlock = true)
        val request = request(startupRenderingGlAccess = glAccess)

        val result = preparer(
            startupGlPrepareTimeoutMs = 10L,
            events = events,
        ).prepareInitialRenderingPipeline(request)

        assertEquals(ScreenCaptureProblemKind.GlInitializationFailed, result.failure().kind)
        assertEquals(1, glAccess.abandonCount)
        assertTrue(!request.planPreparationToken.isCurrent)
        assertEquals(listOf("preflight"), events)
    }

    @Test
    fun runtimeOutputPlanGlTimeoutInvalidatesCandidateWithoutAbandoningLiveLane() = runTest {
        val events = mutableListOf<String>()
        val glAccess = RecordingStartupRenderingGlAccess(suspendBeforeBlock = true)
        val request = outputPlanRequest(
            startupRenderingGlAccess = glAccess,
            abandonGlLaneOnTimeout = false,
        )

        val result = preparer(
            startupGlPrepareTimeoutMs = 10L,
            events = events,
        ).prepareOutputPlan(request)

        assertEquals(ScreenCaptureProblemKind.GlInitializationFailed, result.failure().kind)
        assertEquals(0, glAccess.abandonCount)
        assertTrue(!request.planPreparationToken.isCurrent)
        assertEquals(listOf("preflight"), events)
    }

    @Test
    fun startupGlTimeoutCleansLateEs2SuccessThroughAccessOnCancellation() = runTest {
        val events = mutableListOf<String>()
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)
        val glAccess = RecordingStartupRenderingGlAccess(
            events = events,
            completeSuccessfulBlockAfterCancellation = true,
        )
        val request = request(
            plan = plan,
            startupRenderingGlAccess = glAccess,
        )

        val result = preparer(
            startupGlPrepareTimeoutMs = 10L,
            events = events,
            es2Prepare = {
                events += "es2"
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
        ).prepareInitialRenderingPipeline(request)

        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, result.failure().kind)
        assertEquals(1, glAccess.abandonCount)
        assertEquals(1, es2Fixture.retirement.retireCount)
        assertEquals(listOf("preflight", "glAccess", "es2"), events)
    }

    @Test
    fun startupGlTimeoutAfterEs2BeginsOnWorkerThreadMapsToGlResourceFailure() = runTest {
        val events = mutableListOf<String>()
        val plan = nonTrivialOutputPlan()
        val es2Fixture = preparedEs2Fixture(plan = plan)
        val es2Entered = CountDownLatch(1)
        val glAccess = RecordingStartupRenderingGlAccess(
            events = events,
            runBlockOnWorkerAndSuspendCaller = true,
            awaitBeforeCallerSuspend = es2Entered,
        )
        val request = request(
            plan = plan,
            startupRenderingGlAccess = glAccess,
        )

        val result = preparer(
            startupGlPrepareTimeoutMs = 10L,
            events = events,
            es2Prepare = {
                events += "es2"
                es2Entered.countDown()
                Es2RenderingReadbackPreparationResult.Success(es2Fixture.resources)
            },
        ).prepareInitialRenderingPipeline(request)

        assertEquals(ScreenCaptureProblemKind.GlResourceFailure, result.failure().kind)
        assertEquals(1, glAccess.abandonCount)
        assertEquals(1, es2Fixture.retirement.retireCount)
        assertEquals(listOf("preflight", "glAccess", "es2"), events)
    }

    private fun preparer(
        startupGlPrepareTimeoutMs: Long = 5_000L,
        events: MutableList<String> = mutableListOf(),
        preflight: (OutputPlanPrepareRequest) -> RenderingPipelinePreparationFailure? = {
            events += "preflight"
            null
        },
        es2Prepare: (Es2RenderingReadbackPrepareRequest) -> Es2RenderingReadbackPreparationResult = { es2Request ->
            events += "es2"
            Es2RenderingReadbackPreparationResult.Success(
                preparedEs2Resources(
                    width = es2Request.readbackSpec.width,
                    height = es2Request.readbackSpec.height,
                    rowStrideBytes = es2Request.readbackSpec.rowStrideBytes,
                    shaderVariant = es2Request.selectedColorMode.toShaderVariant(),
                ),
            )
        },
        transformBuild: (FirstPlanRenderTransformPackageBuildRequest) -> FirstPlanRenderTransformPackageBuildResult = { transformRequest ->
            events += "transform"
            FirstPlanRenderTransformPackageBuilder.build(transformRequest)
        },
        encoderPrepare: suspend (PlanPreparationToken, FakeImageEncoderProvider, ImageEncoderRequest) -> ImageEncoderPreparationResult =
            { _, _, encoderRequest ->
                events += "encoder"
                ImageEncoderPreparationResult.Success(preparedEncoder(request = encoderRequest))
            },
    ): Es2RenderingPipelinePreparer =
        Es2RenderingPipelinePreparer(
            encoderPrepare = { token, provider, request ->
                @Suppress("UNCHECKED_CAST")
                encoderPrepare(token, provider as FakeImageEncoderProvider, request)
            },
            startupGlPrepareTimeoutMs = startupGlPrepareTimeoutMs,
            staticTransformPreflight = Es2StaticTransformPreflightOperation(preflight),
            es2ReadbackPrepare = Es2ReadbackPrepareOperation(es2Prepare),
            transformBuild = FirstPlanTransformBuildOperation(transformBuild),
        )

    private fun defaultPreflightPreparer(): Es2RenderingPipelinePreparer =
        Es2RenderingPipelinePreparer(
            encoderPrepare = ImageEncoderPrepareOperation { _, _, _ ->
                throw AssertionError("Encoder preparation should not run after default preflight failure.")
            },
            es2ReadbackPrepare = Es2ReadbackPrepareOperation {
                throw AssertionError("ES2 preparation should not run after default preflight failure.")
            },
            transformBuild = FirstPlanTransformBuildOperation {
                throw AssertionError("Transform build should not run after default preflight failure.")
            },
        )

    private fun request(
        plan: ScreenCaptureOutputPlan = nonTrivialOutputPlan(),
        startupRenderingGlAccess: StartupRenderingGlAccess = RecordingStartupRenderingGlAccess(),
    ): RenderingPipelinePrepareRequest =
        RenderingPipelinePrepareRequest(
            planPreparationToken = PlanPreparationToken(
                ownerToken = Any(),
                planToken = 1L,
                projectionTargetGeneration = 1L,
            ),
            outputPlan = plan,
            projectionTarget = ProjectionTargetSnapshot(
                generation = 1L,
                width = plan.captureTarget.width,
                height = plan.captureTarget.height,
                densityDpi = plan.captureGeometry.densityDpi,
                surface = FakeProjectionSurfaceHandle,
            ),
            projectionTargetHandle = TestProjectionTargetHandle(
                generation = 1L,
                width = plan.captureTarget.width,
                height = plan.captureTarget.height,
                densityDpi = plan.captureGeometry.densityDpi,
            ),
            startupRenderingGlAccess = startupRenderingGlAccess,
            encoderProvider = FakeImageEncoderProvider(),
        )

    private fun outputPlanRequest(
        plan: ScreenCaptureOutputPlan = nonTrivialOutputPlan(),
        startupRenderingGlAccess: StartupRenderingGlAccess = RecordingStartupRenderingGlAccess(),
        abandonGlLaneOnTimeout: Boolean = true,
    ): OutputPlanPrepareRequest =
        OutputPlanPrepareRequest(
            planPreparationToken = PlanPreparationToken(
                ownerToken = Any(),
                planToken = 1L,
                projectionTargetGeneration = 1L,
            ),
            outputPlan = plan,
            projectionTarget = ProjectionTargetSnapshot(
                generation = 1L,
                width = plan.captureTarget.width,
                height = plan.captureTarget.height,
                densityDpi = plan.captureGeometry.densityDpi,
                surface = FakeProjectionSurfaceHandle,
            ),
            projectionTargetHandle = TestProjectionTargetHandle(
                generation = 1L,
                width = plan.captureTarget.width,
                height = plan.captureTarget.height,
                densityDpi = plan.captureGeometry.densityDpi,
            ),
            planRenderingAccess = startupRenderingGlAccess.asPlanRenderingAccess(),
            encoderProvider = FakeImageEncoderProvider(),
            abandonGlLaneOnTimeout = abandonGlLaneOnTimeout,
        )

    private fun nonTrivialOutputPlan(
        parameters: ScreenCaptureParameters = ScreenCaptureParameters(
            sourceRegion = SourceRegion.RightHalf,
            crop = CropInsetsPx(left = 1, top = 2, right = 3, bottom = 4),
            rotation = Rotation.Degrees90,
            outputSize = OutputSize.TargetSize(width = 36, height = 20, contentMode = ContentMode.AspectFit),
        ),
    ): ScreenCaptureOutputPlan {
        val result = ScreenCaptureOutputPlanner(
            OutputPlanningLimits(
                maxOutputPixels = 268_435_456,
                maxEncodedBytes = 1_024,
            ),
        ).plan(
            geometry = CaptureGeometry(
                widthPx = 101,
                heightPx = 77,
                densityDpi = 320,
                source = CaptureGeometrySource.MetricsProvider,
            ),
            parameters = parameters,
        )
        return (result as OutputPlanResult.Success).plan
    }

    private fun preparedEs2Resources(
        plan: ScreenCaptureOutputPlan,
        shaderVariant: Es2RenderingShaderVariant = plan.colorMode.toShaderVariant(),
    ): PreparedEs2RenderingReadbackResources =
        preparedEs2Fixture(
            width = plan.finalImageSize.width,
            height = plan.finalImageSize.height,
            rowStrideBytes = plan.rowStrideBytes,
            shaderVariant = shaderVariant,
        ).resources

    private fun preparedEs2Resources(
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        shaderVariant: Es2RenderingShaderVariant = Es2RenderingShaderVariant.OriginalExternalOes,
    ): PreparedEs2RenderingReadbackResources =
        preparedEs2Fixture(
            width = width,
            height = height,
            rowStrideBytes = rowStrideBytes,
            shaderVariant = shaderVariant,
        ).resources

    private fun preparedEs2Fixture(
        plan: ScreenCaptureOutputPlan,
        shaderVariant: Es2RenderingShaderVariant = plan.colorMode.toShaderVariant(),
        retirement: RecordingRetirementLane = RecordingRetirementLane(),
    ): PreparedEs2Fixture =
        preparedEs2Fixture(
            width = plan.finalImageSize.width,
            height = plan.finalImageSize.height,
            rowStrideBytes = plan.rowStrideBytes,
            shaderVariant = shaderVariant,
            retirement = retirement,
        )

    private fun preparedEs2Fixture(
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        shaderVariant: Es2RenderingShaderVariant = Es2RenderingShaderVariant.OriginalExternalOes,
        retirement: RecordingRetirementLane = RecordingRetirementLane(),
    ): PreparedEs2Fixture {
        return PreparedEs2Fixture(
            retirement = retirement,
            resources = PreparedEs2RenderingReadbackResources(
                retirementLane = retirement,
                glObjects = preparedGlObjects(shaderVariant),
                width = width,
                height = height,
                rowStrideBytes = rowStrideBytes,
                readbackBuffer = ByteBuffer.allocateDirect(rowStrideBytes * height),
            ),
        )
    }

    private fun preparedEncoder(
        request: ImageEncoderRequest,
        encoder: FakeImageEncoder = FakeImageEncoder(
            info = ImageEncoderInfo(
                providerId = "fake-provider",
                outputFormat = dev.dmkr.screencaptureengine.EncodedImageFormats.Jpeg,
                backendName = "fake",
            ),
        ),
    ): PreparedImageEncoderResources =
        PreparedImageEncoderResources(
            encoder = encoder,
            info = encoder.info,
            request = request,
            cleanup = ImmediateProviderEncoderCleanup,
        )

    private fun ScreenCaptureOutputPlan.withReadbackShape(
        encoderRequest: ImageEncoderRequest,
        rowStrideBytes: Int,
        rgbaByteCount: Long,
    ): ScreenCaptureOutputPlan =
        ScreenCaptureOutputPlan(
            captureGeometry = captureGeometry,
            captureTarget = captureTarget,
            sourceRegion = sourceRegion,
            crop = crop,
            appliedSourceRect = appliedSourceRect,
            orientedContentSize = orientedContentSize,
            outputSize = outputSize,
            finalImageSize = finalImageSize,
            rotation = rotation,
            mirror = mirror,
            colorMode = colorMode,
            readbackMode = readbackMode,
            frameRate = frameRate,
            encoderRequest = encoderRequest,
            rowStrideBytes = rowStrideBytes,
            rgbaByteCount = rgbaByteCount,
        )

    private fun ScreenCaptureOutputPlan.withAppliedSourceRect(appliedSourceRect: ImageRect): ScreenCaptureOutputPlan =
        ScreenCaptureOutputPlan(
            captureGeometry = captureGeometry,
            captureTarget = captureTarget,
            sourceRegion = sourceRegion,
            crop = crop,
            appliedSourceRect = appliedSourceRect,
            orientedContentSize = orientedContentSize,
            outputSize = outputSize,
            finalImageSize = finalImageSize,
            rotation = rotation,
            mirror = mirror,
            colorMode = colorMode,
            readbackMode = readbackMode,
            frameRate = frameRate,
            encoderRequest = encoderRequest,
            rowStrideBytes = rowStrideBytes,
            rgbaByteCount = rgbaByteCount,
        )

    private fun RenderingPipelinePreparationResult.failure(): RenderingPipelinePreparationFailure =
        (this as RenderingPipelinePreparationResult.Failure).failure

    private fun ColorMode.toShaderVariant(): Es2RenderingShaderVariant =
        when (this) {
            ColorMode.Original -> Es2RenderingShaderVariant.OriginalExternalOes
            ColorMode.Grayscale -> Es2RenderingShaderVariant.GrayscaleExternalOes
        }

    private class RecordingStartupRenderingGlAccess(
        private val events: MutableList<String>? = null,
        private val failure: Throwable? = null,
        private val suspendBeforeBlock: Boolean = false,
        private val completeSuccessfulBlockAfterCancellation: Boolean = false,
        private val runBlockOnWorkerAndSuspendCaller: Boolean = false,
        private val awaitBeforeCallerSuspend: CountDownLatch? = null,
    ) : StartupRenderingGlAccess {
        var accessCount = 0
        var abandonCount = 0
        override val isGlLaneAbandoned: Boolean
            get() = abandonCount > 0

        override fun abandonGlLane() {
            abandonCount++
        }

        override suspend fun <T> withCurrentStartupRenderingTarget(
            target: ProjectionTargetHandle,
            generation: Long,
            onCancellation: (T) -> Unit,
            block: StartupRenderingGlScope.() -> T,
        ): T {
            accessCount++
            events?.add("glAccess")
            if (runBlockOnWorkerAndSuspendCaller) {
                val testTarget = target as TestProjectionTargetHandle
                val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                Thread {
                    try {
                        val result = block(
                            RecordingStartupRenderingGlScope(
                                target = testTarget,
                                abandonment = this,
                            ),
                        )
                        onCancellation(result)
                    } catch (cause: Throwable) {
                        workerFailure.set(cause)
                    }
                }.start()
                check(awaitBeforeCallerSuspend?.await(1, TimeUnit.SECONDS) != false) {
                    "Worker did not enter ES2 preparation."
                }
                workerFailure.get()?.let { throw it }
                delay(Duration.INFINITE)
            }
            if (completeSuccessfulBlockAfterCancellation) {
                try {
                    delay(Duration.INFINITE)
                } catch (cancellation: CancellationException) {
                    val testTarget = target as TestProjectionTargetHandle
                    val result = block(
                        RecordingStartupRenderingGlScope(
                            target = testTarget,
                            abandonment = this,
                        ),
                    )
                    onCancellation(result)
                    throw cancellation
                }
            }
            if (suspendBeforeBlock) delay(Duration.INFINITE)
            failure?.let { throw it }
            val testTarget = target as TestProjectionTargetHandle
            return block(
                RecordingStartupRenderingGlScope(
                    target = testTarget,
                    abandonment = this,
                ),
            )
        }
    }

    private class RecordingStartupRenderingGlScope(
        target: TestProjectionTargetHandle,
        override val abandonment: GlLaneAbandonment,
    ) : StartupRenderingGlScope {
        override val gl: GlLaneScope = TestGlLaneScope
        override val projectionTarget: ProjectionTargetGlScope = TestProjectionTargetGlScope(target)
        override val retirementLane: GlResourceRetirementLane = RecordingRetirementLane()
    }

    private data object TestGlLaneScope : GlLaneScope {
        override fun targetSizeLimits(): ProjectionTargetSizeLimits =
            ProjectionTargetSizeLimits(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)

        override fun checkCurrentContext(operation: String) = Unit

        override fun checkGl(operation: String) = Unit
    }

    private class TestProjectionTargetGlScope(
        target: TestProjectionTargetHandle,
    ) : ProjectionTargetGlScope {
        override val generation: Long = target.generation
        override val width: Int = target.width
        override val height: Int = target.height
        override val densityDpi: Int = target.densityDpi

        override fun validateExternalOesTexture() = Unit
    }

    private class TestProjectionTargetHandle(
        override val generation: Long,
        override val width: Int,
        override val height: Int,
        override val densityDpi: Int,
    ) : ProjectionTargetHandle {
        override val surface: ProjectionSurfaceHandle = FakeProjectionSurfaceHandle
        override fun close() = Unit
    }

    private open class RecordingRetirementLane : GlResourceRetirementLane {
        var retireCount = 0

        override fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean {
            retireCount++
            return false
        }
    }

    private class ThrowingRetirementLane(
        private val failure: Throwable,
    ) : RecordingRetirementLane() {
        override fun retireGlResources(label: String, block: GlLaneScope.() -> Unit): Boolean {
            retireCount++
            throw failure
        }
    }

    private class PreparedEs2Fixture(
        val retirement: RecordingRetirementLane,
        val resources: PreparedEs2RenderingReadbackResources,
    )

    private fun preparedGlObjects(shaderVariant: Es2RenderingShaderVariant): PreparedEs2RenderingReadbackGlObjects =
        PreparedEs2RenderingReadbackGlObjects(
            outputTextureId = 11,
            outputFramebufferId = 12,
            outputRenderbufferId = 13,
            programId = 14,
            vertexShaderId = 15,
            fragmentShaderId = 16,
            programBinding = Es2RenderingProgramBindingMetadata(
                programId = 14,
                shaderVariant = shaderVariant,
                attributeLocations = Es2RenderingProgramAttributeLocations(
                    position = 1,
                    textureCoordinate = 2,
                ),
                uniformLocations = Es2RenderingProgramUniformLocations(
                    externalOesTextureSampler = 3,
                    textureMatrix = 4,
                ),
                dynamicOesMatrixUniformSlot = Es2DynamicOesMatrixUniformSlot(
                    uniformName = "uTexMatrix",
                    location = 4,
                    matrixElementCount = 16,
                    compositionRule = Es2OesMatrixCompositionRule.RuntimeOesMatrixComposedWithStaticPlanTransform,
                ),
            ),
        )
}
