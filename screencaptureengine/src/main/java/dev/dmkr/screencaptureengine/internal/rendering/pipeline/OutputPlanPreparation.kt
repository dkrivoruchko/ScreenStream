package dev.dmkr.screencaptureengine.internal.rendering.pipeline

import dev.dmkr.screencaptureengine.ImageEncoderProvider
import dev.dmkr.screencaptureengine.internal.gl.GlLaneAbandonment
import dev.dmkr.screencaptureengine.internal.gl.GlLaneScope
import dev.dmkr.screencaptureengine.internal.gl.GlResourceRetirementLane
import dev.dmkr.screencaptureengine.internal.lifecycle.PlanPreparationToken
import dev.dmkr.screencaptureengine.internal.planning.ScreenCaptureOutputPlan
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetHandle
import dev.dmkr.screencaptureengine.internal.platform.projection.ProjectionTargetSnapshot
import dev.dmkr.screencaptureengine.internal.target.ProjectionTargetGlScope
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlAccess
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlScope

/**
 * Prepares rendering/readback/encoder resources for one candidate output plan.
 *
 * Startup uses this contract through [RenderingPipelinePreparer]. Runtime replacement uses the same
 * lower-level preparation boundary with a runtime-owned transaction request.
 */
internal fun interface OutputPlanPreparer {
    suspend fun prepareOutputPlan(request: OutputPlanPrepareRequest): OutputPlanPreparationResult
}

/**
 * Immutable preparation input for a candidate output plan and its current projection target.
 */
internal open class OutputPlanPrepareRequest internal constructor(
    internal val planPreparationToken: PlanPreparationToken,
    internal val outputPlan: ScreenCaptureOutputPlan,
    internal val projectionTarget: ProjectionTargetSnapshot,
    internal val projectionTargetHandle: ProjectionTargetHandle,
    internal val planRenderingAccess: PlanRenderingAccess,
    internal val encoderProvider: ImageEncoderProvider,
    /** Whether a preparation timeout also makes the shared GL lane unusable for later work. */
    internal val abandonGlLaneOnTimeout: Boolean = true,
)

/**
 * Token-checked, frame-free GL access for preparing resources for one candidate output plan.
 */
internal interface PlanRenderingAccess : GlLaneAbandonment {
    suspend fun <T> withCurrentPlanRenderingTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        onCancellation: (T) -> Unit = {},
        block: PlanRenderingGlScope.() -> T,
    ): T
}

/**
 * Scoped rendering state exposed only while candidate output-plan preparation owns GL access.
 */
internal interface PlanRenderingGlScope {
    val gl: GlLaneScope
    val projectionTarget: ProjectionTargetGlScope
    val retirementLane: GlResourceRetirementLane
    val abandonment: GlLaneAbandonment
}

internal typealias OutputPlanPreparationResult = RenderingPipelinePreparationResult

internal typealias OutputPlanPreparationFailure = RenderingPipelinePreparationFailure

internal typealias OutputPlanPrepared = PreparedRenderingPipelineComponents

internal fun StartupRenderingGlAccess.asPlanRenderingAccess(): PlanRenderingAccess =
    StartupPlanRenderingAccess(this)

private class StartupPlanRenderingAccess(
    private val startupAccess: StartupRenderingGlAccess,
) : PlanRenderingAccess {
    override val isGlLaneAbandoned: Boolean
        get() = startupAccess.isGlLaneAbandoned

    override fun abandonGlLane() {
        startupAccess.abandonGlLane()
    }

    override suspend fun <T> withCurrentPlanRenderingTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        onCancellation: (T) -> Unit,
        block: PlanRenderingGlScope.() -> T,
    ): T =
        startupAccess.withCurrentStartupRenderingTarget(
            target = target,
            generation = generation,
            onCancellation = onCancellation,
        ) {
            block(StartupPlanRenderingGlScope(this))
        }
}

private class StartupPlanRenderingGlScope(
    private val startupScope: StartupRenderingGlScope,
) : PlanRenderingGlScope {
    override val gl: GlLaneScope
        get() = startupScope.gl

    override val projectionTarget: ProjectionTargetGlScope
        get() = startupScope.projectionTarget

    override val retirementLane: GlResourceRetirementLane
        get() = startupScope.retirementLane

    override val abandonment: GlLaneAbandonment
        get() = startupScope.abandonment
}
