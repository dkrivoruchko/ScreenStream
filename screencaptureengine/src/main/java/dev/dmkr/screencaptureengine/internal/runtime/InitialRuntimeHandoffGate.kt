package dev.dmkr.screencaptureengine.internal.runtime

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
    internal val targetGenerationMatches: Boolean,
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

            !snapshot.targetGenerationMatches -> InitialRuntimeHandoffDecision.InvariantViolation(
                message = "PreActiveInitialRuntimePlan target generation is stale.",
            )

            snapshot.projectionStopped -> InitialRuntimeHandoffDecision.ProjectionStopped
            !snapshot.callerActive -> InitialRuntimeHandoffDecision.CallerCancelled
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
