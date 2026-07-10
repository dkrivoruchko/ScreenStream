package dev.dmkr.screencaptureengine.internal.rendering.pipeline

import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.internal.encoding.provider.PreparedImageEncoderResources
import dev.dmkr.screencaptureengine.internal.gl.CleanupFailureCollector
import dev.dmkr.screencaptureengine.internal.lifecycle.PlanPreparationToken
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot
import dev.dmkr.screencaptureengine.internal.rendering.es2.FirstPlanRenderTransformPackage
import dev.dmkr.screencaptureengine.internal.rendering.es2.PreparedEs2RenderingReadbackResources
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlAccess

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
    planPreparationToken: PlanPreparationToken,
    outputPlan: ScreenCaptureOutputPlan,
    projectionTarget: ProjectionTargetSnapshot,
    projectionTargetHandle: ProjectionTargetHandle,
    internal val startupRenderingGlAccess: StartupRenderingGlAccess,
    encoderProvider: ImageEncoderProvider,
) : OutputPlanPrepareRequest(
    planPreparationToken = planPreparationToken,
    outputPlan = outputPlan,
    projectionTarget = projectionTarget,
    projectionTargetHandle = projectionTargetHandle,
    planRenderingAccess = startupRenderingGlAccess.asPlanRenderingAccess(),
    encoderProvider = encoderProvider,
)

internal interface PreparedRenderingReadbackResources : AutoCloseable

/**
 * Closeable resource set returned before startup or runtime transaction ownership accepts it.
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

    internal fun moveToActiveRuntimeCandidate(
        outputPlan: ScreenCaptureOutputPlan,
        projectionTarget: ProjectionTargetSnapshot,
    ): ActiveRuntimePreparedRenderingPipelineResourcesCandidate {
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
        return ActiveRuntimePreparedRenderingPipelineResourcesCandidate(
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
 * Move-only complete rendering-pipeline candidate prepared for an active runtime transaction.
 */
internal class ActiveRuntimePreparedRenderingPipelineResourcesCandidate internal constructor(
    private val readbackResources: PreparedRenderingReadbackResources,
    internal val renderTransformPackage: FirstPlanRenderTransformPackage,
    private val encoderResources: PreparedImageEncoderResources,
) : AutoCloseable {
    private val lock = Any()
    private var moved = false
    private var closed = false

    internal val encoderInfo: ImageEncoderInfo
        get() = currentEncoderResources().info

    internal fun moveToActiveRuntimeOwner(): ActiveRuntimePreparedRenderingPipelineResources {
        synchronized(lock) {
            check(!moved) { "ActiveRuntimePreparedRenderingPipelineResourcesCandidate was already moved." }
            check(!closed) { "ActiveRuntimePreparedRenderingPipelineResourcesCandidate is closed." }
            moved = true
        }
        return ActiveRuntimePreparedRenderingPipelineResources(
            readbackResources = readbackResources,
            renderTransformPackage = renderTransformPackage,
            encoderResources = encoderResources,
        )
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed || moved) {
                false
            } else {
                closed = true
                true
            }
        }
        if (shouldClose) {
            closeRenderingPipelineResources(
                readbackResources = readbackResources,
                encoderResources = encoderResources,
            )
        }
    }

    private fun currentEncoderResources(): PreparedImageEncoderResources =
        synchronized(lock) {
            check(!moved) { "ActiveRuntimePreparedRenderingPipelineResourcesCandidate was already moved." }
            check(!closed) { "ActiveRuntimePreparedRenderingPipelineResourcesCandidate is closed." }
            encoderResources
        }
}

/**
 * Move-only prepared resources accepted by PreActiveRuntimeOwner and fenced for initial runtime handoff.
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
 * Prepared rendering resources after ownership has moved into InitialRuntimeResourceOwner.
 */
internal class InitialRuntimePreparedRenderingPipelineResources internal constructor(
    private val readbackResources: PreparedRenderingReadbackResources,
    internal val renderTransformPackage: FirstPlanRenderTransformPackage,
    private val encoderResources: PreparedImageEncoderResources,
) : AutoCloseable {
    private val lock = Any()
    private var moved = false
    private var closed = false

    internal fun moveToActiveRuntimeOwner(): ActiveRuntimePreparedRenderingPipelineResources {
        synchronized(lock) {
            check(!moved) { "InitialRuntimePreparedRenderingPipelineResources were already moved." }
            check(!closed) { "InitialRuntimePreparedRenderingPipelineResources are closed." }
            moved = true
        }
        return ActiveRuntimePreparedRenderingPipelineResources(
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
 * Prepared rendering resources after ownership has moved into the active runtime/session owner.
 */
internal class ActiveRuntimePreparedRenderingPipelineResources internal constructor(
    readbackResources: PreparedRenderingReadbackResources,
    renderTransformPackage: FirstPlanRenderTransformPackage,
    encoderResources: PreparedImageEncoderResources,
) : AutoCloseable {
    private val renderReadbackResources = ActiveRuntimeRenderReadbackResources(
        readbackResources = readbackResources,
        renderTransformPackage = renderTransformPackage,
    )
    private val lock = Any()
    private var encoderResourcesOwner: ActiveRuntimeEncoderResourcesOwner? =
        ActiveRuntimeEncoderResourcesOwner(encoderResources)
    private var renderReadbackResourcesClosed = false
    private var encoderResourcesClosed = false

    internal val renderTransformPackage: FirstPlanRenderTransformPackage
        get() = renderReadbackResources.renderTransformPackage

    internal val renderReadbackResourcesForRuntime: ActiveRuntimeRenderReadbackResources
        get() = renderReadbackResources

    internal val encoderInfo: ImageEncoderInfo
        get() = currentEncoderResourcesOwner().encoderInfo

    internal val encoderResourcesForRuntime: PreparedImageEncoderResources
        get() = currentEncoderResourcesOwner().encoderResourcesForRuntime

    internal fun es2ReadbackResourcesForRuntime(): PreparedEs2RenderingReadbackResources =
        renderReadbackResources.es2ReadbackResourcesForRuntime()

    internal fun replaceEncoderResourcesOnly(
        candidate: ActiveRuntimeEncoderResourcesCandidate,
    ): RetiredActiveRuntimeEncoderResources {
        val replacementOwner = candidate.moveToActiveRuntimeOwner()
        val retiredResources = synchronized(lock) {
            val currentOwner = encoderResourcesOwner
            if (encoderResourcesClosed || currentOwner == null) {
                null
            } else {
                encoderResourcesOwner = replacementOwner
                currentOwner.moveToRetiredOwner()
            }
        }
        if (retiredResources != null) return retiredResources

        replacementOwner.close()
        error("Active runtime encoder resources are closed.")
    }

    internal fun closeEncoderResourcesOnly() {
        val ownerToClose = synchronized(lock) {
            if (encoderResourcesClosed) {
                null
            } else {
                encoderResourcesClosed = true
                encoderResourcesOwner.also {
                    encoderResourcesOwner = null
                }
            }
        }
        ownerToClose?.close()
    }

    override fun close() {
        val closeRenderReadback: Boolean
        val ownerToClose: ActiveRuntimeEncoderResourcesOwner?
        synchronized(lock) {
            closeRenderReadback = !renderReadbackResourcesClosed
            ownerToClose = if (encoderResourcesClosed) null else encoderResourcesOwner
            renderReadbackResourcesClosed = true
            encoderResourcesClosed = true
            encoderResourcesOwner = null
        }
        val cleanupFailures = CleanupFailureCollector()
        if (closeRenderReadback) cleanupFailures.collect { renderReadbackResources.close() }
        ownerToClose?.let { cleanupFailures.collect { it.close() } }
        cleanupFailures.throwIfAny()
    }

    private fun currentEncoderResourcesOwner(): ActiveRuntimeEncoderResourcesOwner =
        synchronized(lock) {
            check(!encoderResourcesClosed) { "Active runtime encoder resources are closed." }
            encoderResourcesOwner ?: error("Active runtime encoder resources are not installed.")
        }
}

internal class ActiveRuntimeRenderReadbackResources internal constructor(
    private val readbackResources: PreparedRenderingReadbackResources,
    internal val renderTransformPackage: FirstPlanRenderTransformPackage,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    internal fun es2ReadbackResourcesForRuntime(): PreparedEs2RenderingReadbackResources =
        readbackResources as? PreparedEs2RenderingReadbackResources
            ?: error("Initial runtime production requires prepared ES2 readback resources.")

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (shouldClose) readbackResources.close()
    }
}

internal class ActiveRuntimeEncoderResourcesCandidate internal constructor(
    private val encoderResources: PreparedImageEncoderResources,
) : AutoCloseable {
    private val lock = Any()
    private var moved = false
    private var closed = false

    internal val encoderInfo: ImageEncoderInfo
        get() = currentEncoderResources().info

    internal val encoderResourcesForValidation: PreparedImageEncoderResources
        get() = currentEncoderResources()

    internal fun moveToActiveRuntimeOwner(): ActiveRuntimeEncoderResourcesOwner {
        synchronized(lock) {
            check(!moved) { "ActiveRuntimeEncoderResourcesCandidate was already moved." }
            check(!closed) { "ActiveRuntimeEncoderResourcesCandidate is closed." }
            moved = true
        }
        return ActiveRuntimeEncoderResourcesOwner(encoderResources)
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed || moved) {
                false
            } else {
                closed = true
                true
            }
        }
        if (shouldClose) encoderResources.close()
    }

    private fun currentEncoderResources(): PreparedImageEncoderResources =
        synchronized(lock) {
            check(!moved) { "ActiveRuntimeEncoderResourcesCandidate was already moved." }
            check(!closed) { "ActiveRuntimeEncoderResourcesCandidate is closed." }
            encoderResources
        }
}

internal class ActiveRuntimeEncoderResourcesOwner internal constructor(
    private val encoderResources: PreparedImageEncoderResources,
) : AutoCloseable {
    private val lock = Any()
    private var moved = false
    private var closed = false

    internal val encoderInfo: ImageEncoderInfo
        get() = currentEncoderResources().info

    internal val encoderResourcesForRuntime: PreparedImageEncoderResources
        get() = currentEncoderResources()

    internal fun moveToRetiredOwner(): RetiredActiveRuntimeEncoderResources {
        synchronized(lock) {
            check(!moved) { "ActiveRuntimeEncoderResourcesOwner was already moved." }
            check(!closed) { "ActiveRuntimeEncoderResourcesOwner is closed." }
            moved = true
        }
        return RetiredActiveRuntimeEncoderResources(encoderResources)
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed || moved) {
                false
            } else {
                closed = true
                true
            }
        }
        if (shouldClose) encoderResources.close()
    }

    private fun currentEncoderResources(): PreparedImageEncoderResources =
        synchronized(lock) {
            check(!moved) { "ActiveRuntimeEncoderResourcesOwner was already moved." }
            check(!closed) { "ActiveRuntimeEncoderResourcesOwner is closed." }
            encoderResources
        }
}

internal class RetiredActiveRuntimeEncoderResources internal constructor(
    private val encoderResources: PreparedImageEncoderResources,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (shouldClose) encoderResources.close()
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
