package io.screenstream.engine.internal.target

import android.graphics.SurfaceTexture
import android.view.Surface
import io.screenstream.engine.internal.TargetQuarantineChild
import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.gl.GlOperationSuccessReceipt
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val latestPendingSource: AtomicBoolean = AtomicBoolean(false)
    private val currentnessVersion: AtomicLong = AtomicLong(1L)
    internal var expectedListenerRemovalOperationIdentity: Long = NO_OPERATION_IDENTITY
        private set
    internal var expectedSetSurfaceDetachOperationIdentity: Long = NO_OPERATION_IDENTITY
        private set
    internal var expectedVirtualDisplayReleaseOperationIdentity: Long = NO_OPERATION_IDENTITY
        private set
    internal var armedListenerSentinelOperationIdentity: Long = NO_OPERATION_IDENTITY
        private set
    internal var observedListenerSentinelOperationIdentity: Long = NO_OPERATION_IDENTITY
        private set
    internal var producerState: TargetProducerState = TargetProducerState.AwaitingEvidence
        private set
    internal var listenerInstalled: Boolean = false
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
    private var frameAdmissionDisposition: TargetFrameAdmissionDisposition = TargetFrameAdmissionDisposition.Open
    private var frameAdmissionEpoch: Long = 1L
    private var frameAdmissionPredecessor: TargetEnteredFact? = null
    private var frameAdmissionSealedFact: TargetFrameAdmissionSealedFact? = null
    private var frameQuiescedFact: TargetFrameQuiescedFact? = null
    private var retainedGlReservation: TargetRetainedGlReservation? = null
    private var retainedGlAdmittedFact: TargetRetainedGlAdmittedFact? = null
    private var retainedGlSettledFact: TargetRetainedGlSettledFact? = null

    private val frameAvailableListener: SurfaceTexture.OnFrameAvailableListener =
        SurfaceTexture.OnFrameAvailableListener {
            publishSourceAvailable()
        }

    private val listenerSentinelRunnable: Runnable = Runnable {
        publishListenerSentinel()
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

    private val precreatedSourceAvailableFact: TargetSourceAvailableFact =
        TargetSourceAvailableFact.create(targetOwner, constructionProof, identity)
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

    internal val hasPendingSource: Boolean
        get() = targetGate.withLock {
            retirement.installedResources &&
                    !generationFenced &&
                    latestPendingSource.get()
        }

    internal val activeLeaseCount: Int
        get() = ports.activeLeaseCount

    internal val isProducerAttachmentPermitted: Boolean
        get() = targetGate.withLock {
            retirement.installedResources && listenerInstalled && !generationFenced &&
                    producerState == TargetProducerState.AwaitingEvidence && !ports.hasProducerPortLocked
        }

    internal val currentProducerState: TargetProducerState
        get() = targetGate.withLock { producerState }

    internal fun currentnessFact(): TargetCurrentnessFact {
        var listener = false
        var producer = TargetProducerState.AwaitingEvidence
        var fenced = false
        var admissionEpoch = 0L
        var sealedFact: TargetFrameAdmissionSealedFact? = null
        var quiescedFact: TargetFrameQuiescedFact? = null
        var admissionRetirementClosed = false
        var version = 0L
        var versionExhausted = false
        targetGate.withLock {
            listener = listenerInstalled
            producer = producerState
            fenced = generationFenced
            admissionEpoch = frameAdmissionEpoch
            sealedFact = frameAdmissionSealedFact
            quiescedFact = frameQuiescedFact
            admissionRetirementClosed = frameAdmissionDisposition == TargetFrameAdmissionDisposition.RetirementClosed
            version = currentnessVersion.get()
            versionExhausted = version == Long.MAX_VALUE
        }
        return TargetCurrentnessFact.create(
            targetOwner,
            constructionProof,
            identity,
            plan,
            listener,
            producer,
            fenced,
            admissionEpoch,
            sealedFact,
            quiescedFact,
            admissionRetirementClosed,
            version,
            versionExhausted,
        )
    }

    internal fun isCurrentnessVersion(expected: Long): Boolean {
        val current = currentnessVersion.get()
        return current != Long.MAX_VALUE && current == expected
    }

    internal val currentnessVersionExhausted: Boolean
        get() = currentnessVersion.get() == Long.MAX_VALUE

    internal fun surfaceReleaseReadyFact(): TargetSurfaceReleaseReadyFact? = targetGate.withLock {
        if (!isSurfaceReleaseReadyLocked()) return@withLock null
        val fencedFact = generationFencedFact ?: return@withLock null
        precreatedSurfaceReleaseReadyFact.takeIf { it.generationFencedFact === fencedFact }
    }

    private fun isSurfaceReleaseReadyLocked(): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return retirement.installedResources &&
                retirementAdmissionClosed &&
                enteredTargetWorkDrained &&
                generationFenced &&
                listenerRemoved &&
                listenerRemovalSettled &&
                expectedListenerRemovalOperationIdentity != NO_OPERATION_IDENTITY &&
                observedListenerSentinelOperationIdentity == expectedListenerRemovalOperationIdentity &&
                (producerState == TargetProducerState.NoProducer ||
                        producerState == TargetProducerState.Detached) &&
                !ports.hasGlFramePortLocked &&
                ports.leaseCountLocked == 0
    }

    internal fun installedConstructionFact(
        requester: TargetOwner,
        proof: () -> Unit,
        constructionOperationIdentity: Long,
        constructionReceipt: GlOperationSuccessReceipt,
    ): TargetConstructionInstalledFact? {
        if (!matchesConstructionProof(requester, proof)) return null
        require(constructionOperationIdentity > 0L)
        val listenerPort = registerListenerInstallationPort(listenerInstallationOperationIdentity) ?: return null
        val exactInstalled = targetGate.withLock {
            retirement.installedResources && !generationFenced && listenerPort.targetIdentity === identity
        }
        if (!exactInstalled) return null
        return TargetConstructionInstalledFact.create(
            targetOwner = targetOwner,
            constructionProof = constructionProof,
            requestedIdentity = requestedIdentity,
            targetIdentity = identity,
            constructionOperationIdentity = constructionOperationIdentity,
            constructionReceipt = constructionReceipt,
            plan = plan,
            listenerInstallationPort = listenerPort,
        )
    }

    internal fun constructionFailureFact(
        requester: TargetOwner,
        proof: () -> Unit,
        constructionOperationIdentity: Long,
        disposition: TargetConstructionFoldDisposition,
        returnDisposition: OperationReturnDisposition,
        failure: Throwable?,
        stage: TargetConstructionStage,
    ): TargetConstructionFailureFact? {
        if (!matchesConstructionProof(requester, proof)) return null
        val exactFailure = targetGate.withLock {
            !retirement.installedResources && disposition != TargetConstructionFoldDisposition.Install
        }
        if (!exactFailure) return null
        return TargetConstructionFailureFact.create(
            targetOwner = targetOwner,
            constructionProof = constructionProof,
            requestedIdentity = requestedIdentity,
            targetIdentity = identity,
            constructionOperationIdentity = constructionOperationIdentity,
            disposition = disposition,
            cleanupTarget = this,
            returnDisposition = returnDisposition,
            failure = failure,
            stage = stage,
        )
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

    internal fun registerListenerInstallationPort(operationIdentity: Long): TargetPorts.AndroidListenerInstallationPort? =
        ports.registerListenerInstallationPort(operationIdentity)

    internal fun applyListenerInstallationReceipt(
        port: TargetPorts.AndroidListenerInstallationPort,
        operation: OperationOccurrence<*>,
    ): TargetListenerInstalledFact? = ports.applyListenerInstallationReceipt(port, operation)

    internal fun registerProducerPort(
        operationIdentity: Long,
        operationKind: TargetProducerOperationKind,
    ): TargetPorts.AndroidSurfacePort? =
        ports.registerProducerPort(operationIdentity, operationKind)

    internal fun prepareStagedProducerPort(
        operationIdentity: Long,
        retargetOccurrenceIdentity: Long,
    ): TargetPorts.StagedAndroidSurfacePort =
        ports.prepareStagedProducerPort(operationIdentity, retargetOccurrenceIdentity)

    internal fun prepareStagedProducerApplicationCandidates(
        stagedPort: TargetPorts.StagedAndroidSurfacePort,
        operation: OperationOccurrence<*>,
    ): Boolean = ports.prepareStagedProducerApplicationCandidates(stagedPort, operation)

    internal fun commitStagedProducerPort(
        stagedPort: TargetPorts.StagedAndroidSurfacePort,
    ): TargetStagedProducerPortCommitResult? = ports.commitStagedProducerPort(stagedPort)

    internal fun markStagedProducerPostAcceptedOrAmbiguous(
        stagedPort: TargetPorts.StagedAndroidSurfacePort,
    ): TargetStagedPortPostExposedFact? = ports.markStagedProducerPostAcceptedOrAmbiguous(stagedPort)

    internal fun retireCommittedStagedProducerPortDefinitelyUnentered(
        stagedPort: TargetPorts.StagedAndroidSurfacePort,
    ): TargetStagedProducerPortRetiredFact? =
        ports.retireCommittedStagedProducerPortDefinitelyUnentered(stagedPort)

    internal fun retireUnusedStagedProducerPortTerminalInapplicable(
        stagedPort: TargetPorts.StagedAndroidSurfacePort,
    ): TargetStagedProducerPortRetiredFact? =
        ports.retireUnusedStagedProducerPortTerminalInapplicable(stagedPort)

    internal fun applyStagedProducerTerminalApplication(
        stagedPort: TargetPorts.StagedAndroidSurfacePort,
    ): TargetProducerApplicationFact? = ports.applyStagedProducerTerminalApplication(stagedPort)

    internal fun producerApplicationCandidateAfterSettlement(
        port: TargetPorts.AndroidSurfacePort,
        operation: OperationOccurrence<*>,
    ): TargetProducerApplicationCandidate? =
        ports.producerApplicationCandidateAfterSettlement(port, operation)

    internal fun applyProducerApplication(candidate: TargetProducerApplicationCandidate): TargetProducerApplicationFact? =
        ports.applyProducerApplication(candidate)

    internal fun prepareProducerApplicationCandidates(port: TargetPorts.AndroidSurfacePort, operation: OperationOccurrence<*>): Boolean =
        ports.prepareProducerApplicationCandidates(port, operation)

    internal fun producerDetachApplicationCandidateAfterSettlement(
        port: TargetPorts.AndroidDetachPort,
        operation: OperationOccurrence<*>,
    ): TargetProducerDetachApplicationCandidate? =
        ports.producerDetachApplicationCandidateAfterSettlement(port, operation)

    internal fun applyProducerDetachApplication(
        candidate: TargetProducerDetachApplicationCandidate,
    ): TargetProducerDetachReceipt? = ports.applyProducerDetachApplication(candidate)

    internal fun prepareProducerDetachApplicationCandidate(port: TargetPorts.AndroidDetachPort, operation: OperationOccurrence<*>): Boolean =
        ports.prepareProducerDetachApplicationCandidate(port, operation)

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
            frameAdmissionDisposition = TargetFrameAdmissionDisposition.Sealed
            frameAdmissionSealedFact = sealedFact
            frameQuiescedFact = null
            bumpCurrentnessLocked()
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
                bumpCurrentnessLocked()
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
            bumpCurrentnessLocked()
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
            bumpCurrentnessLocked()
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
            bumpCurrentnessLocked()
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
            bumpCurrentnessLocked()
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

    internal fun consumePendingSource(): Boolean = targetGate.withLock {
        if (!retirement.installedResources || retirementAdmissionClosed || generationFenced) {
            return@withLock false
        }
        latestPendingSource.getAndSet(false)
    }

    internal fun closeRetirementAdmission(): TargetRetirementAdmissionClosedFact? = targetGate.withLock {
        if (!retirement.installedResources || retirementAdmissionClosed) {
            return@withLock null
        }
        ports.rejectReservedFrameForSealLocked()
        frameAdmissionDisposition = TargetFrameAdmissionDisposition.RetirementClosed
        frameAdmissionSealedFact = null
        frameQuiescedFact = null
        retirementAdmissionClosed = true
        bumpCurrentnessLocked()
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
        bumpCurrentnessLocked()
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
        bumpCurrentnessLocked()
        latestPendingSource.set(false)
        precreatedGenerationFencedFact.also { generationFencedFact = it }
    }

    internal fun registerListenerRemovalPort(operationIdentity: Long): TargetPorts.AndroidListenerRemovalPort? =
        ports.registerListenerRemovalPort(operationIdentity)

    internal fun registerSetSurfaceDetachPort(operationIdentity: Long): TargetPorts.AndroidDetachPort? =
        ports.registerSetSurfaceDetachPort(operationIdentity)

    internal fun registerVirtualDisplayReleasePort(operationIdentity: Long): TargetPorts.AndroidDetachPort? =
        ports.registerVirtualDisplayReleasePort(operationIdentity)

    internal fun prepareStagedDetachPort(
        operationIdentity: Long,
        retargetOccurrenceIdentity: Long,
    ): TargetPorts.StagedAndroidDetachPort =
        ports.prepareStagedDetachPort(operationIdentity, retargetOccurrenceIdentity)

    internal fun prepareStagedDetachApplicationCandidate(
        stagedPort: TargetPorts.StagedAndroidDetachPort,
        operation: OperationOccurrence<*>,
    ): Boolean = ports.prepareStagedDetachApplicationCandidate(stagedPort, operation)

    internal fun commitStagedDetachPort(
        stagedPort: TargetPorts.StagedAndroidDetachPort,
    ): TargetStagedDetachPortCommitResult? = ports.commitStagedDetachPort(stagedPort)

    internal fun markStagedDetachPostAcceptedOrAmbiguous(
        stagedPort: TargetPorts.StagedAndroidDetachPort,
    ): TargetStagedPortPostExposedFact? = ports.markStagedDetachPostAcceptedOrAmbiguous(stagedPort)

    internal fun retireCommittedStagedDetachPortDefinitelyUnentered(
        stagedPort: TargetPorts.StagedAndroidDetachPort,
    ): TargetStagedDetachPortRetiredFact? = ports.retireCommittedStagedDetachPortDefinitelyUnentered(stagedPort)

    internal fun applyStagedDetachTerminalApplication(
        stagedPort: TargetPorts.StagedAndroidDetachPort,
    ): TargetProducerDetachReceipt? = ports.applyStagedDetachTerminalApplication(stagedPort)

    internal fun armListenerSentinelAfterRemovalReturn(operationIdentity: Long): Runnable? =
        ports.armListenerSentinelAfterRemovalReturn(operationIdentity)

    internal fun recordListenerRemovalReturn(port: TargetPorts.AndroidListenerRemovalPort): Boolean =
        ports.recordListenerRemovalReturn(port)

    internal fun applyListenerRemovalSettlement(
        port: TargetPorts.AndroidListenerRemovalPort,
        operation: OperationOccurrence<*>,
    ): TargetListenerRemovalSettledFact? =
        ports.applyListenerRemovalSettlement(port, operation)

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

    internal fun convertConstructionCleanupOccurrencesForTerminal(): Boolean =
        retirement.convertConstructionCleanupOccurrencesForTerminal()

    internal fun claimListenerRemovalIdentityLocked(operationIdentity: Long): TargetRegistrationClaim {
        check(targetGate.isHeldByCurrentThread)
        if (!retirement.installedResources || !generationFenced) {
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
        return retirement.installedResources && !generationFenced && !portAlreadyRegistered && operationIdentity == listenerInstallationOperationIdentity
    }

    internal fun applyListenerInstallationLocked(exactPort: Boolean, operationIdentity: Long): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!exactPort || operationIdentity != listenerInstallationOperationIdentity || listenerInstalled || generationFenced) {
            return false
        }
        listenerInstalled = true
        sourceSignalsAccepted = true
        bumpCurrentnessLocked()
        return true
    }

    internal fun canRegisterProducerPortLocked(portAlreadyRegistered: Boolean): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return retirement.installedResources && listenerInstalled && !generationFenced && !portAlreadyRegistered &&
                producerState == TargetProducerState.AwaitingEvidence
    }

    internal fun listenerForInstallationPortLocked(): SurfaceTexture.OnFrameAvailableListener {
        check(targetGate.isHeldByCurrentThread)
        check(retirement.installedResources && !generationFenced && !listenerInstalled)
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
            generationFenced ||
            producerState != TargetProducerState.AwaitingEvidence
        ) {
            return false
        }
        producerState = TargetProducerState.ProducerAttached
        bumpCurrentnessLocked()
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
            generationFenced ||
            producerState != TargetProducerState.AwaitingEvidence ||
            (evidence.reason == TargetNoProducerReason.ReturnedWithoutProducer &&
                    evidence.operationKind != TargetProducerOperationKind.VirtualDisplayCreation)
        ) {
            return false
        }
        producerState = TargetProducerState.NoProducer
        bumpCurrentnessLocked()
        return true
    }

    /** Terminal/cleanup-only reduction for the exact staged attachment occurrence. */
    internal fun applyStagedProducerTerminalFactLocked(
        fact: TargetProducerApplicationFact,
        exactEvidence: Boolean,
        portOperationIdentity: Long,
        portProvenance: TargetOperationProvenance,
        retargetOccurrenceIdentity: Long,
    ): Boolean {
        check(targetGate.isHeldByCurrentThread)
        val retargetFact = fact as? TargetRetargetProducerApplicationFact ?: return false
        if (!retirement.installedResources || !retirementAdmissionClosed || !exactEvidence ||
            fact.targetGeneration != generation || fact.targetIdentity !== identity ||
            fact.operationIdentity != portOperationIdentity ||
            fact.operationKind != TargetProducerOperationKind.VirtualDisplayAttachment ||
            fact.provenance !== portProvenance ||
            retargetFact.retargetOccurrenceIdentity != retargetOccurrenceIdentity ||
            producerState != TargetProducerState.AwaitingEvidence
        ) {
            return false
        }
        producerState = when (fact) {
            is TargetProducerEvidence -> TargetProducerState.ProducerAttached
            is TargetNoProducerEvidence -> {
                if (fact.reason != TargetNoProducerReason.Unentered &&
                    fact.reason != TargetNoProducerReason.Inapplicable
                ) {
                    return false
                }
                TargetProducerState.NoProducer
            }
            else -> return false
        }
        bumpCurrentnessLocked()
        return true
    }

    internal fun claimDetachIdentityLocked(operationIdentity: Long, detachKind: TargetProducerDetachKind): TargetRegistrationClaim {
        check(targetGate.isHeldByCurrentThread)
        if (!retirement.installedResources || !generationFenced || producerState != TargetProducerState.ProducerAttached) {
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

    internal fun armListenerSentinelLocked(operationIdentity: Long): Runnable? {
        check(targetGate.isHeldByCurrentThread)
        if (!retirement.installedResources ||
            !generationFenced ||
            expectedListenerRemovalOperationIdentity == NO_OPERATION_IDENTITY ||
            operationIdentity != expectedListenerRemovalOperationIdentity ||
            !listenerRemoved ||
            armedListenerSentinelOperationIdentity != NO_OPERATION_IDENTITY ||
            observedListenerSentinelOperationIdentity != NO_OPERATION_IDENTITY
        ) {
            return null
        }
        armedListenerSentinelOperationIdentity = operationIdentity
        return listenerSentinelRunnable
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
        bumpCurrentnessLocked()
        return true
    }

    internal fun applyListenerRemovalSettlementLocked(exactPort: Boolean): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!exactPort || !listenerRemoved || listenerRemovalSettled) return false
        listenerRemovalSettled = true
        bumpCurrentnessLocked()
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
        bumpCurrentnessLocked()
        return true
    }

    private fun publishSourceAvailable() {
        val published = targetGate.withLock {
            if (!retirement.installedResources || !sourceSignalsAccepted || generationFenced) {
                return@withLock false
            }
            latestPendingSource.set(true)
            true
        }
        if (published) sourceSignal.signal(precreatedSourceAvailableFact)
    }

    private fun bumpCurrentnessLocked() {
        check(targetGate.isHeldByCurrentThread)
        currentnessVersion.updateAndGet { current -> if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L }
    }

    private fun publishListenerSentinel() {
        val published = targetGate.withLock {
            val armedIdentity = armedListenerSentinelOperationIdentity
            if (!retirement.installedResources || !generationFenced || armedIdentity == NO_OPERATION_IDENTITY ||
                armedIdentity != expectedListenerRemovalOperationIdentity ||
                !listenerRemoved || observedListenerSentinelOperationIdentity != NO_OPERATION_IDENTITY
            ) {
                return@withLock false
            }
            armedListenerSentinelOperationIdentity = NO_OPERATION_IDENTITY
            observedListenerSentinelOperationIdentity = armedIdentity
            true
        }
        if (published) settlementSignal.signal()
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
