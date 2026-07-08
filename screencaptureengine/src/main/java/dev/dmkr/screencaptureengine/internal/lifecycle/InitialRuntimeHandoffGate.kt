package dev.dmkr.screencaptureengine.internal.lifecycle

/**
 * Snapshot used to validate the pre-active-to-runtime resource handoff.
 *
 * Invariant mismatches are internal errors. When invariants hold, observed projection stop wins
 * over caller cancellation so startup reports the projection lifecycle outcome consistently.
 */
internal class InitialRuntimeHandoffGateSnapshot internal constructor(
    internal val ownerOpen: Boolean,
    internal val ownerStateDescription: String,
    internal val planOwnerMatches: Boolean,
    internal val planTokenMatches: Boolean,
    internal val targetHandleMatches: Boolean,
    internal val targetGenerationMatches: Boolean,
    internal val startupRenderingGlAccessMatches: Boolean,
    internal val preparedResourcesOwnerMatches: Boolean,
    internal val preparedResourcesPlanTokenMatches: Boolean,
    internal val preparedResourcesTargetGenerationMatches: Boolean,
    internal val preparedResourcesStartupRenderingGlAccessMatches: Boolean,
    internal val preparedResourcesPlanPreparationTokenMatches: Boolean,
    internal val preparedResourcesPlanPreparationTokenCurrent: Boolean,
    internal val preparedResourcesOpen: Boolean,
    internal val projectionStopped: Boolean,
    internal val callerActive: Boolean,
)

internal object InitialRuntimeHandoffGate {
    internal fun decide(snapshot: InitialRuntimeHandoffGateSnapshot): InitialRuntimeHandoffDecision =
        when {
            !snapshot.ownerOpen -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreActiveRuntimeOwner is ${snapshot.ownerStateDescription}.",
            )

            !snapshot.planOwnerMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreActiveInitialRuntimePlan belongs to a different owner.",
            )

            !snapshot.planTokenMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreActiveInitialRuntimePlan is stale.",
            )

            !snapshot.targetHandleMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreActiveInitialRuntimePlan target handle is stale.",
            )

            !snapshot.targetGenerationMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreActiveInitialRuntimePlan target generation is stale.",
            )

            !snapshot.startupRenderingGlAccessMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreActiveInitialRuntimePlan startup rendering GL access is stale.",
            )

            !snapshot.preparedResourcesOwnerMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources belong to a different owner.",
            )

            !snapshot.preparedResourcesPlanTokenMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources are stale.",
            )

            !snapshot.preparedResourcesTargetGenerationMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources target generation is stale.",
            )

            !snapshot.preparedResourcesStartupRenderingGlAccessMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources startup rendering GL access is stale.",
            )

            !snapshot.preparedResourcesOpen -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources are not open for handoff.",
            )

            !snapshot.preparedResourcesPlanPreparationTokenMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources plan-preparation token is stale.",
            )

            snapshot.projectionStopped -> InitialRuntimeHandoffDecision.ProjectionStopped
            !snapshot.callerActive -> InitialRuntimeHandoffDecision.CallerCancelled

            !snapshot.preparedResourcesPlanPreparationTokenCurrent -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreparedRenderingPipelineResources plan-preparation token is no longer current.",
            )

            else -> InitialRuntimeHandoffDecision.Ready
        }
}

internal sealed interface InitialRuntimeHandoffDecision {
    data object Ready : InitialRuntimeHandoffDecision
    data object ProjectionStopped : InitialRuntimeHandoffDecision
    data object CallerCancelled : InitialRuntimeHandoffDecision

    class InvariantViolation internal constructor(
        internal val message: String,
    ) : InitialRuntimeHandoffDecision
}
