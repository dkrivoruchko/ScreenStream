package io.screenstream.engine.internal.target

import io.screenstream.engine.internal.android.AndroidFinalLaneNoEntryProof
import io.screenstream.engine.internal.android.AndroidPostPhysicalDisposition
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationEvidence
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationOwnerBag
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationNoPlatformEntryOutcome
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationUnboundClaimRetiredProof
import io.screenstream.engine.internal.android.AndroidTargetOperationBinding
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import kotlin.concurrent.withLock

internal enum class TargetListenerInstallationObligation {
    PendingInstallation,
    Installed,
    NeverInstalled,
    NeverRequested,
    ClaimRetiredBeforeBinding,
    NoPlatformEntry,
}

internal enum class TargetListenerInstallationRequestAdmission {
    AwaitingRequest,
    Claimed,
    Bound,
    NeverRequested,
    RetiredBeforeBinding,
}

/** Target-owned, one-shot authority to construct and retain the matching detached Android root. */
internal class TargetListenerInstallationRequestClaim private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val operationIdentity: Long,
    internal val provenance: TargetOperationProvenance,
    internal val port: TargetPorts.AndroidListenerInstallationPort,
) {
    init {
        require(operationIdentity > 0L)
        check(targetIdentity.matches(targetIdentity.target))
        check(port.targetIdentity === targetIdentity)
        check(port.operationIdentity == operationIdentity)
        check(port.provenance === provenance)
    }

    internal companion object {
        internal fun precreate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            port: TargetPorts.AndroidListenerInstallationPort,
        ): TargetListenerInstallationRequestClaim {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetListenerInstallationRequestClaim(
                port.targetIdentity,
                port.operationIdentity,
                port.provenance,
                port,
            )
        }
    }
}

/** Submission capability published only after Target retained the exact Android binding. */
internal class TargetListenerInstallationBindingCommittedFact private constructor(
    internal val claim: TargetListenerInstallationRequestClaim,
    internal val binding: AndroidTargetOperationBinding,
) {
    init {
        check(binding.targetIdentity === claim.targetIdentity)
        check(binding.operationIdentity == claim.operationIdentity)
        check(binding.provenance === claim.provenance)
        check(binding.targetFact === claim.port.bindingFact)
    }

    internal companion object {
        internal fun precreate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            claim: TargetListenerInstallationRequestClaim,
            binding: AndroidTargetOperationBinding,
        ): TargetListenerInstallationBindingCommittedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetListenerInstallationBindingCommittedFact(claim, binding)
        }
    }
}

/**
 * Exact structural settlement for the branch where retirement closed before Android claimed a
 * listener-install request. It proves absence; it is not an Android occurrence, no-entry proof,
 * removal receipt, or sentinel observation.
 */
internal class TargetListenerInstallationNeverRequestedFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val requestedIdentity: TargetRequestedIdentity,
    internal val operationIdentity: Long,
    internal val cutoffFact: TargetRetirementAdmissionClosedFact,
    internal val workDrainedFact: TargetWorkDrainedFact,
    internal val generationFencedFact: TargetGenerationFencedFact,
) {
    init {
        check(targetIdentity.matches(targetIdentity.target))
        check(targetIdentity.target.requestedIdentity === requestedIdentity)
        require(operationIdentity > 0L)
        check(targetIdentity.target.listenerInstallationOperationIdentity == operationIdentity)
        check(cutoffFact.targetIdentity === targetIdentity)
        check(workDrainedFact.targetIdentity === targetIdentity)
        check(workDrainedFact.admissionClosedFact === cutoffFact)
        check(generationFencedFact.targetIdentity === targetIdentity)
        check(generationFencedFact.workDrainedFact === workDrainedFact)
    }

    internal companion object {
        internal fun precreate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            requestedIdentity: TargetRequestedIdentity,
            operationIdentity: Long,
            cutoffFact: TargetRetirementAdmissionClosedFact,
            workDrainedFact: TargetWorkDrainedFact,
            generationFencedFact: TargetGenerationFencedFact,
        ): TargetListenerInstallationNeverRequestedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetListenerInstallationNeverRequestedFact(
                targetIdentity,
                requestedIdentity,
                operationIdentity,
                cutoffFact,
                workDrainedFact,
                generationFencedFact,
            )
        }
    }
}

internal class TargetListenerInstallationNeverRequestedApplicationResult private constructor(
    internal val fact: TargetListenerInstallationNeverRequestedFact,
) {
    internal companion object {
        internal fun precreate(
            fact: TargetListenerInstallationNeverRequestedFact,
        ): TargetListenerInstallationNeverRequestedApplicationResult =
            TargetListenerInstallationNeverRequestedApplicationResult(fact)
    }
}

internal class TargetListenerInstallationClaimRetiredFact private constructor(
    internal val claim: TargetListenerInstallationRequestClaim,
    internal val androidProof: AndroidTargetListenerInstallationUnboundClaimRetiredProof,
    internal val cutoffFact: TargetRetirementAdmissionClosedFact,
    internal val workDrainedFact: TargetWorkDrainedFact,
    internal val generationFencedFact: TargetGenerationFencedFact,
) {
    internal val targetIdentity: TargetIdentity
        get() = claim.targetIdentity

    init {
        check(androidProof.claim === claim)
        check(cutoffFact.targetIdentity === claim.targetIdentity)
        check(workDrainedFact.targetIdentity === claim.targetIdentity)
        check(workDrainedFact.admissionClosedFact === cutoffFact)
        check(generationFencedFact.targetIdentity === claim.targetIdentity)
        check(generationFencedFact.workDrainedFact === workDrainedFact)
    }

    internal companion object {
        internal fun precreate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            claim: TargetListenerInstallationRequestClaim,
            androidProof: AndroidTargetListenerInstallationUnboundClaimRetiredProof,
            cutoffFact: TargetRetirementAdmissionClosedFact,
            workDrainedFact: TargetWorkDrainedFact,
            generationFencedFact: TargetGenerationFencedFact,
        ): TargetListenerInstallationClaimRetiredFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetListenerInstallationClaimRetiredFact(
                claim,
                androidProof,
                cutoffFact,
                workDrainedFact,
                generationFencedFact,
            )
        }
    }
}

internal class TargetListenerInstallationClaimRetiredApplicationResult private constructor(
    internal val fact: TargetListenerInstallationClaimRetiredFact,
) {
    internal companion object {
        internal fun precreate(
            fact: TargetListenerInstallationClaimRetiredFact,
        ): TargetListenerInstallationClaimRetiredApplicationResult =
            TargetListenerInstallationClaimRetiredApplicationResult(fact)
    }
}

internal class TargetListenerInstallationNoPlatformEntryFact private constructor(
    internal val bindingFact: TargetListenerInstallationBindingCommittedFact,
    internal val outcome: AndroidTargetListenerInstallationNoPlatformEntryOutcome,
    internal val cutoffFact: TargetRetirementAdmissionClosedFact,
    internal val workDrainedFact: TargetWorkDrainedFact,
    internal val generationFencedFact: TargetGenerationFencedFact,
) {
    internal val targetIdentity: TargetIdentity
        get() = bindingFact.claim.targetIdentity

    init {
        check(outcome.binding === bindingFact.binding)
        check(cutoffFact.targetIdentity === targetIdentity)
        check(workDrainedFact.targetIdentity === targetIdentity)
        check(workDrainedFact.admissionClosedFact === cutoffFact)
        check(generationFencedFact.targetIdentity === targetIdentity)
        check(generationFencedFact.workDrainedFact === workDrainedFact)
    }

    internal companion object {
        internal fun precreate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            bindingFact: TargetListenerInstallationBindingCommittedFact,
            outcome: AndroidTargetListenerInstallationNoPlatformEntryOutcome,
            cutoffFact: TargetRetirementAdmissionClosedFact,
            workDrainedFact: TargetWorkDrainedFact,
            generationFencedFact: TargetGenerationFencedFact,
        ): TargetListenerInstallationNoPlatformEntryFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetListenerInstallationNoPlatformEntryFact(
                bindingFact,
                outcome,
                cutoffFact,
                workDrainedFact,
                generationFencedFact,
            )
        }
    }
}

internal class TargetListenerInstallationNoPlatformEntryApplicationResult private constructor(
    internal val fact: TargetListenerInstallationNoPlatformEntryFact,
) {
    internal companion object {
        internal fun precreate(
            fact: TargetListenerInstallationNoPlatformEntryFact,
        ): TargetListenerInstallationNoPlatformEntryApplicationResult =
            TargetListenerInstallationNoPlatformEntryApplicationResult(fact)
    }
}

/**
 * Exact structural settlement of one listener-install obligation after the owning Android lane
 * returned while its accepted ticket and platform occurrence were proven never to have entered.
 * This is deliberately neither a listener-removal receipt nor a sentinel observation.
 */
internal class TargetListenerNeverInstalledFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val targetGeneration: Long,
    internal val operationIdentity: Long,
    internal val provenance: TargetOperationProvenance,
    internal val binding: AndroidTargetOperationBinding,
    internal val finalLaneNoEntryProof:
        AndroidFinalLaneNoEntryProof<AndroidTargetListenerInstallationEvidence>,
) {
    init {
        require(targetGeneration > 0L)
        require(operationIdentity > 0L)
        check(targetIdentity.generation == targetGeneration)
        check(targetIdentity.matches(targetIdentity.target))
        check(provenance.targetIdentity === targetIdentity)
        check(provenance.operationIdentity == operationIdentity)
        check(provenance.portKind == TargetPortKind.ListenerInstallation)
        check(binding.targetIdentity === targetIdentity)
        check(binding.operationIdentity == operationIdentity)
        check(binding.provenance === provenance)
        check(finalLaneNoEntryProof.operationIdentity == operationIdentity)
        check(finalLaneNoEntryProof.operation.identity == operationIdentity)
        check(finalLaneNoEntryProof.ticket.operationIdentity == operationIdentity)
        check(finalLaneNoEntryProof.ticket.occurrence === finalLaneNoEntryProof.operation)
        check(finalLaneNoEntryProof.ticket.finalLaneNoEntryProof === finalLaneNoEntryProof)
        check(finalLaneNoEntryProof.ticket.lane === finalLaneNoEntryProof.lane)
        check(finalLaneNoEntryProof.ticket.workerIdentity === finalLaneNoEntryProof.workerIdentity)
        check(finalLaneNoEntryProof.ticket.terminationReceipt === finalLaneNoEntryProof.terminationReceipt)
        check(finalLaneNoEntryProof.workerIdentity.lane === finalLaneNoEntryProof.lane)
        check(finalLaneNoEntryProof.terminationReceipt.matchesWorker(finalLaneNoEntryProof.workerIdentity))
        check(finalLaneNoEntryProof.lane.terminationReceipt === finalLaneNoEntryProof.terminationReceipt)
        check(finalLaneNoEntryProof.lane.acceptsTerminationReceipt(finalLaneNoEntryProof.terminationReceipt))
    }

    internal companion object {
        internal fun precreate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            provenance: TargetOperationProvenance,
            binding: AndroidTargetOperationBinding,
            finalLaneNoEntryProof:
                AndroidFinalLaneNoEntryProof<AndroidTargetListenerInstallationEvidence>,
        ): TargetListenerNeverInstalledFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetListenerNeverInstalledFact(
                targetIdentity = targetIdentity,
                targetGeneration = targetIdentity.generation,
                operationIdentity = finalLaneNoEntryProof.operationIdentity,
                provenance = provenance,
                binding = binding,
                finalLaneNoEntryProof = finalLaneNoEntryProof,
            )
        }
    }
}

/**
 * Validates Android's frozen proof without taking `targetGate`. The final lane receipt makes the
 * accepted-not-entered state immutable; Target still checks every referential edge before use.
 */
internal fun precreateListenerNeverInstalledFact(
    targetOwner: TargetOwner,
    constructionProof: () -> Unit,
    target: CurrentTarget,
    port: TargetPorts.AndroidListenerInstallationPort,
    binding: AndroidTargetOperationBinding,
    proof: AndroidFinalLaneNoEntryProof<AndroidTargetListenerInstallationEvidence>,
): TargetListenerNeverInstalledFact? {
    val operation = proof.operation
    val ownerBag = operation.ownerBag as? AndroidTargetListenerInstallationOwnerBag ?: return null
    if (ownerBag.target !== target || ownerBag.port !== port || ownerBag.binding !== binding ||
        binding.targetFact !== port.bindingFact || binding.targetIdentity !== target.identity ||
        binding.operationIdentity != port.operationIdentity || binding.provenance !== port.provenance ||
        proof.operationIdentity != port.operationIdentity || proof.ticket.occurrence !== operation ||
        proof.ticket.finalLaneNoEntryProof !== proof ||
        proof.ticket.operationIdentity != operation.identity || proof.ticket.lane !== proof.lane ||
        proof.ticket.workerIdentity !== proof.workerIdentity ||
        proof.ticket.terminationReceipt !== proof.terminationReceipt ||
        proof.workerIdentity.lane !== proof.lane ||
        !proof.terminationReceipt.matchesWorker(proof.workerIdentity) ||
        proof.lane.terminationReceipt !== proof.terminationReceipt ||
        !proof.lane.acceptsTerminationReceipt(proof.terminationReceipt)
    ) {
        return null
    }
    val exactFrozenProof = operation.settlementGate.withLock {
        proof.operation === operation && proof.ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                proof.ticket.postFailureResidue == null &&
                operation.submissionDisposition == OperationSubmissionDisposition.Accepted &&
                operation.submissionFailure == null && operation.submissionAmbiguousFatal == null &&
                operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                operation.disposition == OperationDisposition.Cancelled
    }
    if (!exactFrozenProof) return null
    return TargetListenerNeverInstalledFact.precreate(
        targetOwner = targetOwner,
        constructionProof = constructionProof,
        targetIdentity = target.identity,
        provenance = port.provenance,
        binding = binding,
        finalLaneNoEntryProof = proof,
    )
}
