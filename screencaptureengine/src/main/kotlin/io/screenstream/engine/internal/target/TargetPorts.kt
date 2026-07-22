package io.screenstream.engine.internal.target

import android.graphics.SurfaceTexture
import android.view.Surface
import io.screenstream.engine.internal.android.AndroidTargetPostOutcome
import io.screenstream.engine.internal.android.AndroidTargetOperationBinding
import io.screenstream.engine.internal.android.AndroidTargetPlatformResult
import io.screenstream.engine.internal.android.AndroidFinalLaneNoEntryProof
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationEvidence
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationNoPlatformEntryOutcome
import io.screenstream.engine.internal.settlement.OperationDisposition
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

    internal interface AndroidSurfacePort : TargetProducerPortPreparationResult {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val operationKind: TargetProducerOperationKind
        val provenance: TargetOperationProvenance
        val bindingFact: TargetAndroidProducerBindingFact

        fun withSurface(block: (Surface) -> Unit): TargetPortUseOutcome
    }

    internal interface AndroidDetachPort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val detachKind: TargetProducerDetachKind
        val provenance: TargetOperationProvenance
        val bindingFact: TargetAndroidDetachBindingFact
    }

    /** Detached initial VirtualDisplayRelease candidate; it grants no Target authority before commit. */
    internal interface DetachedInitialReleasePort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val provenance: TargetOperationProvenance
        val port: AndroidDetachPort
        val committedFact: TargetInitialReleasePortCommittedFact
        val retiredUnusedFact: TargetInitialReleasePortRetiredUnusedFact
    }

    /** Detached, fully allocated producer-port candidate. It has no Target authority before commit. */
    internal interface StagedAndroidSurfacePort :
        TargetStagedAndroidOperationCandidate,
        TargetProducerPortPreparationResult {
        val retargetOccurrenceIdentity: Long
        val port: AndroidSurfacePort
        val commitCorrelation: TargetStagedProducerPortCommittedFact
        val unusedCorrelation: TargetStagedProducerPortUnusedFact
    }

    /** Detached, fully allocated detach-port candidate. It has no Target authority before commit. */
    internal interface StagedAndroidDetachPort : TargetStagedAndroidOperationCandidate {
        val retargetOccurrenceIdentity: Long
        val port: AndroidDetachPort
        val commitCorrelation: TargetStagedDetachPortCommittedFact
        val unusedCorrelation: TargetStagedDetachPortUnusedFact
    }

    internal interface AndroidListenerInstallationPort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val provenance: TargetOperationProvenance
        val bindingFact: TargetAndroidListenerInstallationBindingFact

        fun withListener(block: (SurfaceTexture, SurfaceTexture.OnFrameAvailableListener) -> Unit): TargetPortUseOutcome
    }

    internal interface AndroidListenerRemovalPort {
        val targetIdentity: TargetIdentity
        val operationIdentity: Long
        val provenance: TargetOperationProvenance
        val bindingFact: TargetAndroidListenerRemovalBindingFact

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

    private abstract class AndroidPortBindingFact(
        final override val targetIdentity: TargetIdentity,
        final override val targetGeneration: Long,
        final override val operationIdentity: Long,
        final override val provenance: TargetOperationProvenance,
    ) : TargetAndroidPortBindingFact {
        init {
            require(targetGeneration > 0L)
            require(operationIdentity > 0L)
            check(targetIdentity.generation == targetGeneration)
            check(provenance.targetIdentity === targetIdentity)
            check(provenance.operationIdentity == operationIdentity)
        }
    }

    private class ListenerInstallationBindingFact(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
    ) : AndroidPortBindingFact(targetIdentity, targetGeneration, operationIdentity, provenance),
        TargetAndroidListenerInstallationBindingFact

    private class ListenerRemovalBindingFact(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
    ) : AndroidPortBindingFact(targetIdentity, targetGeneration, operationIdentity, provenance),
        TargetAndroidListenerRemovalBindingFact

    private class ProducerBindingFact(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
    ) : AndroidPortBindingFact(targetIdentity, targetGeneration, operationIdentity, provenance),
        TargetProducerPortCommittedFact

    private class ProducerRetiredUnusedFactImpl(
        override val bindingFact: TargetAndroidProducerBindingFact,
    ) : TargetProducerPortRetiredUnusedFact

    private class ProducerPreparationRetiredUnusedFactImpl(
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val provenance: TargetOperationProvenance,
    ) : TargetProducerPreparationRetiredUnusedFact {
        @Volatile
        private var recordedFailure: Throwable? = null

        override val failure: Throwable
            get() = checkNotNull(recordedFailure)

        fun recordFailure(failure: Throwable) {
            check(recordedFailure == null)
            recordedFailure = failure
        }
    }

    private enum class ProducerCandidateDisposition {
        Detached,
        Committed,
        RetiredUnused,
    }

    private class DetachBindingFact(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
    ) : AndroidPortBindingFact(targetIdentity, targetGeneration, operationIdentity, provenance),
        TargetAndroidDetachBindingFact

    private class InitialReleaseCommittedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
    ) : AndroidPortBindingFact(targetIdentity, targetGeneration, operationIdentity, provenance),
        TargetInitialReleasePortCommittedFact

    private class InitialReleaseRetiredUnusedFactImpl(
        override val bindingFact: TargetAndroidDetachBindingFact,
    ) : TargetInitialReleasePortRetiredUnusedFact

    private class AndroidSurfacePortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val operationKind: TargetProducerOperationKind,
        override val provenance: TargetOperationProvenance,
        private val producerEvidence: TargetProducerEvidence,
        private val noProducerEvidence: Array<TargetNoProducerEvidence>,
    ) : AndroidSurfacePort {
        override val bindingFact: TargetProducerPortCommittedFact = ProducerBindingFact(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
        )
        val retiredUnusedFact: TargetProducerPortRetiredUnusedFact =
            ProducerRetiredUnusedFactImpl(bindingFact)
        val lease: TargetLease = TargetLease(owner)
        private var rawHandleConsumed: Boolean = false
        private var outcomeClaimed: Boolean = false
        private var platformSettlementObserved: Boolean = false
        var candidateDisposition: ProducerCandidateDisposition = ProducerCandidateDisposition.Detached
        var androidBinding: AndroidTargetOperationBinding? = null
        val producerApplicationResult = TargetAndroidPlatformApplicationResult.Producer(producerEvidence)
        val noProducerApplicationResults: Array<TargetAndroidPlatformApplicationResult.Producer> =
            Array(TargetNoProducerReason.entries.size) { index ->
                TargetAndroidPlatformApplicationResult.Producer(noProducerEvidence[index])
            }
        val initialSettledResult =
            TargetAndroidPlatformApplicationResult.InitialProducerPortSettledOrAmbiguous(bindingFact)

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
            return producerEvidence.takeUnless { outcomeClaimed || platformSettlementObserved }
        }

        fun noProducerEvidenceLocked(reason: TargetNoProducerReason): TargetNoProducerEvidence? {
            check(owner.targetGate.isHeldByCurrentThread)
            return noProducerEvidence[reason.ordinal].takeUnless { outcomeClaimed || platformSettlementObserved }
        }

        fun recordOutcomeClaimedLocked() {
            check(owner.targetGate.isHeldByCurrentThread)
            check(!outcomeClaimed)
            outcomeClaimed = true
        }

        fun recordPlatformSettlementObservedLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (outcomeClaimed || platformSettlementObserved) return false
            platformSettlementObserved = true
            return true
        }

        fun matchesProducerEvidence(evidence: TargetProducerEvidence): Boolean =
            evidence === producerEvidence

        fun matchesNoProducerEvidence(evidence: TargetNoProducerEvidence): Boolean =
            evidence === noProducerEvidence[evidence.reason.ordinal]

        fun belongsTo(candidate: TargetPorts): Boolean = owner === candidate
    }

    private class AndroidDetachPortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val detachKind: TargetProducerDetachKind,
        override val provenance: TargetOperationProvenance,
        override val bindingFact: TargetAndroidDetachBindingFact,
        private val receipt: TargetProducerDetachReceipt,
    ) : AndroidDetachPort {
        init {
            check(bindingFact.targetIdentity === targetIdentity)
            check(bindingFact.operationIdentity == operationIdentity)
            check(bindingFact.provenance === provenance)
        }
        private var consumed: Boolean = false
        var androidBinding: AndroidTargetOperationBinding? = null
        val applicationResult = TargetAndroidPlatformApplicationResult.Detach(receipt)

        fun detachReceiptLocked(): TargetProducerDetachReceipt? {
            check(owner.targetGate.isHeldByCurrentThread)
            return receipt.takeUnless { consumed }
        }

        fun recordConsumedLocked() {
            check(owner.targetGate.isHeldByCurrentThread)
            check(!consumed)
            consumed = true
        }

    }

    private enum class InitialReleaseCandidateDisposition {
        Detached,
        Committed,
        RetiredUnused,
    }

    private class DetachedInitialReleasePortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val provenance: TargetOperationProvenance,
        override val port: AndroidDetachPortImpl,
        override val committedFact: TargetInitialReleasePortCommittedFact,
        override val retiredUnusedFact: TargetInitialReleasePortRetiredUnusedFact,
    ) : DetachedInitialReleasePort {
        var disposition: InitialReleaseCandidateDisposition = InitialReleaseCandidateDisposition.Detached
        var binding: AndroidTargetOperationBinding? = null

        init {
            check(port.targetIdentity === targetIdentity)
            check(port.operationIdentity == operationIdentity)
            check(port.detachKind == TargetProducerDetachKind.VirtualDisplayRelease)
            check(port.provenance === provenance)
            check(port.bindingFact === committedFact)
            check(retiredUnusedFact.bindingFact === committedFact)
        }

        fun belongsTo(candidate: TargetPorts): Boolean = owner === candidate
    }

    private enum class StagedPortDisposition {
        Detached,
        CommittedUnsubmitted,
        PostAcceptedOrAmbiguous,
        AppliedOrRetired,
        Unused,
    }

    private abstract class StagedPortFactImpl(
        final override val targetIdentity: TargetIdentity,
        final override val targetGeneration: Long,
        final override val operationIdentity: Long,
        final override val provenance: TargetOperationProvenance,
        final override val retargetOccurrenceIdentity: Long,
        final override val correlation: TargetStagedAndroidOperationCorrelation,
    ) : TargetStagedPortFact {
        init {
            require(targetGeneration > 0L)
            require(operationIdentity > 0L)
            require(retargetOccurrenceIdentity > 0L)
            check(targetIdentity.generation == targetGeneration)
            check(provenance.targetIdentity === targetIdentity)
            check(provenance.operationIdentity == operationIdentity)
            check(correlation.targetIdentity === targetIdentity)
            check(correlation.requestedIdentity === provenance.requestedIdentity)
            check(correlation.operationIdentity == operationIdentity)
            check(correlation.portKind == provenance.portKind)
            check(correlation.reconfigurationIdentity == retargetOccurrenceIdentity)
        }
    }

    private class StagedProducerCommittedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
        correlation: TargetStagedAndroidOperationCorrelation,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
        correlation,
    ), TargetStagedProducerPortCommittedFact

    private class StagedDetachCommittedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
        correlation: TargetStagedAndroidOperationCorrelation,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
        correlation,
    ), TargetStagedDetachPortCommittedFact

    private class StagedProducerUnusedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
        correlation: TargetStagedAndroidOperationCorrelation,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
        correlation,
    ), TargetStagedProducerPortUnusedFact {
        private var fixedCleanupRetiredFact: TargetStagedProducerPortRetiredFact? = null

        val cleanupRetiredFact: TargetStagedProducerPortRetiredFact
            get() = checkNotNull(fixedCleanupRetiredFact)

        fun bindCleanupRetiredFact(fact: TargetStagedProducerPortRetiredFact): Boolean {
            if (fixedCleanupRetiredFact != null || fact.operationIdentity != operationIdentity ||
                fact.retargetOccurrenceIdentity != retargetOccurrenceIdentity || fact.provenance !== provenance
            ) {
                return false
            }
            fixedCleanupRetiredFact = fact
            return true
        }
    }

    private class StagedDetachUnusedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
        correlation: TargetStagedAndroidOperationCorrelation,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
        correlation,
    ), TargetStagedDetachPortUnusedFact

    private class StagedPostExposedFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
        correlation: TargetStagedAndroidOperationCorrelation,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
        correlation,
    ), TargetStagedPortPostExposedFact

    private class StagedProducerRetiredFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
        correlation: TargetStagedAndroidOperationCorrelation,
        noProducerEvidence: TargetNoProducerEvidence,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
        correlation,
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
        correlation: TargetStagedAndroidOperationCorrelation,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
        correlation,
    ), TargetStagedDetachPortRetiredFact

    private class StagedProducerSettledOrAmbiguousFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
        correlation: TargetStagedAndroidOperationCorrelation,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
        correlation,
    ), TargetStagedProducerPortSettledOrAmbiguousFact

    private class StagedDetachSettledFactImpl(
        targetIdentity: TargetIdentity,
        targetGeneration: Long,
        operationIdentity: Long,
        provenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
        correlation: TargetStagedAndroidOperationCorrelation,
    ) : StagedPortFactImpl(
        targetIdentity,
        targetGeneration,
        operationIdentity,
        provenance,
        retargetOccurrenceIdentity,
        correlation,
    ), TargetStagedDetachPortSettledFact

    private class StagedAndroidSurfacePortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val retargetOccurrenceIdentity: Long,
        override val port: AndroidSurfacePortImpl,
        override val provenance: TargetOperationProvenance,
        unenteredEvidence: TargetNoProducerEvidence,
        inapplicableEvidence: TargetNoProducerEvidence,
    ) : StagedAndroidSurfacePort {
        var disposition: StagedPortDisposition = StagedPortDisposition.Detached
        var androidBinding: AndroidTargetOperationBinding? = null
        override val correlation: TargetStagedAndroidOperationCorrelation =
            TargetStagedAndroidOperationCorrelation.create(
                owner.targetOwner,
                owner.constructionProof,
                provenance,
                retargetOccurrenceIdentity,
            )
        override val commitCorrelation: TargetStagedProducerPortCommittedFact = StagedProducerCommittedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            correlation,
        )
        override val unusedCorrelation: TargetStagedProducerPortUnusedFact = StagedProducerUnusedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            correlation,
        )
        val postExposedFact: TargetStagedPortPostExposedFact = StagedPostExposedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            correlation,
        )
        val unenteredRetiredFact: TargetStagedProducerPortRetiredFact = StagedProducerRetiredFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            correlation,
            unenteredEvidence,
        )
        val inapplicableRetiredFact: TargetStagedProducerPortRetiredFact = StagedProducerRetiredFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            correlation,
            inapplicableEvidence,
        )
        val settledFact: TargetStagedProducerPortSettledOrAmbiguousFact =
            StagedProducerSettledOrAmbiguousFactImpl(
                targetIdentity,
                targetIdentity.generation,
                operationIdentity,
                provenance,
                retargetOccurrenceIdentity,
                correlation,
            )
        val settledResult =
            TargetAndroidPlatformApplicationResult.ProducerPortSettledOrAmbiguous(settledFact)

        init {
            require(retargetOccurrenceIdentity > 0L)
            check(port.targetIdentity === targetIdentity)
            check(port.operationIdentity == operationIdentity)
            check(port.provenance === provenance)
            check(correlation.targetIdentity === targetIdentity)
            check(correlation.operationIdentity == operationIdentity)
            check(correlation.reconfigurationIdentity == retargetOccurrenceIdentity)
            check((unusedCorrelation as StagedProducerUnusedFactImpl).bindCleanupRetiredFact(inapplicableRetiredFact))
        }

        fun belongsTo(candidate: TargetPorts): Boolean = owner === candidate
    }

    private class StagedAndroidDetachPortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val retargetOccurrenceIdentity: Long,
        override val port: AndroidDetachPortImpl,
        override val provenance: TargetOperationProvenance,
    ) : StagedAndroidDetachPort {
        var disposition: StagedPortDisposition = StagedPortDisposition.Detached
        var androidBinding: AndroidTargetOperationBinding? = null
        override val correlation: TargetStagedAndroidOperationCorrelation =
            TargetStagedAndroidOperationCorrelation.create(
                owner.targetOwner,
                owner.constructionProof,
                provenance,
                retargetOccurrenceIdentity,
            )
        override val commitCorrelation: TargetStagedDetachPortCommittedFact = StagedDetachCommittedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            correlation,
        )
        override val unusedCorrelation: TargetStagedDetachPortUnusedFact = StagedDetachUnusedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            correlation,
        )
        val postExposedFact: TargetStagedPortPostExposedFact = StagedPostExposedFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            correlation,
        )
        val unenteredRetiredFact: TargetStagedDetachPortRetiredFact = StagedDetachRetiredFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            correlation,
        )
        val settledFact: TargetStagedDetachPortSettledFact = StagedDetachSettledFactImpl(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
            retargetOccurrenceIdentity,
            correlation,
        )
        val settledResult = TargetAndroidPlatformApplicationResult.DetachPortSettled(settledFact)

        init {
            require(retargetOccurrenceIdentity > 0L)
            check(port.targetIdentity === targetIdentity)
            check(port.operationIdentity == operationIdentity)
            check(port.provenance === provenance)
            check(correlation.targetIdentity === targetIdentity)
            check(correlation.operationIdentity == operationIdentity)
            check(correlation.reconfigurationIdentity == retargetOccurrenceIdentity)
        }

        fun belongsTo(candidate: TargetPorts): Boolean = owner === candidate
    }

    private class AndroidListenerInstallationPortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val provenance: TargetOperationProvenance,
    ) : AndroidListenerInstallationPort {
        override val bindingFact: TargetAndroidListenerInstallationBindingFact = ListenerInstallationBindingFact(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
        )
        val lease: TargetLease = TargetLease(owner)
        private enum class Disposition {
            PendingEntry,
            Entered,
            NeverInstalled,
            NeverRequested,
            RetiredWithoutPlatformEntry,
        }

        private var disposition: Disposition = Disposition.PendingEntry
        var androidBinding: AndroidTargetOperationBinding? = null
        val installedFact: TargetListenerInstalledFact = TargetListenerInstalledFact.create(
            owner.targetOwner,
            owner.constructionProof,
            targetIdentity,
            operationIdentity,
            provenance,
        )
        val applicationResult = TargetAndroidPlatformApplicationResult.ListenerInstalled(installedFact)

        override fun withListener(
            block: (SurfaceTexture, SurfaceTexture.OnFrameAvailableListener) -> Unit,
        ): TargetPortUseOutcome = owner.withListenerInstallationLease(this, block).toPortUseOutcome()

        fun claimLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            if (disposition != Disposition.PendingEntry) return false
            disposition = Disposition.Entered
            return true
        }

        fun canRetireNeverInstalledLocked(binding: AndroidTargetOperationBinding): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            return disposition == Disposition.PendingEntry && androidBinding === binding
        }

        fun isEnteredLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            return disposition == Disposition.Entered
        }

        fun recordNeverInstalledLocked(binding: AndroidTargetOperationBinding) {
            check(owner.targetGate.isHeldByCurrentThread)
            check(canRetireNeverInstalledLocked(binding))
            disposition = Disposition.NeverInstalled
        }

        fun canRetireNeverRequestedLocked(): Boolean {
            check(owner.targetGate.isHeldByCurrentThread)
            return disposition == Disposition.PendingEntry && androidBinding == null
        }

        fun recordNeverRequestedLocked() {
            check(owner.targetGate.isHeldByCurrentThread)
            check(canRetireNeverRequestedLocked())
            disposition = Disposition.NeverRequested
        }

        fun recordRetiredWithoutPlatformEntryLocked() {
            check(owner.targetGate.isHeldByCurrentThread)
            check(disposition == Disposition.PendingEntry)
            disposition = Disposition.RetiredWithoutPlatformEntry
        }
    }

    private class AndroidListenerRemovalPortImpl(
        private val owner: TargetPorts,
        override val targetIdentity: TargetIdentity,
        override val operationIdentity: Long,
        override val provenance: TargetOperationProvenance,
    ) : AndroidListenerRemovalPort {
        override val bindingFact: TargetAndroidListenerRemovalBindingFact = ListenerRemovalBindingFact(
            targetIdentity,
            targetIdentity.generation,
            operationIdentity,
            provenance,
        )
        val lease: TargetLease = TargetLease(owner)
        private var consumed: Boolean = false
        var androidBinding: AndroidTargetOperationBinding? = null
        var sentinelBinding: AndroidTargetOperationBinding? = null
        val returnedFact: TargetListenerRemovalReturnedFact = TargetListenerRemovalReturnedFact.create(
            owner.targetOwner,
            owner.constructionProof,
            targetIdentity,
            operationIdentity,
            provenance,
        )
        val settledFact: TargetListenerRemovalSettledFact = TargetListenerRemovalSettledFact.create(
            owner.targetOwner,
            owner.constructionProof,
            targetIdentity,
            operationIdentity,
            provenance,
        )
        val sentinelObservedFact: TargetListenerSentinelObservedFact = TargetListenerSentinelObservedFact.create(
            owner.targetOwner,
            owner.constructionProof,
            targetIdentity,
            operationIdentity,
            provenance,
        )
        val returnedResult = TargetAndroidPlatformApplicationResult.ListenerRemovalReturned(returnedFact)
        val settledResult = TargetAndroidPlatformApplicationResult.ListenerRemovalSettled(settledFact)
        val sentinelObservedResult = TargetAndroidPlatformApplicationResult.ListenerSentinelObserved(sentinelObservedFact)

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
    private var listenerInstallationRequestClaim: TargetListenerInstallationRequestClaim? = null
    private var listenerInstallationBindingFact: TargetListenerInstallationBindingCommittedFact? = null
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
    private val precreatedListenerInstallationRequestClaim: TargetListenerInstallationRequestClaim =
        TargetListenerInstallationRequestClaim.precreate(
            targetOwner,
            constructionProof,
            precreatedListenerInstallationPort,
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

    internal fun listenerInstallationPortForConstructionFact(
        operationIdentity: Long,
    ): AndroidListenerInstallationPort? = targetGate.withLock {
        listenerInstallationPort?.takeIf {
            it === precreatedListenerInstallationPort && it.operationIdentity == operationIdentity
        }
    }

    internal fun listenerInstallationPortForConstructionClosure(
        operationIdentity: Long,
    ): AndroidListenerInstallationPort? = precreatedListenerInstallationPort.takeIf {
        it.operationIdentity == operationIdentity
    }

    internal fun claimListenerInstallationRequest(operationIdentity: Long): TargetListenerInstallationRequestClaim? {
        require(operationIdentity > 0L)
        return targetGate.withLock {
            val exact = listenerInstallationPort ?: return@withLock null
            if (listenerInstallationRequestClaim != null || listenerInstallationBindingFact != null ||
                exact !== precreatedListenerInstallationPort || exact.operationIdentity != operationIdentity ||
                !exact.canRetireNeverRequestedLocked() ||
                !target.claimListenerInstallationRequestLocked(exactPort = true, operationIdentity)
            ) {
                return@withLock null
            }
            precreatedListenerInstallationRequestClaim.also { listenerInstallationRequestClaim = it }
        }
    }

    internal val hasNeverRequestedListenerInstallationLocked: Boolean
        get() {
            check(targetGate.isHeldByCurrentThread)
            val exact = listenerInstallationPort
            return listenerInstallationRequestClaim == null && listenerInstallationBindingFact == null &&
                    exact === precreatedListenerInstallationPort &&
                    exact.canRetireNeverRequestedLocked() && leaseCount == 0
        }

    internal fun retireNeverRequestedListenerInstallationLocked() {
        check(targetGate.isHeldByCurrentThread)
        val exact = checkNotNull(listenerInstallationPort)
        check(exact === precreatedListenerInstallationPort)
        check(leaseCount == 0)
        exact.recordNeverRequestedLocked()
        listenerInstallationPort = null
    }

    internal fun canRetireClaimedListenerInstallationLocked(
        claim: TargetListenerInstallationRequestClaim,
    ): Boolean {
        check(targetGate.isHeldByCurrentThread)
        val exact = listenerInstallationPort
        return claim === precreatedListenerInstallationRequestClaim &&
                listenerInstallationRequestClaim === claim && listenerInstallationBindingFact == null &&
                exact === claim.port && exact?.androidBinding == null &&
                exact?.canRetireNeverRequestedLocked() == true && leaseCount == 0
    }

    internal fun retireClaimedListenerInstallationLocked(
        claim: TargetListenerInstallationRequestClaim,
    ) {
        check(targetGate.isHeldByCurrentThread)
        check(canRetireClaimedListenerInstallationLocked(claim))
        val exact = checkNotNull(listenerInstallationPort)
        exact.recordRetiredWithoutPlatformEntryLocked()
        listenerInstallationPort = null
    }

    internal fun canRetireBoundListenerWithoutPlatformEntryLocked(
        bindingFact: TargetListenerInstallationBindingCommittedFact,
        outcome: AndroidTargetListenerInstallationNoPlatformEntryOutcome,
    ): Boolean {
        check(targetGate.isHeldByCurrentThread)
        val exact = listenerInstallationPort
        return listenerInstallationBindingFact === bindingFact &&
                listenerInstallationRequestClaim === bindingFact.claim &&
                bindingFact.claim === precreatedListenerInstallationRequestClaim &&
                outcome.binding === bindingFact.binding && exact === bindingFact.claim.port &&
                exact?.androidBinding === bindingFact.binding &&
                exact?.canRetireNeverInstalledLocked(bindingFact.binding) == true && leaseCount == 0
    }

    internal fun retireBoundListenerWithoutPlatformEntryLocked(
        bindingFact: TargetListenerInstallationBindingCommittedFact,
        outcome: AndroidTargetListenerInstallationNoPlatformEntryOutcome,
    ) {
        check(targetGate.isHeldByCurrentThread)
        check(canRetireBoundListenerWithoutPlatformEntryLocked(bindingFact, outcome))
        val exact = checkNotNull(listenerInstallationPort)
        exact.recordRetiredWithoutPlatformEntryLocked()
        listenerInstallationPort = null
    }

    internal fun prepareProducerPort(
        operationIdentity: Long,
        operationKind: TargetProducerOperationKind,
    ): TargetProducerPortPreparationResult {
        require(operationIdentity > 0L)
        val portKind = when (operationKind) {
            TargetProducerOperationKind.VirtualDisplayCreation ->
                TargetPortKind.VirtualDisplayCreation

            TargetProducerOperationKind.VirtualDisplayAttachment ->
                TargetPortKind.VirtualDisplayAttachment
        }
        val provenance = provenance(operationIdentity, portKind)
        val retiredUnused = ProducerPreparationRetiredUnusedFactImpl(
            target.identity,
            operationIdentity,
            provenance,
        )
        return try {
            val producerEvidence = targetProducerEvidence(
                targetOwner,
                constructionProof,
                generation,
                operationIdentity,
                operationKind,
                provenance,
            )
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
            AndroidSurfacePortImpl(
                this,
                target.identity,
                operationIdentity,
                operationKind,
                provenance,
                producerEvidence,
                noProducerEvidence,
            )
        } catch (failure: Throwable) {
            retiredUnused.recordFailure(failure)
            retiredUnused
        }
    }

    internal fun prepareStagedProducerPort(
        operationIdentity: Long,
        retargetOccurrenceIdentity: Long,
    ): TargetProducerPortPreparationResult {
        require(operationIdentity > 0L)
        require(retargetOccurrenceIdentity > 0L)
        val operationKind = TargetProducerOperationKind.VirtualDisplayAttachment
        val portKind = TargetPortKind.VirtualDisplayAttachment
        val provenance = provenance(operationIdentity, portKind)
        val retiredUnused = ProducerPreparationRetiredUnusedFactImpl(
            target.identity,
            operationIdentity,
            provenance,
        )
        return try {
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
            StagedAndroidSurfacePortImpl(
                this,
                target.identity,
                operationIdentity,
                retargetOccurrenceIdentity,
                port,
                provenance,
                noProducerEvidence[TargetNoProducerReason.Unentered.ordinal],
                noProducerEvidence[TargetNoProducerReason.Inapplicable.ordinal],
            )
        } catch (failure: Throwable) {
            retiredUnused.recordFailure(failure)
            retiredUnused
        }
    }

    internal fun bindAndroidOperation(
        port: AndroidSurfacePort,
        binding: AndroidTargetOperationBinding,
    ): TargetProducerPortCommitResult? {
        val exact = port as? AndroidSurfacePortImpl ?: return null
        if (!exact.belongsTo(this)) return null
        if (binding.targetFact !== exact.bindingFact || binding.targetIdentity !== target.identity ||
            binding.operationIdentity != exact.operationIdentity || binding.provenance !== exact.provenance
        ) {
            return null
        }
        return targetGate.withLock {
            when (exact.candidateDisposition) {
                ProducerCandidateDisposition.Committed ->
                    return@withLock exact.bindingFact.takeIf {
                        producerPort === exact && exact.androidBinding === binding
                    }

                ProducerCandidateDisposition.RetiredUnused -> return@withLock exact.retiredUnusedFact
                ProducerCandidateDisposition.Detached -> Unit
            }
            if (exact.androidBinding != null || !target.canRegisterProducerPortLocked(producerPort != null)) {
                exact.candidateDisposition = ProducerCandidateDisposition.RetiredUnused
                return@withLock exact.retiredUnusedFact
            }
            exact.androidBinding = binding
            producerPort = exact
            exact.candidateDisposition = ProducerCandidateDisposition.Committed
            exact.bindingFact
        }
    }

    internal fun bindAndroidOperation(
        port: AndroidDetachPort,
        binding: AndroidTargetOperationBinding,
    ): Boolean {
        val exact = port as? AndroidDetachPortImpl ?: return false
        if (binding.targetFact !== exact.bindingFact || binding.targetIdentity !== target.identity ||
            binding.operationIdentity != exact.operationIdentity || binding.provenance !== exact.provenance
        ) {
            return false
        }
        return targetGate.withLock {
            if (producerDetachPort !== exact || exact.androidBinding != null) false
            else {
                exact.androidBinding = binding
                true
            }
        }
    }

    internal fun commitListenerInstallationBinding(
        claim: TargetListenerInstallationRequestClaim,
        binding: AndroidTargetOperationBinding,
    ): TargetListenerInstallationBindingCommittedFact? {
        val exact = claim.port as? AndroidListenerInstallationPortImpl ?: return null
        if (claim !== precreatedListenerInstallationRequestClaim || binding.targetFact !== exact.bindingFact ||
            claim.targetIdentity !== target.identity || claim.operationIdentity != exact.operationIdentity ||
            claim.provenance !== exact.provenance || binding.targetIdentity !== target.identity ||
            binding.operationIdentity != exact.operationIdentity || binding.provenance !== exact.provenance
        ) {
            return null
        }
        val committedFact = TargetListenerInstallationBindingCommittedFact.precreate(
            targetOwner,
            constructionProof,
            claim,
            binding,
        )
        return targetGate.withLock {
            if (listenerInstallationPort !== exact || listenerInstallationRequestClaim !== claim ||
                listenerInstallationBindingFact != null || exact.androidBinding != null ||
                !target.commitListenerInstallationBindingLocked(
                    exactClaim = true,
                    operationIdentity = exact.operationIdentity,
                )
            ) null
            else {
                exact.androidBinding = binding
                listenerInstallationBindingFact = committedFact
                committedFact
            }
        }
    }

    internal fun recoverListenerInstallationBindingCommittedFact(
        claim: TargetListenerInstallationRequestClaim,
        binding: AndroidTargetOperationBinding,
    ): TargetListenerInstallationBindingCommittedFact? = targetGate.withLock {
        val exactPort = listenerInstallationPort ?: return@withLock null
        val exactFact = listenerInstallationBindingFact ?: return@withLock null
        if (!resources.installedResources || target.listenerInstalled || target.listenerInstallationObligation !=
            TargetListenerInstallationObligation.PendingInstallation ||
            !target.isListenerInstallationRequestBoundLocked(claim.operationIdentity) ||
            claim !== precreatedListenerInstallationRequestClaim ||
            listenerInstallationRequestClaim !== claim || exactPort !== claim.port ||
            exactPort.androidBinding !== binding || !exactPort.canRetireNeverInstalledLocked(binding) ||
            leaseCount != 0 || exactFact.claim !== claim || exactFact.binding !== binding
        ) {
            return@withLock null
        }
        exactFact
    }

    internal fun bindAndroidListenerRemovalOperations(
        port: AndroidListenerRemovalPort,
        removalBinding: AndroidTargetOperationBinding,
        sentinelBinding: AndroidTargetOperationBinding,
    ): Boolean {
        val exact = port as? AndroidListenerRemovalPortImpl ?: return false
        if (removalBinding.targetFact !== exact.bindingFact || sentinelBinding.targetFact !== exact.bindingFact ||
            removalBinding.targetIdentity !== target.identity || sentinelBinding.targetIdentity !== target.identity ||
            removalBinding.operationIdentity != exact.operationIdentity ||
            sentinelBinding.operationIdentity != exact.operationIdentity ||
            removalBinding.provenance !== exact.provenance || sentinelBinding.provenance !== exact.provenance
        ) {
            return false
        }
        return targetGate.withLock {
            if (listenerRemovalPort !== exact || exact.androidBinding != null || exact.sentinelBinding != null) false
            else {
                exact.androidBinding = removalBinding
                exact.sentinelBinding = sentinelBinding
                true
            }
        }
    }

    internal fun applyListenerInstallationNeverInstalled(
        proof: AndroidFinalLaneNoEntryProof<AndroidTargetListenerInstallationEvidence>,
    ): TargetAndroidPlatformApplicationResult.ListenerNeverInstalled? {
        val exact = targetGate.withLock {
            listenerInstallationPort?.takeIf { port ->
                resources.installedResources && target.retirementAdmissionClosed && target.enteredTargetWorkDrained &&
                        target.generationFenced &&
                        target.listenerInstallationObligation == TargetListenerInstallationObligation.PendingInstallation &&
                        !target.listenerInstalled && !target.sourceSignalsAccepted &&
                        port.operationIdentity == target.listenerInstallationOperationIdentity
            }
        } ?: return null
        val binding = exact.androidBinding ?: return null
        val committedBinding = targetGate.withLock {
            listenerInstallationBindingFact?.takeIf {
                it.claim === listenerInstallationRequestClaim && it.binding === binding
            }
        } ?: return null
        val fact = precreateListenerNeverInstalledFact(
            targetOwner = targetOwner,
            constructionProof = constructionProof,
            target = target,
            port = exact,
            binding = binding,
            proof = proof,
        ) ?: return null
        val result = TargetAndroidPlatformApplicationResult.ListenerNeverInstalled(fact)
        return targetGate.withLock {
            if (listenerInstallationPort !== exact || exact.androidBinding !== binding ||
                listenerInstallationBindingFact !== committedBinding ||
                !exact.canRetireNeverInstalledLocked(binding) || !target.canApplyListenerNeverInstalledLocked(fact)
            ) {
                return@withLock null
            }
            target.recordListenerNeverInstalledLocked(fact)
            exact.recordNeverInstalledLocked(binding)
            listenerInstallationPort = null
            result
        }
    }

    internal fun bindAndroidOperation(
        stagedPort: StagedAndroidSurfacePort,
        binding: AndroidTargetOperationBinding,
    ): Boolean {
        val staged = stagedPort as? StagedAndroidSurfacePortImpl ?: return false
        if (!staged.belongsTo(this) || staged.commitCorrelation.correlation !== staged.correlation ||
            binding.targetFact !== staged.commitCorrelation ||
            binding.targetIdentity !== target.identity || binding.operationIdentity != staged.operationIdentity ||
            binding.reconfigurationIdentity != staged.correlation.reconfigurationIdentity ||
            binding.provenance !== staged.port.provenance
        ) {
            return false
        }
        return targetGate.withLock {
            if (staged.disposition != StagedPortDisposition.Detached || staged.androidBinding != null) {
                false
            } else {
                staged.androidBinding = binding
                true
            }
        }
    }

    internal fun bindAndroidOperation(
        stagedPort: StagedAndroidDetachPort,
        binding: AndroidTargetOperationBinding,
    ): Boolean {
        val staged = stagedPort as? StagedAndroidDetachPortImpl ?: return false
        if (!staged.belongsTo(this) || staged.commitCorrelation.correlation !== staged.correlation ||
            binding.targetFact !== staged.commitCorrelation ||
            binding.targetIdentity !== target.identity || binding.operationIdentity != staged.operationIdentity ||
            binding.reconfigurationIdentity != staged.correlation.reconfigurationIdentity ||
            binding.provenance !== staged.port.provenance
        ) {
            return false
        }
        return targetGate.withLock {
            if (staged.disposition != StagedPortDisposition.Detached || staged.androidBinding != null) {
                false
            } else {
                staged.androidBinding = binding
                true
            }
        }
    }

    /**
     * Consumes only Android's sealed post outcome. Target does not inspect the Handler ticket or
     * OperationOccurrence on this path; the opaque committed fact is the rooted correlation.
     */
    internal fun consumeAndroidPostOutcome(outcome: AndroidTargetPostOutcome): TargetStagedPortFact? {
        val fact = outcome.targetFact
        if (fact.targetIdentity !== target.identity || fact.targetGeneration != generation) return null
        return when (outcome) {
            is AndroidTargetPostOutcome.RetiredUnused -> targetGate.withLock {
                when (fact) {
                    is StagedProducerUnusedFactImpl -> fact.cleanupRetiredFact

                    is TargetStagedDetachPortUnusedFact -> fact
                    else -> null
                }
            }

            is AndroidTargetPostOutcome.PostExposed -> targetGate.withLock {
                when (fact) {
                    is TargetStagedProducerPortCommittedFact -> {
                        val staged = committedStagedProducerPort ?: return@withLock null
                        if (staged.commitCorrelation !== fact || staged.androidBinding !== outcome.binding ||
                            staged.port !== producerPort
                        ) {
                            return@withLock null
                        }
                        if (staged.disposition == StagedPortDisposition.PostAcceptedOrAmbiguous) {
                            return@withLock staged.postExposedFact
                        }
                        if (staged.disposition != StagedPortDisposition.CommittedUnsubmitted) return@withLock null
                        staged.disposition = StagedPortDisposition.PostAcceptedOrAmbiguous
                        staged.postExposedFact
                    }

                    is TargetStagedDetachPortCommittedFact -> {
                        val staged = committedStagedDetachPort ?: return@withLock null
                        if (staged.commitCorrelation !== fact || staged.androidBinding !== outcome.binding ||
                            staged.port !== producerDetachPort
                        ) {
                            return@withLock null
                        }
                        if (staged.disposition == StagedPortDisposition.PostAcceptedOrAmbiguous) {
                            return@withLock staged.postExposedFact
                        }
                        if (staged.disposition != StagedPortDisposition.CommittedUnsubmitted) return@withLock null
                        staged.disposition = StagedPortDisposition.PostAcceptedOrAmbiguous
                        staged.postExposedFact
                    }

                    else -> null
                }
            }

            is AndroidTargetPostOutcome.DefinitelyUnentered -> targetGate.withLock {
                when (fact) {
                    is TargetStagedProducerPortCommittedFact -> {
                        val staged = committedStagedProducerPort ?: return@withLock null
                        if (staged.commitCorrelation !== fact || staged.androidBinding !== outcome.binding ||
                            staged.port !== producerPort ||
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

                    is TargetStagedDetachPortCommittedFact -> {
                        val staged = committedStagedDetachPort ?: return@withLock null
                        if (staged.commitCorrelation !== fact || staged.androidBinding !== outcome.binding ||
                            staged.port !== producerDetachPort ||
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

                    else -> null
                }
            }
        }
    }

    internal fun consumeAndroidPlatformResult(
        result: AndroidTargetPlatformResult,
    ): TargetAndroidPlatformApplicationResult? = targetGate.withLock {
        when (result) {
            is AndroidTargetPlatformResult.ProducerAttached -> {
                val exactPort = producerPort ?: return@withLock null
                val staged = committedStagedProducerPort
                val bindingMatches = if (staged == null) {
                    exactPort.bindingFact === result.targetFact && exactPort.androidBinding === result.binding
                } else {
                    staged.commitCorrelation === result.targetFact && staged.androidBinding === result.binding &&
                            staged.port === exactPort &&
                            staged.disposition == StagedPortDisposition.PostAcceptedOrAmbiguous
                }
                if (!bindingMatches) return@withLock null
                val evidence = exactPort.producerEvidenceLocked() ?: return@withLock null
                if (!target.applyProducerEvidenceLocked(
                        evidence,
                        exactPort.matchesProducerEvidence(evidence),
                        exactPort.operationIdentity,
                        exactPort.operationKind,
                        exactPort.provenance,
                    )
                ) {
                    return@withLock null
                }
                exactPort.recordOutcomeClaimedLocked()
                if (staged != null) {
                    staged.disposition = StagedPortDisposition.AppliedOrRetired
                    producerPort = null
                    committedStagedProducerPort = null
                }
                exactPort.producerApplicationResult
            }

            is AndroidTargetPlatformResult.ProducerUnavailable -> {
                val exactPort = producerPort ?: return@withLock null
                if (committedStagedProducerPort != null || exactPort.bindingFact !== result.targetFact ||
                    exactPort.androidBinding !== result.binding
                ) {
                    return@withLock null
                }
                val evidence = exactPort.noProducerEvidenceLocked(result.reason) ?: return@withLock null
                if (!target.applyNoProducerEvidenceLocked(
                        evidence,
                        exactPort.matchesNoProducerEvidence(evidence),
                        exactPort.operationIdentity,
                        exactPort.operationKind,
                        exactPort.provenance,
                    )
                ) {
                    return@withLock null
                }
                exactPort.recordOutcomeClaimedLocked()
                exactPort.noProducerApplicationResults[result.reason.ordinal]
            }

            is AndroidTargetPlatformResult.ProducerPortSettled -> {
                val staged = committedStagedProducerPort ?: return@withLock null
                if (staged.commitCorrelation !== result.targetFact || staged.androidBinding !== result.binding ||
                    staged.port !== producerPort ||
                    staged.disposition != StagedPortDisposition.PostAcceptedOrAmbiguous
                ) {
                    return@withLock null
                }
                staged.settledResult
            }

            is AndroidTargetPlatformResult.InitialProducerPortSettledOrAmbiguous -> {
                val exactPort = producerPort ?: return@withLock null
                if (committedStagedProducerPort != null || exactPort.bindingFact !== result.targetFact ||
                    exactPort.androidBinding !== result.binding ||
                    !exactPort.recordPlatformSettlementObservedLocked()
                ) {
                    return@withLock null
                }
                exactPort.initialSettledResult
            }

            is AndroidTargetPlatformResult.ProducerDetached -> {
                val exactPort = producerDetachPort ?: return@withLock null
                val staged = committedStagedDetachPort
                val bindingMatches = if (staged == null) {
                    exactPort.bindingFact === result.targetFact && exactPort.androidBinding === result.binding
                } else {
                    staged.commitCorrelation === result.targetFact && staged.androidBinding === result.binding &&
                            staged.port === exactPort &&
                            staged.disposition == StagedPortDisposition.PostAcceptedOrAmbiguous
                }
                if (!bindingMatches) return@withLock null
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
                exactPort.applicationResult
            }

            is AndroidTargetPlatformResult.DetachPortSettled -> {
                val staged = committedStagedDetachPort ?: return@withLock null
                if (staged.commitCorrelation !== result.targetFact || staged.androidBinding !== result.binding ||
                    staged.port !== producerDetachPort ||
                    staged.disposition != StagedPortDisposition.PostAcceptedOrAmbiguous ||
                    !target.retirementAdmissionClosed
                ) {
                    return@withLock null
                }
                staged.disposition = StagedPortDisposition.AppliedOrRetired
                producerDetachPort = null
                committedStagedDetachPort = null
                staged.settledResult
            }

            is AndroidTargetPlatformResult.ListenerInstalled -> {
                val exact = listenerInstallationPort ?: return@withLock null
                val committedBinding = listenerInstallationBindingFact
                if (committedBinding == null || committedBinding.binding !== result.binding ||
                    committedBinding.claim !== listenerInstallationRequestClaim ||
                    exact.bindingFact !== result.targetFact || exact.androidBinding !== result.binding ||
                    !exact.isEnteredLocked() ||
                    !target.applyListenerInstallationLocked(true, exact.operationIdentity)
                ) {
                    return@withLock null
                }
                exact.applicationResult
            }

            is AndroidTargetPlatformResult.ListenerRemovalReturned -> {
                val exact = listenerRemovalPort ?: return@withLock null
                if (exact.bindingFact !== result.targetFact || exact.androidBinding !== result.binding ||
                    !target.recordListenerRemovalReturnLocked(true, exact.operationIdentity)
                ) {
                    return@withLock null
                }
                exact.returnedResult
            }

            is AndroidTargetPlatformResult.ListenerRemovalSettled -> {
                val exact = listenerRemovalPort ?: return@withLock null
                if (exact.bindingFact !== result.targetFact || exact.androidBinding !== result.binding ||
                    !target.applyListenerRemovalSettlementLocked(true)
                ) {
                    return@withLock null
                }
                exact.settledResult
            }

            is AndroidTargetPlatformResult.ListenerSentinelObserved -> {
                val exact = listenerRemovalPort ?: return@withLock null
                if (exact.bindingFact !== result.targetFact || exact.sentinelBinding !== result.binding ||
                    !target.applyListenerSentinelObservedLocked(exact.operationIdentity)
                ) {
                    return@withLock null
                }
                exact.sentinelObservedResult
            }
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
                    return@withLock staged.commitCorrelation.takeIf {
                        committedStagedProducerPort === staged && producerPort === staged.port
                    }

                StagedPortDisposition.Unused -> return@withLock staged.unusedCorrelation
                StagedPortDisposition.Detached -> Unit
            }
            if (staged.androidBinding == null || target.retirementAdmissionClosed ||
                !target.canRegisterProducerPortLocked(producerPort != null) ||
                committedStagedProducerPort != null
            ) {
                staged.disposition = StagedPortDisposition.Unused
                return@withLock staged.unusedCorrelation
            }
            producerPort = staged.port
            committedStagedProducerPort = staged
            staged.disposition = StagedPortDisposition.CommittedUnsubmitted
            staged.commitCorrelation
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

    internal fun prepareInitialVirtualDisplayReleasePort(
        operationIdentity: Long,
    ): DetachedInitialReleasePort {
        require(operationIdentity > 0L)
        val provenance = provenance(operationIdentity, TargetPortKind.VirtualDisplayRelease)
        val committedFact = InitialReleaseCommittedFactImpl(
            target.identity,
            generation,
            operationIdentity,
            provenance,
        )
        val retiredUnusedFact = InitialReleaseRetiredUnusedFactImpl(committedFact)
        val receipt = targetProducerDetachReceipt(
            targetOwner,
            constructionProof,
            generation,
            operationIdentity,
            TargetProducerDetachKind.VirtualDisplayRelease,
            provenance,
        )
        val port = AndroidDetachPortImpl(
            this,
            target.identity,
            operationIdentity,
            TargetProducerDetachKind.VirtualDisplayRelease,
            provenance,
            committedFact,
            receipt,
        )
        return DetachedInitialReleasePortImpl(
            this,
            target.identity,
            operationIdentity,
            provenance,
            port,
            committedFact,
            retiredUnusedFact,
        )
    }

    internal fun commitInitialVirtualDisplayReleasePort(
        candidate: DetachedInitialReleasePort,
        binding: AndroidTargetOperationBinding,
    ): TargetInitialReleasePortCommitResult? {
        val exact = candidate as? DetachedInitialReleasePortImpl ?: return null
        if (!exact.belongsTo(this) || binding.targetFact !== exact.committedFact ||
            binding.targetIdentity !== target.identity || binding.operationIdentity != exact.operationIdentity ||
            binding.provenance !== exact.provenance
        ) {
            return null
        }
        return targetGate.withLock {
            when (exact.disposition) {
                InitialReleaseCandidateDisposition.Committed ->
                    return@withLock exact.committedFact.takeIf {
                        exact.binding === binding && producerDetachPort === exact.port &&
                                exact.port.androidBinding === binding
                    }

                InitialReleaseCandidateDisposition.RetiredUnused -> return@withLock exact.retiredUnusedFact
                InitialReleaseCandidateDisposition.Detached -> Unit
            }
            if (producerDetachPort != null ||
                target.claimDetachIdentityLocked(
                    exact.operationIdentity,
                    TargetProducerDetachKind.VirtualDisplayRelease,
                ) != TargetRegistrationClaim.New
            ) {
                exact.disposition = InitialReleaseCandidateDisposition.RetiredUnused
                return@withLock exact.retiredUnusedFact
            }
            exact.binding = binding
            exact.port.androidBinding = binding
            producerDetachPort = exact.port
            exact.disposition = InitialReleaseCandidateDisposition.Committed
            exact.committedFact
        }
    }

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
            DetachBindingFact(target.identity, generation, operationIdentity, provenance),
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
                    return@withLock staged.commitCorrelation.takeIf {
                        committedStagedDetachPort === staged && producerDetachPort === staged.port
                    }

                StagedPortDisposition.Unused -> return@withLock staged.unusedCorrelation
                StagedPortDisposition.Detached -> Unit
            }
            if (staged.androidBinding == null ||
                producerDetachPort != null || committedStagedDetachPort != null ||
                target.claimDetachIdentityLocked(staged.operationIdentity, staged.port.detachKind) != TargetRegistrationClaim.New
            ) {
                staged.disposition = StagedPortDisposition.Unused
                return@withLock staged.unusedCorrelation
            }
            producerDetachPort = staged.port
            committedStagedDetachPort = staged
            staged.disposition = StagedPortDisposition.CommittedUnsubmitted
            staged.commitCorrelation
        }
    }

    private fun registerDetachPort(operationIdentity: Long, portKind: TargetPortKind, detachKind: TargetProducerDetachKind): AndroidDetachPort? {
        require(operationIdentity > 0L)
        val provenance = provenance(operationIdentity, portKind)
        val receipt = targetProducerDetachReceipt(targetOwner, constructionProof, generation, operationIdentity, detachKind, provenance)
        val candidate = AndroidDetachPortImpl(
            this,
            target.identity,
            operationIdentity,
            detachKind,
            provenance,
            DetachBindingFact(target.identity, generation, operationIdentity, provenance),
            receipt,
        )
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
                target.generationFenced || target.listenerInstalled || leaseCount == Int.MAX_VALUE ||
                !target.isListenerInstallationRequestBoundLocked(port.operationIdentity)
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
