package dev.dmkr.screencaptureengine.internal.runtime

/**
 * Owner-mediated GL access for startup rendering preparation.
 *
 * Access is scoped to the current owning GL lane and a generation-checked projection target. The
 * retirement lane and abandonment handle refer to the same GL lane as [StartupRenderingGlScope.gl].
 * Typed startup access failures describe projection-target ownership or generation invariants so
 * callers can distinguish them from ordinary GL initialization/resource failures.
 */
internal interface StartupRenderingGlAccess : GlLaneAbandonment {
    /**
     * Runs [block] on the target owner's current GL lane after validating the target.
     *
     * [target] must belong to the owner providing this access, [generation] must match the live
     * target generation, and the target must still be open. If coroutine cancellation races with a
     * successfully returned value from [block], [onCancellation] receives that value for cleanup.
     */
    suspend fun <T> withCurrentStartupRenderingTarget(
        target: ProjectionTargetHandle,
        generation: Long,
        onCancellation: (T) -> Unit = {},
        block: StartupRenderingGlScope.() -> T,
    ): T
}

/**
 * Scoped startup GL state exposed only while the access block is active.
 */
internal interface StartupRenderingGlScope {
    val gl: GlLaneScope
    val projectionTarget: ProjectionTargetGlScope
    val retirementLane: GlResourceRetirementLane
    val abandonment: GlLaneAbandonment
}

/**
 * Typed startup GL access failure for owner/generation/closed-target invariants.
 */
internal class StartupRenderingGlAccessException internal constructor(
    internal val reason: StartupRenderingGlAccessFailureReason,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Projection-target invariants checked before startup rendering preparation receives GL access.
 */
internal enum class StartupRenderingGlAccessFailureReason {
    ProjectionTargetOwnerMismatch,
    ProjectionTargetGenerationMismatch,
    ProjectionTargetClosed,
    ProjectionTargetOwnerClosed,
}
