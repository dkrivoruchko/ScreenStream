@file:Suppress("unused") // Dormant until controller authority integration.

package dev.dmkr.screencaptureengine.internal.control

internal data class GeometrySnapshot(val width: Int, val height: Int, val densityDpi: Int) {
    init {
        require(width > 0 && height > 0 && densityDpi > 0)
    }
}

internal enum class TerminalWinnerClass { CaptureEnded, OwnerStop, Failed }

internal enum class TerminalFailureDomain {
    None,
    ProjectionUnavailable,
    PlatformFailure,
    RenderingFailure,
    EncodingFailure,
    ResourceExhausted,
    InternalFailure,
}

/** One closed source per lossless terminal slot; public terminal policy is derived later. */
internal enum class TerminalEvidence(
    val winnerClass: TerminalWinnerClass,
    val failureDomain: TerminalFailureDomain,
    val priority: Int,
) {
    ProjectionStopped(TerminalWinnerClass.CaptureEnded, TerminalFailureDomain.ProjectionUnavailable, 1),
    DisplayStopped(TerminalWinnerClass.CaptureEnded, TerminalFailureDomain.ProjectionUnavailable, 1),
    OwnerStopped(TerminalWinnerClass.OwnerStop, TerminalFailureDomain.None, 2),
    StartedEncoderStall(TerminalWinnerClass.Failed, TerminalFailureDomain.EncodingFailure, 3),
    PoisonedProviderPreparationRequired(TerminalWinnerClass.Failed, TerminalFailureDomain.EncodingFailure, 3),
    StartedGlTimeout(TerminalWinnerClass.Failed, TerminalFailureDomain.RenderingFailure, 3),
    StartedPlatformTimeout(TerminalWinnerClass.Failed, TerminalFailureDomain.PlatformFailure, 3),
    ListenerRetirementUnprovable(TerminalWinnerClass.Failed, TerminalFailureDomain.InternalFailure, 3),
    MetricsCollectionTerminated(TerminalWinnerClass.Failed, TerminalFailureDomain.PlatformFailure, 3),
    UnsafePlatformBinding(TerminalWinnerClass.Failed, TerminalFailureDomain.PlatformFailure, 3),
    UnsafeRenderingState(TerminalWinnerClass.Failed, TerminalFailureDomain.RenderingFailure, 3),
    UnsafeGlOutOfMemory(TerminalWinnerClass.Failed, TerminalFailureDomain.ResourceExhausted, 3),
    UnsafeProviderRetainedOwnership(TerminalWinnerClass.Failed, TerminalFailureDomain.EncodingFailure, 3),
    InternalControllerInvariant(TerminalWinnerClass.Failed, TerminalFailureDomain.InternalFailure, 3),
    PlatformRecoveryExhausted(TerminalWinnerClass.Failed, TerminalFailureDomain.PlatformFailure, 3),
    RenderingRecoveryExhausted(TerminalWinnerClass.Failed, TerminalFailureDomain.RenderingFailure, 3),
    EncodingRecoveryExhausted(TerminalWinnerClass.Failed, TerminalFailureDomain.EncodingFailure, 3),
    ResourceRecoveryExhausted(TerminalWinnerClass.Failed, TerminalFailureDomain.ResourceExhausted, 3),
}

/** Session and committed-revision fence carried by every identity-tagged terminal source. */
internal class ControllerTerminalFence(
    val session: SessionIdentity,
    val revisions: CommittedRevisions,
    val cancellationMarker: ControllerCancellationMarkerRevision,
)

/** Exact typed GL task whose started work may still own its named resource bag. */
internal sealed interface ControllerGlTaskIdentity {
    val operation: ControllerOperationIdentity
    val resourceBag: ControllerResourceBagIdentity

    class Bootstrap(
        val origin: ControllerFactOrigin.Startup,
        override val operation: ControllerOperationIdentity,
        override val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerGlTaskIdentity

    class TargetCreate(
        val origin: ControllerFactOrigin,
        val target: TargetIdentity,
        override val operation: ControllerOperationIdentity,
        override val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerGlTaskIdentity

    class PipelinePrepare(
        val origin: ControllerFactOrigin,
        override val operation: ControllerOperationIdentity,
        override val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerGlTaskIdentity

    class TargetProbe(
        val target: TargetIdentity,
        override val operation: ControllerOperationIdentity,
        override val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerGlTaskIdentity

    class Production(
        val owner: CompleteOwnerIdentity,
        val target: TargetIdentity,
        val attempt: ControllerProductionAttemptIdentity,
        override val operation: ControllerOperationIdentity,
        override val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerGlTaskIdentity

    class PboProgress(
        val owner: CompleteOwnerIdentity,
        val target: TargetIdentity,
        val attempt: ControllerProductionAttemptIdentity,
        override val operation: ControllerOperationIdentity,
        override val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerGlTaskIdentity

    class TargetDestroy(
        val target: TargetIdentity,
        override val operation: ControllerOperationIdentity,
        override val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerGlTaskIdentity
}

/** Exact serial platform operation phase retained after a started timeout or unsafe binding. */
internal sealed interface ControllerPlatformOperationIdentity {
    val operation: ControllerOperationIdentity

    class DeviceMemorySample(override val operation: ControllerOperationIdentity) : ControllerPlatformOperationIdentity

    class CallbackAttach(
        override val operation: ControllerOperationIdentity,
        val attachment: ControllerCallbackAttachmentIdentity,
    ) : ControllerPlatformOperationIdentity

    class CallbackDetach(
        override val operation: ControllerOperationIdentity,
        val attachment: ControllerCallbackAttachmentIdentity,
    ) : ControllerPlatformOperationIdentity

    class Create(
        override val operation: ControllerOperationIdentity,
        val candidate: CandidateIdentity,
        val target: TargetIdentity,
        val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerPlatformOperationIdentity

    class Retarget(
        override val operation: ControllerOperationIdentity,
        val origin: ControllerFactOrigin,
        val candidate: CandidateIdentity,
        val previousTarget: TargetIdentity,
        val candidateTarget: TargetIdentity,
        val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerPlatformOperationIdentity {
        init {
            require(origin.candidate == candidate)
        }
    }

    sealed interface TerminalCleanup : ControllerPlatformOperationIdentity {
        val attachment: ControllerCallbackAttachmentIdentity

        class WithoutTarget(
            override val operation: ControllerOperationIdentity,
            override val attachment: ControllerCallbackAttachmentIdentity,
        ) : TerminalCleanup

        class WithTarget(
            override val operation: ControllerOperationIdentity,
            override val attachment: ControllerCallbackAttachmentIdentity,
            val target: TargetIdentity,
            val resourceBag: ControllerResourceBagIdentity,
        ) : TerminalCleanup
    }
}

internal sealed interface ControllerProviderOwnershipIdentity {
    val operation: ControllerOperationIdentity
    val resourceBag: ControllerResourceBagIdentity

    class CandidatePreparation(
        val origin: ControllerFactOrigin,
        override val operation: ControllerOperationIdentity,
        override val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerProviderOwnershipIdentity

    class ActiveEncode(
        val owner: CompleteOwnerIdentity,
        val attempt: ControllerProductionAttemptIdentity,
        override val operation: ControllerOperationIdentity,
        override val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerProviderOwnershipIdentity

    class RetiredCleanup(
        val owner: CompleteOwnerIdentity,
        override val operation: ControllerOperationIdentity,
        override val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerProviderOwnershipIdentity
}

/** Exact mechanical terminal records; policy is selected only by the future pure reducer. */
internal sealed interface ControllerTerminalCause {
    val fence: ControllerTerminalFence
    val evidence: TerminalEvidence

    class ProjectionStopped(
        override val fence: ControllerTerminalFence,
        val attachment: ControllerCallbackAttachmentIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.ProjectionStopped
    }

    class DisplayStopped(
        override val fence: ControllerTerminalFence,
        val target: TargetIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.DisplayStopped
    }

    class OwnerStopped(override val fence: ControllerTerminalFence) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.OwnerStopped
    }

    class StartedEncoderStall(
        override val fence: ControllerTerminalFence,
        val owner: CompleteOwnerIdentity,
        val operation: ControllerOperationIdentity,
        val attempt: ControllerProductionAttemptIdentity,
        val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.StartedEncoderStall
    }

    class PoisonedProviderPreparationRequired(
        override val fence: ControllerTerminalFence,
        val poisoned: ControllerProviderOwnershipIdentity.CandidatePreparation,
        val requiredOrigin: ControllerFactOrigin,
    ) : ControllerTerminalCause {
        init {
            require(poisoned.origin != requiredOrigin)
        }

        override val evidence: TerminalEvidence = TerminalEvidence.PoisonedProviderPreparationRequired
    }

    class StartedGlTimeout(
        override val fence: ControllerTerminalFence,
        val task: ControllerGlTaskIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.StartedGlTimeout
    }

    class StartedPlatformTimeout(
        override val fence: ControllerTerminalFence,
        val platform: ControllerPlatformOperationIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.StartedPlatformTimeout
    }

    class ListenerRetirementUnprovable(
        override val fence: ControllerTerminalFence,
        val target: TargetIdentity,
        val operation: ControllerOperationIdentity,
        val resourceBag: ControllerResourceBagIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.ListenerRetirementUnprovable
    }

    class MetricsCollectionTerminated(
        override val fence: ControllerTerminalFence,
        val attachment: ControllerMetricsAttachmentIdentity,
        val operation: ControllerOperationIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.MetricsCollectionTerminated
    }

    class UnsafePlatformBinding(
        override val fence: ControllerTerminalFence,
        val retarget: ControllerPlatformOperationIdentity.Retarget,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.UnsafePlatformBinding
    }

    class UnsafeRenderingState(
        override val fence: ControllerTerminalFence,
        val task: ControllerGlTaskIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.UnsafeRenderingState
    }

    class UnsafeGlOutOfMemory(
        override val fence: ControllerTerminalFence,
        val task: ControllerGlTaskIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.UnsafeGlOutOfMemory
    }

    class UnsafeProviderRetainedOwnership(
        override val fence: ControllerTerminalFence,
        val ownership: ControllerProviderOwnershipIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.UnsafeProviderRetainedOwnership
    }

    class InternalControllerInvariant(
        override val fence: ControllerTerminalFence,
        val invariant: ControllerInvariantIdentity,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.InternalControllerInvariant
    }

    class PlatformRecoveryExhausted(
        override val fence: ControllerTerminalFence,
        val reconfiguration: ReconfigurationIdentity,
        val reason: ReasonToken,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.PlatformRecoveryExhausted
    }

    class RenderingRecoveryExhausted(
        override val fence: ControllerTerminalFence,
        val reconfiguration: ReconfigurationIdentity,
        val reason: ReasonToken,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.RenderingRecoveryExhausted
    }

    class EncodingRecoveryExhausted(
        override val fence: ControllerTerminalFence,
        val reconfiguration: ReconfigurationIdentity,
        val reason: ReasonToken,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.EncodingRecoveryExhausted
    }

    class ResourceRecoveryExhausted(
        override val fence: ControllerTerminalFence,
        val reconfiguration: ReconfigurationIdentity,
        val reason: ReasonToken,
    ) : ControllerTerminalCause {
        override val evidence: TerminalEvidence = TerminalEvidence.ResourceRecoveryExhausted
    }
}

/** Explicit projection usability proof for one exact pre-public retirement record. */
internal enum class PrePublicProjectionFreshness {
    ReusableBeforeAttachment,
    ReusableAfterDetachAcknowledged,
    DiscardConsumed,
    DiscardStopped,
    DiscardAttachmentUncertain,
    DiscardCancellationWithoutFreshness,
}

internal enum class PrePublicStartFailureEvidence {
    InvalidRequest,
    InvalidState,
    ProjectionUnavailable,
    PlatformFailure,
    RenderingFailure,
    EncodingFailure,
    ResourceExhausted,
    InternalFailure,
}

internal sealed interface PrePublicStartOutcome {
    class Failure(
        val evidence: PrePublicStartFailureEvidence,
        val cause: Throwable? = null,
    ) : PrePublicStartOutcome

    data object CallerCancellation : PrePublicStartOutcome
}

internal class PrePublicStartRetirement(
    val projectionFreshness: PrePublicProjectionFreshness,
    val outcome: PrePublicStartOutcome,
) {
    val requiresFreshProjection: Boolean
        get() = when (projectionFreshness) {
            PrePublicProjectionFreshness.ReusableBeforeAttachment,
            PrePublicProjectionFreshness.ReusableAfterDetachAcknowledged,
                -> false

            PrePublicProjectionFreshness.DiscardConsumed,
            PrePublicProjectionFreshness.DiscardStopped,
            PrePublicProjectionFreshness.DiscardAttachmentUncertain,
            PrePublicProjectionFreshness.DiscardCancellationWithoutFreshness,
                -> true
        }

    init {
        when (outcome) {
            is PrePublicStartOutcome.Failure ->
                require(projectionFreshness != PrePublicProjectionFreshness.DiscardCancellationWithoutFreshness)

            PrePublicStartOutcome.CallerCancellation -> require(
                projectionFreshness == PrePublicProjectionFreshness.ReusableBeforeAttachment ||
                    projectionFreshness == PrePublicProjectionFreshness.DiscardConsumed ||
                    projectionFreshness == PrePublicProjectionFreshness.DiscardCancellationWithoutFreshness,
            )
        }
    }
}

internal data class MetricsEvidence(val width: Int, val height: Int, val densityDpi: Int)
internal data class CapturedResizeEvidence(val width: Int, val height: Int)
internal enum class SourceTrustEvidence { NotReady, Invalid, NoLongerAvailable, InvalidResize }
internal data class PauseEvidence(val physicalPaused: Boolean, val debtSequence: IngressSequence?)

internal enum class ReconfigurationReasonKind(val priority: Int) {
    PlatformFailure(0),
    RenderingFailure(1),
    EncodingFailure(2),
    ResourceExhausted(3),
    InvalidRequest(4),
    Reconfiguring(5),
}

internal enum class ReconfigurationStage(val priority: Int) {
    SourceValidity(0),
    PlatformAssignment(1),
    TargetAcquisition(2),
    RenderReadbackOwner(3),
    EncoderProviderOwner(4),
    MemoryAdmission(5),
    RequestedPlan(6),
    PlatformPause(7),
    Convergence(8),
}

internal enum class ReconfigurationReasonKey {
    SourceUnavailable,
    PlatformAssignment,
    TargetAcquisition,
    RenderingOwner,
    EncodingOwner,
    ResourceAdmission,
    RequestedPlan,
    PlatformPause,
    FreshTarget,
    Convergence,
}

internal enum class ReconfigurationReasonSpec(
    val key: ReconfigurationReasonKey,
    val kind: ReconfigurationReasonKind,
    val stage: ReconfigurationStage,
) {
    SourceNotReady(
        ReconfigurationReasonKey.SourceUnavailable,
        ReconfigurationReasonKind.Reconfiguring,
        ReconfigurationStage.SourceValidity,
    ),
    SourceUnavailable(
        ReconfigurationReasonKey.SourceUnavailable,
        ReconfigurationReasonKind.PlatformFailure,
        ReconfigurationStage.SourceValidity,
    ),
    PlatformAssignment(
        ReconfigurationReasonKey.PlatformAssignment,
        ReconfigurationReasonKind.PlatformFailure,
        ReconfigurationStage.PlatformAssignment,
    ),
    PlatformReconfiguring(
        ReconfigurationReasonKey.PlatformAssignment,
        ReconfigurationReasonKind.Reconfiguring,
        ReconfigurationStage.PlatformAssignment,
    ),
    TargetAcquisition(
        ReconfigurationReasonKey.TargetAcquisition,
        ReconfigurationReasonKind.Reconfiguring,
        ReconfigurationStage.TargetAcquisition,
    ),
    TargetRenderingFailure(
        ReconfigurationReasonKey.TargetAcquisition,
        ReconfigurationReasonKind.RenderingFailure,
        ReconfigurationStage.TargetAcquisition,
    ),
    RenderingOwner(
        ReconfigurationReasonKey.RenderingOwner,
        ReconfigurationReasonKind.RenderingFailure,
        ReconfigurationStage.RenderReadbackOwner,
    ),
    EncodingOwner(
        ReconfigurationReasonKey.EncodingOwner,
        ReconfigurationReasonKind.EncodingFailure,
        ReconfigurationStage.EncoderProviderOwner,
    ),
    ResourceAdmission(
        ReconfigurationReasonKey.ResourceAdmission,
        ReconfigurationReasonKind.ResourceExhausted,
        ReconfigurationStage.MemoryAdmission,
    ),
    RequestedPlan(
        ReconfigurationReasonKey.RequestedPlan,
        ReconfigurationReasonKind.InvalidRequest,
        ReconfigurationStage.RequestedPlan,
    ),
    PlatformPause(
        ReconfigurationReasonKey.PlatformPause,
        ReconfigurationReasonKind.Reconfiguring,
        ReconfigurationStage.PlatformPause,
    ),
    FreshTarget(
        ReconfigurationReasonKey.FreshTarget,
        ReconfigurationReasonKind.Reconfiguring,
        ReconfigurationStage.TargetAcquisition,
    ),
    Convergence(
        ReconfigurationReasonKey.Convergence,
        ReconfigurationReasonKind.Reconfiguring,
        ReconfigurationStage.Convergence,
    ),
}
