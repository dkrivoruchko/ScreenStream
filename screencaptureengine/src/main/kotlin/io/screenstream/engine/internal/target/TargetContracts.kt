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
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val reconciliationIdentity: Long,
) {
    init {
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

/** Passive Target-local snapshot; the Controller still rechecks its sole topology stamp and live owner. */
internal class TargetCurrentnessFact private constructor(
    internal val targetIdentity: TargetIdentity,
    internal val plan: TargetPlan,
    internal val listenerInstalled: Boolean,
    internal val producerState: TargetProducerState,
    internal val generationFenced: Boolean,
    internal val frameAdmissionEpoch: Long,
    internal val frameAdmissionSealedFact: TargetFrameAdmissionSealedFact?,
    internal val frameQuiescedFact: TargetFrameQuiescedFact?,
    internal val frameAdmissionRetirementClosed: Boolean,
    internal val version: Long,
    internal val versionExhausted: Boolean,
) {
    init {
        require(frameAdmissionEpoch > 0L)
        require(version > 0L)
        check(versionExhausted == (version == Long.MAX_VALUE))
        check(targetIdentity.matches(targetIdentity.target))
        check(targetIdentity.target.plan === plan)
        check(frameQuiescedFact == null || frameQuiescedFact.sealedFact === frameAdmissionSealedFact)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetIdentity: TargetIdentity,
            plan: TargetPlan,
            listenerInstalled: Boolean,
            producerState: TargetProducerState,
            generationFenced: Boolean,
            frameAdmissionEpoch: Long,
            frameAdmissionSealedFact: TargetFrameAdmissionSealedFact?,
            frameQuiescedFact: TargetFrameQuiescedFact?,
            frameAdmissionRetirementClosed: Boolean,
            version: Long,
            versionExhausted: Boolean,
        ): TargetCurrentnessFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetCurrentnessFact(
                targetIdentity,
                plan,
                listenerInstalled,
                producerState,
                generationFenced,
                frameAdmissionEpoch,
                frameAdmissionSealedFact,
                frameQuiescedFact,
                frameAdmissionRetirementClosed,
                version,
                versionExhausted,
            )
        }
    }
}

internal sealed interface TargetConstructionResultFact {
    val requestedIdentity: TargetRequestedIdentity
    val targetIdentity: TargetIdentity
    val constructionOperationIdentity: Long
}

internal class TargetConstructionInstalledFact private constructor(
    override val requestedIdentity: TargetRequestedIdentity,
    override val targetIdentity: TargetIdentity,
    override val constructionOperationIdentity: Long,
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
            constructionReceipt: GlOperationSuccessReceipt,
            plan: TargetPlan,
            listenerInstallationPort: TargetPorts.AndroidListenerInstallationPort,
        ): TargetConstructionInstalledFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetConstructionInstalledFact(
                requestedIdentity,
                targetIdentity,
                constructionOperationIdentity,
                constructionReceipt,
                plan,
                listenerInstallationPort,
            )
        }
    }
}

internal class TargetConstructionFailureFact private constructor(
    override val requestedIdentity: TargetRequestedIdentity,
    override val targetIdentity: TargetIdentity,
    override val constructionOperationIdentity: Long,
    internal val disposition: TargetConstructionFoldDisposition,
    internal val cleanupTarget: CurrentTarget,
    internal val returnDisposition: OperationReturnDisposition,
    internal val failure: Throwable?,
    internal val stage: TargetConstructionStage,
) : TargetConstructionResultFact {
    init {
        require(constructionOperationIdentity > 0L)
        require(disposition != TargetConstructionFoldDisposition.Install)
        check(targetIdentity.matches(cleanupTarget))
        check(cleanupTarget.requestedIdentity === requestedIdentity)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            requestedIdentity: TargetRequestedIdentity,
            targetIdentity: TargetIdentity,
            constructionOperationIdentity: Long,
            disposition: TargetConstructionFoldDisposition,
            cleanupTarget: CurrentTarget,
            returnDisposition: OperationReturnDisposition,
            failure: Throwable?,
            stage: TargetConstructionStage,
        ): TargetConstructionFailureFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetConstructionFailureFact(
                requestedIdentity,
                targetIdentity,
                constructionOperationIdentity,
                disposition,
                cleanupTarget,
                returnDisposition,
                failure,
                stage,
            )
        }
    }
}

internal class TargetConstructionAdmissionClosedFact private constructor(
    internal val lastTargetGenerationAtClose: Long,
) {
    init {
        require(lastTargetGenerationAtClose >= 0L)
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            lastTargetGenerationAtClose: Long,
        ): TargetConstructionAdmissionClosedFact {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetConstructionAdmissionClosedFact(lastTargetGenerationAtClose)
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
    val operationIdentity: Long
    val portKind: TargetPortKind
}

internal sealed interface TargetProducerApplicationCandidate

internal sealed interface TargetProducerDetachApplicationCandidate

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

internal sealed interface TargetStagedPortFact : TargetRetargetOccurrenceFact {
    val targetIdentity: TargetIdentity
    val targetGeneration: Long
    val operationIdentity: Long
    val provenance: TargetOperationProvenance
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

internal sealed interface TargetStagedProducerPortRetiredFact : TargetStagedPortFact {
    val noProducerEvidence: TargetNoProducerEvidence
}

/**
 * Retires only a definitely-unentered detach port. It is deliberately not a physical detach
 * receipt and therefore cannot change ProducerAttached by itself.
 */
internal sealed interface TargetStagedDetachPortRetiredFact : TargetStagedPortFact

private class TargetOperationProvenanceImpl(
    override val targetIdentity: TargetIdentity,
    override val operationIdentity: Long,
    override val portKind: TargetPortKind,
) : TargetOperationProvenance {
    init {
        require(operationIdentity > 0L)
        check(targetIdentity.matches(targetIdentity.target))
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
    return TargetOperationProvenanceImpl(targetIdentity, operationIdentity, portKind)
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
