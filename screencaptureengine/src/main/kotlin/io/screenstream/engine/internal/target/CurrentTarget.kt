package io.screenstream.engine.internal.target

import android.graphics.SurfaceTexture
import android.view.Surface
import io.screenstream.engine.internal.TargetQuarantineChild
import io.screenstream.engine.internal.android.AndroidTargetPostOutcome
import io.screenstream.engine.internal.android.AndroidTargetOperationBinding
import io.screenstream.engine.internal.android.AndroidTargetPlatformResult
import io.screenstream.engine.internal.android.AndroidFinalLaneNoEntryProof
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationEvidence
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationNoPlatformEntryOutcome
import io.screenstream.engine.internal.android.AndroidTargetListenerInstallationUnboundClaimRetiredProof
import io.screenstream.engine.internal.gl.GlDestructionEvidence
import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.gl.GlOperationSuccessReceipt
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class TargetRegistrationClaim {
    New,
    Existing,
    Denied,
}

private enum class TargetFrameAdmissionDisposition {
    Open,
    Sealed,
    RetirementClosed,
}

internal class CurrentTarget private constructor(
    private val targetOwner: TargetOwner,
    private val constructionProof: () -> Unit,
    private val targetGate: ReentrantLock,
    internal val requestedIdentity: TargetRequestedIdentity,
    internal val plan: TargetPlan,
    internal val generation: Long,
    internal val listenerInstallationOperationIdentity: Long,
    private val sourceSignal: TargetSourceSignal,
    clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    surfaceReleaseOperationIdentity: Long,
    surfaceReleaseDeadlineIdentity: Long,
    surfaceReleaseDeadlineWakeGeneration: Long,
    surfaceReleaseTimeoutCause: Throwable,
    private val targetDestructionIdentity: GlFiniteOperationIdentity,
    private val namespaceDestructionIdentity: GlFiniteOperationIdentity,
) {
    internal val identity: TargetIdentity =
        TargetIdentity.create(targetOwner, constructionProof, this, generation)
    internal val quarantineChild: TargetQuarantineChild.Current = TargetQuarantineChild.Current(this)
    internal var expectedListenerRemovalOperationIdentity: Long = NO_OPERATION_IDENTITY
        private set
    internal var expectedSetSurfaceDetachOperationIdentity: Long = NO_OPERATION_IDENTITY
        private set
    internal var expectedVirtualDisplayReleaseOperationIdentity: Long = NO_OPERATION_IDENTITY
        private set
    internal var observedListenerSentinelOperationIdentity: Long = NO_OPERATION_IDENTITY
        private set
    internal var producerState: TargetProducerState = TargetProducerState.AwaitingEvidence
        private set
    internal var listenerInstalled: Boolean = false
        private set
    internal var listenerInstallationObligation: TargetListenerInstallationObligation =
        TargetListenerInstallationObligation.PendingInstallation
        private set
    internal var listenerInstallationRequestAdmission: TargetListenerInstallationRequestAdmission =
        TargetListenerInstallationRequestAdmission.AwaitingRequest
        private set
    internal var sourceSignalsAccepted: Boolean = false
        private set
    internal var retirementAdmissionClosed: Boolean = false
        private set
    internal var enteredTargetWorkDrained: Boolean = false
        private set
    internal var generationFenced: Boolean = false
        private set
    internal var listenerRemoved: Boolean = false
        private set
    internal var listenerRemovalSettled: Boolean = false
        private set
    private var retirementAdmissionClosedFact: TargetRetirementAdmissionClosedFact? = null
    private var workDrainedFact: TargetWorkDrainedFact? = null
    private var generationFencedFact: TargetGenerationFencedFact? = null
    private var listenerNeverInstalledFact: TargetListenerNeverInstalledFact? = null
    private var listenerNeverRequestedFact: TargetListenerInstallationNeverRequestedFact? = null
    private var listenerClaimRetiredFact: TargetListenerInstallationClaimRetiredFact? = null
    private var listenerNoPlatformEntryFact: TargetListenerInstallationNoPlatformEntryFact? = null
    private var frameAdmissionDisposition: TargetFrameAdmissionDisposition = TargetFrameAdmissionDisposition.Open
    private var frameAdmissionEpoch: Long = 1L
    private var frameAdmissionPredecessor: TargetEnteredFact? = null
    private var frameAdmissionSealedFact: TargetFrameAdmissionSealedFact? = null
    private var frameQuiescedFact: TargetFrameQuiescedFact? = null
    private var retainedGlReservation: TargetRetainedGlReservation? = null
    private var retainedGlAdmittedFact: TargetRetainedGlAdmittedFact? = null
    private var retainedGlSettledFact: TargetRetainedGlSettledFact? = null

    private val listenerTargetIdentity: TargetIdentity = identity
    private val frameAvailableListener: SurfaceTexture.OnFrameAvailableListener =
        SurfaceTexture.OnFrameAvailableListener {
            publishSourceAvailable(listenerTargetIdentity)
        }

    private val retirement: TargetRetirement =
        TargetRetirement.create(
            targetOwner,
            constructionProof,
            this,
            targetGate,
            clock,
            settlementSignal,
            surfaceReleaseOperationIdentity,
            surfaceReleaseDeadlineIdentity,
            surfaceReleaseDeadlineWakeGeneration,
            surfaceReleaseTimeoutCause,
            targetDestructionIdentity,
            namespaceDestructionIdentity,
        )

    private val ports: TargetPorts =
        TargetPorts.create(
            targetOwner,
            constructionProof,
            this,
            targetGate,
            generation,
            listenerInstallationOperationIdentity,
            surfaceReleaseOperationIdentity,
            targetDestructionIdentity.operationIdentity,
            settlementSignal,
            retirement,
        )
    private val precreatedMechanicallyRetiredFact: CurrentTargetMechanicallyRetiredFact =
        CurrentTargetMechanicallyRetiredFact.precreate(
            targetOwner,
            constructionProof,
            identity,
            retirement.precreatedRetirementCompleteEvidence,
        )

    private val precreatedSourceAvailableFact: TargetSourceAvailableFact =
        TargetSourceAvailableFact.create(targetOwner, constructionProof, identity)
    private val pendingSourceLatch: TargetPendingSourceLatch =
        TargetPendingSourceLatch.create(
            targetOwner,
            constructionProof,
            identity,
            precreatedSourceAvailableFact,
            frameAdmissionEpoch,
        )
    private val precreatedRetirementAdmissionClosedFact: TargetRetirementAdmissionClosedFact =
        TargetRetirementAdmissionClosedFact.create(targetOwner, constructionProof, identity)
    private val precreatedWorkDrainedFact: TargetWorkDrainedFact =
        TargetWorkDrainedFact.create(
            targetOwner,
            constructionProof,
            identity,
            precreatedRetirementAdmissionClosedFact,
        )
    private val precreatedGenerationFencedFact: TargetGenerationFencedFact =
        TargetGenerationFencedFact.create(targetOwner, constructionProof, identity, precreatedWorkDrainedFact)
    private val precreatedSurfaceReleaseReadyFact: TargetSurfaceReleaseReadyFact =
        TargetSurfaceReleaseReadyFact.create(
            targetOwner,
            constructionProof,
            identity,
            precreatedGenerationFencedFact,
        )

    init {
        check(targetOwner.acceptsConstructionProof(constructionProof))
        require(generation > 0L)
        require(listenerInstallationOperationIdentity > 0L)
        require(surfaceReleaseOperationIdentity > 0L)
        require(surfaceReleaseDeadlineIdentity > 0L)
        require(surfaceReleaseDeadlineWakeGeneration > 0L)
        require(targetDestructionIdentity.operationIdentity > 0L)
        require(namespaceDestructionIdentity.operationIdentity > 0L)
        require(namespaceDestructionIdentity.operationIdentity != targetDestructionIdentity.operationIdentity)
        retirement.bindPorts(targetOwner, constructionProof, ports)
    }

    internal val surfaceReleaseOccurrence:
            OperationOccurrence<TargetSurfaceReleaseEvidence>
        get() = retirement.surfaceReleaseOccurrence

    internal val activeLeaseCount: Int
        get() = ports.activeLeaseCount

    internal fun claimMechanicallyRetiredFact(
        evidence: TargetRetirementCompleteEvidence,
    ): CurrentTargetMechanicallyRetiredFact? = targetGate.withLock {
        precreatedMechanicallyRetiredFact.takeIf {
            it.retirementEvidence === evidence && retirement.isExactMechanicalRetirementLocked(evidence)
        }
    }

    internal fun surfaceReleaseReadyFact(): TargetSurfaceReleaseReadyFact? = targetGate.withLock {
        if (!isSurfaceReleaseReadyLocked()) return@withLock null
        val fencedFact = generationFencedFact ?: return@withLock null
        precreatedSurfaceReleaseReadyFact.takeIf { it.generationFencedFact === fencedFact }
    }

    private fun isSurfaceReleaseReadyLocked(): Boolean {
        check(targetGate.isHeldByCurrentThread)
        val listenerReady = when (listenerInstallationObligation) {
            TargetListenerInstallationObligation.PendingInstallation -> false
            TargetListenerInstallationObligation.NeverInstalled ->
                listenerInstallationRequestAdmission == TargetListenerInstallationRequestAdmission.Bound &&
                        listenerNeverInstalledFact?.targetIdentity === identity &&
                        expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY &&
                        !listenerRemoved && !listenerRemovalSettled &&
                        observedListenerSentinelOperationIdentity == NO_OPERATION_IDENTITY
            TargetListenerInstallationObligation.NeverRequested ->
                listenerNeverRequestedFact?.let { fact ->
                    listenerInstallationRequestAdmission ==
                            TargetListenerInstallationRequestAdmission.NeverRequested &&
                            fact.targetIdentity === identity &&
                            fact.requestedIdentity === requestedIdentity &&
                            fact.cutoffFact === retirementAdmissionClosedFact &&
                            fact.workDrainedFact === workDrainedFact &&
                            fact.generationFencedFact === generationFencedFact
                } == true && expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY &&
                        !listenerRemoved && !listenerRemovalSettled &&
                        observedListenerSentinelOperationIdentity == NO_OPERATION_IDENTITY
            TargetListenerInstallationObligation.ClaimRetiredBeforeBinding ->
                listenerInstallationRequestAdmission ==
                        TargetListenerInstallationRequestAdmission.RetiredBeforeBinding &&
                        listenerClaimRetiredFact?.let { fact ->
                            fact.targetIdentity === identity && fact.cutoffFact === retirementAdmissionClosedFact &&
                                    fact.workDrainedFact === workDrainedFact &&
                                    fact.generationFencedFact === generationFencedFact
                        } == true &&
                        expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY &&
                        !listenerRemoved && !listenerRemovalSettled &&
                        observedListenerSentinelOperationIdentity == NO_OPERATION_IDENTITY
            TargetListenerInstallationObligation.NoPlatformEntry ->
                listenerInstallationRequestAdmission == TargetListenerInstallationRequestAdmission.Bound &&
                        listenerNoPlatformEntryFact?.let { fact ->
                            fact.targetIdentity === identity && fact.cutoffFact === retirementAdmissionClosedFact &&
                                    fact.workDrainedFact === workDrainedFact &&
                                    fact.generationFencedFact === generationFencedFact
                        } == true &&
                        expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY &&
                        !listenerRemoved && !listenerRemovalSettled &&
                        observedListenerSentinelOperationIdentity == NO_OPERATION_IDENTITY
            TargetListenerInstallationObligation.Installed ->
                listenerInstallationRequestAdmission == TargetListenerInstallationRequestAdmission.Bound &&
                        listenerRemoved && listenerRemovalSettled &&
                        expectedListenerRemovalOperationIdentity != NO_OPERATION_IDENTITY &&
                        observedListenerSentinelOperationIdentity == expectedListenerRemovalOperationIdentity
        }
        val producerReady = producerState == TargetProducerState.NoProducer ||
                producerState == TargetProducerState.Detached
        return retirement.installedResources &&
                retirementAdmissionClosed &&
                enteredTargetWorkDrained &&
                generationFenced &&
                listenerReady && producerReady &&
                !ports.hasGlFramePortLocked &&
                ports.leaseCountLocked == 0
    }

    internal fun precreateInstalledConstructionFact(
        constructionProvenance: TargetConstructionProvenance,
        constructionReceipt: GlOperationSuccessReceipt,
    ): TargetConstructionInstalledFact {
        val listenerPort = checkNotNull(ports.listenerInstallationPortForConstructionClosure(
            listenerInstallationOperationIdentity,
        ))
        return TargetConstructionInstalledFact.create(
            targetOwner = targetOwner,
            constructionProof = constructionProof,
            requestedIdentity = requestedIdentity,
            targetIdentity = identity,
            constructionOperationIdentity = constructionProvenance.constructionOperationIdentity,
            constructionProvenance = constructionProvenance,
            constructionReceipt = constructionReceipt,
            plan = plan,
            listenerInstallationPort = listenerPort,
        )
    }

    internal fun claimInstalledConstructionFact(
        requester: TargetOwner,
        proof: () -> Unit,
        precreatedFact: TargetConstructionInstalledFact,
    ): TargetConstructionInstalledFact? {
        if (!matchesConstructionProof(requester, proof)) return null
        val listenerPort = ports.listenerInstallationPortForConstructionFact(
            listenerInstallationOperationIdentity,
        ) ?: return null
        val exactInstalled = targetGate.withLock {
            retirement.installedResources && !generationFenced && listenerPort.targetIdentity === identity &&
                    precreatedFact.targetIdentity === identity &&
                    precreatedFact.constructionProvenance.targetGeneration == generation &&
                    precreatedFact.listenerInstallationPort === listenerPort
        }
        return precreatedFact.takeIf { exactInstalled }
    }

    internal fun precreateConstructionFailureFact(
        constructionProvenance: TargetConstructionProvenance,
    ): TargetConstructionFailureFact = TargetConstructionFailureFact.precreate(
        targetOwner = targetOwner,
        constructionProof = constructionProof,
        requestedIdentity = requestedIdentity,
        targetIdentity = identity,
        constructionOperationIdentity = constructionProvenance.constructionOperationIdentity,
        constructionProvenance = constructionProvenance,
        cleanupTarget = this,
    )

    internal fun bindConstructionFailureFact(
        requester: TargetOwner,
        proof: () -> Unit,
        precreatedFact: TargetConstructionFailureFact,
        disposition: TargetConstructionFoldDisposition,
        returnDisposition: OperationReturnDisposition,
        failure: Throwable?,
        stage: TargetConstructionStage,
    ): TargetConstructionFailureFact? {
        if (!matchesConstructionProof(requester, proof)) return null
        val exactFailure = targetGate.withLock {
            !retirement.installedResources && disposition != TargetConstructionFoldDisposition.Install &&
                    precreatedFact.targetIdentity === identity &&
                    precreatedFact.constructionProvenance.targetGeneration == generation
        }
        if (!exactFailure) return null
        precreatedFact.bind(
            disposition = disposition,
            returnDisposition = returnDisposition,
            failure = failure,
            stage = stage,
        )
        return precreatedFact
    }

    internal fun appliedSurfaceReleaseReceipt(): TargetSurfaceReleaseReceipt? =
        retirement.appliedSurfaceReleaseReceipt()

    internal val isFullyRetired: Boolean
        get() = retirement.isFullyRetired

    internal fun retirementSuffixEvidence(): TargetRetirementSuffixEvidence =
        retirement.retirementSuffixEvidence()

    internal fun quarantineEvidence(): TargetQuarantineEvidence? =
        retirement.quarantineEvidence()

    internal fun usesTargetGate(expectedGate: ReentrantLock): Boolean =
        targetGate === expectedGate

    internal fun matchesConstructionProof(expectedOwner: TargetOwner, proof: () -> Unit): Boolean =
        targetOwner === expectedOwner && constructionProof === proof && targetOwner.acceptsConstructionProof(proof)

    internal fun matchesPrecreatedIdentity(
        expectedPlan: TargetPlan,
        expectedRequestedIdentity: TargetRequestedIdentity,
        expectedGeneration: Long,
        listenerInstallationOperationIdentity: Long,
        surfaceReleaseOperationIdentity: Long,
        surfaceReleaseDeadlineIdentity: Long,
        surfaceReleaseDeadlineWakeGeneration: Long,
        surfaceReleaseTimeoutCause: Throwable,
        targetDestructionIdentity: GlFiniteOperationIdentity,
        namespaceDestructionIdentity: GlFiniteOperationIdentity,
    ): Boolean {
        val deadline = surfaceReleaseOccurrence.deadlineOccurrence ?: return false
        val wakeLink = surfaceReleaseOccurrence.controlWakeLink ?: return false
        return plan === expectedPlan &&
                requestedIdentity === expectedRequestedIdentity &&
                generation == expectedGeneration &&
                ports.matchesListenerInstallationOperationIdentity(listenerInstallationOperationIdentity) &&
                surfaceReleaseOccurrence.identity ==
                surfaceReleaseOperationIdentity &&
                deadline.identity == surfaceReleaseDeadlineIdentity &&
                wakeLink.generation ==
                surfaceReleaseDeadlineWakeGeneration &&
                deadline.timeoutCause === surfaceReleaseTimeoutCause &&
                this.targetDestructionIdentity.operationIdentity ==
                targetDestructionIdentity.operationIdentity &&
                this.targetDestructionIdentity.deadlineIdentity ==
                targetDestructionIdentity.deadlineIdentity &&
                this.targetDestructionIdentity.initialWakeGeneration ==
                targetDestructionIdentity.initialWakeGeneration &&
                this.targetDestructionIdentity.timeoutCause ===
                targetDestructionIdentity.timeoutCause &&
                this.namespaceDestructionIdentity.operationIdentity ==
                namespaceDestructionIdentity.operationIdentity &&
                this.namespaceDestructionIdentity.deadlineIdentity ==
                namespaceDestructionIdentity.deadlineIdentity &&
                this.namespaceDestructionIdentity.initialWakeGeneration ==
                namespaceDestructionIdentity.initialWakeGeneration &&
                this.namespaceDestructionIdentity.timeoutCause ===
                namespaceDestructionIdentity.timeoutCause
    }

    /** Android must claim this request before it creates an occurrence or binding. */
    internal fun claimListenerInstallationRequest(
        operationIdentity: Long,
    ): TargetListenerInstallationRequestClaim? =
        ports.claimListenerInstallationRequest(operationIdentity)

    internal fun prepareProducerPort(
        operationIdentity: Long,
        operationKind: TargetProducerOperationKind,
    ): TargetProducerPortPreparationResult =
        ports.prepareProducerPort(operationIdentity, operationKind)

    internal fun prepareStagedProducerPort(
        operationIdentity: Long,
        retargetOccurrenceIdentity: Long,
    ): TargetProducerPortPreparationResult =
        ports.prepareStagedProducerPort(operationIdentity, retargetOccurrenceIdentity)

    internal fun consumeAndroidTargetPostOutcome(
        outcome: AndroidTargetPostOutcome,
    ): TargetStagedPortFact? = ports.consumeAndroidPostOutcome(outcome)

    internal fun bindAndroidTargetOperation(
        stagedPort: TargetPorts.StagedAndroidSurfacePort,
        binding: AndroidTargetOperationBinding,
    ): Boolean = ports.bindAndroidOperation(stagedPort, binding)

    internal fun bindAndroidTargetOperation(
        stagedPort: TargetPorts.StagedAndroidDetachPort,
        binding: AndroidTargetOperationBinding,
    ): Boolean = ports.bindAndroidOperation(stagedPort, binding)

    internal fun bindAndroidTargetOperation(
        port: TargetPorts.AndroidSurfacePort,
        binding: AndroidTargetOperationBinding,
    ): TargetProducerPortCommitResult? = ports.bindAndroidOperation(port, binding)

    internal fun bindAndroidTargetOperation(
        port: TargetPorts.AndroidDetachPort,
        binding: AndroidTargetOperationBinding,
    ): Boolean = ports.bindAndroidOperation(port, binding)

    internal fun bindAndroidTargetOperation(
        claim: TargetListenerInstallationRequestClaim,
        binding: AndroidTargetOperationBinding,
    ): TargetListenerInstallationBindingCommittedFact? =
        ports.commitListenerInstallationBinding(claim, binding)

    internal fun recoverListenerInstallationBindingCommittedFact(
        claim: TargetListenerInstallationRequestClaim,
        binding: AndroidTargetOperationBinding,
    ): TargetListenerInstallationBindingCommittedFact? =
        ports.recoverListenerInstallationBindingCommittedFact(claim, binding)

    internal fun bindAndroidListenerRemovalOperations(
        port: TargetPorts.AndroidListenerRemovalPort,
        removalBinding: AndroidTargetOperationBinding,
        sentinelBinding: AndroidTargetOperationBinding,
    ): Boolean = ports.bindAndroidListenerRemovalOperations(port, removalBinding, sentinelBinding)

    internal fun consumeAndroidTargetPlatformResult(
        result: AndroidTargetPlatformResult,
    ): TargetAndroidPlatformApplicationResult? = ports.consumeAndroidPlatformResult(result)

    internal fun applyListenerInstallationNeverInstalled(
        proof: AndroidFinalLaneNoEntryProof<AndroidTargetListenerInstallationEvidence>,
    ): TargetAndroidPlatformApplicationResult.ListenerNeverInstalled? =
        ports.applyListenerInstallationNeverInstalled(proof)

    internal fun applyListenerInstallationNeverRequested(
        cutoffFact: TargetRetirementAdmissionClosedFact,
        drainedFact: TargetWorkDrainedFact,
        fencedFact: TargetGenerationFencedFact,
    ): TargetListenerInstallationNeverRequestedApplicationResult? {
        if (cutoffFact.targetIdentity !== identity || drainedFact.targetIdentity !== identity ||
            drainedFact.admissionClosedFact !== cutoffFact || fencedFact.targetIdentity !== identity ||
            fencedFact.workDrainedFact !== drainedFact
        ) {
            return null
        }
        val candidate = TargetListenerInstallationNeverRequestedFact.precreate(
            targetOwner = targetOwner,
            constructionProof = constructionProof,
            targetIdentity = identity,
            requestedIdentity = requestedIdentity,
            operationIdentity = listenerInstallationOperationIdentity,
            cutoffFact = cutoffFact,
            workDrainedFact = drainedFact,
            generationFencedFact = fencedFact,
        )
        val result = TargetListenerInstallationNeverRequestedApplicationResult.precreate(candidate)
        return targetGate.withLock {
            if (!retirement.installedResources || !retirementAdmissionClosed || !enteredTargetWorkDrained ||
                !generationFenced || retirementAdmissionClosedFact !== cutoffFact ||
                workDrainedFact !== drainedFact || generationFencedFact !== fencedFact ||
                listenerInstallationRequestAdmission !=
                TargetListenerInstallationRequestAdmission.AwaitingRequest ||
                listenerInstallationObligation != TargetListenerInstallationObligation.PendingInstallation ||
                listenerInstalled || sourceSignalsAccepted || listenerNeverInstalledFact != null ||
                listenerNeverRequestedFact != null || listenerClaimRetiredFact != null ||
                listenerNoPlatformEntryFact != null || expectedListenerRemovalOperationIdentity != NO_OPERATION_IDENTITY ||
                listenerRemoved || listenerRemovalSettled ||
                observedListenerSentinelOperationIdentity != NO_OPERATION_IDENTITY ||
                !ports.hasNeverRequestedListenerInstallationLocked
            ) {
                return@withLock null
            }
            listenerInstallationRequestAdmission = TargetListenerInstallationRequestAdmission.NeverRequested
            listenerInstallationObligation = TargetListenerInstallationObligation.NeverRequested
            listenerNeverRequestedFact = candidate
            ports.retireNeverRequestedListenerInstallationLocked()
            result
        }
    }

    internal fun applyListenerInstallationClaimRetiredBeforeBinding(
        claim: TargetListenerInstallationRequestClaim,
        androidProof: AndroidTargetListenerInstallationUnboundClaimRetiredProof,
        cutoffFact: TargetRetirementAdmissionClosedFact,
        drainedFact: TargetWorkDrainedFact,
        fencedFact: TargetGenerationFencedFact,
    ): TargetListenerInstallationClaimRetiredApplicationResult? {
        if (claim.targetIdentity !== identity || androidProof.claim !== claim || cutoffFact.targetIdentity !== identity ||
            drainedFact.targetIdentity !== identity || drainedFact.admissionClosedFact !== cutoffFact ||
            fencedFact.targetIdentity !== identity || fencedFact.workDrainedFact !== drainedFact
        ) {
            return null
        }
        val candidate = TargetListenerInstallationClaimRetiredFact.precreate(
            targetOwner,
            constructionProof,
            claim,
            androidProof,
            cutoffFact,
            drainedFact,
            fencedFact,
        )
        val result = TargetListenerInstallationClaimRetiredApplicationResult.precreate(candidate)
        return targetGate.withLock {
            if (!hasExactListenerAbsenceEvidenceLocked(cutoffFact, drainedFact, fencedFact) ||
                listenerInstallationRequestAdmission != TargetListenerInstallationRequestAdmission.Claimed ||
                listenerInstallationObligation != TargetListenerInstallationObligation.PendingInstallation ||
                listenerClaimRetiredFact != null || listenerNoPlatformEntryFact != null ||
                !ports.canRetireClaimedListenerInstallationLocked(claim)
            ) {
                return@withLock null
            }
            listenerInstallationRequestAdmission = TargetListenerInstallationRequestAdmission.RetiredBeforeBinding
            listenerInstallationObligation = TargetListenerInstallationObligation.ClaimRetiredBeforeBinding
            listenerClaimRetiredFact = candidate
            ports.retireClaimedListenerInstallationLocked(claim)
            result
        }
    }

    internal fun applyListenerInstallationNoPlatformEntry(
        bindingFact: TargetListenerInstallationBindingCommittedFact,
        outcome: AndroidTargetListenerInstallationNoPlatformEntryOutcome,
        cutoffFact: TargetRetirementAdmissionClosedFact,
        drainedFact: TargetWorkDrainedFact,
        fencedFact: TargetGenerationFencedFact,
    ): TargetListenerInstallationNoPlatformEntryApplicationResult? {
        if (bindingFact.claim.targetIdentity !== identity || outcome.binding !== bindingFact.binding ||
            cutoffFact.targetIdentity !== identity || drainedFact.targetIdentity !== identity ||
            drainedFact.admissionClosedFact !== cutoffFact || fencedFact.targetIdentity !== identity ||
            fencedFact.workDrainedFact !== drainedFact
        ) {
            return null
        }
        val candidate = TargetListenerInstallationNoPlatformEntryFact.precreate(
            targetOwner,
            constructionProof,
            bindingFact,
            outcome,
            cutoffFact,
            drainedFact,
            fencedFact,
        )
        val result = TargetListenerInstallationNoPlatformEntryApplicationResult.precreate(candidate)
        return targetGate.withLock {
            if (!hasExactListenerAbsenceEvidenceLocked(cutoffFact, drainedFact, fencedFact) ||
                listenerInstallationRequestAdmission != TargetListenerInstallationRequestAdmission.Bound ||
                listenerInstallationObligation != TargetListenerInstallationObligation.PendingInstallation ||
                listenerClaimRetiredFact != null || listenerNoPlatformEntryFact != null ||
                !ports.canRetireBoundListenerWithoutPlatformEntryLocked(bindingFact, outcome)
            ) {
                return@withLock null
            }
            listenerInstallationObligation = TargetListenerInstallationObligation.NoPlatformEntry
            listenerNoPlatformEntryFact = candidate
            ports.retireBoundListenerWithoutPlatformEntryLocked(bindingFact, outcome)
            result
        }
    }

    private fun hasExactListenerAbsenceEvidenceLocked(
        cutoffFact: TargetRetirementAdmissionClosedFact,
        drainedFact: TargetWorkDrainedFact,
        fencedFact: TargetGenerationFencedFact,
    ): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return retirement.installedResources && retirementAdmissionClosed && enteredTargetWorkDrained &&
                generationFenced && retirementAdmissionClosedFact === cutoffFact &&
                workDrainedFact === drainedFact && generationFencedFact === fencedFact &&
                !listenerInstalled && !sourceSignalsAccepted && listenerNeverInstalledFact == null &&
                listenerNeverRequestedFact == null && expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY &&
                !listenerRemoved && !listenerRemovalSettled &&
                observedListenerSentinelOperationIdentity == NO_OPERATION_IDENTITY
    }

    internal fun commitStagedProducerPort(
        stagedPort: TargetPorts.StagedAndroidSurfacePort,
    ): TargetStagedProducerPortCommitResult? = ports.commitStagedProducerPort(stagedPort)

    internal fun detachedGlFramePort(operationIdentity: Long): TargetPorts.GlFramePort? =
        ports.detachedGlFramePort(operationIdentity)

    internal fun commitGlFramePort(port: TargetPorts.GlFramePort): Boolean =
        ports.commitGlFramePort(port)

    internal fun commitGlFrameReservation(port: TargetPorts.GlFramePort): TargetFrameReservationResult =
        ports.commitGlFrameReservation(port)

    internal fun enterGlFramePort(port: TargetPorts.GlFramePort): TargetFrameEntryResult =
        ports.enterGlFramePort(port)

    internal fun retireGlFramePortAfterSettlement(port: TargetPorts.GlFramePort, operation: OperationOccurrence<*>): Boolean =
        ports.retireGlFramePortAfterSettlement(port, operation)

    internal fun retireGlFramePortFactAfterSettlement(
        port: TargetPorts.GlFramePort,
        operation: OperationOccurrence<*>,
    ): TargetFramePortRetiredFact? = ports.retireGlFramePortFactAfterSettlement(port, operation)

    internal val frameAdmissionEpochLocked: Long
        get() {
            check(targetGate.isHeldByCurrentThread)
            return frameAdmissionEpoch
        }

    internal fun isFrameAdmissionOpenLocked(expectedEpoch: Long): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return frameAdmissionDisposition == TargetFrameAdmissionDisposition.Open &&
                frameAdmissionEpoch == expectedEpoch && !retirementAdmissionClosed && !generationFenced
    }

    internal fun recordFrameEnteredLocked(fact: TargetEnteredFact) {
        check(targetGate.isHeldByCurrentThread)
        check(isFrameAdmissionOpenLocked(fact.frameAdmissionEpoch))
        check(fact.targetIdentity === identity)
        check(frameAdmissionPredecessor == null || frameAdmissionPredecessor === fact)
        frameAdmissionPredecessor = fact
    }

    internal fun acceptsEnteredFrameLocked(fact: TargetEnteredFact): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return fact.targetIdentity === identity && frameAdmissionPredecessor === fact &&
                (frameAdmissionDisposition == TargetFrameAdmissionDisposition.Open ||
                        frameAdmissionDisposition == TargetFrameAdmissionDisposition.RetirementClosed ||
                        frameAdmissionSealedFact?.predecessor === fact)
    }

    internal fun recordFramePortRetiredLocked(fact: TargetEnteredFact) {
        check(targetGate.isHeldByCurrentThread)
        if (frameAdmissionPredecessor === fact) frameAdmissionPredecessor = null
    }

    internal fun sealFrameAdmission(retainedReconfigurationIdentity: Long): TargetFrameAdmissionSealedFact? {
        require(retainedReconfigurationIdentity > 0L)
        var epoch = 0L
        var predecessor: TargetEnteredFact? = null
        val preparable = targetGate.withLock {
            if (!retirement.installedResources || retirementAdmissionClosed || generationFenced ||
                frameAdmissionDisposition != TargetFrameAdmissionDisposition.Open
            ) {
                return@withLock false
            }
            epoch = frameAdmissionEpoch
            predecessor = frameAdmissionPredecessor
            true
        }
        if (!preparable) return null
        val sealedFact = TargetFrameAdmissionSealedFact.create(
            targetOwner,
            constructionProof,
            identity,
            epoch,
            retainedReconfigurationIdentity,
            predecessor,
        )
        return targetGate.withLock {
            if (!retirement.installedResources || retirementAdmissionClosed || generationFenced ||
                frameAdmissionDisposition != TargetFrameAdmissionDisposition.Open ||
                frameAdmissionEpoch != epoch || frameAdmissionPredecessor !== predecessor
            ) {
                return@withLock null
            }
            ports.rejectReservedFrameForSealLocked()
            pendingSourceLatch.seal(epoch)
            frameAdmissionDisposition = TargetFrameAdmissionDisposition.Sealed
            frameAdmissionSealedFact = sealedFact
            frameQuiescedFact = null
            sealedFact
        }
    }

    internal fun claimFrameQuiesced(sealedFact: TargetFrameAdmissionSealedFact): TargetFrameQuiescedFact? {
        val candidate = TargetFrameQuiescedFact.create(targetOwner, constructionProof, sealedFact)
        return targetGate.withLock {
            if (frameAdmissionDisposition != TargetFrameAdmissionDisposition.Sealed ||
                frameAdmissionSealedFact !== sealedFact || sealedFact.targetIdentity !== identity ||
                frameAdmissionPredecessor != null || ports.hasGlFramePortLocked || ports.leaseCountLocked != 0
            ) {
                return@withLock null
            }
            frameQuiescedFact ?: candidate.also {
                frameQuiescedFact = it
            }
        }
    }

    internal fun reserveRetainedGlMutation(
        quiescedFact: TargetFrameQuiescedFact,
        retainedReconfigurationIdentity: Long,
    ): TargetRetainedGlReservationResult {
        require(retainedReconfigurationIdentity > 0L)
        val candidate = TargetRetainedGlReservation.create(
            targetOwner,
            constructionProof,
            this,
            quiescedFact.targetIdentity,
            quiescedFact,
            retainedReconfigurationIdentity,
        )
        return targetGate.withLock {
            val inertReason = when {
                frameAdmissionDisposition == TargetFrameAdmissionDisposition.RetirementClosed ->
                    TargetRetainedGlInertReason.RetirementClosed
                !isExactCurrentQuiescenceLocked(quiescedFact) ->
                    TargetRetainedGlInertReason.StaleQuiescence
                else -> null
            }
            if (inertReason != null) return@withLock candidate.inertFact(inertReason)
            val existingReservation = retainedGlReservation
            if (existingReservation != null &&
                (existingReservation.quiescedFact !== quiescedFact ||
                        retainedGlSettledFact !== existingReservation.settledFact ||
                        existingReservation.retainedReconfigurationIdentity == retainedReconfigurationIdentity)
            ) {
                return@withLock candidate.inertFact(TargetRetainedGlInertReason.ReservationAlreadyPresent)
            }
            retainedGlReservation = candidate
            retainedGlAdmittedFact = null
            retainedGlSettledFact = null
            candidate.reservedFact
        }
    }

    internal fun enterRetainedGlMutation(
        reservedFact: TargetRetainedGlReservedFact,
    ): TargetRetainedGlEntryResult {
        val reservation = reservedFact.reservation
        return targetGate.withLock {
            val inertReason = when {
                frameAdmissionDisposition == TargetFrameAdmissionDisposition.RetirementClosed ->
                    TargetRetainedGlInertReason.RetirementClosed
                !reservation.matchesTarget(this) || reservation.targetIdentity !== identity ||
                        reservation.targetGeneration != generation || reservedFact !== reservation.reservedFact ||
                        retainedGlReservation !== reservation ||
                        !isExactCurrentQuiescenceLocked(reservation.quiescedFact) ->
                    TargetRetainedGlInertReason.StaleQuiescence
                retainedGlAdmittedFact != null || retainedGlSettledFact != null ->
                    TargetRetainedGlInertReason.RepeatedEntry
                else -> null
            }
            if (inertReason != null) return@withLock reservation.inertFact(inertReason)
            retainedGlAdmittedFact = reservation.admittedFact
            reservation.admittedFact
        }
    }

    internal fun settleRetainedGlMutation(
        admittedFact: TargetRetainedGlAdmittedFact,
    ): TargetRetainedGlSettlementResult {
        val reservation = admittedFact.reservation
        return targetGate.withLock {
            val exactReservation = reservation.matchesTarget(this) && reservation.targetIdentity === identity &&
                    reservation.targetGeneration == generation && admittedFact === reservation.admittedFact &&
                    retainedGlReservation === reservation && retainedGlAdmittedFact === admittedFact
            if (!exactReservation) {
                return@withLock reservation.settlementRejectedFact
            }
            val settled = retainedGlSettledFact
            if (settled != null) return@withLock settled
            retainedGlSettledFact = reservation.settledFact
            reservation.settledFact
        }
    }

    private fun isExactCurrentQuiescenceLocked(quiescedFact: TargetFrameQuiescedFact): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return frameAdmissionDisposition == TargetFrameAdmissionDisposition.Sealed &&
                frameQuiescedFact === quiescedFact && frameAdmissionSealedFact === quiescedFact.sealedFact &&
                quiescedFact.targetIdentity === identity && quiescedFact.targetGeneration == generation &&
                quiescedFact.sealedEpoch == frameAdmissionEpoch &&
                quiescedFact.originRetainedReconfigurationIdentity ==
                frameAdmissionSealedFact?.originRetainedReconfigurationIdentity
    }

    internal fun reopenFrameAdmission(
        quiescedFact: TargetFrameQuiescedFact,
        retainedReconfigurationIdentity: Long,
    ): TargetFrameAdmissionReopenResult {
        require(retainedReconfigurationIdentity > 0L)
        var currentEpoch = 0L
        var rejection = TargetFrameAdmissionReopenRejectionReason.StaleFact
        val reservable = targetGate.withLock {
            currentEpoch = frameAdmissionEpoch
            rejection = when {
                frameAdmissionDisposition == TargetFrameAdmissionDisposition.RetirementClosed ->
                    TargetFrameAdmissionReopenRejectionReason.RetirementClosed
                frameAdmissionDisposition != TargetFrameAdmissionDisposition.Sealed ||
                        frameQuiescedFact !== quiescedFact ||
                        frameAdmissionSealedFact !== quiescedFact.sealedFact ->
                    TargetFrameAdmissionReopenRejectionReason.StaleFact
                retainedGlReservation != null && retainedGlSettledFact == null ->
                    TargetFrameAdmissionReopenRejectionReason.RetainedGlMutationUnsettled
                !retirement.installedResources || generationFenced ||
                        producerState != TargetProducerState.ProducerAttached ->
                    TargetFrameAdmissionReopenRejectionReason.TargetNotReady
                currentEpoch == Long.MAX_VALUE -> TargetFrameAdmissionReopenRejectionReason.EpochExhausted
                else -> return@withLock true
            }
            false
        }
        if (!reservable) {
            return TargetFrameAdmissionReopenRejectedFact.create(
                targetOwner,
                constructionProof,
                identity,
                quiescedFact,
                rejection,
            )
        }
        val reopenedEpoch = currentEpoch + 1L
        val reopened = TargetFrameAdmissionReopenedFact.create(
            targetOwner,
            constructionProof,
            quiescedFact,
            reopenedEpoch,
            retainedReconfigurationIdentity,
        )
        val reopenedPendingSourceEpoch = pendingSourceLatch.prepareEpoch(reopenedEpoch)
        var secondPhaseRejection = TargetFrameAdmissionReopenRejectionReason.StaleFact
        val applied = targetGate.withLock {
            val rejection = when {
                frameAdmissionDisposition == TargetFrameAdmissionDisposition.RetirementClosed ->
                    TargetFrameAdmissionReopenRejectionReason.RetirementClosed
                frameAdmissionDisposition != TargetFrameAdmissionDisposition.Sealed ||
                        frameAdmissionEpoch != currentEpoch || frameQuiescedFact !== quiescedFact ||
                        frameAdmissionSealedFact !== quiescedFact.sealedFact ->
                    TargetFrameAdmissionReopenRejectionReason.StaleFact
                retainedGlReservation != null && retainedGlSettledFact == null ->
                    TargetFrameAdmissionReopenRejectionReason.RetainedGlMutationUnsettled
                !retirement.installedResources || generationFenced ||
                        producerState != TargetProducerState.ProducerAttached ->
                    TargetFrameAdmissionReopenRejectionReason.TargetNotReady
                else -> null
            }
            if (rejection != null) {
                secondPhaseRejection = rejection
                return@withLock false
            }
            frameAdmissionEpoch = reopenedEpoch
            frameAdmissionDisposition = TargetFrameAdmissionDisposition.Open
            frameAdmissionPredecessor = null
            frameAdmissionSealedFact = null
            frameQuiescedFact = null
            retainedGlReservation = null
            retainedGlAdmittedFact = null
            retainedGlSettledFact = null
            pendingSourceLatch.reopen(currentEpoch, reopenedPendingSourceEpoch)
            true
        }
        return if (applied) {
            reopened
        } else {
            TargetFrameAdmissionReopenRejectedFact.create(
                targetOwner,
                constructionProof,
                identity,
                quiescedFact,
                secondPhaseRejection,
            )
        }
    }

    internal fun claimPendingSource(
        expectedTargetIdentity: TargetIdentity,
        expectedFrameAdmissionEpoch: Long,
    ): TargetPendingSourceClaim? =
        pendingSourceLatch.claim(expectedTargetIdentity, expectedFrameAdmissionEpoch)

    internal fun commitPendingSource(
        claim: TargetPendingSourceClaim,
    ): TargetPendingSourceCommitResult = pendingSourceLatch.commit(claim)

    internal fun closeRetirementAdmission(): TargetRetirementAdmissionClosedFact? = targetGate.withLock {
        if (!retirement.installedResources || retirementAdmissionClosed) {
            return@withLock null
        }
        ports.rejectReservedFrameForSealLocked()
        frameAdmissionDisposition = TargetFrameAdmissionDisposition.RetirementClosed
        frameAdmissionSealedFact = null
        frameQuiescedFact = null
        retirementAdmissionClosed = true
        sourceSignalsAccepted = false
        pendingSourceLatch.closeForRetirement()
        precreatedRetirementAdmissionClosedFact.also { retirementAdmissionClosedFact = it }
    }

    internal fun recordEnteredTargetWorkDrained(
        admissionClosedFact: TargetRetirementAdmissionClosedFact,
    ): TargetWorkDrainedFact? = targetGate.withLock {
        if (!retirement.installedResources || !retirementAdmissionClosed || enteredTargetWorkDrained ||
            retirementAdmissionClosedFact !== admissionClosedFact ||
            admissionClosedFact.targetIdentity !== identity ||
            ports.hasGlFramePortLocked || ports.leaseCountLocked != 0
        ) {
            return@withLock null
        }
        enteredTargetWorkDrained = true
        precreatedWorkDrainedFact.also { workDrainedFact = it }
    }

    internal fun fenceGeneration(workDrainedFact: TargetWorkDrainedFact): TargetGenerationFencedFact? = targetGate.withLock {
        if (!retirement.installedResources || !retirementAdmissionClosed || !enteredTargetWorkDrained || generationFenced ||
            this.workDrainedFact !== workDrainedFact || workDrainedFact.targetIdentity !== identity
        ) {
            return@withLock null
        }
        sourceSignalsAccepted = false
        generationFenced = true
        pendingSourceLatch.fenceGeneration()
        precreatedGenerationFencedFact.also { generationFencedFact = it }
    }

    internal fun registerListenerRemovalPort(operationIdentity: Long): TargetPorts.AndroidListenerRemovalPort? =
        ports.registerListenerRemovalPort(operationIdentity)

    internal fun registerSetSurfaceDetachPort(operationIdentity: Long): TargetPorts.AndroidDetachPort? =
        ports.registerSetSurfaceDetachPort(operationIdentity)

    internal fun registerVirtualDisplayReleasePort(operationIdentity: Long): TargetPorts.AndroidDetachPort? =
        ports.registerVirtualDisplayReleasePort(operationIdentity)

    internal fun prepareInitialVirtualDisplayReleasePort(
        operationIdentity: Long,
    ): TargetPorts.DetachedInitialReleasePort =
        ports.prepareInitialVirtualDisplayReleasePort(operationIdentity)

    internal fun commitInitialVirtualDisplayReleasePort(
        candidate: TargetPorts.DetachedInitialReleasePort,
        binding: AndroidTargetOperationBinding,
    ): TargetInitialReleasePortCommitResult? =
        ports.commitInitialVirtualDisplayReleasePort(candidate, binding)

    internal fun prepareStagedDetachPort(
        operationIdentity: Long,
        retargetOccurrenceIdentity: Long,
    ): TargetPorts.StagedAndroidDetachPort =
        ports.prepareStagedDetachPort(operationIdentity, retargetOccurrenceIdentity)

    internal fun commitStagedDetachPort(
        stagedPort: TargetPorts.StagedAndroidDetachPort,
    ): TargetStagedDetachPortCommitResult? = ports.commitStagedDetachPort(stagedPort)

    internal fun prepareSurfaceReleaseOperation(
        readinessFact: TargetSurfaceReleaseReadyFact,
    ): TargetRetirement.SurfaceReleaseOperation? =
        retirement.surfaceReleaseOperation.takeIf { it.prepareInstalled(readinessFact) }

    internal fun prepareUninstalledSurfaceReleaseOperation(
        constructionSettled: Boolean,
    ): TargetRetirement.SurfaceReleaseOperation? =
        retirement.surfaceReleaseOperation.takeIf { it.prepareUninstalled(constructionSettled) }

    internal fun signalSurfaceReleaseSettlement() {
        settlementSignal.signal()
    }

    internal fun prepareTargetScopeDestructionGraph(
        targetIdentity: GlFiniteOperationIdentity,
        namespaceIdentity: GlFiniteOperationIdentity,
    ): TargetRetirement.TargetScopeDestructionGraph? =
        retirement.prepareTargetScopeDestructionGraph(targetIdentity, namespaceIdentity)

    internal fun applyTargetScopeDestructionGraph(graph: TargetRetirement.TargetScopeDestructionGraph): Boolean =
        retirement.applyTargetScopeDestructionGraph(graph)

    internal fun applyTargetScopeNamespaceGraph(graph: TargetRetirement.TargetScopeDestructionGraph): Boolean =
        retirement.applyTargetScopeNamespaceGraph(graph)

    internal fun adoptConstructionOesTextureName(oesTextureName: Int) {
        retirement.adoptConstructionOesTextureName(oesTextureName)
    }

    internal fun adoptConstructionSurfaceTexture(surfaceTexture: SurfaceTexture) {
        retirement.adoptConstructionSurfaceTexture(surfaceTexture)
    }

    internal fun adoptConstructionSurface(surface: Surface) {
        retirement.adoptConstructionSurface(surface)
    }

    internal fun finishConstructionOwnership(installed: Boolean) {
        retirement.finishConstructionOwnership(installed)
        if (installed) ports.installPrecreatedListenerInstallationPortLocked()
    }

    internal fun settleConstructionResourceObligations(): Boolean =
        retirement.settleConstructionResourceObligations()

    internal fun convertCleanupBaseOccurrencesForTerminal(): TargetTerminalCleanupBaseOccurrencesFact? =
        retirement.convertCleanupBaseOccurrencesForTerminal()

    internal fun convertSelectedNamespaceOccurrenceForTerminal():
            TargetTerminalCleanupNamespaceOccurrenceFact? =
        retirement.convertSelectedNamespaceOccurrenceForTerminal()

    internal fun selectedNamespaceOccurrenceForSubmission(
        graph: TargetRetirement.TargetScopeDestructionGraph,
        terminalFact: TargetTerminalCleanupNamespaceOccurrenceFact?,
    ): OperationOccurrence<GlDestructionEvidence>? =
        retirement.selectedNamespaceOccurrenceForSubmission(graph, terminalFact)

    internal fun claimListenerRemovalIdentityLocked(operationIdentity: Long): TargetRegistrationClaim {
        check(targetGate.isHeldByCurrentThread)
        if (!retirement.installedResources || !generationFenced ||
            listenerInstallationObligation != TargetListenerInstallationObligation.Installed
        ) {
            return TargetRegistrationClaim.Denied
        }
        if (expectedListenerRemovalOperationIdentity != NO_OPERATION_IDENTITY) {
            return if (expectedListenerRemovalOperationIdentity == operationIdentity) {
                TargetRegistrationClaim.Existing
            } else {
                TargetRegistrationClaim.Denied
            }
        }
        if (operationIdentity == expectedSetSurfaceDetachOperationIdentity || operationIdentity == expectedVirtualDisplayReleaseOperationIdentity) {
            return TargetRegistrationClaim.Denied
        }
        expectedListenerRemovalOperationIdentity = operationIdentity
        return TargetRegistrationClaim.New
    }

    internal fun canRegisterListenerInstallationLocked(operationIdentity: Long, portAlreadyRegistered: Boolean): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return retirement.installedResources && !retirementAdmissionClosed && !generationFenced &&
                listenerInstallationObligation == TargetListenerInstallationObligation.PendingInstallation &&
                !portAlreadyRegistered && operationIdentity == listenerInstallationOperationIdentity
    }

    internal fun claimListenerInstallationRequestLocked(
        exactPort: Boolean,
        operationIdentity: Long,
    ): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!exactPort || operationIdentity != listenerInstallationOperationIdentity ||
            !retirement.installedResources || retirementAdmissionClosed || generationFenced ||
            listenerInstallationRequestAdmission != TargetListenerInstallationRequestAdmission.AwaitingRequest ||
            listenerInstallationObligation != TargetListenerInstallationObligation.PendingInstallation ||
            listenerInstalled || sourceSignalsAccepted
        ) {
            return false
        }
        listenerInstallationRequestAdmission = TargetListenerInstallationRequestAdmission.Claimed
        return true
    }

    internal fun commitListenerInstallationBindingLocked(
        exactClaim: Boolean,
        operationIdentity: Long,
    ): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!exactClaim || operationIdentity != listenerInstallationOperationIdentity ||
            !retirement.installedResources || retirementAdmissionClosed || generationFenced ||
            listenerInstallationRequestAdmission != TargetListenerInstallationRequestAdmission.Claimed ||
            listenerInstallationObligation != TargetListenerInstallationObligation.PendingInstallation ||
            listenerInstalled || sourceSignalsAccepted
        ) {
            return false
        }
        listenerInstallationRequestAdmission = TargetListenerInstallationRequestAdmission.Bound
        return true
    }

    internal fun isListenerInstallationRequestBoundLocked(operationIdentity: Long): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return operationIdentity == listenerInstallationOperationIdentity &&
                listenerInstallationRequestAdmission == TargetListenerInstallationRequestAdmission.Bound
    }

    internal fun applyListenerInstallationLocked(exactPort: Boolean, operationIdentity: Long): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!exactPort || operationIdentity != listenerInstallationOperationIdentity || listenerInstalled || generationFenced ||
            listenerInstallationObligation != TargetListenerInstallationObligation.PendingInstallation ||
            listenerInstallationRequestAdmission != TargetListenerInstallationRequestAdmission.Bound
        ) {
            return false
        }
        listenerInstalled = true
        listenerInstallationObligation = TargetListenerInstallationObligation.Installed
        sourceSignalsAccepted = true
        return true
    }

    internal fun canApplyListenerNeverInstalledLocked(fact: TargetListenerNeverInstalledFact): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return retirement.installedResources && retirementAdmissionClosed && enteredTargetWorkDrained &&
                generationFenced &&
                listenerInstallationRequestAdmission == TargetListenerInstallationRequestAdmission.Bound &&
                listenerInstallationObligation == TargetListenerInstallationObligation.PendingInstallation &&
                !listenerInstalled && !sourceSignalsAccepted && listenerNeverInstalledFact == null &&
                fact.targetIdentity === identity && fact.targetGeneration == generation &&
                fact.operationIdentity == listenerInstallationOperationIdentity &&
                expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY && !listenerRemoved &&
                !listenerRemovalSettled && observedListenerSentinelOperationIdentity == NO_OPERATION_IDENTITY &&
                producerState == TargetProducerState.AwaitingEvidence
    }

    internal fun recordListenerNeverInstalledLocked(fact: TargetListenerNeverInstalledFact) {
        check(targetGate.isHeldByCurrentThread)
        check(canApplyListenerNeverInstalledLocked(fact))
        listenerInstallationObligation = TargetListenerInstallationObligation.NeverInstalled
        listenerNeverInstalledFact = fact
    }

    internal fun canRegisterProducerPortLocked(portAlreadyRegistered: Boolean): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return retirement.installedResources &&
                !retirementAdmissionClosed &&
                listenerInstallationObligation == TargetListenerInstallationObligation.Installed &&
                listenerInstalled && !generationFenced && !portAlreadyRegistered &&
                producerState == TargetProducerState.AwaitingEvidence
    }

    internal fun listenerForInstallationPortLocked(): SurfaceTexture.OnFrameAvailableListener {
        check(targetGate.isHeldByCurrentThread)
        check(retirement.installedResources && !generationFenced && !listenerInstalled &&
                listenerInstallationRequestAdmission == TargetListenerInstallationRequestAdmission.Bound &&
                listenerInstallationObligation == TargetListenerInstallationObligation.PendingInstallation)
        return frameAvailableListener
    }

    internal fun applyProducerEvidenceLocked(
        evidence: TargetProducerEvidence,
        exactEvidence: Boolean,
        portOperationIdentity: Long,
        portOperationKind: TargetProducerOperationKind,
        portProvenance: TargetOperationProvenance,
    ): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!exactEvidence ||
            evidence.targetGeneration != generation ||
            evidence.targetIdentity !== identity ||
            evidence.operationIdentity != portOperationIdentity ||
            evidence.operationKind != portOperationKind ||
            evidence.provenance !== portProvenance ||
            generationFenced && !retirementAdmissionClosed ||
            producerState != TargetProducerState.AwaitingEvidence
        ) {
            return false
        }
        producerState = TargetProducerState.ProducerAttached
        return true
    }

    internal fun applyNoProducerEvidenceLocked(
        evidence: TargetNoProducerEvidence,
        exactEvidence: Boolean,
        portOperationIdentity: Long,
        portOperationKind: TargetProducerOperationKind,
        portProvenance: TargetOperationProvenance,
    ): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!exactEvidence ||
            evidence.targetGeneration != generation ||
            evidence.targetIdentity !== identity ||
            evidence.operationIdentity != portOperationIdentity ||
            evidence.operationKind != portOperationKind ||
            evidence.provenance !== portProvenance ||
            generationFenced && !retirementAdmissionClosed ||
            producerState != TargetProducerState.AwaitingEvidence ||
            evidence.reason == TargetNoProducerReason.Inapplicable && !retirementAdmissionClosed ||
            (evidence.reason == TargetNoProducerReason.ReturnedWithoutProducer &&
                    evidence.operationKind != TargetProducerOperationKind.VirtualDisplayCreation)
        ) {
            return false
        }
        producerState = TargetProducerState.NoProducer
        return true
    }

    internal fun claimDetachIdentityLocked(operationIdentity: Long, detachKind: TargetProducerDetachKind): TargetRegistrationClaim {
        check(targetGate.isHeldByCurrentThread)
        val producerCanDetach = producerState == TargetProducerState.ProducerAttached
        if (!retirement.installedResources || !generationFenced || !producerCanDetach) {
            return TargetRegistrationClaim.Denied
        }
        val expectedIdentity = when (detachKind) {
            TargetProducerDetachKind.VirtualDisplayDetach ->
                expectedSetSurfaceDetachOperationIdentity

            TargetProducerDetachKind.VirtualDisplayRelease ->
                expectedVirtualDisplayReleaseOperationIdentity
        }
        if (expectedIdentity != NO_OPERATION_IDENTITY) {
            return if (expectedIdentity == operationIdentity) {
                TargetRegistrationClaim.Existing
            } else {
                TargetRegistrationClaim.Denied
            }
        }
        if (operationIdentity == expectedListenerRemovalOperationIdentity ||
            operationIdentity == expectedSetSurfaceDetachOperationIdentity ||
            operationIdentity == expectedVirtualDisplayReleaseOperationIdentity
        ) {
            return TargetRegistrationClaim.Denied
        }
        when (detachKind) {
            TargetProducerDetachKind.VirtualDisplayDetach ->
                expectedSetSurfaceDetachOperationIdentity = operationIdentity

            TargetProducerDetachKind.VirtualDisplayRelease ->
                expectedVirtualDisplayReleaseOperationIdentity = operationIdentity
        }
        return TargetRegistrationClaim.New
    }

    /**
     * Retires only a committed port whose Android post definitely never entered. This deliberately
     * preserves ProducerAttached and therefore proves no physical VirtualDisplay detach.
     */
    internal fun retireDefinitelyUnenteredDetachPortLocked(
        operationIdentity: Long,
        detachKind: TargetProducerDetachKind,
    ): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!retirement.installedResources || !generationFenced ||
            producerState != TargetProducerState.ProducerAttached
        ) {
            return false
        }
        return when (detachKind) {
            TargetProducerDetachKind.VirtualDisplayDetach -> {
                if (expectedSetSurfaceDetachOperationIdentity != operationIdentity) return false
                expectedSetSurfaceDetachOperationIdentity = NO_OPERATION_IDENTITY
                true
            }

            TargetProducerDetachKind.VirtualDisplayRelease -> {
                if (expectedVirtualDisplayReleaseOperationIdentity != operationIdentity) return false
                expectedVirtualDisplayReleaseOperationIdentity = NO_OPERATION_IDENTITY
                true
            }
        }
    }

    internal fun recordListenerRemovalReturnLocked(exactPort: Boolean, operationIdentity: Long): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!retirement.installedResources || !generationFenced ||
            expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY ||
            !exactPort || operationIdentity != expectedListenerRemovalOperationIdentity ||
            listenerRemoved
        ) {
            return false
        }
        listenerRemoved = true
        return true
    }

    internal fun applyListenerRemovalSettlementLocked(exactPort: Boolean): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!exactPort || !listenerRemoved || listenerRemovalSettled) return false
        listenerRemovalSettled = true
        return true
    }

    internal fun applyListenerSentinelObservedLocked(operationIdentity: Long): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!retirement.installedResources || !generationFenced || !listenerRemoved ||
            expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY ||
            operationIdentity != expectedListenerRemovalOperationIdentity ||
            observedListenerSentinelOperationIdentity != NO_OPERATION_IDENTITY
        ) {
            return false
        }
        observedListenerSentinelOperationIdentity = operationIdentity
        return true
    }

    internal fun applyProducerDetachReceiptLocked(
        receipt: TargetProducerDetachReceipt,
        exactPort: Boolean,
        portOperationIdentity: Long,
        portDetachKind: TargetProducerDetachKind,
        portProvenance: TargetOperationProvenance,
    ): Boolean {
        check(targetGate.isHeldByCurrentThread)
        val expectedIdentity = when (receipt.detachKind) {
            TargetProducerDetachKind.VirtualDisplayDetach ->
                expectedSetSurfaceDetachOperationIdentity

            TargetProducerDetachKind.VirtualDisplayRelease ->
                expectedVirtualDisplayReleaseOperationIdentity
        }
        if (!retirement.installedResources || !generationFenced || !exactPort ||
            receipt.targetGeneration != generation ||
            receipt.targetIdentity !== identity ||
            receipt.operationIdentity != expectedIdentity ||
            receipt.operationIdentity != portOperationIdentity ||
            receipt.detachKind != portDetachKind ||
            receipt.provenance !== portProvenance ||
            producerState != TargetProducerState.ProducerAttached
        ) {
            return false
        }
        producerState = TargetProducerState.Detached
        return true
    }

    private fun publishSourceAvailable(callbackTargetIdentity: TargetIdentity) {
        val publishedFact = targetGate.withLock {
            if (!retirement.installedResources || !sourceSignalsAccepted || retirementAdmissionClosed ||
                generationFenced || callbackTargetIdentity !== identity || !callbackTargetIdentity.matches(this)
            ) {
                return@withLock null
            }
            pendingSourceLatch.offer(callbackTargetIdentity)
        }
        if (publishedFact != null) sourceSignal.signal(publishedFact)
    }

    internal companion object {
        private const val NO_OPERATION_IDENTITY: Long = 0L

        internal fun precreateCandidate(
            targetOwner: TargetOwner,
            constructionProof: () -> Unit,
            targetGate: ReentrantLock,
            requestedIdentity: TargetRequestedIdentity,
            plan: TargetPlan,
            generation: Long,
            listenerInstallationOperationIdentity: Long,
            sourceSignal: TargetSourceSignal,
            clock: EngineClock,
            settlementSignal: SettlementSignal,
            surfaceReleaseOperationIdentity: Long,
            surfaceReleaseDeadlineIdentity: Long,
            surfaceReleaseDeadlineWakeGeneration: Long,
            surfaceReleaseTimeoutCause: Throwable,
            targetDestructionIdentity: GlFiniteOperationIdentity,
            namespaceDestructionIdentity: GlFiniteOperationIdentity,
        ): CurrentTarget =
            CurrentTarget(
                targetOwner,
                constructionProof,
                targetGate,
                requestedIdentity,
                plan,
                generation,
                listenerInstallationOperationIdentity,
                sourceSignal,
                clock,
                settlementSignal,
                surfaceReleaseOperationIdentity,
                surfaceReleaseDeadlineIdentity,
                surfaceReleaseDeadlineWakeGeneration,
                surfaceReleaseTimeoutCause,
                targetDestructionIdentity,
                namespaceDestructionIdentity,
            )
    }
}
