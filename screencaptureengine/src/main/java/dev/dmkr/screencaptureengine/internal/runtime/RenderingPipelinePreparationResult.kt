package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind

/**
 * Result of preparing rendering/readback/encoder resources before runtime ownership accepts them.
 */
internal sealed class RenderingPipelinePreparationResult private constructor() {
    /**
     * Preparation completed and returned move-only resources still owned by the preparer result.
     */
    internal class Success internal constructor(
        internal val components: PreparedRenderingPipelineComponents,
    ) : RenderingPipelinePreparationResult()

    /**
     * Preparation failed with a typed problem suitable for startup arbitration.
     */
    internal class Failure internal constructor(
        internal val failure: RenderingPipelinePreparationFailure,
    ) : RenderingPipelinePreparationResult()

    /**
     * The preparation token became stale before the result could be accepted.
     *
     * This is a lifecycle outcome, not a GL/readback/encoder resource failure. The owner that issued
     * the token maps it to projection-stop, caller-cancellation, rollback, or supersession semantics.
     */
    internal data object LifecycleStale : RenderingPipelinePreparationResult()
}

/**
 * Typed failure emitted by rendering pipeline preparation.
 *
 * Only failure kinds that can occur while validating the initial rendering/readback/encoder
 * readiness boundary are allowed here. Stale lifecycle outcomes use
 * [RenderingPipelinePreparationResult.LifecycleStale] instead.
 */
internal class RenderingPipelinePreparationFailure internal constructor(
    internal val kind: ScreenCaptureProblemKind,
    internal val message: String,
    internal val cause: Throwable? = null,
) {
    init {
        require(kind in AllowedKinds) { "Rendering pipeline preparation failure kind is not allowed: $kind." }
    }

    internal companion object {
        internal val AllowedKinds: Set<ScreenCaptureProblemKind> = setOf(
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
    }
}
