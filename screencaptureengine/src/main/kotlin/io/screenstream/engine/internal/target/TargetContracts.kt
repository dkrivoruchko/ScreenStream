package io.screenstream.engine.internal.target

import io.screenstream.engine.internal.settlement.OperationReceipt

internal fun interface TargetSourceSignal {
    fun signal(targetGeneration: Long)
}

internal enum class PreparedTargetDisposition {
    Unclaimed,
    Installed,
    CleanupClaimed,
}

internal enum class TargetConstructionAdmissionDisposition {
    Active,
    Terminal,
}

internal enum class TargetConstructionFoldDisposition {
    Install,
    CleanupFailure,
    CleanupStale,
    CleanupTerminal,
    CleanupCollision,
}

internal enum class TargetMode {
    Full,
    Downscaled,
}

internal class TargetPlan(
    internal val mode: TargetMode,
    internal val targetWidthPx: Int,
    internal val targetHeightPx: Int,
) {
    init {
        require(targetWidthPx > 0)
        require(targetHeightPx > 0)
    }
}

internal class TargetCurrentnessSnapshot(
    internal val target: CurrentTarget,
    internal val generation: Long,
    internal val plan: TargetPlan,
    internal val listenerInstalled: Boolean,
    internal val producerState: TargetProducerState,
    internal val generationFenced: Boolean,
    internal val version: Long,
)

internal enum class TargetProducerOperationKind {
    VirtualDisplayCreation,
    VirtualDisplayAttachment,
}

internal enum class TargetNoProducerReason {
    Inapplicable,
    Unentered,
    ReturnedWithoutProducer,
}

internal enum class TargetProducerDetachKind {
    VirtualDisplayDetach,
    VirtualDisplayRelease,
}

internal enum class TargetPortKind {
    ListenerInstallation,
    VirtualDisplayCreation,
    VirtualDisplayAttachment,
    VirtualDisplayDetach,
    VirtualDisplayRelease,
    ListenerRemoval,
    SurfaceRelease,
    GlFrame,
    TargetScopeDestruction,
}

internal enum class TargetProducerState {
    AwaitingEvidence,
    ProducerAttached,
    NoProducer,
    Detached,
}

internal enum class TargetResourceObligation {
    AwaitingSettlement,
    Required,
    Completed,
    Inapplicable,
}

internal sealed interface TargetOperationProvenance

internal sealed interface TargetProducerApplicationCandidate

internal sealed interface TargetProducerDetachApplicationCandidate

internal sealed interface TargetProducerApplicationFact {
    val targetGeneration: Long
    val operationIdentity: Long
    val operationKind: TargetProducerOperationKind
    val provenance: TargetOperationProvenance
}

internal sealed interface TargetProducerEvidence : TargetProducerApplicationFact

internal sealed interface TargetNoProducerEvidence : TargetProducerApplicationFact {
    val reason: TargetNoProducerReason
}

internal sealed interface TargetProducerDetachReceipt : OperationReceipt {
    val targetGeneration: Long
    val operationIdentity: Long
    val detachKind: TargetProducerDetachKind
    val provenance: TargetOperationProvenance
}

private class TargetOperationProvenanceImpl(
    val target: CurrentTarget,
    val operationIdentity: Long,
    val portKind: TargetPortKind,
) : TargetOperationProvenance

private class TargetProducerEvidenceImpl(
    override val targetGeneration: Long,
    override val operationIdentity: Long,
    override val operationKind: TargetProducerOperationKind,
    override val provenance: TargetOperationProvenance,
) : TargetProducerEvidence

private class TargetNoProducerEvidenceImpl(
    override val targetGeneration: Long,
    override val operationIdentity: Long,
    override val operationKind: TargetProducerOperationKind,
    override val reason: TargetNoProducerReason,
    override val provenance: TargetOperationProvenance,
) : TargetNoProducerEvidence

private class TargetProducerDetachReceiptImpl(
    override val targetGeneration: Long,
    override val operationIdentity: Long,
    override val detachKind: TargetProducerDetachKind,
    override val provenance: TargetOperationProvenance,
) : TargetProducerDetachReceipt

internal fun targetOperationProvenance(
    targetOwner: TargetOwner,
    constructionProof: () -> Unit,
    target: CurrentTarget,
    operationIdentity: Long,
    portKind: TargetPortKind,
): TargetOperationProvenance {
    check(targetOwner.acceptsConstructionProof(constructionProof))
    return TargetOperationProvenanceImpl(target, operationIdentity, portKind)
}

internal fun targetProducerEvidence(
    targetOwner: TargetOwner,
    constructionProof: () -> Unit,
    targetGeneration: Long,
    operationIdentity: Long,
    operationKind: TargetProducerOperationKind,
    provenance: TargetOperationProvenance,
): TargetProducerEvidence {
    check(targetOwner.acceptsConstructionProof(constructionProof))
    return TargetProducerEvidenceImpl(targetGeneration, operationIdentity, operationKind, provenance)
}

internal fun targetNoProducerEvidence(
    targetOwner: TargetOwner,
    constructionProof: () -> Unit,
    targetGeneration: Long,
    operationIdentity: Long,
    operationKind: TargetProducerOperationKind,
    reason: TargetNoProducerReason,
    provenance: TargetOperationProvenance,
): TargetNoProducerEvidence {
    check(targetOwner.acceptsConstructionProof(constructionProof))
    return TargetNoProducerEvidenceImpl(targetGeneration, operationIdentity, operationKind, reason, provenance)
}

internal fun targetProducerDetachReceipt(
    targetOwner: TargetOwner,
    constructionProof: () -> Unit,
    targetGeneration: Long,
    operationIdentity: Long,
    detachKind: TargetProducerDetachKind,
    provenance: TargetOperationProvenance,
): TargetProducerDetachReceipt {
    check(targetOwner.acceptsConstructionProof(constructionProof))
    return TargetProducerDetachReceiptImpl(targetGeneration, operationIdentity, detachKind, provenance)
}
