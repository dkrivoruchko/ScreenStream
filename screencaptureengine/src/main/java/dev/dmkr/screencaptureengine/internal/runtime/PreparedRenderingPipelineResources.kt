package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan

/**
 * Prepares readback and encoder resources for the initial output plan under a plan-preparation token.
 */
internal fun interface RenderingPipelinePreparer {
    suspend fun prepareInitialRenderingPipeline(request: RenderingPipelinePrepareRequest): RenderingPipelinePreparationResult
}

/**
 * Immutable preparation input derived from the frozen initial output plan and current projection target.
 */
internal class RenderingPipelinePrepareRequest internal constructor(
    internal val planPreparationToken: PlanPreparationToken,
    internal val outputPlan: ScreenCaptureOutputPlan,
    internal val projectionTarget: ProjectionTargetSnapshot,
    internal val projectionTargetHandle: ProjectionTargetHandle,
    internal val startupRenderingGlAccess: StartupRenderingGlAccess,
    internal val encoderProvider: ImageEncoderProvider,
)

internal interface PreparedRenderingReadbackResources : AutoCloseable

/**
 * Closeable resource pair returned by a preparer before ownership is accepted by [PreActiveRuntimeOwner].
 */
internal class PreparedRenderingPipelineComponents internal constructor(
    internal val readbackResources: PreparedRenderingReadbackResources,
    internal val renderTransformPackage: FirstPlanRenderTransformPackage,
    internal val encoderResources: PreparedImageEncoderResources,
) : AutoCloseable {
    private val lock = Any()
    private var moved = false
    private var closed = false

    internal fun moveToPreActiveOwner(
        planPreparationToken: PlanPreparationToken,
        ownerToken: Any,
        planToken: Long,
        outputPlan: ScreenCaptureOutputPlan,
        projectionTarget: ProjectionTargetSnapshot,
        projectionTargetGeneration: Long,
        startupRenderingGlAccess: StartupRenderingGlAccess,
    ): PreparedRenderingPipelineResources {
        synchronized(lock) {
            check(!moved) { "PreparedRenderingPipelineComponents were already moved." }
            check(!closed) { "PreparedRenderingPipelineComponents are closed." }
            validateRenderTransformPackageConsistency(
                renderTransformPackage = renderTransformPackage,
                outputPlan = outputPlan,
                projectionTarget = projectionTarget,
                encoderResources = encoderResources,
            )
            moved = true
        }
        return PreparedRenderingPipelineResources(
            planPreparationToken = planPreparationToken,
            ownerToken = ownerToken,
            planToken = planToken,
            outputPlan = outputPlan,
            projectionTarget = projectionTarget,
            projectionTargetGeneration = projectionTargetGeneration,
            startupRenderingGlAccess = startupRenderingGlAccess,
            readbackResources = readbackResources,
            renderTransformPackage = renderTransformPackage,
            encoderResources = encoderResources,
        )
    }

    override fun close() {
        synchronized(lock) {
            if (closed || moved) return
            closed = true
        }
        closeRenderingPipelineResources(
            readbackResources = readbackResources,
            encoderResources = encoderResources,
        )
    }
}

/**
 * Move-only prepared resources accepted by [PreActiveRuntimeOwner] and fenced for initial runtime handoff.
 */
internal class PreparedRenderingPipelineResources internal constructor(
    internal val planPreparationToken: PlanPreparationToken,
    internal val ownerToken: Any,
    internal val planToken: Long,
    private val outputPlan: ScreenCaptureOutputPlan,
    private val projectionTarget: ProjectionTargetSnapshot,
    internal val projectionTargetGeneration: Long,
    internal val startupRenderingGlAccess: StartupRenderingGlAccess,
    private val readbackResources: PreparedRenderingReadbackResources,
    internal val renderTransformPackage: FirstPlanRenderTransformPackage,
    private val encoderResources: PreparedImageEncoderResources,
) : AutoCloseable {
    private val lock = Any()
    private var moved = false
    private var closed = false

    internal val isOpenForHandoff: Boolean
        get() = synchronized(lock) { !moved && !closed }

    internal fun moveToInitialRuntimeOwner(): InitialRuntimePreparedRenderingPipelineResources {
        synchronized(lock) {
            check(!moved) { "PreparedRenderingPipelineResources were already moved." }
            check(!closed) { "PreparedRenderingPipelineResources are closed." }
            validateRenderTransformPackageConsistency(
                renderTransformPackage = renderTransformPackage,
                outputPlan = outputPlan,
                projectionTarget = projectionTarget,
                encoderResources = encoderResources,
            )
            moved = true
        }
        return InitialRuntimePreparedRenderingPipelineResources(
            readbackResources = readbackResources,
            renderTransformPackage = renderTransformPackage,
            encoderResources = encoderResources,
        )
    }

    override fun close() {
        synchronized(lock) {
            if (closed || moved) return
            closed = true
        }
        closeRenderingPipelineResources(
            readbackResources = readbackResources,
            encoderResources = encoderResources,
        )
    }
}

/**
 * Prepared rendering resources after ownership has moved into [InitialRuntimeResourceOwner].
 */
internal class InitialRuntimePreparedRenderingPipelineResources internal constructor(
    private val readbackResources: PreparedRenderingReadbackResources,
    internal val renderTransformPackage: FirstPlanRenderTransformPackage,
    private val encoderResources: PreparedImageEncoderResources,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        closeRenderingPipelineResources(
            readbackResources = readbackResources,
            encoderResources = encoderResources,
        )
    }
}

private fun closeRenderingPipelineResources(
    readbackResources: PreparedRenderingReadbackResources,
    encoderResources: PreparedImageEncoderResources,
) {
    val cleanupFailures = CleanupFailureCollector()
    cleanupFailures.collect { readbackResources.close() }
    cleanupFailures.collect { encoderResources.close() }
    cleanupFailures.throwIfAny()
}

private fun validateRenderTransformPackageConsistency(
    renderTransformPackage: FirstPlanRenderTransformPackage,
    outputPlan: ScreenCaptureOutputPlan,
    projectionTarget: ProjectionTargetSnapshot,
    encoderResources: PreparedImageEncoderResources,
) {
    val expectedLogicalContentSize = outputPlan.captureGeometry.let { geometry ->
        Size(width = geometry.widthPx, height = geometry.heightPx)
    }
    val expectedCaptureTargetSize = Size(
        width = outputPlan.captureTarget.width,
        height = outputPlan.captureTarget.height,
    )
    val readbackShape = renderTransformPackage.readbackShape

    check(renderTransformPackage.projectionTargetGeneration == projectionTarget.generation) {
        "FirstPlanRenderTransformPackage projection target generation does not match handoff target."
    }
    check(projectionTarget.width == outputPlan.captureTarget.width && projectionTarget.height == outputPlan.captureTarget.height) {
        "Projection target dimensions do not match handoff output plan."
    }
    check(projectionTarget.densityDpi == outputPlan.captureGeometry.densityDpi) {
        "Projection target density does not match handoff output plan."
    }
    check(renderTransformPackage.logicalContentSize == expectedLogicalContentSize) {
        "FirstPlanRenderTransformPackage logical content size does not match handoff output plan."
    }
    check(renderTransformPackage.captureTargetSize == expectedCaptureTargetSize) {
        "FirstPlanRenderTransformPackage capture target size does not match handoff output plan."
    }
    check(renderTransformPackage.appliedSourceRect == outputPlan.appliedSourceRect) {
        "FirstPlanRenderTransformPackage applied source rect does not match handoff output plan."
    }
    check(renderTransformPackage.colorMode == outputPlan.colorMode) {
        "FirstPlanRenderTransformPackage color mode does not match handoff output plan."
    }
    check(renderTransformPackage.outputViewport.x == 0 && renderTransformPackage.outputViewport.y == 0) {
        "FirstPlanRenderTransformPackage output viewport origin does not match handoff output plan."
    }
    check(
        renderTransformPackage.outputViewport.width == outputPlan.finalImageSize.width &&
                renderTransformPackage.outputViewport.height == outputPlan.finalImageSize.height,
    ) {
        "FirstPlanRenderTransformPackage output viewport size does not match handoff output plan."
    }
    check(encoderResources.request == outputPlan.encoderRequest) {
        "PreparedImageEncoderResources request does not match handoff output plan."
    }
    check(readbackShape.width == outputPlan.encoderRequest.width && readbackShape.height == outputPlan.encoderRequest.height) {
        "FirstPlanRenderTransformPackage readback size does not match handoff encoder request."
    }
    check(readbackShape.rowStrideBytes == outputPlan.encoderRequest.rowStrideBytes) {
        "FirstPlanRenderTransformPackage row stride does not match handoff encoder request."
    }
    check(readbackShape.byteCount == outputPlan.rgbaByteCount) {
        "FirstPlanRenderTransformPackage byte count does not match handoff output plan."
    }
    check(readbackShape.inputFormat == outputPlan.encoderRequest.inputFormat) {
        "FirstPlanRenderTransformPackage input format does not match handoff encoder request."
    }
    check(readbackShape.readbackMode == outputPlan.readbackMode) {
        "FirstPlanRenderTransformPackage readback mode does not match handoff output plan."
    }
}
