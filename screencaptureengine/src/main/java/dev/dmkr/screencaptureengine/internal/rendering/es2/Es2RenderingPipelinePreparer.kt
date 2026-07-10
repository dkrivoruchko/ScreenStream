package dev.dmkr.screencaptureengine.internal.rendering.es2

import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.ReadbackMode
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImageEncoderPreparationResult
import dev.dmkr.screencaptureengine.internal.lifecycle.PlanPreparationToken
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.OutputPlanPreparationResult
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.OutputPlanPrepareRequest
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.OutputPlanPreparer
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.PlanRenderingAccess
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.PreparedRenderingPipelineComponents
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationFailure
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparationResult
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePrepareRequest
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.RenderingPipelinePreparer
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlAccessException
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlAccessFailureReason
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Prepares the initial ES2 rendering pipeline up to readiness for runtime handoff.
 *
 * This preparer performs token-current checks, cheap static transform validation, startup GL access,
 * ES2 render/readback resource readiness, first-plan transform package creation, and encoder
 * preparation. It owns successful ES2 and encoder resources until returning them, and closes them
 * when a later stage fails or the preparation token becomes stale.
 *
 * A stale token observed before allocation returns [RenderingPipelinePreparationResult.LifecycleStale]
 * instead of a resource failure. Startup GL access failures are classified from typed access failures
 * and the current preparation stage; projection-target ownership/generation failures map to
 * [ScreenCaptureProblemKind.GlInvariantViolation]. Timeout invalidates the token and lets the GL
 * access cancellation hook close any late ES2 success. Startup requests abandon their failed GL
 * lane; runtime replacement requests preserve the live lane so the previous plan remains usable.
 *
 * This boundary does not install a frame loop, call `SurfaceTexture.updateTexImage()`,
 * call `glReadPixels`, invoke encoder `encode`, publish frames, or expose public session state.
 */
internal class Es2RenderingPipelinePreparer internal constructor(
    private val encoderPrepare: ImageEncoderPrepareOperation,
    private val startupGlPrepareTimeoutMs: Long = STARTUP_GL_PREPARE_TIMEOUT_MS,
    private val es2ReadbackPreparer: Es2RenderingReadbackResourcePreparer = Es2RenderingReadbackResourcePreparer(),
    private val staticTransformPreflight: Es2StaticTransformPreflightOperation =
        Es2StaticTransformPreflightOperation(::defaultStaticTransformPreflight),
    private val es2ReadbackPrepare: Es2ReadbackPrepareOperation =
        Es2ReadbackPrepareOperation(es2ReadbackPreparer::prepare),
    private val transformBuild: FirstPlanTransformBuildOperation =
        FirstPlanTransformBuildOperation(FirstPlanRenderTransformPackageBuilder::build),
) : RenderingPipelinePreparer, OutputPlanPreparer {
    init {
        require(startupGlPrepareTimeoutMs > 0L) {
            "startupGlPrepareTimeoutMs must be positive, was $startupGlPrepareTimeoutMs"
        }
    }

    override suspend fun prepareInitialRenderingPipeline(
        request: RenderingPipelinePrepareRequest,
    ): RenderingPipelinePreparationResult =
        prepareOutputPlan(request)

    override suspend fun prepareOutputPlan(
        request: OutputPlanPrepareRequest,
    ): OutputPlanPreparationResult {
        val token = request.planPreparationToken
        if (!token.isCurrent) return RenderingPipelinePreparationResult.LifecycleStale

        staticTransformPreflight.preflight(request)?.let { failure ->
            return RenderingPipelinePreparationResult.Failure(failure)
        }

        val readbackSpec = when (val specResult = buildReadbackSpec(request.outputPlan)) {
            is ReadbackSpecBuildResult.Failure -> {
                return RenderingPipelinePreparationResult.Failure(specResult.failure)
            }

            is ReadbackSpecBuildResult.Success -> specResult.readbackSpec
        }

        val es2Resources = when (val es2Result = prepareEs2Resources(request = request, readbackSpec = readbackSpec)) {
            is Es2PipelineReadbackResult.Failure -> {
                return if (!token.isCurrent && es2Result.staleTokenDominates) {
                    RenderingPipelinePreparationResult.LifecycleStale
                } else {
                    RenderingPipelinePreparationResult.Failure(es2Result.failure)
                }
            }

            Es2PipelineReadbackResult.LifecycleStale -> return RenderingPipelinePreparationResult.LifecycleStale
            is Es2PipelineReadbackResult.Success -> es2Result.resources
        }

        if (!token.isCurrent) {
            closeIgnoringFailure(es2Resources)
            return RenderingPipelinePreparationResult.LifecycleStale
        }

        val transformPackage = when (
            val transformResult = buildTransformPackage(
                outputPlan = request.outputPlan,
                projectionTarget = request.projectionTarget,
                readbackSpec = readbackSpec,
                es2Resources = es2Resources,
            )
        ) {
            is TransformPackagePreparationResult.Failure -> {
                closeIgnoringFailure(es2Resources)
                return if (token.isCurrent) {
                    RenderingPipelinePreparationResult.Failure(transformResult.failure)
                } else {
                    RenderingPipelinePreparationResult.LifecycleStale
                }
            }

            is TransformPackagePreparationResult.Success -> transformResult.renderTransformPackage
        }

        if (!token.isCurrent) {
            closeIgnoringFailure(es2Resources)
            return RenderingPipelinePreparationResult.LifecycleStale
        }

        val preparedEncoderResources = try {
            when (
                val encoderResult = prepareEncoder(
                    token = token,
                    provider = request.encoderProvider,
                    request = request.outputPlan.encoderRequest,
                )
            ) {
                is ImageEncoderPreparationResult.Failure -> {
                    closeIgnoringFailure(es2Resources)
                    return if (token.isCurrent) {
                        RenderingPipelinePreparationResult.Failure(encoderResult.toRenderingPipelineFailure())
                    } else {
                        RenderingPipelinePreparationResult.LifecycleStale
                    }
                }

                is ImageEncoderPreparationResult.Success -> {
                    encoderResult.preparedEncoder
                }
            }
        } catch (cancellation: CancellationException) {
            closeIgnoringFailure(es2Resources)
            throw cancellation
        } catch (cause: Throwable) {
            closeIgnoringFailure(es2Resources)
            if (!token.isCurrent) {
                return RenderingPipelinePreparationResult.LifecycleStale
            }
            throw cause
        }

        if (!token.isCurrent) {
            closeIgnoringFailure(es2Resources)
            closeIgnoringFailure(preparedEncoderResources)
            return RenderingPipelinePreparationResult.LifecycleStale
        }

        return RenderingPipelinePreparationResult.Success(
            PreparedRenderingPipelineComponents(
                readbackResources = es2Resources,
                renderTransformPackage = transformPackage,
                encoderResources = preparedEncoderResources,
            ),
        )
    }

    private suspend fun prepareEs2Resources(
        request: OutputPlanPrepareRequest,
        readbackSpec: Es2ReadbackSpec,
    ): Es2PipelineReadbackResult {
        val stage = AtomicReference(StartupGlPreparationStage.AcquiringCurrentTarget)
        return try {
            val result = withTimeoutOrNull(startupGlPrepareTimeoutMs.milliseconds) {
                request.planRenderingAccess.withCurrentPlanRenderingTarget(
                    target = request.projectionTargetHandle,
                    generation = request.planPreparationToken.projectionTargetGeneration,
                    onCancellation = ::closeSuccessfulEs2Preparation,
                ) {
                    stage.set(StartupGlPreparationStage.PreparingEs2Resources)
                    es2ReadbackPrepare.prepare(
                        Es2RenderingReadbackPrepareRequest(
                            gl = gl,
                            projectionTarget = projectionTarget,
                            projectionTargetSpec = Es2ProjectionTargetSpec(
                                width = request.projectionTarget.width,
                                height = request.projectionTarget.height,
                                densityDpi = request.projectionTarget.densityDpi,
                                generation = request.projectionTarget.generation,
                            ),
                            readbackSpec = readbackSpec,
                            selectedColorMode = request.outputPlan.colorMode,
                            retirementLane = retirementLane,
                        ),
                    )
                }
            } ?: return glPreparationTimedOut(
                request = request,
                stage = stage.get(),
            )

            when (result) {
                is Es2RenderingReadbackPreparationResult.Failure ->
                    Es2PipelineReadbackResult.Failure(result.toRenderingPipelineFailure())

                is Es2RenderingReadbackPreparationResult.Success ->
                    Es2PipelineReadbackResult.Success(result.resources)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Throwable) {
            if (!request.planPreparationToken.isCurrent) {
                Es2PipelineReadbackResult.LifecycleStale
            } else {
                Es2PipelineReadbackResult.Failure(
                    classifyPlanRenderingAccessFailure(
                        access = request.planRenderingAccess,
                        stage = stage.get(),
                        cause = cause,
                    ),
                )
            }
        }
    }

    private fun glPreparationTimedOut(
        request: OutputPlanPrepareRequest,
        stage: StartupGlPreparationStage,
    ): Es2PipelineReadbackResult.Failure {
        request.planPreparationToken.invalidate()
        if (request.abandonGlLaneOnTimeout) {
            request.planRenderingAccess.abandonGlLane()
        }
        val kind = when (stage) {
            StartupGlPreparationStage.AcquiringCurrentTarget -> ScreenCaptureProblemKind.GlInitializationFailed
            StartupGlPreparationStage.PreparingEs2Resources -> ScreenCaptureProblemKind.GlResourceFailure
        }
        return Es2PipelineReadbackResult.Failure(
            failure = RenderingPipelinePreparationFailure(
                kind = kind,
                message = "ES2 output-plan preparation timed out after $startupGlPrepareTimeoutMs ms.",
                cause = null,
            ),
            staleTokenDominates = false,
        )
    }

    private fun buildTransformPackage(
        outputPlan: ScreenCaptureOutputPlan,
        projectionTarget: ProjectionTargetSnapshot,
        readbackSpec: Es2ReadbackSpec,
        es2Resources: PreparedEs2RenderingReadbackResources,
    ): TransformPackagePreparationResult =
        try {
            when (
                val result = transformBuild.build(
                    FirstPlanRenderTransformPackageBuildRequest(
                        outputPlan = outputPlan,
                        projectionTarget = projectionTarget,
                        readbackSpec = readbackSpec,
                        programBinding = es2Resources.programBinding.toFirstPlanEs2ProgramBinding(),
                    ),
                )
            ) {
                is FirstPlanRenderTransformPackageBuildResult.Failure ->
                    TransformPackagePreparationResult.Failure(result.toRenderingPipelineFailure())

                is FirstPlanRenderTransformPackageBuildResult.Success ->
                    TransformPackagePreparationResult.Success(result.renderTransformPackage)
            }
        } catch (cause: Throwable) {
            TransformPackagePreparationResult.Failure(
                RenderingPipelinePreparationFailure(
                    kind = ScreenCaptureProblemKind.InternalInvariantViolation,
                    message = "First-plan render transform package preparation failed.",
                    cause = cause,
                ),
            )
        }

    private suspend fun prepareEncoder(
        token: PlanPreparationToken,
        provider: ImageEncoderProvider,
        request: ImageEncoderRequest,
    ): ImageEncoderPreparationResult =
        encoderPrepare.prepare(token = token, provider = provider, request = request)
}

/**
 * Cheap static validation that runs before GL allocation, direct readback storage, or provider work.
 */
internal fun interface Es2StaticTransformPreflightOperation {
    fun preflight(request: OutputPlanPrepareRequest): RenderingPipelinePreparationFailure?
}

/**
 * Current-GL-lane ES2 resource preparation operation.
 *
 * The implementation validates shader/program, output target, and readback storage readiness only.
 */
internal fun interface Es2ReadbackPrepareOperation {
    fun prepare(request: Es2RenderingReadbackPrepareRequest): Es2RenderingReadbackPreparationResult
}

/**
 * Builds the retained first-plan transform package from already-prepared ES2 metadata.
 */
internal fun interface FirstPlanTransformBuildOperation {
    fun build(request: FirstPlanRenderTransformPackageBuildRequest): FirstPlanRenderTransformPackageBuildResult
}

/**
 * Prepares and validates an encoder through the isolated provider-preparation path.
 */
internal fun interface ImageEncoderPrepareOperation {
    suspend fun prepare(
        token: PlanPreparationToken,
        provider: ImageEncoderProvider,
        request: ImageEncoderRequest,
    ): ImageEncoderPreparationResult
}

private sealed class Es2PipelineReadbackResult private constructor() {
    class Success(val resources: PreparedEs2RenderingReadbackResources) : Es2PipelineReadbackResult()

    class Failure(
        val failure: RenderingPipelinePreparationFailure,
        val staleTokenDominates: Boolean = true,
    ) : Es2PipelineReadbackResult()

    data object LifecycleStale : Es2PipelineReadbackResult()
}

private sealed class TransformPackagePreparationResult private constructor() {
    class Success(val renderTransformPackage: FirstPlanRenderTransformPackage) : TransformPackagePreparationResult()

    class Failure(val failure: RenderingPipelinePreparationFailure) : TransformPackagePreparationResult()
}

private sealed class ReadbackSpecBuildResult private constructor() {
    class Success(val readbackSpec: Es2ReadbackSpec) : ReadbackSpecBuildResult()

    class Failure(val failure: RenderingPipelinePreparationFailure) : ReadbackSpecBuildResult()
}

private enum class StartupGlPreparationStage {
    AcquiringCurrentTarget,
    PreparingEs2Resources,
}

private const val STARTUP_GL_PREPARE_TIMEOUT_MS: Long = 5_000L
private const val RGBA_8888_BYTES_PER_PIXEL: Int = 4

private fun defaultStaticTransformPreflight(request: OutputPlanPrepareRequest): RenderingPipelinePreparationFailure? {
    val plan = request.outputPlan
    return when {
        plan.captureGeometry.widthPx <= 0 || plan.captureGeometry.heightPx <= 0 -> RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.OutputPlanInvalid,
            message = "First-plan capture geometry is empty.",
            cause = null,
        )

        plan.captureTarget.width <= 0 || plan.captureTarget.height <= 0 -> RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.OutputPlanInvalid,
            message = "First-plan capture target is empty.",
            cause = null,
        )

        plan.finalImageSize.width <= 0 || plan.finalImageSize.height <= 0 -> RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.OutputPlanInvalid,
            message = "First-plan final image size is empty.",
            cause = null,
        )

        plan.appliedSourceRect.width <= 0 || plan.appliedSourceRect.height <= 0 -> RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.OutputPlanInvalid,
            message = "First-plan source crop is empty.",
            cause = null,
        )

        !hasFinitePositiveStaticScale(plan) -> RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.OutputPlanInvalid,
            message = "First-plan static transform scale is not representable.",
            cause = null,
        )

        plan.readbackMode != ReadbackMode.Es2 -> RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.ReadbackUnavailable,
            message = "Initial ES2 rendering pipeline requires ES2 readback.",
            cause = null,
        )

        plan.encoderRequest.inputFormat != ImageEncoderInputFormat.Rgba8888SrgbOpaque -> RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.ReadbackUnavailable,
            message = "Initial ES2 rendering pipeline requires RGBA8888 sRGB opaque encoder input.",
            cause = null,
        )

        else -> validateStaticReadbackShape(plan)
    }
}

private fun hasFinitePositiveStaticScale(plan: ScreenCaptureOutputPlan): Boolean {
    val logicalWidth = plan.captureGeometry.widthPx.toDouble()
    val logicalHeight = plan.captureGeometry.heightPx.toDouble()
    val scaleX = plan.captureTarget.width.toDouble() / logicalWidth
    val scaleY = plan.captureTarget.height.toDouble() / logicalHeight
    val sourceWidth = plan.appliedSourceRect.width.toDouble() / logicalWidth
    val sourceHeight = plan.appliedSourceRect.height.toDouble() / logicalHeight
    return scaleX.isFinite() && scaleX > 0.0 &&
            scaleY.isFinite() && scaleY > 0.0 &&
            sourceWidth.isFinite() && sourceWidth > 0.0 &&
            sourceHeight.isFinite() && sourceHeight > 0.0
}

private fun validateStaticReadbackShape(plan: ScreenCaptureOutputPlan): RenderingPipelinePreparationFailure? {
    val expectedRowStrideBytes = checkedRgba8888RowStrideBytes(plan.finalImageSize.width)
        ?: return RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.ReadbackUnavailable,
            message = "Initial ES2 readback row stride overflowed.",
            cause = null,
        )
    val expectedByteCount = checkedMultiplyLong(expectedRowStrideBytes, plan.finalImageSize.height)
        ?: return RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.ReadbackUnavailable,
            message = "Initial ES2 readback byte count overflowed.",
            cause = null,
        )
    return when {
        plan.rowStrideBytes != expectedRowStrideBytes || plan.encoderRequest.rowStrideBytes != expectedRowStrideBytes ->
            RenderingPipelinePreparationFailure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "Initial ES2 readback requires tight RGBA8888 row stride.",
                cause = null,
            )

        plan.rgbaByteCount != expectedByteCount -> RenderingPipelinePreparationFailure(
            kind = ScreenCaptureProblemKind.ReadbackUnavailable,
            message = "Initial ES2 readback byte count does not match the output plan.",
            cause = null,
        )

        else -> null
    }
}

private fun buildReadbackSpec(outputPlan: ScreenCaptureOutputPlan): ReadbackSpecBuildResult =
    try {
        ReadbackSpecBuildResult.Success(
            Es2ReadbackSpec(
                width = outputPlan.finalImageSize.width,
                height = outputPlan.finalImageSize.height,
                rowStrideBytes = outputPlan.encoderRequest.rowStrideBytes,
                byteCount = outputPlan.rgbaByteCount,
                inputFormat = outputPlan.encoderRequest.inputFormat,
                readbackMode = outputPlan.readbackMode,
            ),
        )
    } catch (cause: Throwable) {
        ReadbackSpecBuildResult.Failure(
            RenderingPipelinePreparationFailure(
                kind = ScreenCaptureProblemKind.ReadbackUnavailable,
                message = "Initial ES2 readback spec could not be built.",
                cause = cause,
            ),
        )
    }

private fun classifyPlanRenderingAccessFailure(
    access: PlanRenderingAccess,
    stage: StartupGlPreparationStage,
    cause: Throwable,
): RenderingPipelinePreparationFailure {
    val kind = when {
        cause is StartupRenderingGlAccessException -> when (cause.reason) {
            StartupRenderingGlAccessFailureReason.ProjectionTargetOwnerMismatch,
            StartupRenderingGlAccessFailureReason.ProjectionTargetGenerationMismatch,
            StartupRenderingGlAccessFailureReason.ProjectionTargetClosed,
            StartupRenderingGlAccessFailureReason.ProjectionTargetOwnerClosed,
                -> ScreenCaptureProblemKind.GlInvariantViolation
        }

        access.isGlLaneAbandoned -> ScreenCaptureProblemKind.GlResourceFailure
        stage == StartupGlPreparationStage.AcquiringCurrentTarget -> ScreenCaptureProblemKind.GlInitializationFailed
        stage == StartupGlPreparationStage.PreparingEs2Resources -> ScreenCaptureProblemKind.GlResourceFailure
        else -> ScreenCaptureProblemKind.GlResourceFailure
    }
    return RenderingPipelinePreparationFailure(
        kind = kind,
        message = "Startup ES2 rendering GL access failed.",
        cause = cause.sanitizedStartupGlAccessCause(),
    )
}

private fun closeSuccessfulEs2Preparation(result: Es2RenderingReadbackPreparationResult) {
    if (result is Es2RenderingReadbackPreparationResult.Success) {
        closeIgnoringFailure(result.resources)
    }
}

private fun Es2RenderingReadbackPreparationResult.Failure.toRenderingPipelineFailure(): RenderingPipelinePreparationFailure =
    RenderingPipelinePreparationFailure(kind = kind, message = message, cause = cause)

private fun FirstPlanRenderTransformPackageBuildResult.Failure.toRenderingPipelineFailure(): RenderingPipelinePreparationFailure =
    RenderingPipelinePreparationFailure(kind = kind, message = message, cause = cause)

private fun ImageEncoderPreparationResult.Failure.toRenderingPipelineFailure(): RenderingPipelinePreparationFailure =
    RenderingPipelinePreparationFailure(kind = kind, message = message, cause = cause)

private fun Throwable.sanitizedStartupGlAccessCause(): Throwable =
    if (this is StartupRenderingGlAccessException) {
        IllegalStateException(message ?: "Startup rendering GL access invariant failed.")
    } else {
        this
    }

private fun checkedRgba8888RowStrideBytes(width: Int): Int? =
    try {
        Math.multiplyExact(width, RGBA_8888_BYTES_PER_PIXEL)
    } catch (_: ArithmeticException) {
        null
    }

private fun checkedMultiplyLong(left: Int, right: Int): Long? =
    try {
        Math.multiplyExact(left.toLong(), right.toLong())
    } catch (_: ArithmeticException) {
        null
    }

private fun closeIgnoringFailure(closeable: AutoCloseable) {
    runCatching { closeable.close() }
}
