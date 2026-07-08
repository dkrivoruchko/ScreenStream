package dev.dmkr.screencaptureengine.internal.rendering.pipeline

import dev.dmkr.screencaptureengine.internal.encoding.provider.FakeImageEncoder
import dev.dmkr.screencaptureengine.internal.encoding.provider.ImmediateProviderEncoderCleanup
import dev.dmkr.screencaptureengine.internal.encoding.provider.PreparedImageEncoderResources
import dev.dmkr.screencaptureengine.internal.lifecycle.InitialRuntimeResourceOwner
import dev.dmkr.screencaptureengine.internal.lifecycle.PreActiveInitialRuntimePlan
import dev.dmkr.screencaptureengine.internal.lifecycle.PreActiveRuntimeOwner
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot
import dev.dmkr.screencaptureengine.internal.rendering.es2.Es2ReadbackSpec
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanEs2ProgramBinding
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanEs2ShaderVariant
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanRenderTransformPackage
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanRenderTransformPackageBuildRequest
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanRenderTransformPackageBuildResult
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanRenderTransformPackageBuilder

internal suspend fun PreActiveRuntimeOwner.transferToInitialRuntimeResourceOwner(
    preparedPlan: PreActiveInitialRuntimePlan,
): InitialRuntimeResourceOwner {
    val preparedResources = prepareInitialRenderingPipeline(
        preparedPlan = preparedPlan,
        preparer = TestRenderingPipelinePreparer(),
    )
    return transferToInitialRuntimeResourceOwner(
        preparedPlan = preparedPlan,
        preparedResources = preparedResources,
    )
}

internal class TestRenderingPipelinePreparer(
    private val resourcesFactory: () -> TestPreparedRenderingPipelineResource = ::TestPreparedRenderingPipelineResource,
    private val renderTransformPackageFactory: (RenderingPipelinePrepareRequest) -> FirstPlanRenderTransformPackage =
        ::testRenderTransformPackage,
) : RenderingPipelinePreparer {
    val requests = mutableListOf<RenderingPipelinePrepareRequest>()
    val preparedResources = mutableListOf<TestPreparedRenderingPipelineResource>()
    val preparedEncoders = mutableListOf<FakeImageEncoder>()
    var prepareFailure: Throwable? = null
    var preparationFailure: RenderingPipelinePreparationFailure? = null
    var beforeFailure: (() -> Unit)? = null
    var beforePrepare: (suspend () -> Unit)? = null
    var afterPrepare: (() -> Unit)? = null
    var beforeReturn: (suspend () -> Unit)? = null

    override suspend fun prepareInitialRenderingPipeline(
        request: RenderingPipelinePrepareRequest,
    ): RenderingPipelinePreparationResult {
        requests += request
        beforePrepare?.invoke()
        preparationFailure?.let {
            beforeFailure?.invoke()
            return RenderingPipelinePreparationResult.Failure(it)
        }
        prepareFailure?.let {
            beforeFailure?.invoke()
            throw it
        }
        val resources = resourcesFactory()
        val renderTransformPackage = renderTransformPackageFactory(request)
        val encoder = FakeImageEncoder()
        preparedResources += resources
        preparedEncoders += encoder
        afterPrepare?.invoke()
        beforeReturn?.invoke()
        return RenderingPipelinePreparationResult.Success(
            PreparedRenderingPipelineComponents(
                readbackResources = resources,
                renderTransformPackage = renderTransformPackage,
                encoderResources = PreparedImageEncoderResources(
                    encoder = encoder,
                    info = encoder.info,
                    request = request.outputPlan.encoderRequest,
                    cleanup = ImmediateProviderEncoderCleanup,
                ),
            ),
        )
    }
}

internal fun testRenderTransformPackage(
    request: RenderingPipelinePrepareRequest,
): FirstPlanRenderTransformPackage =
    testRenderTransformPackage(
        outputPlan = request.outputPlan,
        projectionTarget = request.projectionTarget,
    )

internal fun testRenderTransformPackage(
    outputPlan: ScreenCaptureOutputPlan,
    projectionTarget: ProjectionTargetSnapshot,
): FirstPlanRenderTransformPackage {
    val result = FirstPlanRenderTransformPackageBuilder.build(
        FirstPlanRenderTransformPackageBuildRequest(
            outputPlan = outputPlan,
            projectionTarget = projectionTarget,
            readbackSpec = Es2ReadbackSpec(
                width = outputPlan.finalImageSize.width,
                height = outputPlan.finalImageSize.height,
                rowStrideBytes = outputPlan.rowStrideBytes,
                byteCount = outputPlan.rgbaByteCount,
                inputFormat = outputPlan.encoderRequest.inputFormat,
                readbackMode = outputPlan.readbackMode,
            ),
            programBinding = FirstPlanEs2ProgramBinding(
                shaderVariant = FirstPlanEs2ShaderVariant.OriginalColor,
                programId = 10,
                positionAttributeLocation = 1,
                textureCoordinateAttributeLocation = 2,
                textureSamplerUniformLocation = 3,
                textureMatrixUniformLocation = 4,
            ),
        ),
    )
    return when (result) {
        is FirstPlanRenderTransformPackageBuildResult.Success -> result.renderTransformPackage
        is FirstPlanRenderTransformPackageBuildResult.Failure -> error(result.message)
    }
}

internal class TestPreparedRenderingPipelineResource : PreparedRenderingReadbackResources {
    var closeCount = 0

    override fun close() {
        closeCount++
    }
}
