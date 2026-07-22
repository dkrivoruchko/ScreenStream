package io.screenstream.engine.internal.target

import io.screenstream.engine.internal.gl.GlOperationKind
import io.screenstream.engine.internal.gl.GlOperationSuccessReceipt
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnDisposition

internal fun interface TargetSourceSignal {
    fun signal(fact: TargetSourceAvailableFact)
}

/**
 * The immutable controller-issued identity copied from the sole Session topology stamp together with the
 * reconciliation occurrence that requested this Target. It is an identity fence only: it is not a resource
 * lease and cannot install or make a [CurrentTarget] current.
 */
internal class TargetRequestedIdentity(
    internal val startupIdentity: Long,
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val reconciliationIdentity: Long,
) {
    init {
        require(startupIdentity > 0L)
        require(desiredRevision > 0L)
        require(geometryGeneration > 0L)
        require(lifecycleEpoch > 0L)
        require(reconciliationIdentity > 0L)
    }
}

/** Exact referential identity of one Target owner. It grants no raw-resource access. */
internal class TargetIdentity private constructor(
    internal val target: CurrentTarget,
    internal val generation: Long,
) {
    init {
        require(generation > 0L)
    }

    internal fun matches(candidate: CurrentTarget): Boolean =
        target === candidate && generation == candidate.generation

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            target: CurrentTarget,
            generation: Long,
        ): TargetIdentity {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetIdentity(target, generation)
        }
    }
}

internal sealed interface TargetPredecessorRetiredFact {
    val targetGeneration: Long
}

/** Exact precreated proof that the owner consumed one installed Target after its full mechanical retirement. */
internal class CurrentTargetMechanicallyRetiredFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val retirementEvidence: TargetRetirementCompleteEvidence,
) : TargetPredecessorRetiredFact {
    override val targetGeneration: Long
        get() = targetIdentity.generation

    init {
        check(targetIdentity.matches(targetIdentity.target))
        check(retirementEvidence.targetIdentity === targetIdentity)
    }

    internal companion object {
        internal fun precreate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            retirementEvidence: TargetRetirementCompleteEvidence,
        ): CurrentTargetMechanicallyRetiredFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return CurrentTargetMechanicallyRetiredFact(targetIdentity, retirementEvidence)
        }
    }
}

internal class TargetSourceAvailableFact private constructor(
    internal val targetIdentity: TargetIdentity,
) {
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
        ): TargetSourceAvailableFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetSourceAvailableFact(targetIdentity)
        }
    }
}

/**
 * A non-owning one-shot claim on the pending-source latch of one exact open Target frame-admission epoch.
 * Its commit outcomes are created with it, before the controller's final gated commit. Claiming does not
 * consume the pending indication and grants no Target resource access.
 */
internal class TargetPendingSourceClaim private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val frameAdmissionEpoch: Long,
) {
    private lateinit var precreatedInertResult: TargetPendingSourceCommitInertFact

    init {
        require(frameAdmissionEpoch > 0L)
        check(targetIdentity.matches(targetIdentity.target))
    }

    internal fun inertResult(
        targetOwner: TargetOwner,
        constructionProof: () -> Unit,
    ): TargetPendingSourceCommitInertFact {
        check(targetOwner.acceptsConstructionProof(constructionProof))
        return precreatedInertResult
    }

    private fun bindInertResult(result: TargetPendingSourceCommitInertFact) {
        check(!this::precreatedInertResult.isInitialized)
        check(result.claim === this)
        precreatedInertResult = result
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            frameAdmissionEpoch: Long,
        ): TargetPendingSourceClaim {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            val claim = TargetPendingSourceClaim(targetIdentity, frameAdmissionEpoch)
            claim.bindInertResult(
                TargetPendingSourceCommitInertFact.create(
                    targetOwner,
                    constructionProof,
                    claim,
                ),
            )
            return claim
        }
    }
}

internal sealed interface TargetPendingSourceCommitResult {
    val claim: TargetPendingSourceClaim
}

/** Exact proof that one conflated pending-source indication was consumed for the claim's open epoch. */
internal class TargetPendingSourceConsumedFact private constructor(
    override val claim: TargetPendingSourceClaim,
    internal val sourceAvailableFact: TargetSourceAvailableFact,
) : TargetPendingSourceCommitResult {
    internal val targetIdentity: TargetIdentity
        get() = claim.targetIdentity

    internal val frameAdmissionEpoch: Long
        get() = claim.frameAdmissionEpoch

    init {
        check(sourceAvailableFact.targetIdentity === claim.targetIdentity)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            claim: TargetPendingSourceClaim,
            sourceAvailableFact: TargetSourceAvailableFact,
        ): TargetPendingSourceConsumedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetPendingSourceConsumedFact(claim, sourceAvailableFact)
        }
    }
}

/** Typed inert result; it proves that this claim consumed no pending-source indication. */
internal class TargetPendingSourceCommitInertFact private constructor(
    override val claim: TargetPendingSourceClaim,
) : TargetPendingSourceCommitResult {
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            claim: TargetPendingSourceClaim,
        ): TargetPendingSourceCommitInertFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetPendingSourceCommitInertFact(claim)
        }
    }
}

internal enum class PreparedTargetDisposition {
    Unclaimed,
    Installed,
    CleanupClaimed,
}

/**
 * Exact Target-owned provenance reserved before the allocation-sensitive construction closure is built.
 * It is neither Controller currentness nor a resource receipt.
 */
internal class TargetConstructionProvenance private constructor(
    internal val requestedIdentity: TargetRequestedIdentity,
    internal val plan: TargetPlan,
    internal val predecessorGeneration: Long,
    internal val targetGeneration: Long,
    internal val constructionOperationIdentity: Long,
) {
    init {
        require(predecessorGeneration in 0L..<Long.MAX_VALUE)
        require(targetGeneration == predecessorGeneration + 1L)
        require(constructionOperationIdentity > 0L)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            requestedIdentity: TargetRequestedIdentity,
            plan: TargetPlan,
            predecessorGeneration: Long,
            targetGeneration: Long,
            constructionOperationIdentity: Long,
        ): TargetConstructionProvenance {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetConstructionProvenance(
                requestedIdentity,
                plan,
                predecessorGeneration,
                targetGeneration,
                constructionOperationIdentity,
            )
        }
    }
}

/** Correlates the generic occurrence entry with one construction; it is deliberately not entry evidence. */
internal class TargetConstructionEntryProvenance private constructor(
    internal val constructionProvenance: TargetConstructionProvenance,
) {
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            constructionProvenance: TargetConstructionProvenance,
        ): TargetConstructionEntryProvenance {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetConstructionEntryProvenance(constructionProvenance)
        }
    }
}

internal enum class TargetConstructionAbsenceKind {
    OwnerGraphPrecreationFailed,
}

/** Precreated structural-absence provenance. Recording a failure allocates nothing and creates no receipt. */
internal class TargetConstructionAbsenceProvenance private constructor(
    internal val constructionProvenance: TargetConstructionProvenance,
    internal val kind: TargetConstructionAbsenceKind,
) {
    @Volatile
    private var recordedFailure: Throwable? = null

    internal val failure: Throwable
        get() = checkNotNull(recordedFailure)

    internal fun recordFailure(failure: Throwable) {
        check(recordedFailure == null)
        recordedFailure = failure
    }

    internal companion object {
        internal fun precreateOwnerGraphFailure(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            constructionProvenance: TargetConstructionProvenance,
        ): TargetConstructionAbsenceProvenance {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetConstructionAbsenceProvenance(
                constructionProvenance,
                TargetConstructionAbsenceKind.OwnerGraphPrecreationFailed,
            )
        }
    }
}

/**
 * The closed result of one Target-owned preparation attempt. A reserved generation is never
 * rolled back: either it owns the exact prospective [PreparedTarget], or it records that no
 * structural/physical Target root was created for that generation.
 */
internal sealed interface TargetPreparationOutcome {
    val constructionProvenance: TargetConstructionProvenance

    val requestedIdentity: TargetRequestedIdentity
        get() = constructionProvenance.requestedIdentity
    val plan: TargetPlan
        get() = constructionProvenance.plan
    val targetGeneration: Long
        get() = constructionProvenance.targetGeneration

    class Prepared private constructor(
        override val constructionProvenance: TargetConstructionProvenance,
    ) : TargetPreparationOutcome {
        private var boundPreparedTarget: PreparedTarget? = null

        internal val preparedTarget: PreparedTarget
            get() = checkNotNull(boundPreparedTarget)

        internal fun bindPreparedTarget(target: PreparedTarget) {
            check(boundPreparedTarget == null)
            check(target.requestedIdentity === requestedIdentity)
            check(target.plan === plan)
            check(target.targetGeneration == targetGeneration)
            check(target.constructionOperationIdentity == constructionProvenance.constructionOperationIdentity)
            boundPreparedTarget = target
        }

        internal companion object {
            internal fun precreate(
                targetOwner: TargetOwner,
                constructionProof: () -> Unit,
                constructionProvenance: TargetConstructionProvenance,
            ): Prepared {
                check(targetOwner.acceptsConstructionProof(constructionProof))
                return Prepared(constructionProvenance)
            }
        }
    }

    class StructurallyAbsent private constructor(
        override val constructionProvenance: TargetConstructionProvenance,
        internal val absenceProvenance: TargetConstructionAbsenceProvenance,
    ) : TargetPreparationOutcome {
        internal val failure: Throwable
            get() = absenceProvenance.failure

        internal fun recordPrecreationFailure(failure: Throwable) {
            absenceProvenance.recordFailure(failure)
        }

        internal companion object {
            internal fun precreate(
                targetOwner: TargetOwner,
                constructionProof: () -> Unit,
                constructionProvenance: TargetConstructionProvenance,
            ): StructurallyAbsent {
                check(targetOwner.acceptsConstructionProof(constructionProof))
                return StructurallyAbsent(
                    constructionProvenance,
                    TargetConstructionAbsenceProvenance.precreateOwnerGraphFailure(
                        targetOwner,
                        constructionProof,
                        constructionProvenance,
                    ),
                )
            }
        }
    }
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

internal sealed interface TargetConstructionResultFact {
    val requestedIdentity: TargetRequestedIdentity
    val targetIdentity: TargetIdentity
    val constructionOperationIdentity: Long
    val constructionProvenance: TargetConstructionProvenance
}

internal class TargetConstructionInstalledFact private constructor(
    override val requestedIdentity: TargetRequestedIdentity,
    override val targetIdentity: TargetIdentity,
    override val constructionOperationIdentity: Long,
    override val constructionProvenance: TargetConstructionProvenance,
    internal val constructionReceipt: GlOperationSuccessReceipt,
    internal val plan: TargetPlan,
    internal val listenerInstallationPort: TargetPorts.AndroidListenerInstallationPort,
) : TargetConstructionResultFact {
    init {
        require(constructionOperationIdentity > 0L)
        check(constructionReceipt.operationIdentity == constructionOperationIdentity)
        check(constructionReceipt.operationKind == GlOperationKind.TargetConstruction)
        check(targetIdentity.matches(targetIdentity.target))
        check(targetIdentity.target.requestedIdentity === requestedIdentity)
        check(constructionProvenance.requestedIdentity === requestedIdentity)
        check(constructionProvenance.targetGeneration == targetIdentity.generation)
        check(constructionProvenance.constructionOperationIdentity == constructionOperationIdentity)
        check(targetIdentity.target.plan === plan)
        check(listenerInstallationPort.targetIdentity === targetIdentity)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            requestedIdentity: TargetRequestedIdentity,
            targetIdentity: TargetIdentity,
            constructionOperationIdentity: Long,
            constructionProvenance: TargetConstructionProvenance,
            constructionReceipt: GlOperationSuccessReceipt,
            plan: TargetPlan,
            listenerInstallationPort: TargetPorts.AndroidListenerInstallationPort,
        ): TargetConstructionInstalledFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetConstructionInstalledFact(
                requestedIdentity,
                targetIdentity,
                constructionOperationIdentity,
                constructionProvenance,
                constructionReceipt,
                plan,
                listenerInstallationPort,
            )
        }
    }
}

private enum class TargetConstructionFailureFactBinding {
    Unbound,
    Bound,
}

internal class TargetConstructionFailureFact private constructor(
    override val requestedIdentity: TargetRequestedIdentity,
    override val targetIdentity: TargetIdentity,
    override val constructionOperationIdentity: Long,
    override val constructionProvenance: TargetConstructionProvenance,
    internal val cleanupTarget: CurrentTarget,
) : TargetConstructionResultFact {
    @Volatile
    private var binding: TargetConstructionFailureFactBinding = TargetConstructionFailureFactBinding.Unbound
    private var boundDisposition: TargetConstructionFoldDisposition? = null
    private var boundReturnDisposition: OperationReturnDisposition = OperationReturnDisposition.Empty
    private var boundFailure: Throwable? = null
    private var boundStage: TargetConstructionStage = TargetConstructionStage.Empty

    internal val disposition: TargetConstructionFoldDisposition
        get() {
            check(binding == TargetConstructionFailureFactBinding.Bound)
            return checkNotNull(boundDisposition)
        }

    internal val returnDisposition: OperationReturnDisposition
        get() {
            check(binding == TargetConstructionFailureFactBinding.Bound)
            return boundReturnDisposition
        }

    internal val failure: Throwable?
        get() {
            check(binding == TargetConstructionFailureFactBinding.Bound)
            return boundFailure
        }

    internal val stage: TargetConstructionStage
        get() {
            check(binding == TargetConstructionFailureFactBinding.Bound)
            return boundStage
        }

    init {
        require(constructionOperationIdentity > 0L)
        check(targetIdentity.matches(cleanupTarget))
        check(cleanupTarget.requestedIdentity === requestedIdentity)
        check(constructionProvenance.requestedIdentity === requestedIdentity)
        check(constructionProvenance.targetGeneration == targetIdentity.generation)
        check(constructionProvenance.constructionOperationIdentity == constructionOperationIdentity)
    }

    internal fun bind(
        disposition: TargetConstructionFoldDisposition,
        returnDisposition: OperationReturnDisposition,
        failure: Throwable?,
        stage: TargetConstructionStage,
    ) {
        check(binding == TargetConstructionFailureFactBinding.Unbound)
        require(disposition != TargetConstructionFoldDisposition.Install)
        boundDisposition = disposition
        boundReturnDisposition = returnDisposition
        boundFailure = failure
        boundStage = stage
        binding = TargetConstructionFailureFactBinding.Bound
    }

    internal companion object {
        internal fun precreate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            requestedIdentity: TargetRequestedIdentity,
            targetIdentity: TargetIdentity,
            constructionOperationIdentity: Long,
            constructionProvenance: TargetConstructionProvenance,
            cleanupTarget: CurrentTarget,
        ): TargetConstructionFailureFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetConstructionFailureFact(
                requestedIdentity,
                targetIdentity,
                constructionOperationIdentity,
                constructionProvenance,
                cleanupTarget,
            )
        }
    }
}

private const val TARGET_CONSTRUCTION_GENERATION_UNRECORDED: Long = -1L

internal class TargetConstructionAdmissionClosedFact private constructor() {
    @Volatile
    private var recordedLastTargetGeneration: Long = TARGET_CONSTRUCTION_GENERATION_UNRECORDED

    internal val lastTargetGenerationAtClose: Long
        get() {
            check(recordedLastTargetGeneration != TARGET_CONSTRUCTION_GENERATION_UNRECORDED)
            return recordedLastTargetGeneration
        }

    internal fun recordClosureLocked(lastTargetGenerationAtClose: Long) {
        require(lastTargetGenerationAtClose >= 0L)
        if (recordedLastTargetGeneration == TARGET_CONSTRUCTION_GENERATION_UNRECORDED) {
            recordedLastTargetGeneration = lastTargetGenerationAtClose
        } else {
            check(recordedLastTargetGeneration == lastTargetGenerationAtClose)
        }
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
        ): TargetConstructionAdmissionClosedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetConstructionAdmissionClosedFact()
        }
    }
}

internal class TargetRetirementAdmissionClosedFact private constructor(
    internal val targetIdentity: TargetIdentity,
) {
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
        ): TargetRetirementAdmissionClosedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetRetirementAdmissionClosedFact(targetIdentity)
        }
    }
}

internal class TargetWorkDrainedFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val admissionClosedFact: TargetRetirementAdmissionClosedFact,
) {
    init {
        check(targetIdentity === admissionClosedFact.targetIdentity)
    }
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            admissionClosedFact: TargetRetirementAdmissionClosedFact,
        ): TargetWorkDrainedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetWorkDrainedFact(targetIdentity, admissionClosedFact)
        }
    }
}

internal class TargetGenerationFencedFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val workDrainedFact: TargetWorkDrainedFact,
) {
    init {
        check(targetIdentity === workDrainedFact.targetIdentity)
    }
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            workDrainedFact: TargetWorkDrainedFact,
        ): TargetGenerationFencedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetGenerationFencedFact(targetIdentity, workDrainedFact)
        }
    }
}

internal class TargetSurfaceReleaseReadyFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val generationFencedFact: TargetGenerationFencedFact,
) {
    init {
        check(targetIdentity === generationFencedFact.targetIdentity)
    }
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            generationFencedFact: TargetGenerationFencedFact,
        ): TargetSurfaceReleaseReadyFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetSurfaceReleaseReadyFact(targetIdentity, generationFencedFact)
        }
    }
}

internal class TargetListenerInstalledFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val operationIdentity: Long,
    internal val provenance: TargetOperationProvenance,
) {
    init {
        require(operationIdentity > 0L)
        check(provenance.targetIdentity === targetIdentity)
        check(provenance.operationIdentity == operationIdentity)
        check(provenance.portKind == TargetPortKind.ListenerInstallation)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            operationIdentity: Long,
            provenance: TargetOperationProvenance,
        ): TargetListenerInstalledFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetListenerInstalledFact(targetIdentity, operationIdentity, provenance)
        }
    }
}

internal class TargetListenerRemovalSettledFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val operationIdentity: Long,
    internal val provenance: TargetOperationProvenance,
) {
    init {
        require(operationIdentity > 0L)
        check(provenance.targetIdentity === targetIdentity)
        check(provenance.operationIdentity == operationIdentity)
        check(provenance.portKind == TargetPortKind.ListenerRemoval)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            operationIdentity: Long,
            provenance: TargetOperationProvenance,
        ): TargetListenerRemovalSettledFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetListenerRemovalSettledFact(targetIdentity, operationIdentity, provenance)
        }
    }
}

internal class TargetListenerRemovalReturnedFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val operationIdentity: Long,
    internal val provenance: TargetOperationProvenance,
) {
    init {
        require(operationIdentity > 0L)
        check(provenance.targetIdentity === targetIdentity)
        check(provenance.operationIdentity == operationIdentity)
        check(provenance.portKind == TargetPortKind.ListenerRemoval)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            operationIdentity: Long,
            provenance: TargetOperationProvenance,
        ): TargetListenerRemovalReturnedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetListenerRemovalReturnedFact(targetIdentity, operationIdentity, provenance)
        }
    }
}

internal class TargetListenerSentinelObservedFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val operationIdentity: Long,
    internal val provenance: TargetOperationProvenance,
) {
    init {
        require(operationIdentity > 0L)
        check(provenance.targetIdentity === targetIdentity)
        check(provenance.operationIdentity == operationIdentity)
        check(provenance.portKind == TargetPortKind.ListenerRemoval)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            operationIdentity: Long,
            provenance: TargetOperationProvenance,
        ): TargetListenerSentinelObservedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetListenerSentinelObservedFact(targetIdentity, operationIdentity, provenance)
        }
    }
}

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

/** Admission/body-return outcome only. It is never a physical release, producer, or detach receipt. */
internal enum class TargetPortUseOutcome {
    Rejected,
    BodyReturned,
}

internal enum class TargetFrameAdmissionReopenRejectionReason {
    StaleFact,
    EpochExhausted,
    RetirementClosed,
    TargetNotReady,
    RetainedGlMutationUnsettled,
}

internal sealed interface TargetFrameEntryResult

internal sealed interface TargetFrameReservationResult

internal class TargetFrameReservedFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val targetGeneration: Long,
    internal val frameAdmissionEpoch: Long,
    internal val operationIdentity: Long,
    internal val provenance: TargetOperationProvenance,
) : TargetFrameReservationResult {
    init {
        require(targetGeneration > 0L)
        require(frameAdmissionEpoch > 0L)
        require(operationIdentity > 0L)
        check(targetIdentity.generation == targetGeneration)
        check(provenance.targetIdentity === targetIdentity)
        check(provenance.operationIdentity == operationIdentity)
        check(provenance.portKind == TargetPortKind.GlFrame)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            frameAdmissionEpoch: Long,
            operationIdentity: Long,
            provenance: TargetOperationProvenance,
        ): TargetFrameReservedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetFrameReservedFact(
                targetIdentity,
                targetIdentity.generation,
                frameAdmissionEpoch,
                operationIdentity,
                provenance,
            )
        }
    }
}

internal class TargetEnteredFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val targetGeneration: Long,
    internal val frameAdmissionEpoch: Long,
    internal val operationIdentity: Long,
    internal val provenance: TargetOperationProvenance,
) : TargetFrameEntryResult {
    init {
        require(targetGeneration > 0L)
        require(frameAdmissionEpoch > 0L)
        require(operationIdentity > 0L)
        check(targetIdentity.generation == targetGeneration)
        check(provenance.targetIdentity === targetIdentity)
        check(provenance.operationIdentity == operationIdentity)
        check(provenance.portKind == TargetPortKind.GlFrame)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            frameAdmissionEpoch: Long,
            operationIdentity: Long,
            provenance: TargetOperationProvenance,
        ): TargetEnteredFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetEnteredFact(
                targetIdentity,
                targetIdentity.generation,
                frameAdmissionEpoch,
                operationIdentity,
                provenance,
            )
        }
    }
}

internal class TargetFrameRejectedBySealOrStaleEpochFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val targetGeneration: Long,
    internal val frameAdmissionEpoch: Long,
    internal val operationIdentity: Long,
    internal val provenance: TargetOperationProvenance,
) : TargetFrameEntryResult, TargetFrameReservationResult {
    init {
        require(targetGeneration > 0L)
        require(frameAdmissionEpoch > 0L)
        require(operationIdentity > 0L)
        check(targetIdentity.generation == targetGeneration)
        check(provenance.targetIdentity === targetIdentity)
        check(provenance.operationIdentity == operationIdentity)
        check(provenance.portKind == TargetPortKind.GlFrame)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            frameAdmissionEpoch: Long,
            operationIdentity: Long,
            provenance: TargetOperationProvenance,
        ): TargetFrameRejectedBySealOrStaleEpochFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetFrameRejectedBySealOrStaleEpochFact(
                targetIdentity,
                targetIdentity.generation,
                frameAdmissionEpoch,
                operationIdentity,
                provenance,
            )
        }
    }
}

internal class TargetFramePortRetiredFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val targetGeneration: Long,
    internal val frameAdmissionEpoch: Long,
    internal val operationIdentity: Long,
    internal val provenance: TargetOperationProvenance,
    internal val entryResult: TargetFrameEntryResult,
) {
    init {
        require(targetGeneration > 0L)
        require(frameAdmissionEpoch > 0L)
        require(operationIdentity > 0L)
        check(targetIdentity.generation == targetGeneration)
        check(provenance.targetIdentity === targetIdentity)
        check(provenance.operationIdentity == operationIdentity)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            frameAdmissionEpoch: Long,
            operationIdentity: Long,
            provenance: TargetOperationProvenance,
            entryResult: TargetFrameEntryResult,
        ): TargetFramePortRetiredFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetFramePortRetiredFact(
                targetIdentity,
                targetIdentity.generation,
                frameAdmissionEpoch,
                operationIdentity,
                provenance,
                entryResult,
            )
        }
    }
}

internal class TargetFrameAdmissionSealedFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val targetGeneration: Long,
    internal val sealedEpoch: Long,
    internal val originRetainedReconfigurationIdentity: Long,
    internal val predecessor: TargetEnteredFact?,
) {
    init {
        require(targetGeneration > 0L)
        require(sealedEpoch > 0L)
        require(originRetainedReconfigurationIdentity > 0L)
        check(targetIdentity.generation == targetGeneration)
        check(predecessor == null ||
                predecessor.targetIdentity === targetIdentity && predecessor.frameAdmissionEpoch == sealedEpoch)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            sealedEpoch: Long,
            originRetainedReconfigurationIdentity: Long,
            predecessor: TargetEnteredFact?,
        ): TargetFrameAdmissionSealedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetFrameAdmissionSealedFact(
                targetIdentity,
                targetIdentity.generation,
                sealedEpoch,
                originRetainedReconfigurationIdentity,
                predecessor,
            )
        }
    }
}

internal class TargetFrameQuiescedFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val targetGeneration: Long,
    internal val sealedFact: TargetFrameAdmissionSealedFact,
) {
    internal val sealedEpoch: Long = sealedFact.sealedEpoch
    internal val originRetainedReconfigurationIdentity: Long =
        sealedFact.originRetainedReconfigurationIdentity

    init {
        check(targetIdentity === sealedFact.targetIdentity)
        check(targetGeneration == sealedFact.targetGeneration)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            sealedFact: TargetFrameAdmissionSealedFact,
        ): TargetFrameQuiescedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetFrameQuiescedFact(
                sealedFact.targetIdentity,
                sealedFact.targetGeneration,
                sealedFact,
            )
        }
    }
}

internal enum class TargetRetainedGlInertReason {
    StaleQuiescence,
    RetirementClosed,
    ReservationAlreadyPresent,
    RepeatedEntry,
}

internal sealed interface TargetRetainedGlReservationResult

internal sealed interface TargetRetainedGlEntryResult

internal sealed interface TargetRetainedGlMechanicalSettlementFact

internal sealed interface TargetRetainedGlSettlementResult {
    fun claimSettledFact(): TargetRetainedGlSettledFact?
}

internal class TargetRetainedGlReservedFact private constructor(
    internal val reservation: TargetRetainedGlReservation,
) : TargetRetainedGlReservationResult {
    init {
        validateRetainedGlFactBindings(reservation)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            reservation: TargetRetainedGlReservation,
        ): TargetRetainedGlReservedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetRetainedGlReservedFact(reservation)
        }
    }
}

internal class TargetRetainedGlAdmittedFact private constructor(
    internal val reservation: TargetRetainedGlReservation,
) : TargetRetainedGlEntryResult {
    init {
        validateRetainedGlFactBindings(reservation)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            reservation: TargetRetainedGlReservation,
        ): TargetRetainedGlAdmittedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetRetainedGlAdmittedFact(reservation)
        }
    }
}

internal class TargetRetainedGlSettledFact private constructor(
    internal val reservation: TargetRetainedGlReservation,
    internal val admittedFact: TargetRetainedGlAdmittedFact,
) : TargetRetainedGlMechanicalSettlementFact, TargetRetainedGlSettlementResult {
    init {
        validateRetainedGlFactBindings(reservation)
        check(admittedFact === reservation.admittedFact)
    }

    override fun claimSettledFact(): TargetRetainedGlSettledFact = this

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            reservation: TargetRetainedGlReservation,
            admittedFact: TargetRetainedGlAdmittedFact,
        ): TargetRetainedGlSettledFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetRetainedGlSettledFact(reservation, admittedFact)
        }
    }
}

internal class TargetRetainedGlSettlementRejectedFact private constructor(
    internal val reservation: TargetRetainedGlReservation,
    internal val admittedFact: TargetRetainedGlAdmittedFact,
) : TargetRetainedGlSettlementResult {
    init {
        validateRetainedGlFactBindings(reservation)
        check(admittedFact.reservation === reservation)
    }

    override fun claimSettledFact(): TargetRetainedGlSettledFact? = null

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            reservation: TargetRetainedGlReservation,
            admittedFact: TargetRetainedGlAdmittedFact,
        ): TargetRetainedGlSettlementRejectedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetRetainedGlSettlementRejectedFact(reservation, admittedFact)
        }
    }
}

internal class TargetRetainedGlInertFact private constructor(
    internal val reservation: TargetRetainedGlReservation,
    internal val reason: TargetRetainedGlInertReason,
) : TargetRetainedGlReservationResult,
    TargetRetainedGlEntryResult,
    TargetRetainedGlMechanicalSettlementFact {
    init {
        validateRetainedGlFactBindings(reservation)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            reservation: TargetRetainedGlReservation,
            reason: TargetRetainedGlInertReason,
        ): TargetRetainedGlInertFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetRetainedGlInertFact(reservation, reason)
        }
    }
}

internal class TargetRetainedGlReservation private constructor(
    private val targetOwner: TargetOwner,
    private val target: CurrentTarget,
    internal val targetIdentity: TargetIdentity,
    internal val quiescedFact: TargetFrameQuiescedFact,
    internal val targetGeneration: Long,
    internal val retainedReconfigurationIdentity: Long,
    constructionProof: () -> Unit,
) {
    internal val reservedFact: TargetRetainedGlReservedFact =
        TargetRetainedGlReservedFact.create(targetOwner, constructionProof, this)
    internal val admittedFact: TargetRetainedGlAdmittedFact =
        TargetRetainedGlAdmittedFact.create(targetOwner, constructionProof, this)
    internal val settlementRejectedFact: TargetRetainedGlSettlementRejectedFact =
        TargetRetainedGlSettlementRejectedFact.create(targetOwner, constructionProof, this, admittedFact)
    internal val settledFact: TargetRetainedGlSettledFact =
        TargetRetainedGlSettledFact.create(targetOwner, constructionProof, this, admittedFact)
    private val inertFacts: Array<TargetRetainedGlInertFact> =
        Array(TargetRetainedGlInertReason.entries.size) { index ->
            TargetRetainedGlInertFact.create(
                targetOwner,
                constructionProof,
                this,
                TargetRetainedGlInertReason.entries[index],
            )
        }

    init {
        require(targetGeneration > 0L)
        require(retainedReconfigurationIdentity > 0L)
        check(targetOwner.acceptsConstructionProof(constructionProof))
        check(targetIdentity.generation == targetGeneration)
        check(quiescedFact.targetIdentity === targetIdentity)
        check(quiescedFact.targetGeneration == targetGeneration)
    }

    internal fun enter(): TargetRetainedGlEntryResult =
        targetOwner.enterRetainedGlMutation(target, reservedFact)

    internal fun settle(admittedFact: TargetRetainedGlAdmittedFact): TargetRetainedGlSettlementResult =
        targetOwner.settleRetainedGlMutation(target, admittedFact)

    internal fun inertFact(reason: TargetRetainedGlInertReason): TargetRetainedGlInertFact =
        inertFacts[reason.ordinal]

    internal fun matchesTarget(candidate: CurrentTarget): Boolean = target === candidate

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            target: CurrentTarget,
            targetIdentity: TargetIdentity,
            quiescedFact: TargetFrameQuiescedFact,
            retainedReconfigurationIdentity: Long,
        ): TargetRetainedGlReservation {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetRetainedGlReservation(
                targetOwner,
                target,
                targetIdentity,
                quiescedFact,
                targetIdentity.generation,
                retainedReconfigurationIdentity,
                constructionProof,
            )
        }
    }
}

private fun validateRetainedGlFactBindings(reservation: TargetRetainedGlReservation) {
    check(reservation.targetIdentity.generation == reservation.targetGeneration)
    check(reservation.quiescedFact.targetIdentity === reservation.targetIdentity)
    check(reservation.quiescedFact.targetGeneration == reservation.targetGeneration)
    check(reservation.retainedReconfigurationIdentity > 0L)
}

internal sealed interface TargetFrameAdmissionReopenResult

internal class TargetFrameAdmissionReopenedFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val targetGeneration: Long,
    internal val quiescedFact: TargetFrameQuiescedFact,
    internal val reopenedEpoch: Long,
    internal val retainedReconfigurationIdentity: Long,
) : TargetFrameAdmissionReopenResult {
    init {
        require(reopenedEpoch > 0L)
        require(retainedReconfigurationIdentity > 0L)
        check(targetIdentity === quiescedFact.targetIdentity)
        check(targetGeneration == quiescedFact.targetGeneration)
        check(reopenedEpoch > quiescedFact.sealedEpoch)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            quiescedFact: TargetFrameQuiescedFact,
            reopenedEpoch: Long,
            retainedReconfigurationIdentity: Long,
        ): TargetFrameAdmissionReopenedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetFrameAdmissionReopenedFact(
                quiescedFact.targetIdentity,
                quiescedFact.targetGeneration,
                quiescedFact,
                reopenedEpoch,
                retainedReconfigurationIdentity,
            )
        }
    }
}

internal class TargetFrameAdmissionReopenRejectedFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val quiescedFact: TargetFrameQuiescedFact,
    internal val reason: TargetFrameAdmissionReopenRejectionReason,
) : TargetFrameAdmissionReopenResult {
    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            quiescedFact: TargetFrameQuiescedFact,
            reason: TargetFrameAdmissionReopenRejectionReason,
        ): TargetFrameAdmissionReopenRejectedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetFrameAdmissionReopenRejectedFact(targetIdentity, quiescedFact, reason)
        }
    }
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

internal sealed interface TargetOperationProvenance {
    val targetIdentity: TargetIdentity
    val requestedIdentity: TargetRequestedIdentity
    val operationIdentity: Long
    val portKind: TargetPortKind
}

/**
 * Target-owned immutable correlation created with a detached Android-operation candidate. It binds only
 * Target identity/provenance and the Controller-supplied serialized reconfiguration identity; it grants no
 * Android entry, currentness, raw-handle access, or Target commit authority.
 */
internal class TargetStagedAndroidOperationCorrelation private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val requestedIdentity: TargetRequestedIdentity,
    internal val operationIdentity: Long,
    internal val portKind: TargetPortKind,
    internal val reconfigurationIdentity: Long,
) {
    init {
        require(operationIdentity > 0L)
        require(reconfigurationIdentity > 0L)
        check(targetIdentity.matches(targetIdentity.target))
        check(targetIdentity.target.requestedIdentity === requestedIdentity)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            provenance: TargetOperationProvenance,
            reconfigurationIdentity: Long,
        ): TargetStagedAndroidOperationCorrelation {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetStagedAndroidOperationCorrelation(
                targetIdentity = provenance.targetIdentity,
                requestedIdentity = provenance.requestedIdentity,
                operationIdentity = provenance.operationIdentity,
                portKind = provenance.portKind,
                reconfigurationIdentity = reconfigurationIdentity,
            )
        }
    }
}

/** A fully allocated detached candidate. Android must create its durable root only after receiving this value. */
internal sealed interface TargetStagedAndroidOperationCandidate {
    val targetIdentity: TargetIdentity
    val operationIdentity: Long
    val provenance: TargetOperationProvenance
    val correlation: TargetStagedAndroidOperationCorrelation
}

internal sealed interface TargetAndroidPortBindingFact {
    val targetIdentity: TargetIdentity
    val targetGeneration: Long
    val operationIdentity: Long
    val provenance: TargetOperationProvenance
}

internal sealed interface TargetAndroidListenerInstallationBindingFact : TargetAndroidPortBindingFact

internal sealed interface TargetAndroidListenerRemovalBindingFact : TargetAndroidPortBindingFact

internal sealed interface TargetAndroidProducerBindingFact : TargetAndroidPortBindingFact

internal sealed interface TargetAndroidDetachBindingFact : TargetAndroidPortBindingFact

internal sealed interface TargetProducerPortPreparationResult

/** No producer candidate or Android root was published because detached precreation failed. */
internal sealed interface TargetProducerPreparationRetiredUnusedFact :
    TargetProducerPortPreparationResult {
    val targetIdentity: TargetIdentity
    val operationIdentity: Long
    val provenance: TargetOperationProvenance
    val failure: Throwable
}

internal sealed interface TargetProducerPortCommitResult

internal sealed interface TargetProducerPortCommittedFact :
    TargetProducerPortCommitResult,
    TargetAndroidProducerBindingFact

/** A fully prepared producer candidate lost the atomic binding commit and never entered Target state. */
internal sealed interface TargetProducerPortRetiredUnusedFact : TargetProducerPortCommitResult {
    val bindingFact: TargetAndroidProducerBindingFact
}

internal sealed interface TargetInitialReleasePortCommitResult

internal sealed interface TargetInitialReleasePortCommittedFact :
    TargetInitialReleasePortCommitResult,
    TargetAndroidDetachBindingFact

internal sealed interface TargetInitialReleasePortRetiredUnusedFact :
    TargetInitialReleasePortCommitResult {
    val bindingFact: TargetAndroidDetachBindingFact
}

internal sealed interface TargetProducerApplicationFact {
    val targetIdentity: TargetIdentity
        get() = provenance.targetIdentity
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
    val targetIdentity: TargetIdentity
        get() = provenance.targetIdentity
    val targetGeneration: Long
    val operationIdentity: Long
    val detachKind: TargetProducerDetachKind
    val provenance: TargetOperationProvenance
}

/**
 * Exact positive aggregate-replacement identity shared only for correlation/root binding. The old
 * detach and new producer remain separate Android operations with separate physical returns.
 */
internal sealed interface TargetRetargetOccurrenceFact {
    val retargetOccurrenceIdentity: Long
}

internal sealed interface TargetRetargetProducerApplicationFact :
    TargetProducerApplicationFact,
    TargetRetargetOccurrenceFact

internal sealed interface TargetRetargetProducerDetachReceipt :
    TargetProducerDetachReceipt,
    TargetRetargetOccurrenceFact

internal sealed interface TargetStagedProducerPortCommitResult

internal sealed interface TargetStagedDetachPortCommitResult

internal sealed interface TargetStagedPortFact : TargetRetargetOccurrenceFact, TargetAndroidPortBindingFact {
    val correlation: TargetStagedAndroidOperationCorrelation

    override val retargetOccurrenceIdentity: Long
        get() = correlation.reconfigurationIdentity
}

internal sealed interface TargetStagedProducerPortCommittedFact :
    TargetStagedProducerPortCommitResult,
    TargetStagedPortFact

internal sealed interface TargetStagedDetachPortCommittedFact :
    TargetStagedDetachPortCommitResult,
    TargetStagedPortFact

/** A detached prepared producer port lost commit and never became Target authority. */
internal sealed interface TargetStagedProducerPortUnusedFact :
    TargetStagedProducerPortCommitResult,
    TargetStagedPortFact

/** A detached prepared detach port lost commit and never became Target authority. */
internal sealed interface TargetStagedDetachPortUnusedFact :
    TargetStagedDetachPortCommitResult,
    TargetStagedPortFact

/** The exact post was accepted or became acceptance-ambiguous; inert port retirement is closed. */
internal sealed interface TargetStagedPortPostExposedFact : TargetStagedPortFact

internal sealed interface TargetStagedProducerPortRetiredFact : TargetStagedPortFact

/**
 * Retires only a definitely-unentered detach port. It is deliberately not a physical detach
 * receipt and therefore cannot change ProducerAttached by itself.
 */
internal sealed interface TargetStagedDetachPortRetiredFact : TargetStagedPortFact

/** The entered producer call settled without proof; the opaque committed graph remains authoritative. */
internal sealed interface TargetStagedProducerPortSettledOrAmbiguousFact : TargetStagedPortFact

/** The entered detach call returned without detach proof; only its exact port is retired. */
internal sealed interface TargetStagedDetachPortSettledFact : TargetStagedPortFact

internal sealed interface TargetAndroidPlatformApplicationResult {
    class Producer(
        internal val fact: TargetProducerApplicationFact,
    ) : TargetAndroidPlatformApplicationResult

    class Detach(
        internal val receipt: TargetProducerDetachReceipt,
    ) : TargetAndroidPlatformApplicationResult

    class ListenerInstalled(
        internal val fact: TargetListenerInstalledFact,
    ) : TargetAndroidPlatformApplicationResult

    class ListenerNeverInstalled(
        internal val fact: TargetListenerNeverInstalledFact,
    ) : TargetAndroidPlatformApplicationResult

    class ListenerRemovalReturned(
        internal val fact: TargetListenerRemovalReturnedFact,
    ) : TargetAndroidPlatformApplicationResult

    class ListenerRemovalSettled(
        internal val fact: TargetListenerRemovalSettledFact,
    ) : TargetAndroidPlatformApplicationResult

    class ListenerSentinelObserved(
        internal val fact: TargetListenerSentinelObservedFact,
    ) : TargetAndroidPlatformApplicationResult

    class ProducerPortSettledOrAmbiguous(
        internal val fact: TargetStagedProducerPortSettledOrAmbiguousFact,
    ) : TargetAndroidPlatformApplicationResult

    class InitialProducerPortSettledOrAmbiguous(
        internal val fact: TargetAndroidProducerBindingFact,
    ) : TargetAndroidPlatformApplicationResult

    class DetachPortSettled(
        internal val fact: TargetStagedDetachPortSettledFact,
    ) : TargetAndroidPlatformApplicationResult
}

private class TargetOperationProvenanceImpl(
    override val targetIdentity: TargetIdentity,
    override val requestedIdentity: TargetRequestedIdentity,
    override val operationIdentity: Long,
    override val portKind: TargetPortKind,
) : TargetOperationProvenance {
    init {
        require(operationIdentity > 0L)
        check(targetIdentity.matches(targetIdentity.target))
        check(targetIdentity.target.requestedIdentity === requestedIdentity)
    }
}

private class TargetProducerEvidenceImpl(
    override val targetGeneration: Long,
    override val operationIdentity: Long,
    override val operationKind: TargetProducerOperationKind,
    override val provenance: TargetOperationProvenance,
) : TargetProducerEvidence {
    init {
        check(targetGeneration == provenance.targetIdentity.generation)
        check(operationIdentity == provenance.operationIdentity)
    }
}

private class TargetNoProducerEvidenceImpl(
    override val targetGeneration: Long,
    override val operationIdentity: Long,
    override val operationKind: TargetProducerOperationKind,
    override val reason: TargetNoProducerReason,
    override val provenance: TargetOperationProvenance,
) : TargetNoProducerEvidence {
    init {
        check(targetGeneration == provenance.targetIdentity.generation)
        check(operationIdentity == provenance.operationIdentity)
    }
}

private class TargetProducerDetachReceiptImpl(
    override val targetGeneration: Long,
    override val operationIdentity: Long,
    override val detachKind: TargetProducerDetachKind,
    override val provenance: TargetOperationProvenance,
) : TargetProducerDetachReceipt {
    init {
        check(targetGeneration == provenance.targetIdentity.generation)
        check(operationIdentity == provenance.operationIdentity)
    }
}

private class TargetRetargetProducerEvidenceImpl(
    override val targetGeneration: Long,
    override val operationIdentity: Long,
    override val operationKind: TargetProducerOperationKind,
    override val provenance: TargetOperationProvenance,
    override val retargetOccurrenceIdentity: Long,
) : TargetProducerEvidence, TargetRetargetProducerApplicationFact {
    init {
        require(retargetOccurrenceIdentity > 0L)
        check(targetGeneration == provenance.targetIdentity.generation)
        check(operationIdentity == provenance.operationIdentity)
    }
}

private class TargetRetargetNoProducerEvidenceImpl(
    override val targetGeneration: Long,
    override val operationIdentity: Long,
    override val operationKind: TargetProducerOperationKind,
    override val reason: TargetNoProducerReason,
    override val provenance: TargetOperationProvenance,
    override val retargetOccurrenceIdentity: Long,
) : TargetNoProducerEvidence, TargetRetargetProducerApplicationFact {
    init {
        require(retargetOccurrenceIdentity > 0L)
        check(targetGeneration == provenance.targetIdentity.generation)
        check(operationIdentity == provenance.operationIdentity)
    }
}

private class TargetRetargetProducerDetachReceiptImpl(
    override val targetGeneration: Long,
    override val operationIdentity: Long,
    override val detachKind: TargetProducerDetachKind,
    override val provenance: TargetOperationProvenance,
    override val retargetOccurrenceIdentity: Long,
) : TargetRetargetProducerDetachReceipt {
    init {
        require(retargetOccurrenceIdentity > 0L)
        check(targetGeneration == provenance.targetIdentity.generation)
        check(operationIdentity == provenance.operationIdentity)
    }
}

internal fun targetOperationProvenance(
    targetOwner: TargetOwner,
    constructionProof: () -> Unit,
    targetIdentity: TargetIdentity,
    operationIdentity: Long,
    portKind: TargetPortKind,
): TargetOperationProvenance {
    check(targetOwner.acceptsConstructionProof(constructionProof))
    return TargetOperationProvenanceImpl(
        targetIdentity,
        targetIdentity.target.requestedIdentity,
        operationIdentity,
        portKind,
    )
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
    check(provenance.portKind == operationKind.portKind())
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
    check(provenance.portKind == operationKind.portKind())
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
    val expectedPortKind = when (detachKind) {
        TargetProducerDetachKind.VirtualDisplayDetach -> TargetPortKind.VirtualDisplayDetach
        TargetProducerDetachKind.VirtualDisplayRelease -> TargetPortKind.VirtualDisplayRelease
    }
    check(provenance.portKind == expectedPortKind)
    return TargetProducerDetachReceiptImpl(targetGeneration, operationIdentity, detachKind, provenance)
}

internal fun targetRetargetProducerEvidence(
    targetOwner: TargetOwner,
    constructionProof: () -> Unit,
    targetGeneration: Long,
    operationIdentity: Long,
    operationKind: TargetProducerOperationKind,
    provenance: TargetOperationProvenance,
    retargetOccurrenceIdentity: Long,
): TargetProducerEvidence {
    check(targetOwner.acceptsConstructionProof(constructionProof))
    check(provenance.portKind == operationKind.portKind())
    return TargetRetargetProducerEvidenceImpl(
        targetGeneration,
        operationIdentity,
        operationKind,
        provenance,
        retargetOccurrenceIdentity,
    )
}

internal fun targetRetargetNoProducerEvidence(
    targetOwner: TargetOwner,
    constructionProof: () -> Unit,
    targetGeneration: Long,
    operationIdentity: Long,
    operationKind: TargetProducerOperationKind,
    reason: TargetNoProducerReason,
    provenance: TargetOperationProvenance,
    retargetOccurrenceIdentity: Long,
): TargetNoProducerEvidence {
    check(targetOwner.acceptsConstructionProof(constructionProof))
    check(provenance.portKind == operationKind.portKind())
    return TargetRetargetNoProducerEvidenceImpl(
        targetGeneration,
        operationIdentity,
        operationKind,
        reason,
        provenance,
        retargetOccurrenceIdentity,
    )
}

internal fun targetRetargetProducerDetachReceipt(
    targetOwner: TargetOwner,
    constructionProof: () -> Unit,
    targetGeneration: Long,
    operationIdentity: Long,
    detachKind: TargetProducerDetachKind,
    provenance: TargetOperationProvenance,
    retargetOccurrenceIdentity: Long,
): TargetProducerDetachReceipt {
    check(targetOwner.acceptsConstructionProof(constructionProof))
    val expectedPortKind = when (detachKind) {
        TargetProducerDetachKind.VirtualDisplayDetach -> TargetPortKind.VirtualDisplayDetach
        TargetProducerDetachKind.VirtualDisplayRelease -> TargetPortKind.VirtualDisplayRelease
    }
    check(provenance.portKind == expectedPortKind)
    return TargetRetargetProducerDetachReceiptImpl(
        targetGeneration,
        operationIdentity,
        detachKind,
        provenance,
        retargetOccurrenceIdentity,
    )
}

private fun TargetProducerOperationKind.portKind(): TargetPortKind = when (this) {
    TargetProducerOperationKind.VirtualDisplayCreation -> TargetPortKind.VirtualDisplayCreation
    TargetProducerOperationKind.VirtualDisplayAttachment -> TargetPortKind.VirtualDisplayAttachment
}
