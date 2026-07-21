package io.screenstream.engine.internal.target

import android.graphics.SurfaceTexture
import android.view.Surface
import io.screenstream.engine.internal.android.AndroidVirtualDisplayAttachEvidence
import io.screenstream.engine.internal.android.AndroidVirtualDisplayCreationEvidence
import io.screenstream.engine.internal.android.AndroidVirtualDisplayDetachEvidence
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class TargetPorts private constructor(
    private val targetOwner: TargetOwner,
    private val constructionProof: () -> Unit,
    private val target: CurrentTarget,
    private val targetGate: ReentrantLock,
    private val generation: Long,
    private val listenerInstallationOperationIdentity: Long,
    surfaceReleaseOperationIdentity: Long,
    targetDestructionOperationIdentity: Long,
    private val settlementSignal: SettlementSignal,
    private val resources: ResourceAccess,
) {
    internal interface ResourceAccess {
        val installedResources: Boolean
        val surface: Surface?
        val surfaceTexture: SurfaceTexture?
        val oesTextureName: Int
    }

    internal interface AndroidSurfacePort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val operationKind: TargetProducerOperationKind
        val provenance: TargetOperationProvenance

        fun withSurface(block: (Surface) -> Unit): TargetPortUseOutcome
    }

    internal interface AndroidDetachPort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val detachKind: TargetProducerDetachKind
        val provenance: TargetOperationProvenance
    }

    /** Detached, fully allocated producer-port candidate. It has no Target authority before commit. */
    internal interface StagedAndroidSurfacePort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val retargetOccurrenceIdentity: Long
        val port: AndroidSurfacePort
    }

    /** Detached, fully allocated detach-port candidate. It has no Target authority before commit. */
    internal interface StagedAndroidDetachPort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val retargetOccurrenceIdentity: Long
        val port: AndroidDetachPort
    }

    internal interface AndroidListenerInstallationPort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val provenance: TargetOperationProvenance

        fun withListener(block: (SurfaceTexture, SurfaceTexture.OnFrameAvailableListener) -> Unit): TargetPortUseOutcome
    }

    internal interface AndroidListenerRemovalPort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val provenance: TargetOperationProvenance

        fun withSurfaceTexture(block: (SurfaceTexture) -> Unit): TargetPortUseOutcome
    }

    internal interface GlFramePort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val provenance: TargetOperationProvenance
        val frameAdmissionEpoch: Long

        /** The GL Runnable's first domain transition. The returned fact is precreated. */
        fun enter(): TargetFrameEntryResult
        fun withHandles(block: (SurfaceTexture, Int) -> Unit): TargetPortUseOutcome
    }

    internal class TargetLease(
        private val owner: TargetPorts,
    ) {
        private enum class Disposition {
            Active,
            Released,
            FatalRetained,
        }

        private var disposition: Disposition = Disposition.Active

        fun release(): Boolean {
            val released = owner.targetGate.withLock {
                if (disposition != Disposition.Active) return@withLock false
                check(owner.leaseCount > 0)
                disposition = Disposition.Released
                owner.leaseCount -= 1
                true
            }
            if (released) owner.settlementSignal.signal()
            return released
        }

        fun retainAfterFatal(): Boolean = owner.targetGate.withLock {
            if (disposition != Disposition.Active) return@withLock false
            disposition = Disposition.FatalRetained
            true
        }
    }

    private class AndroidSurfacePortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val operationKind: TargetProducerOperationKind,
        override val provenance: TargetOperationProvenance,
        private val producerEvidence: TargetProducerEvidence,
        private val noProducerEvidence: Array<TargetNoProducerEvidence>,
    ) : AndroidSurfacePort {
        val lease: TargetLease = TargetLease(owner)
        private var rawHandleConsumed: Boolean = false
        private var outcomeClaimed: Boolean = false
        @Volatile
        private var applicationCandidates: ProducerApplicationCandidates? = null

        override fun withSurface(block: (Surface) -> Unit): TargetPortUseOutcome =
            owner.withSurfaceLease(this, block).toPortUseOutcome()

        fun claimRawHandleLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (rawHandleConsumed) return false
            rawHandleConsumed = true
            return true
        }

        fun producerEvidenceLocked(): TargetProducerEvidence? {
            check(owner.targetGate.isHeldByCurrentThread)
            return producerEvidence.takeUnless { outcomeClaimed }
        }

        fun noProducerEvidenceLocked(reason: TargetNoProducerReason): TargetNoProducerEvidence? {
            check(owner.targetGate.isHeldByCurrentThread)
            return noProducerEvidence[reason.ordinal].takeUnless { outcomeClaimed }
        }

        fun recordOutcomeClaimedLocked() {
            check(owner.targetGate.isHeldByCurrentThread)
            check(!outcomeClaimed)
            outcomeClaimed = true
        }

        fun bindApplicationCandidatesLocked(candidates: ProducerApplicationCandidates): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (applicationCandidates != null) return false
            applicationCandidates = candidates
            return true
        }

        fun applicationCandidate(
            operation: OperationOccurrence<*>,
            outcome: ProducerMechanicalOutcome,
        ): TargetProducerApplicationCandidate? {
            val candidates = applicationCandidates ?: return null
            if (candidates.operation !== operation) return null
            return candidates[outcome]
        }

        fun matchesProducerEvidence(evidence: TargetProducerEvidence): Boolean =
            evidence === producerEvidence

        fun matchesNoProducerEvidence(evidence: TargetNoProducerEvidence): Boolean =
            evidence === noProducerEvidence[evidence.reason.ordinal]
    }

    private class AndroidDetachPortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val detachKind: TargetProducerDetachKind,
        override val provenance: TargetOperationProvenance,
        private val receipt: TargetProducerDetachReceipt,
    ) : AndroidDetachPort {
        private var consumed: Boolean = false
        @Volatile
        private var applicationCandidate: ProducerDetachApplicationCandidate? = null

        fun detachReceiptLocked(): TargetProducerDetachReceipt? {
            check(owner.targetGate.isHeldByCurrentThread)
            return receipt.takeUnless { consumed }
        }

        fun recordConsumedLocked() {
            check(owner.targetGate.isHeldByCurrentThread)
            check(!consumed)
            consumed = true
        }

        fun bindApplicationCandidateLocked(candidate: ProducerDetachApplicationCandidate): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (applicationCandidate != null) return false
            applicationCandidate = candidate
            return true
        }

        fun applicationCandidate(operation: OperationOccurrence<*>): TargetProducerDetachApplicationCandidate? =
            applicationCandidate?.takeIf { it.operation === operation }
    }

    private class ProducerApplicationCandidate(
        val owner: TargetPorts,
        val port: AndroidSurfacePort,
        val operation: OperationOccurrence<*>,
        val outcome: ProducerMechanicalOutcome,
    ) : TargetProducerApplicationCandidate

    private class ProducerApplicationCandidates(
        owner: TargetPorts,
        port: AndroidSurfacePort,
        val operation: OperationOccurrence<*>,
    ) {
        private val producer = ProducerApplicationCandidate(owner, port, operation, ProducerMechanicalOutcome.Producer)
        private val returnedWithoutProducer = ProducerApplicationCandidate(owner, port, operation, ProducerMechanicalOutcome.ReturnedWithoutProducer)
        private val unentered = ProducerApplicationCandidate(owner, port, operation, ProducerMechanicalOutcome.Unentered)
        private val inapplicable = ProducerApplicationCandidate(owner, port, operation, ProducerMechanicalOutcome.Inapplicable)

        operator fun get(outcome: ProducerMechanicalOutcome): TargetProducerApplicationCandidate? = when (outcome) {
            ProducerMechanicalOutcome.None -> null
            ProducerMechanicalOutcome.Producer -> producer
            ProducerMechanicalOutcome.ReturnedWithoutProducer -> returnedWithoutProducer
            ProducerMechanicalOutcome.Unentered -> unentered
            ProducerMechanicalOutcome.Inapplicable -> inapplicable
        }
    }

    private class ProducerDetachApplicationCandidate(
        val owner: TargetPorts,
        val port: AndroidDetachPort,
        val operation: OperationOccurrence<*>,
    ) : TargetProducerDetachApplicationCandidate

    private enum class StagedPortDisposition {
        Detached,
        CommittedUnsubmitted,
        PostAcceptedOrAmbiguous,
        AppliedOrRetired,
        Unused,
    }

    private enum class StagedOccurrencePhase {
        PreSubmission,
        DefinitelyUnentered,
        PostAcceptedOrAmbiguous,
        NormalReturned,
        OtherSettled,
    }

    private abstract class StagedPortFactImpl(
        final override val targetIdentity: TargetIdentity,
        final override val targetGeneration: Long,
        final override val operationIdentity: Long,
        final override val provenance: TargetOperationProvenance,
        final override val retargetOccurrenceIdentity: Long,
    ) : TargetStagedPortFact {
        init {
            require(targetGeneration > 0L)
            require(operationIdentity > 0L)
            require(retargetOccurrenceIdentity > 0L)
            check(targetIdentity.generation == targetGeneration)
            check(provenance.targetIdentity === targetIdentity)
            check(provenance.operationIdentity == operationIdentity)
        }
    }

    private class StagedProducerCommittedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
    ), TargetStagedProducerPortCommittedFact

    private class StagedDetachCommittedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
    ), TargetStagedDetachPortCommittedFact

    private class StagedProducerUnusedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
    ), TargetStagedProducerPortUnusedFact

    private class StagedDetachUnusedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
    ), TargetStagedDetachPortUnusedFact

    private class StagedPostExposedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
    ), TargetStagedPortPostExposedFact

    private class StagedProducerRetiredFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
        override val noProducerEvidence: TargetNoProducerEvidence,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
    ), TargetStagedProducerPortRetiredFact {
        init {
            val retargetEvidence = noProducerEvidence as? TargetRetargetProducerApplicationFact
            check(retargetEvidence?.retargetOccurrenceIdentity == retargetOccurrenceIdentity)
            check(noProducerEvidence.operationIdentity == operationIdentity)
            check(noProducerEvidence.provenance === provenance)
        }
    }

    private class StagedDetachRetiredFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
    ), TargetStagedDetachPortRetiredFact

    private class StagedAndroidSurfacePortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val retargetOccurrenceIdentity: Long,
        override val port: AndroidSurfacePortImpl,
        provenance: TargetOperationProvenance,
        unenteredEvidence: TargetNoProducerEvidence,
        inapplicableEvidence: TargetNoProducerEvidence,
    ) : StagedAndroidSurfacePort {
        var disposition: StagedPortDisposition = StagedPortDisposition.Detached
        var boundOperation: OperationOccurrence<*>? = null
        val committedFact: TargetStagedProducerPortCommittedFact = StagedProducerCommittedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
        )
        val unusedFact: TargetStagedProducerPortUnusedFact = StagedProducerUnusedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
        )
        val postExposedFact: TargetStagedPortPostExposedFact = StagedPostExposedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
        )
        val unenteredRetiredFact: TargetStagedProducerPortRetiredFact = StagedProducerRetiredFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            unenteredEvidence,
        )
        val inapplicableRetiredFact: TargetStagedProducerPortRetiredFact = StagedProducerRetiredFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            inapplicableEvidence,
        )

        init {
            require(retargetOccurrenceIdentity > 0L)
            check(port.targetIdentity === targetIdentity)
            check(port.operationIdentity == operationIdentity)
            check(port.provenance === provenance)
        }

        fun belongsTo(candidate: TargetPorts): Boolean = owner === candidate
    }

    private class StagedAndroidDetachPortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val retargetOccurrenceIdentity: Long,
        override val port: AndroidDetachPortImpl,
        provenance: TargetOperationProvenance,
    ) : StagedAndroidDetachPort {
        var disposition: StagedPortDisposition = StagedPortDisposition.Detached
        var boundOperation: OperationOccurrence<*>? = null
        val committedFact: TargetStagedDetachPortCommittedFact = StagedDetachCommittedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
        )
        val unusedFact: TargetStagedDetachPortUnusedFact = StagedDetachUnusedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
        )
        val postExposedFact: TargetStagedPortPostExposedFact = StagedPostExposedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
        )
        val unenteredRetiredFact: TargetStagedDetachPortRetiredFact = StagedDetachRetiredFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
        )

        init {
            require(retargetOccurrenceIdentity > 0L)
            check(port.targetIdentity === targetIdentity)
            check(port.operationIdentity == operationIdentity)
            check(port.provenance === provenance)
        }

        fun belongsTo(candidate: TargetPorts): Boolean = owner === candidate
    }

    private class AndroidListenerInstallationPortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val provenance: TargetOperationProvenance,
    ) : AndroidListenerInstallationPort {
        val lease: TargetLease = TargetLease(owner)
        private var consumed: Boolean = false

        override fun withListener(
            block: (SurfaceTexture, SurfaceTexture.OnFrameAvailableListener) -> Unit,
        ): TargetPortUseOutcome = owner.withListenerInstallationLease(this, block).toPortUseOutcome()

        fun claimLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (consumed) return false
            consumed = true
            return true
        }
    }

    private class AndroidListenerRemovalPortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val provenance: TargetOperationProvenance,
    ) : AndroidListenerRemovalPort {
        val lease: TargetLease = TargetLease(owner)
        private var consumed: Boolean = false

        override fun withSurfaceTexture(block: (SurfaceTexture) -> Unit): TargetPortUseOutcome =
            owner.withListenerRemovalLease(this, block).toPortUseOutcome()

        fun claimLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (consumed) return false
            consumed = true
            return true
        }
    }

    private class GlFramePortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val provenance: TargetOperationProvenance,
        override val frameAdmissionEpoch: Long,
    ) : GlFramePort {
        private enum class Disposition {
            Detached,
            Reserved,
            Entered,
            Rejected,
            Retired,
        }

        val lease: TargetLease = TargetLease(owner)
        private var consumed: Boolean = false
        private var disposition: Disposition = Disposition.Detached
        val enteredFact: TargetEnteredFact = TargetEnteredFact.create(
            owner.targetOwner,
            owner.constructionProof,
            targetIdentity,
            frameAdmissionEpoch,
            operationIdentity,
            provenance,
        )
        val reservedFact: TargetFrameReservedFact = TargetFrameReservedFact.create(
            owner.targetOwner,
            owner.constructionProof,
            targetIdentity,
            frameAdmissionEpoch,
            operationIdentity,
            provenance,
        )
        val rejectedFact: TargetFrameRejectedBySealOrStaleEpochFact =
            TargetFrameRejectedBySealOrStaleEpochFact.create(
                owner.targetOwner,
                owner.constructionProof,
                targetIdentity,
                frameAdmissionEpoch,
                operationIdentity,
                provenance,
            )
        private val retiredAfterEntryFact: TargetFramePortRetiredFact = TargetFramePortRetiredFact.create(
            owner.targetOwner,
            owner.constructionProof,
            targetIdentity,
            frameAdmissionEpoch,
            operationIdentity,
            provenance,
            enteredFact,
        )
        private val retiredAfterRejectionFact: TargetFramePortRetiredFact = TargetFramePortRetiredFact.create(
            owner.targetOwner,
            owner.constructionProof,
            targetIdentity,
            frameAdmissionEpoch,
            operationIdentity,
            provenance,
            rejectedFact,
        )

        override fun enter(): TargetFrameEntryResult = owner.enterGlFramePort(this)

        override fun withHandles(block: (SurfaceTexture, Int) -> Unit): TargetPortUseOutcome =
            owner.withGlFrameLease(this, block).toPortUseOutcome()

        fun reserveLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (disposition != Disposition.Detached) return false
            disposition = Disposition.Reserved
            return true
        }

        fun enterLocked(): TargetFrameEntryResult {
            check(owner.targetGate.isHeldByCurrentThread)
            return when (disposition) {
                Disposition.Reserved -> {
                    disposition = Disposition.Entered
                    enteredFact
                }
                Disposition.Rejected -> rejectedFact
                Disposition.Entered -> enteredFact
                Disposition.Detached, Disposition.Retired -> rejectedFact
            }
        }

        fun rejectLocked(): TargetFrameRejectedBySealOrStaleEpochFact {
            check(owner.targetGate.isHeldByCurrentThread)
            if (disposition == Disposition.Detached || disposition == Disposition.Reserved) {
                disposition = Disposition.Rejected
            }
            return rejectedFact
        }

        fun isEnteredLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            return disposition == Disposition.Entered
        }

        fun isReservedLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            return disposition == Disposition.Reserved
        }

        fun decidedEntryResultLocked(): TargetFrameEntryResult? {
            check(owner.targetGate.isHeldByCurrentThread)
            return when (disposition) {
                Disposition.Entered -> enteredFact
                Disposition.Rejected, Disposition.Retired -> rejectedFact
                Disposition.Detached, Disposition.Reserved -> null
            }
        }

        fun retireLocked(): TargetFramePortRetiredFact? {
            check(owner.targetGate.isHeldByCurrentThread)
            val fact = when (disposition) {
                Disposition.Entered -> retiredAfterEntryFact
                Disposition.Reserved, Disposition.Rejected -> retiredAfterRejectionFact
                Disposition.Detached, Disposition.Retired -> return null
            }
            disposition = Disposition.Retired
            return fact
        }

        fun claimLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (consumed) return false
            consumed = true
            return true
        }

        fun belongsTo(expectedTarget: CurrentTarget): Boolean = owner.target === expectedTarget
    }

    private var producerPort: AndroidSurfacePortImpl? = null
    private var producerDetachPort: AndroidDetachPortImpl? = null
    private var committedStagedProducerPort: StagedAndroidSurfacePortImpl? = null
    private var committedStagedDetachPort: StagedAndroidDetachPortImpl? = null
    private var listenerInstallationPort: AndroidListenerInstallationPortImpl? = null
    private var listenerRemovalPort: AndroidListenerRemovalPortImpl? = null
    private var surfaceReleasePort: TargetRetirement.SurfaceReleasePort? = null
    private var glFramePort: GlFramePortImpl? = null
    private var leaseCount: Int = 0

    private val precreatedListenerInstallationPort: AndroidListenerInstallationPortImpl =
        AndroidListenerInstallationPortImpl(
            this,
            target.identity,
            listenerInstallationOperationIdentity,
            provenance(listenerInstallationOperationIdentity, TargetPortKind.ListenerInstallation),
        )

    internal val precreatedSurfaceReleasePort: TargetRetirement.SurfaceReleasePort =
        TargetRetirement.SurfaceReleasePort.create(
            this,
            surfaceReleaseOperationIdentity,
            provenance(surfaceReleaseOperationIdentity, TargetPortKind.SurfaceRelease),
        )

    internal val targetScopeDestructionCommand: TargetRetirement.TargetScopeDestructionCommand =
        TargetRetirement.TargetScopeDestructionCommand.create(
            this,
            generation,
            targetDestructionOperationIdentity,
            provenance(targetDestructionOperationIdentity, TargetPortKind.TargetScopeDestruction),
        )

    internal val activeLeaseCount: Int
        get() = targetGate.withLock { leaseCount }

    internal fun acceptsLockedClaim(requester: TargetPorts): Boolean =
        requester === this && targetGate.isHeldByCurrentThread

    internal val hasProducerPortLocked: Boolean
        get() {
            check(targetGate.isHeldByCurrentThread)
            return producerPort != null
        }

    internal val hasGlFramePortLocked: Boolean
        get() {
            check(targetGate.isHeldByCurrentThread)
            return glFramePort != null
        }

    internal val leaseCountLocked: Int
        get() {
            check(targetGate.isHeldByCurrentThread)
            return leaseCount
        }

    internal val hasNoLeasesOrInstalledWorkLocked: Boolean
        get() {
            check(targetGate.isHeldByCurrentThread)
            return leaseCount == 0 && !target.listenerInstalled && !target.sourceSignalsAccepted
        }

    internal fun matchesListenerInstallationOperationIdentity(operationIdentity: Long): Boolean =
        target.listenerInstallationOperationIdentity == operationIdentity

    internal fun installPrecreatedListenerInstallationPortLocked() {
        check(targetGate.isHeldByCurrentThread)
        check(listenerInstallationPort == null)
        check(target.canRegisterListenerInstallationLocked(
            listenerInstallationOperationIdentity,
            portAlreadyRegistered = false,
        ))
        listenerInstallationPort = precreatedListenerInstallationPort
    }

    internal fun registerListenerInstallationPort(operationIdentity: Long): AndroidListenerInstallationPort? {
        require(operationIdentity > 0L)
        return targetGate.withLock {
            val existing = listenerInstallationPort
            if (existing != null) {
                return@withLock existing.takeIf { it.operationIdentity == operationIdentity }
            }
            if (!target.canRegisterListenerInstallationLocked(operationIdentity, portAlreadyRegistered = false)) {
                return@withLock null
            }
            precreatedListenerInstallationPort.also { listenerInstallationPort = it }
        }
    }

    internal fun applyListenerInstallationReceipt(
        port: AndroidListenerInstallationPort,
        operation: OperationOccurrence<*>,
    ): TargetListenerInstalledFact? {
        val mechanicallyReturned = operation.settlementGate.withLock {
            operation.identity == port.operationIdentity && operation.returnCell.disposition == OperationReturnDisposition.Normal
        }
        if (!mechanicallyReturned) return null
        val applied = targetGate.withLock {
            target.applyListenerInstallationLocked(port === listenerInstallationPort, port.operationIdentity)
        }
        if (!applied) return null
        return TargetListenerInstalledFact.create(
            targetOwner,
            constructionProof,
            target.identity,
            port.operationIdentity,
            port.provenance,
        )
    }

    internal fun registerProducerPort(operationIdentity: Long, operationKind: TargetProducerOperationKind): AndroidSurfacePort? {
        require(operationIdentity > 0L)
        val portKind = when (operationKind) {
            TargetProducerOperationKind.VirtualDisplayCreation ->
                TargetPortKind.VirtualDisplayCreation

            TargetProducerOperationKind.VirtualDisplayAttachment ->
                TargetPortKind.VirtualDisplayAttachment
        }
        val provenance = provenance(operationIdentity, portKind)
        val producerEvidence = targetProducerEvidence(targetOwner, constructionProof, generation, operationIdentity, operationKind, provenance)
        val noProducerEvidence: Array<TargetNoProducerEvidence> =
            Array(TargetNoProducerReason.entries.size) { index ->
                targetNoProducerEvidence(
                    targetOwner,
                    constructionProof,
                    generation,
                    operationIdentity,
                    operationKind,
                    TargetNoProducerReason.entries[index],
                    provenance,
                )
            }
        val candidate = AndroidSurfacePortImpl(this, target.identity, operationIdentity, operationKind, provenance, producerEvidence, noProducerEvidence)
        return targetGate.withLock {
            if (!target.canRegisterProducerPortLocked(producerPort != null)) {
                return@withLock null
            }
            candidate.also { producerPort = it }
        }
    }

    internal fun prepareStagedProducerPort(
        operationIdentity: Long,
        retargetOccurrenceIdentity: Long,
    ): StagedAndroidSurfacePort {
        require(operationIdentity > 0L)
        require(retargetOccurrenceIdentity > 0L)
        val operationKind = TargetProducerOperationKind.VirtualDisplayAttachment
        val portKind = TargetPortKind.VirtualDisplayAttachment
        val provenance = provenance(operationIdentity, portKind)
        val producerEvidence = targetRetargetProducerEvidence(
            targetOwner,
            constructionProof,
            generation,
            operationIdentity,
            operationKind,
            provenance,
            retargetOccurrenceIdentity,
        )
        val noProducerEvidence: Array<TargetNoProducerEvidence> =
            Array(TargetNoProducerReason.entries.size) { index ->
                targetRetargetNoProducerEvidence(
                    targetOwner,
                    constructionProof,
                    generation,
                    operationIdentity,
                    operationKind,
                    TargetNoProducerReason.entries[index],
                    provenance,
                    retargetOccurrenceIdentity,
                )
            }
        val port = AndroidSurfacePortImpl(
            this,
            target.identity,
            operationIdentity,
            operationKind,
            provenance,
            producerEvidence,
            noProducerEvidence,
        )
        return StagedAndroidSurfacePortImpl(
            this,
            target.identity,
            operationIdentity,
            retargetOccurrenceIdentity,
            port,
            provenance,
            noProducerEvidence[TargetNoProducerReason.Unentered.ordinal],
            noProducerEvidence[TargetNoProducerReason.Inapplicable.ordinal],
        )
    }

    internal fun prepareStagedProducerApplicationCandidates(
        stagedPort: StagedAndroidSurfacePort,
        operation: OperationOccurrence<*>,
    ): Boolean {
        val staged = stagedPort as? StagedAndroidSurfacePortImpl ?: return false
        if (!staged.belongsTo(this) || operation.identity != staged.operationIdentity ||
            operation.returnCell.evidence !is AndroidVirtualDisplayAttachEvidence
        ) {
            return false
        }
        val candidates = ProducerApplicationCandidates(this, staged.port, operation)
        return targetGate.withLock {
            val bound = staged.disposition == StagedPortDisposition.Detached && staged.boundOperation == null &&
                    staged.port.bindApplicationCandidatesLocked(candidates)
            if (bound) staged.boundOperation = operation
            bound
        }
    }

    internal fun commitStagedProducerPort(
        stagedPort: StagedAndroidSurfacePort,
    ): TargetStagedProducerPortCommitResult? {
        val staged = stagedPort as? StagedAndroidSurfacePortImpl ?: return null
        if (!staged.belongsTo(this)) return null
        return targetGate.withLock {
            when (staged.disposition) {
                StagedPortDisposition.CommittedUnsubmitted,
                StagedPortDisposition.PostAcceptedOrAmbiguous,
                StagedPortDisposition.AppliedOrRetired ->
                    return@withLock staged.committedFact.takeIf {
                        committedStagedProducerPort === staged && producerPort === staged.port
                    }

                StagedPortDisposition.Unused -> return@withLock staged.unusedFact
                StagedPortDisposition.Detached -> Unit
            }
            val boundOperation = staged.boundOperation
            if (boundOperation == null ||
                boundOperation.returnCell.evidence !is AndroidVirtualDisplayAttachEvidence ||
                !target.canRegisterProducerPortLocked(producerPort != null) ||
                committedStagedProducerPort != null
            ) {
                staged.disposition = StagedPortDisposition.Unused
                return@withLock staged.unusedFact
            }
            producerPort = staged.port
            committedStagedProducerPort = staged
            staged.disposition = StagedPortDisposition.CommittedUnsubmitted
            staged.committedFact
        }
    }

    internal fun markStagedProducerPostAcceptedOrAmbiguous(
        stagedPort: StagedAndroidSurfacePort,
    ): TargetStagedPortPostExposedFact? {
        val staged = stagedPort as? StagedAndroidSurfacePortImpl ?: return null
        if (!staged.belongsTo(this)) return null
        val operation = staged.boundOperation ?: return null
        val phase = stagedOccurrencePhase(operation)
        if (phase != StagedOccurrencePhase.PostAcceptedOrAmbiguous &&
            phase != StagedOccurrencePhase.NormalReturned
        ) {
            return null
        }
        return targetGate.withLock {
            if (staged !== committedStagedProducerPort || staged.port !== producerPort ||
                staged.boundOperation !== operation ||
                staged.disposition != StagedPortDisposition.CommittedUnsubmitted
            ) {
                return@withLock null
            }
            staged.disposition = StagedPortDisposition.PostAcceptedOrAmbiguous
            staged.postExposedFact
        }
    }

    internal fun retireCommittedStagedProducerPortDefinitelyUnentered(
        stagedPort: StagedAndroidSurfacePort,
    ): TargetStagedProducerPortRetiredFact? {
        val staged = stagedPort as? StagedAndroidSurfacePortImpl ?: return null
        if (!staged.belongsTo(this)) return null
        val operation = staged.boundOperation ?: return null
        if (stagedOccurrencePhase(operation) != StagedOccurrencePhase.DefinitelyUnentered) return null
        return targetGate.withLock {
            if (staged !== committedStagedProducerPort || staged.port !== producerPort ||
                staged.boundOperation !== operation ||
                staged.disposition != StagedPortDisposition.CommittedUnsubmitted &&
                staged.disposition != StagedPortDisposition.PostAcceptedOrAmbiguous
            ) {
                return@withLock null
            }
            val evidence = staged.port.noProducerEvidenceLocked(TargetNoProducerReason.Unentered)
                ?: return@withLock null
            if (!target.applyNoProducerEvidenceLocked(
                    evidence,
                    staged.port.matchesNoProducerEvidence(evidence),
                    staged.port.operationIdentity,
                    staged.port.operationKind,
                    staged.port.provenance,
                )
            ) {
                return@withLock null
            }
            staged.port.recordOutcomeClaimedLocked()
            staged.disposition = StagedPortDisposition.AppliedOrRetired
            producerPort = null
            committedStagedProducerPort = null
            staged.unenteredRetiredFact
        }
    }

    internal fun retireUnusedStagedProducerPortTerminalInapplicable(
        stagedPort: StagedAndroidSurfacePort,
    ): TargetStagedProducerPortRetiredFact? {
        val staged = stagedPort as? StagedAndroidSurfacePortImpl ?: return null
        if (!staged.belongsTo(this)) return null
        val operation = staged.boundOperation ?: return null
        if (stagedOccurrencePhase(operation) != StagedOccurrencePhase.DefinitelyUnentered) return null
        return targetGate.withLock {
            if (staged.boundOperation !== operation || staged.disposition != StagedPortDisposition.Unused ||
                producerPort != null || committedStagedProducerPort != null
            ) {
                return@withLock null
            }
            val evidence = staged.port.noProducerEvidenceLocked(TargetNoProducerReason.Inapplicable)
                ?: return@withLock null
            if (!target.applyStagedProducerTerminalFactLocked(
                    evidence,
                    staged.port.matchesNoProducerEvidence(evidence),
                    staged.port.operationIdentity,
                    staged.port.provenance,
                    staged.retargetOccurrenceIdentity,
                )
            ) {
                return@withLock null
            }
            staged.port.recordOutcomeClaimedLocked()
            staged.disposition = StagedPortDisposition.AppliedOrRetired
            staged.inapplicableRetiredFact
        }
    }

    internal fun applyStagedProducerTerminalApplication(
        stagedPort: StagedAndroidSurfacePort,
    ): TargetProducerApplicationFact? {
        val staged = stagedPort as? StagedAndroidSurfacePortImpl ?: return null
        if (!staged.belongsTo(this)) return null
        val operation = staged.boundOperation ?: return null
        val outcome = operation.settlementGate.withLock {
            producerMechanicalOutcomeLocked(staged.port, operation)
        }
        if (outcome != ProducerMechanicalOutcome.Producer &&
            outcome != ProducerMechanicalOutcome.Unentered &&
            outcome != ProducerMechanicalOutcome.Inapplicable
        ) {
            return null
        }
        return targetGate.withLock {
            if (staged !== committedStagedProducerPort || staged.port !== producerPort ||
                staged.boundOperation !== operation ||
                staged.disposition != StagedPortDisposition.CommittedUnsubmitted &&
                staged.disposition != StagedPortDisposition.PostAcceptedOrAmbiguous
            ) {
                return@withLock null
            }
            val fact: TargetProducerApplicationFact = when (outcome) {
                ProducerMechanicalOutcome.Producer ->
                    staged.port.producerEvidenceLocked()

                ProducerMechanicalOutcome.Unentered ->
                    staged.port.noProducerEvidenceLocked(TargetNoProducerReason.Unentered)

                ProducerMechanicalOutcome.Inapplicable ->
                    staged.port.noProducerEvidenceLocked(TargetNoProducerReason.Inapplicable)

                ProducerMechanicalOutcome.None,
                ProducerMechanicalOutcome.ReturnedWithoutProducer -> null
            } ?: return@withLock null
            val exactEvidence = when (fact) {
                is TargetProducerEvidence -> staged.port.matchesProducerEvidence(fact)
                is TargetNoProducerEvidence -> staged.port.matchesNoProducerEvidence(fact)
                else -> false
            }
            if (!target.applyStagedProducerTerminalFactLocked(
                    fact,
                    exactEvidence,
                    staged.port.operationIdentity,
                    staged.port.provenance,
                    staged.retargetOccurrenceIdentity,
                )
            ) {
                return@withLock null
            }
            staged.port.recordOutcomeClaimedLocked()
            staged.disposition = StagedPortDisposition.AppliedOrRetired
            producerPort = null
            committedStagedProducerPort = null
            fact
        }
    }

    internal fun prepareProducerApplicationCandidates(port: AndroidSurfacePort, operation: OperationOccurrence<*>): Boolean {
        val exactPort = port as? AndroidSurfacePortImpl ?: return false
        val candidates = ProducerApplicationCandidates(this, port, operation)
        return targetGate.withLock {
            port === producerPort && operation.identity == port.operationIdentity && exactPort.bindApplicationCandidatesLocked(candidates)
        }
    }

    internal fun producerApplicationCandidateAfterSettlement(
        port: AndroidSurfacePort,
        operation: OperationOccurrence<*>,
    ): TargetProducerApplicationCandidate? = operation.settlementGate.withLock {
        val outcome = producerMechanicalOutcomeLocked(port, operation)
        (port as? AndroidSurfacePortImpl)?.applicationCandidate(operation, outcome)
    }

    internal fun applyProducerApplication(candidate: TargetProducerApplicationCandidate): TargetProducerApplicationFact? {
        val exactCandidate = candidate as? ProducerApplicationCandidate ?: return null
        if (exactCandidate.owner !== this) return null
        return targetGate.withLock {
            val port = exactCandidate.port
            val exactPort = producerPort ?: return@withLock null
            if (port !== exactPort ||
                port.provenance !== exactPort.provenance ||
                exactCandidate.operation.identity != exactPort.operationIdentity
            ) {
                return@withLock null
            }
            val staged = committedStagedProducerPort
            if (staged != null &&
                (staged.port !== exactPort || staged.disposition != StagedPortDisposition.PostAcceptedOrAmbiguous)
            ) {
                return@withLock null
            }
            val fact = when (exactCandidate.outcome) {
                ProducerMechanicalOutcome.Producer ->
                    exactPort.producerEvidenceLocked()

                ProducerMechanicalOutcome.ReturnedWithoutProducer ->
                    exactPort.noProducerEvidenceLocked(TargetNoProducerReason.ReturnedWithoutProducer)

                ProducerMechanicalOutcome.Unentered ->
                    exactPort.noProducerEvidenceLocked(TargetNoProducerReason.Unentered)

                ProducerMechanicalOutcome.Inapplicable -> {
                    if (!target.retirementAdmissionClosed ||
                        target.generationFenced ||
                        target.producerState != TargetProducerState.AwaitingEvidence
                    ) {
                        return@withLock null
                    }
                    exactPort.noProducerEvidenceLocked(TargetNoProducerReason.Inapplicable)
                }

                ProducerMechanicalOutcome.None -> null
            } ?: return@withLock null
            val applied = when (fact) {
                is TargetProducerEvidence -> target.applyProducerEvidenceLocked(
                    fact,
                    exactPort.matchesProducerEvidence(fact),
                    exactPort.operationIdentity,
                    exactPort.operationKind,
                    exactPort.provenance,
                )

                is TargetNoProducerEvidence -> target.applyNoProducerEvidenceLocked(
                    fact,
                    exactPort.matchesNoProducerEvidence(fact),
                    exactPort.operationIdentity,
                    exactPort.operationKind,
                    exactPort.provenance,
                )
            }
            if (!applied) return@withLock null
            exactPort.recordOutcomeClaimedLocked()
            if (staged != null) {
                staged.disposition = StagedPortDisposition.AppliedOrRetired
                producerPort = null
                committedStagedProducerPort = null
            }
            fact
        }
    }

    internal fun producerDetachApplicationCandidateAfterSettlement(
        port: AndroidDetachPort,
        operation: OperationOccurrence<*>,
    ): TargetProducerDetachApplicationCandidate? {
        val mechanicallyReturned = operation.settlementGate.withLock {
            operation.identity == port.operationIdentity && operation.returnCell.disposition == OperationReturnDisposition.Normal
        }
        if (!mechanicallyReturned) return null
        return (port as? AndroidDetachPortImpl)?.applicationCandidate(operation)
    }

    internal fun applyProducerDetachApplication(
        candidate: TargetProducerDetachApplicationCandidate,
    ): TargetProducerDetachReceipt? {
        val exactCandidate = candidate as? ProducerDetachApplicationCandidate ?: return null
        if (exactCandidate.owner !== this) return null
        return targetGate.withLock {
            val port = exactCandidate.port
            val exactPort = producerDetachPort ?: return@withLock null
            if (port !== exactPort || port.provenance !== exactPort.provenance || exactCandidate.operation.identity != exactPort.operationIdentity) {
                return@withLock null
            }
            val staged = committedStagedDetachPort
            if (staged != null &&
                (staged.port !== exactPort || staged.disposition != StagedPortDisposition.PostAcceptedOrAmbiguous)
            ) {
                return@withLock null
            }
            val receipt = exactPort.detachReceiptLocked() ?: return@withLock null
            if (!target.applyProducerDetachReceiptLocked(
                    receipt,
                    exactPort = true,
                    portOperationIdentity = exactPort.operationIdentity,
                    portDetachKind = exactPort.detachKind,
                    portProvenance = exactPort.provenance,
                )
            ) {
                return@withLock null
            }
            exactPort.recordConsumedLocked()
            if (staged != null) {
                staged.disposition = StagedPortDisposition.AppliedOrRetired
                producerDetachPort = null
                committedStagedDetachPort = null
            }
            receipt
        }
    }

    internal fun detachedGlFramePort(operationIdentity: Long): GlFramePort? {
        require(operationIdentity > 0L)
        var epoch = 0L
        val canPrecreate = targetGate.withLock {
            if (!resources.installedResources || !target.listenerInstalled || target.generationFenced || glFramePort != null ||
                target.producerState != TargetProducerState.ProducerAttached
            ) {
                return@withLock false
            }
            epoch = target.frameAdmissionEpochLocked
            true
        }
        if (!canPrecreate) return null
        val provenance = provenance(operationIdentity, TargetPortKind.GlFrame)
        val candidate = GlFramePortImpl(this, target.identity, operationIdentity, provenance, epoch)
        return targetGate.withLock {
            if (!resources.installedResources || !target.listenerInstalled || target.generationFenced || glFramePort != null ||
                target.producerState != TargetProducerState.ProducerAttached ||
                !target.isFrameAdmissionOpenLocked(epoch)
            ) {
                candidate.rejectLocked()
                return@withLock candidate
            }
            candidate
        }
    }

    internal fun commitGlFrameReservation(port: GlFramePort): TargetFrameReservationResult {
        val candidate = port as? GlFramePortImpl
        if (candidate == null) {
            return TargetFrameRejectedBySealOrStaleEpochFact.create(
                targetOwner,
                constructionProof,
                port.targetIdentity,
                port.frameAdmissionEpoch,
                port.operationIdentity,
                port.provenance,
            )
        }
        return targetGate.withLock {
            if (candidate === glFramePort) return@withLock candidate.rejectedFact
            if (!candidate.belongsTo(target) || !resources.installedResources ||
                !target.listenerInstalled || target.generationFenced || glFramePort != null ||
                target.producerState != TargetProducerState.ProducerAttached ||
                !target.isFrameAdmissionOpenLocked(candidate.frameAdmissionEpoch) || !candidate.reserveLocked()
            ) {
                candidate.rejectLocked()
                return@withLock candidate.rejectedFact
            }
            glFramePort = candidate
            candidate.reservedFact
        }
    }

    internal fun commitGlFramePort(port: GlFramePort): Boolean =
        commitGlFrameReservation(port) is TargetFrameReservedFact

    internal fun enterGlFramePort(port: GlFramePort): TargetFrameEntryResult {
        val candidate = port as? GlFramePortImpl
        if (candidate == null) {
            return TargetFrameRejectedBySealOrStaleEpochFact.create(
                targetOwner,
                constructionProof,
                port.targetIdentity,
                port.frameAdmissionEpoch,
                port.operationIdentity,
                port.provenance,
            )
        }
        return targetGate.withLock {
            if (candidate !== glFramePort || candidate.provenance !== glFramePort?.provenance) {
                return@withLock candidate.rejectLocked()
            }
            val decidedResult = candidate.decidedEntryResultLocked()
            if (decidedResult != null) return@withLock decidedResult
            if (!target.isFrameAdmissionOpenLocked(candidate.frameAdmissionEpoch)) {
                return@withLock candidate.rejectLocked()
            }
            check(candidate.isReservedLocked())
            val result = candidate.enterLocked()
            if (result is TargetEnteredFact) target.recordFrameEnteredLocked(result)
            result
        }
    }

    internal fun retireGlFramePortFactAfterSettlement(
        port: GlFramePort,
        operation: OperationOccurrence<*>,
    ): TargetFramePortRetiredFact? {
        val mechanicallySettled = operation.settlementGate.withLock {
            if (operation.identity != port.operationIdentity) return@withLock false
            if (operation.returnCell.disposition != OperationReturnDisposition.Empty) {
                return@withLock true
            }
            if (operation.submissionDisposition == OperationSubmissionDisposition.None &&
                operation.entryDisposition == OperationEntryDisposition.Unentered
            ) {
                return@withLock true
            }
            operation.entryDisposition != OperationEntryDisposition.Entered &&
                    (operation.disposition == OperationDisposition.Cancelled ||
                            operation.disposition == OperationDisposition.SchedulerRejected ||
                            operation.disposition == OperationDisposition.DeadlineGuardFailed)
        }
        if (!mechanicallySettled) return null
        return targetGate.withLock {
            if (port !== glFramePort || port.provenance !== glFramePort?.provenance) {
                return@withLock null
            }
            val candidate = port as? GlFramePortImpl ?: return@withLock null
            val retiredFact = candidate.retireLocked() ?: return@withLock null
            glFramePort = null
            target.recordFramePortRetiredLocked(candidate.enteredFact)
            retiredFact
        }
    }

    internal fun retireGlFramePortAfterSettlement(port: GlFramePort, operation: OperationOccurrence<*>): Boolean =
        retireGlFramePortFactAfterSettlement(port, operation) != null

    internal fun rejectReservedFrameForSealLocked(): TargetFrameRejectedBySealOrStaleEpochFact? {
        check(targetGate.isHeldByCurrentThread)
        val port = glFramePort ?: return null
        return port.rejectedFact.takeIf { port.isReservedLocked() }?.also { port.rejectLocked() }
    }

    internal fun registerListenerRemovalPort(operationIdentity: Long): AndroidListenerRemovalPort? {
        require(operationIdentity > 0L)
        val provenance = provenance(operationIdentity, TargetPortKind.ListenerRemoval)
        val candidate = AndroidListenerRemovalPortImpl(this, target.identity, operationIdentity, provenance)
        return targetGate.withLock {
            when (target.claimListenerRemovalIdentityLocked(operationIdentity)) {
                TargetRegistrationClaim.New ->
                    candidate.also { listenerRemovalPort = it }

                TargetRegistrationClaim.Existing ->
                    listenerRemovalPort?.takeIf { it.operationIdentity == operationIdentity }

                TargetRegistrationClaim.Denied -> null
            }
        }
    }

    internal fun registerSetSurfaceDetachPort(operationIdentity: Long): AndroidDetachPort? =
        registerDetachPort(operationIdentity, TargetPortKind.VirtualDisplayDetach, TargetProducerDetachKind.VirtualDisplayDetach)

    internal fun registerVirtualDisplayReleasePort(operationIdentity: Long): AndroidDetachPort? =
        registerDetachPort(operationIdentity, TargetPortKind.VirtualDisplayRelease, TargetProducerDetachKind.VirtualDisplayRelease)

    internal fun prepareStagedDetachPort(
        operationIdentity: Long,
        retargetOccurrenceIdentity: Long,
    ): StagedAndroidDetachPort {
        require(operationIdentity > 0L)
        require(retargetOccurrenceIdentity > 0L)
        val detachKind = TargetProducerDetachKind.VirtualDisplayDetach
        val portKind = TargetPortKind.VirtualDisplayDetach
        val provenance = provenance(operationIdentity, portKind)
        val receipt = targetRetargetProducerDetachReceipt(
            targetOwner,
            constructionProof,
            generation,
            operationIdentity,
            detachKind,
            provenance,
            retargetOccurrenceIdentity,
        )
        val port = AndroidDetachPortImpl(
            this,
            target.identity,
            operationIdentity,
            detachKind,
            provenance,
            receipt,
        )
        return StagedAndroidDetachPortImpl(
            this,
            target.identity,
            operationIdentity,
            retargetOccurrenceIdentity,
            port,
            provenance,
        )
    }

    internal fun prepareStagedDetachApplicationCandidate(
        stagedPort: StagedAndroidDetachPort,
        operation: OperationOccurrence<*>,
    ): Boolean {
        val staged = stagedPort as? StagedAndroidDetachPortImpl ?: return false
        if (!staged.belongsTo(this) || operation.identity != staged.operationIdentity ||
            operation.returnCell.evidence !is AndroidVirtualDisplayDetachEvidence
        ) {
            return false
        }
        val candidate = ProducerDetachApplicationCandidate(this, staged.port, operation)
        return targetGate.withLock {
            val bound = staged.disposition == StagedPortDisposition.Detached && staged.boundOperation == null &&
                    staged.port.bindApplicationCandidateLocked(candidate)
            if (bound) staged.boundOperation = operation
            bound
        }
    }

    internal fun commitStagedDetachPort(
        stagedPort: StagedAndroidDetachPort,
    ): TargetStagedDetachPortCommitResult? {
        val staged = stagedPort as? StagedAndroidDetachPortImpl ?: return null
        if (!staged.belongsTo(this)) return null
        return targetGate.withLock {
            when (staged.disposition) {
                StagedPortDisposition.CommittedUnsubmitted,
                StagedPortDisposition.PostAcceptedOrAmbiguous,
                StagedPortDisposition.AppliedOrRetired ->
                    return@withLock staged.committedFact.takeIf {
                        committedStagedDetachPort === staged && producerDetachPort === staged.port
                    }

                StagedPortDisposition.Unused -> return@withLock staged.unusedFact
                StagedPortDisposition.Detached -> Unit
            }
            val boundOperation = staged.boundOperation
            if (boundOperation == null ||
                boundOperation.returnCell.evidence !is AndroidVirtualDisplayDetachEvidence ||
                producerDetachPort != null || committedStagedDetachPort != null ||
                target.claimDetachIdentityLocked(staged.operationIdentity, staged.port.detachKind) != TargetRegistrationClaim.New
            ) {
                staged.disposition = StagedPortDisposition.Unused
                return@withLock staged.unusedFact
            }
            producerDetachPort = staged.port
            committedStagedDetachPort = staged
            staged.disposition = StagedPortDisposition.CommittedUnsubmitted
            staged.committedFact
        }
    }

    internal fun markStagedDetachPostAcceptedOrAmbiguous(
        stagedPort: StagedAndroidDetachPort,
    ): TargetStagedPortPostExposedFact? {
        val staged = stagedPort as? StagedAndroidDetachPortImpl ?: return null
        if (!staged.belongsTo(this)) return null
        val operation = staged.boundOperation ?: return null
        val phase = stagedOccurrencePhase(operation)
        if (phase != StagedOccurrencePhase.PostAcceptedOrAmbiguous &&
            phase != StagedOccurrencePhase.NormalReturned
        ) {
            return null
        }
        return targetGate.withLock {
            if (staged !== committedStagedDetachPort || staged.port !== producerDetachPort ||
                staged.boundOperation !== operation ||
                staged.disposition != StagedPortDisposition.CommittedUnsubmitted
            ) {
                return@withLock null
            }
            staged.disposition = StagedPortDisposition.PostAcceptedOrAmbiguous
            staged.postExposedFact
        }
    }

    internal fun retireCommittedStagedDetachPortDefinitelyUnentered(
        stagedPort: StagedAndroidDetachPort,
    ): TargetStagedDetachPortRetiredFact? {
        val staged = stagedPort as? StagedAndroidDetachPortImpl ?: return null
        if (!staged.belongsTo(this)) return null
        val operation = staged.boundOperation ?: return null
        if (stagedOccurrencePhase(operation) != StagedOccurrencePhase.DefinitelyUnentered) return null
        return targetGate.withLock {
            if (staged !== committedStagedDetachPort || staged.port !== producerDetachPort ||
                staged.boundOperation !== operation ||
                staged.disposition != StagedPortDisposition.CommittedUnsubmitted &&
                staged.disposition != StagedPortDisposition.PostAcceptedOrAmbiguous ||
                !target.retireDefinitelyUnenteredDetachPortLocked(
                    staged.operationIdentity,
                    staged.port.detachKind,
                )
            ) {
                return@withLock null
            }
            producerDetachPort = null
            committedStagedDetachPort = null
            staged.disposition = StagedPortDisposition.AppliedOrRetired
            staged.unenteredRetiredFact
        }
    }

    internal fun applyStagedDetachTerminalApplication(
        stagedPort: StagedAndroidDetachPort,
    ): TargetProducerDetachReceipt? {
        val staged = stagedPort as? StagedAndroidDetachPortImpl ?: return null
        if (!staged.belongsTo(this)) return null
        val operation = staged.boundOperation ?: return null
        if (stagedOccurrencePhase(operation) != StagedOccurrencePhase.NormalReturned) return null
        return targetGate.withLock {
            if (staged !== committedStagedDetachPort || staged.port !== producerDetachPort ||
                staged.boundOperation !== operation ||
                staged.disposition != StagedPortDisposition.CommittedUnsubmitted &&
                staged.disposition != StagedPortDisposition.PostAcceptedOrAmbiguous
            ) {
                return@withLock null
            }
            val receipt = staged.port.detachReceiptLocked() ?: return@withLock null
            if (!target.applyProducerDetachReceiptLocked(
                    receipt,
                    exactPort = true,
                    portOperationIdentity = staged.port.operationIdentity,
                    portDetachKind = staged.port.detachKind,
                    portProvenance = staged.port.provenance,
                )
            ) {
                return@withLock null
            }
            staged.port.recordConsumedLocked()
            staged.disposition = StagedPortDisposition.AppliedOrRetired
            producerDetachPort = null
            committedStagedDetachPort = null
            receipt
        }
    }

    private fun registerDetachPort(operationIdentity: Long, portKind: TargetPortKind, detachKind: TargetProducerDetachKind): AndroidDetachPort? {
        require(operationIdentity > 0L)
        val provenance = provenance(operationIdentity, portKind)
        val receipt = targetProducerDetachReceipt(targetOwner, constructionProof, generation, operationIdentity, detachKind, provenance)
        val candidate = AndroidDetachPortImpl(this, target.identity, operationIdentity, detachKind, provenance, receipt)
        return targetGate.withLock {
            when (target.claimDetachIdentityLocked(operationIdentity, detachKind)) {
                TargetRegistrationClaim.New ->
                    candidate.also { producerDetachPort = it }

                TargetRegistrationClaim.Existing ->
                    producerDetachPort?.takeIf { it.operationIdentity == operationIdentity }

                TargetRegistrationClaim.Denied -> null
            }
        }
    }

    internal fun prepareProducerDetachApplicationCandidate(port: AndroidDetachPort, operation: OperationOccurrence<*>): Boolean {
        val exactPort = port as? AndroidDetachPortImpl ?: return false
        val candidate = ProducerDetachApplicationCandidate(this, port, operation)
        return targetGate.withLock {
            port === producerDetachPort && operation.identity == port.operationIdentity && exactPort.bindApplicationCandidateLocked(candidate)
        }
    }

    internal fun armListenerSentinelAfterRemovalReturn(operationIdentity: Long): Runnable? = targetGate.withLock {
        target.armListenerSentinelLocked(operationIdentity)
    }

    internal fun recordListenerRemovalReturn(port: AndroidListenerRemovalPort): Boolean = targetGate.withLock {
        target.recordListenerRemovalReturnLocked(port === listenerRemovalPort, port.operationIdentity)
    }

    internal fun applyListenerRemovalSettlement(
        port: AndroidListenerRemovalPort,
        operation: OperationOccurrence<*>,
    ): TargetListenerRemovalSettledFact? {
        val mechanicallyReturned = operation.settlementGate.withLock {
            operation.identity == port.operationIdentity && operation.returnCell.disposition == OperationReturnDisposition.Normal
        }
        if (!mechanicallyReturned) return null
        val applied = targetGate.withLock {
            target.applyListenerRemovalSettlementLocked(port === listenerRemovalPort)
        }
        if (!applied) return null
        return TargetListenerRemovalSettledFact.create(
            targetOwner,
            constructionProof,
            target.identity,
            port.operationIdentity,
            port.provenance,
        )
    }

    internal fun detachedSurfaceReleasePort(surfaceRequired: Boolean): TargetRetirement.SurfaceReleasePort? = targetGate.withLock {
        if (!surfaceRequired || surfaceReleasePort != null) return@withLock surfaceReleasePort
        precreatedSurfaceReleasePort
    }

    internal fun commitSurfaceReleasePort(port: TargetRetirement.SurfaceReleasePort, surfaceRequired: Boolean): Boolean = targetGate.withLock {
        if (port !== precreatedSurfaceReleasePort || !surfaceRequired || surfaceReleasePort != null) {
            return@withLock false
        }
        surfaceReleasePort = port
        true
    }

    internal fun isExactSurfaceReleasePort(port: TargetRetirement.SurfaceReleasePort): Boolean =
        targetGate.withLock { port === surfaceReleasePort }

    internal fun releaseEnteredSurface(port: TargetRetirement.SurfaceReleasePort): Boolean =
        port.releaseSurface()

    private fun withSurfaceLease(port: AndroidSurfacePortImpl, block: (Surface) -> Unit): Boolean = withLease(
        port.lease,
        claim = { port.claimRawHandleLocked() },
        admission = {
            port === producerPort && resources.installedResources && !target.generationFenced && target.producerState == TargetProducerState.AwaitingEvidence
        },
        value = { resources.surface },
        block = block,
    )

    private fun withListenerInstallationLease(
        port: AndroidListenerInstallationPortImpl,
        block: (SurfaceTexture, SurfaceTexture.OnFrameAvailableListener) -> Unit,
    ): Boolean {
        var surfaceTexture: SurfaceTexture? = null
        var listener: SurfaceTexture.OnFrameAvailableListener? = null
        val admitted = targetGate.withLock {
            if (port !== listenerInstallationPort || !resources.installedResources ||
                target.generationFenced || target.listenerInstalled || leaseCount == Int.MAX_VALUE
            ) {
                return@withLock false
            }
            surfaceTexture = resources.surfaceTexture ?: return@withLock false
            listener = target.listenerForInstallationPortLocked()
            if (!port.claimLocked()) return@withLock false
            leaseCount += 1
            true
        }
        if (!admitted) return false
        try {
            block(checkNotNull(surfaceTexture), checkNotNull(listener))
        } catch (exception: Exception) {
            check(port.lease.release())
            throw exception
        } catch (fatal: Throwable) {
            check(port.lease.retainAfterFatal())
            throw fatal
        }
        check(port.lease.release())
        return true
    }

    private fun withListenerRemovalLease(port: AndroidListenerRemovalPortImpl, block: (SurfaceTexture) -> Unit): Boolean = withLease(
        port.lease,
        claim = { port.claimLocked() },
        admission = { port === listenerRemovalPort && resources.installedResources && target.generationFenced && !target.listenerRemoved },
        value = { resources.surfaceTexture },
        block = block,
    )

    internal fun releaseSurface(port: TargetRetirement.SurfaceReleasePort): Boolean {
        var surface: Surface? = null
        val admitted = targetGate.withLock {
            if (port !== surfaceReleasePort || leaseCount == Int.MAX_VALUE) {
                return@withLock false
            }
            surface = resources.surface ?: return@withLock false
            if (!port.claimConsumedLocked(this)) return@withLock false
            leaseCount += 1
            true
        }
        if (!admitted) return false
        try {
            checkNotNull(surface).release()
        } catch (exception: Exception) {
            check(port.releaseLease())
            throw exception
        } catch (fatal: Throwable) {
            check(port.retainLeaseAfterFatal())
            throw fatal
        }
        check(port.releaseLease())
        return true
    }

    internal fun withTargetScopeDestructionLease(command: TargetRetirement.TargetScopeDestructionCommand, block: (SurfaceTexture?, Int) -> Unit): Boolean {
        var surfaceTexture: SurfaceTexture? = null
        var oesTextureName = 0
        val admitted = targetGate.withLock {
            if (command !== targetScopeDestructionCommand || leaseCount == Int.MAX_VALUE) {
                return@withLock false
            }
            surfaceTexture = resources.surfaceTexture
            oesTextureName = resources.oesTextureName
            if (surfaceTexture == null && oesTextureName == 0) return@withLock false
            if (!command.claimConsumedLocked(this)) return@withLock false
            leaseCount += 1
            true
        }
        if (!admitted) return false
        try {
            block(surfaceTexture, oesTextureName)
        } catch (exception: Exception) {
            check(command.releaseLease())
            throw exception
        } catch (fatal: Throwable) {
            check(command.retainLeaseAfterFatal())
            throw fatal
        }
        check(command.releaseLease())
        return true
    }

    private fun withGlFrameLease(port: GlFramePortImpl, block: (SurfaceTexture, Int) -> Unit): Boolean {
        var surfaceTexture: SurfaceTexture? = null
        var oesTextureName = 0
        val admitted = targetGate.withLock {
            if (port !== glFramePort || !resources.installedResources || target.generationFenced ||
                !port.isEnteredLocked() || !target.acceptsEnteredFrameLocked(port.enteredFact) ||
                leaseCount == Int.MAX_VALUE
            ) {
                return@withLock false
            }
            surfaceTexture = resources.surfaceTexture ?: return@withLock false
            oesTextureName = resources.oesTextureName
            if (oesTextureName == 0) return@withLock false
            if (!port.claimLocked()) return@withLock false
            leaseCount += 1
            true
        }
        if (!admitted) return false
        try {
            block(checkNotNull(surfaceTexture), oesTextureName)
        } catch (exception: Exception) {
            check(port.lease.release())
            throw exception
        } catch (fatal: Throwable) {
            check(port.lease.retainAfterFatal())
            throw fatal
        }
        check(port.lease.release())
        return true
    }

    private inline fun <T> withLease(
        lease: TargetLease,
        claim: () -> Boolean,
        admission: () -> Boolean,
        value: () -> T?,
        block: (T) -> Unit,
    ): Boolean {
        var ownedValue: T? = null
        val admitted = targetGate.withLock {
            if (!admission() || leaseCount == Int.MAX_VALUE) return@withLock false
            ownedValue = value() ?: return@withLock false
            if (!claim()) return@withLock false
            leaseCount += 1
            true
        }
        if (!admitted) return false
        try {
            block(checkNotNull(ownedValue))
        } catch (exception: Exception) {
            check(lease.release())
            throw exception
        } catch (fatal: Throwable) {
            check(lease.retainAfterFatal())
            throw fatal
        }
        check(lease.release())
        return true
    }

    private fun provenance(operationIdentity: Long, portKind: TargetPortKind): TargetOperationProvenance =
        targetOperationProvenance(targetOwner, constructionProof, target.identity, operationIdentity, portKind)

    private fun stagedOccurrencePhase(operation: OperationOccurrence<*>): StagedOccurrencePhase =
        operation.settlementGate.withLock {
            if (operation.returnCell.disposition == OperationReturnDisposition.Normal) {
                return@withLock StagedOccurrencePhase.NormalReturned
            }
            val definitelyUnentered = operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                    operation.entryDisposition != OperationEntryDisposition.Entered &&
                    (operation.disposition == OperationDisposition.DeadlineGuardFailed ||
                            operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                            operation.disposition == OperationDisposition.Cancelled ||
                            operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                            operation.submissionFailure != null ||
                            operation.disposition == OperationDisposition.SchedulerRejected &&
                            operation.submissionFailure != null)
            if (definitelyUnentered) return@withLock StagedOccurrencePhase.DefinitelyUnentered
            if (operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                operation.entryDisposition == OperationEntryDisposition.Unentered &&
                operation.submissionDisposition == OperationSubmissionDisposition.None &&
                operation.submissionFailure == null &&
                operation.submissionAmbiguousFatal == null
            ) {
                return@withLock StagedOccurrencePhase.PreSubmission
            }
            if (operation.entryDisposition == OperationEntryDisposition.Entered ||
                operation.returnCell.disposition != OperationReturnDisposition.Empty ||
                operation.submissionDisposition == OperationSubmissionDisposition.Submitting ||
                operation.submissionDisposition == OperationSubmissionDisposition.Accepted ||
                operation.submissionAmbiguousFatal != null
            ) {
                return@withLock StagedOccurrencePhase.PostAcceptedOrAmbiguous
            }
            StagedOccurrencePhase.OtherSettled
        }

    private fun producerMechanicalOutcomeLocked(port: AndroidSurfacePort, operation: OperationOccurrence<*>): ProducerMechanicalOutcome {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (operation.identity != port.operationIdentity) return ProducerMechanicalOutcome.None
        if (operation.returnCell.disposition == OperationReturnDisposition.Normal) {
            return when (port.operationKind) {
                TargetProducerOperationKind.VirtualDisplayCreation -> {
                    val evidence = operation.returnCell.evidence as? AndroidVirtualDisplayCreationEvidence
                        ?: return ProducerMechanicalOutcome.None
                    if (evidence.virtualDisplay == null) {
                        ProducerMechanicalOutcome.ReturnedWithoutProducer
                    } else {
                        ProducerMechanicalOutcome.Producer
                    }
                }

                TargetProducerOperationKind.VirtualDisplayAttachment ->
                    if (operation.returnCell.evidence is AndroidVirtualDisplayAttachEvidence) {
                        ProducerMechanicalOutcome.Producer
                    } else {
                        ProducerMechanicalOutcome.None
                    }
            }
        }
        if (operation.returnCell.disposition != OperationReturnDisposition.Empty ||
            operation.entryDisposition == OperationEntryDisposition.Entered
        ) {
            return ProducerMechanicalOutcome.None
        }
        if (operation.submissionFailure != null ||
            operation.disposition == OperationDisposition.SchedulerRejected ||
            operation.disposition == OperationDisposition.DeadlineGuardFailed ||
            operation.submissionAmbiguousFatal != null &&
            operation.submissionDisposition == OperationSubmissionDisposition.Cancelled &&
            operation.entryDisposition == OperationEntryDisposition.Cancelled &&
            operation.disposition == OperationDisposition.Cancelled
        ) {
            return ProducerMechanicalOutcome.Unentered
        }
        return if (operation.domain == OperationDomain.Cleanup &&
            operation.entryDisposition == OperationEntryDisposition.Cancelled &&
            operation.disposition == OperationDisposition.Cancelled &&
            operation.submissionFailure == null &&
            operation.submissionAmbiguousFatal == null
        ) {
            ProducerMechanicalOutcome.Inapplicable
        } else {
            ProducerMechanicalOutcome.None
        }
    }

    private enum class ProducerMechanicalOutcome {
        None,
        Producer,
        ReturnedWithoutProducer,
        Unentered,
        Inapplicable,
    }

    internal companion object {
        internal fun create(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            target: CurrentTarget,
            targetGate: ReentrantLock,
            generation: Long,
            listenerInstallationOperationIdentity: Long,
            surfaceReleaseOperationIdentity: Long,
            targetDestructionOperationIdentity: Long,
            settlementSignal: SettlementSignal,
            resources: ResourceAccess,
        ): TargetPorts {
            check(targetOwner.acceptsConstructionProof(constructionProof))
            return TargetPorts(
                targetOwner,
                constructionProof,
                target,
                targetGate,
                generation,
                listenerInstallationOperationIdentity,
                surfaceReleaseOperationIdentity,
                targetDestructionOperationIdentity,
                settlementSignal,
                resources,
            )
        }
    }
}

private fun Boolean.toPortUseOutcome(): TargetPortUseOutcome =
    if (this) TargetPortUseOutcome.BodyReturned else TargetPortUseOutcome.Rejected
