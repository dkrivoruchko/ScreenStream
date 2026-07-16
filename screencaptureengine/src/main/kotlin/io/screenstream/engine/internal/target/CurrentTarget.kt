package io.screenstream.engine.internal.target

import android.graphics.SurfaceTexture
import android.view.Surface
import io.screenstream.engine.internal.gl.GlFiniteOperationIdentity
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class TargetRegistrationClaim {
    New,
    Existing,
    Denied,
}

internal class CurrentTarget private constructor(
    private val targetOwner: TargetOwner,
    private val constructionProof: () -> Unit,
    private val targetGate: ReentrantLock,
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
    private val latestPendingSource: AtomicBoolean = AtomicBoolean(false)
    internal var expectedListenerRemovalOperationIdentity: Long = NO_OPERATION_IDENTITY
    internal var expectedSetSurfaceDetachOperationIdentity: Long = NO_OPERATION_IDENTITY
    internal var expectedVirtualDisplayReleaseOperationIdentity: Long = NO_OPERATION_IDENTITY
    internal var armedListenerSentinelOperationIdentity: Long = NO_OPERATION_IDENTITY
    internal var observedListenerSentinelOperationIdentity: Long = NO_OPERATION_IDENTITY
    internal var producerState: TargetProducerState = TargetProducerState.AwaitingEvidence
    internal var listenerInstalled: Boolean = false
    internal var sourceSignalsAccepted: Boolean = false
    internal var retirementAdmissionClosed: Boolean = false
    internal var enteredTargetWorkDrained: Boolean = false
    internal var generationFenced: Boolean = false
    internal var listenerRemoved: Boolean = false
    internal var listenerRemovalSettled: Boolean = false

    internal val frameAvailableListener: SurfaceTexture.OnFrameAvailableListener =
        SurfaceTexture.OnFrameAvailableListener {
            publishSourceAvailable()
        }

    internal val listenerSentinelRunnable: Runnable = Runnable {
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

    internal val isSurfaceReleaseReady: Boolean
        get() = targetGate.withLock {
            retirement.installedResources &&
                    retirementAdmissionClosed &&
                    enteredTargetWorkDrained &&
                    generationFenced &&
                    listenerRemoved &&
                    listenerRemovalSettled &&
                    expectedListenerRemovalOperationIdentity !=
                    NO_OPERATION_IDENTITY &&
                    observedListenerSentinelOperationIdentity ==
                    expectedListenerRemovalOperationIdentity &&
                    (producerState == TargetProducerState.NoProducer ||
                            producerState == TargetProducerState.Detached) &&
                    !ports.hasGlFramePortLocked &&
                    ports.leaseCountLocked == 0
        }

    internal val hasAppliedSurfaceReleaseReceipt: Boolean
        get() = retirement.hasAppliedSurfaceReleaseReceipt

    internal val isFullyRetired: Boolean
        get() = retirement.isFullyRetired

    internal fun usesTargetGate(expectedGate: ReentrantLock): Boolean =
        targetGate === expectedGate

    internal fun matchesConstructionProof(expectedOwner: TargetOwner, proof: () -> Unit): Boolean =
        targetOwner === expectedOwner && constructionProof === proof && targetOwner.acceptsConstructionProof(proof)

    internal fun matchesPrecreatedIdentity(
        expectedPlan: TargetPlan,
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
        return plan === expectedPlan &&
                generation == expectedGeneration &&
                ports.matchesListenerInstallationOperationIdentity(listenerInstallationOperationIdentity) &&
                surfaceReleaseOccurrence.identity ==
                surfaceReleaseOperationIdentity &&
                deadline.identity == surfaceReleaseDeadlineIdentity &&
                deadline.wakeLink.generation ==
                surfaceReleaseDeadlineWakeGeneration &&
                deadline.wakeLink.timeoutCause === surfaceReleaseTimeoutCause &&
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
    ): Boolean = ports.applyListenerInstallationReceipt(port, operation)

    internal fun registerProducerPort(
        operationIdentity: Long,
        operationKind: TargetProducerOperationKind,
    ): TargetPorts.AndroidSurfacePort? =
        ports.registerProducerPort(operationIdentity, operationKind)

    internal fun applyProducerEvidence(evidence: TargetProducerEvidence): Boolean = ports.applyProducerEvidence(evidence)

    internal fun applyNoProducerEvidence(evidence: TargetNoProducerEvidence): Boolean = ports.applyNoProducerEvidence(evidence)

    internal fun producerEvidenceAfterSettlement(port: TargetPorts.AndroidSurfacePort, operation: OperationOccurrence<*>): TargetProducerEvidence? =
        ports.producerEvidenceAfterSettlement(port, operation)

    internal fun noProducerEvidenceAfterSettlement(
        port: TargetPorts.AndroidSurfacePort,
        operation: OperationOccurrence<*>,
        reason: TargetNoProducerReason,
    ): TargetNoProducerEvidence? =
        ports.noProducerEvidenceAfterSettlement(port, operation, reason)

    internal fun producerDetachReceiptAfterSettlement(
        port: TargetPorts.AndroidDetachPort,
        operation: OperationOccurrence<*>,
    ): TargetProducerDetachReceipt? =
        ports.producerDetachReceiptAfterSettlement(port, operation)

    internal fun detachedGlFramePort(operationIdentity: Long): TargetPorts.GlFramePort? =
        ports.detachedGlFramePort(operationIdentity)

    internal fun commitGlFramePort(port: TargetPorts.GlFramePort): Boolean =
        ports.commitGlFramePort(port)

    internal fun retireGlFramePortAfterSettlement(port: TargetPorts.GlFramePort, operation: OperationOccurrence<*>): Boolean =
        ports.retireGlFramePortAfterSettlement(port, operation)

    internal fun consumePendingSource(): Boolean = targetGate.withLock {
        if (!retirement.installedResources || retirementAdmissionClosed || generationFenced) {
            return@withLock false
        }
        latestPendingSource.getAndSet(false)
    }

    internal fun recordRetirementAdmissionClosed(): Boolean = targetGate.withLock {
        if (!retirement.installedResources || retirementAdmissionClosed) {
            return@withLock false
        }
        retirementAdmissionClosed = true
        true
    }

    internal fun recordEnteredTargetWorkDrained(): Boolean = targetGate.withLock {
        if (!retirement.installedResources || !retirementAdmissionClosed || enteredTargetWorkDrained ||
            ports.hasGlFramePortLocked || ports.leaseCountLocked != 0
        ) {
            return@withLock false
        }
        enteredTargetWorkDrained = true
        true
    }

    internal fun fenceGeneration(): Boolean = targetGate.withLock {
        if (!retirement.installedResources || !retirementAdmissionClosed || !enteredTargetWorkDrained || generationFenced) {
            return@withLock false
        }
        sourceSignalsAccepted = false
        generationFenced = true
        latestPendingSource.set(false)
        true
    }

    internal fun registerListenerRemovalPort(operationIdentity: Long): TargetPorts.AndroidListenerRemovalPort? =
        ports.registerListenerRemovalPort(operationIdentity)

    internal fun registerSetSurfaceDetachPort(operationIdentity: Long): TargetPorts.AndroidDetachPort? =
        ports.registerSetSurfaceDetachPort(operationIdentity)

    internal fun registerVirtualDisplayReleasePort(operationIdentity: Long): TargetPorts.AndroidDetachPort? =
        ports.registerVirtualDisplayReleasePort(operationIdentity)

    internal fun armListenerSentinelAfterRemovalReturn(operationIdentity: Long): Runnable? =
        ports.armListenerSentinelAfterRemovalReturn(operationIdentity)

    internal fun recordListenerRemovalReturn(port: TargetPorts.AndroidListenerRemovalPort): Boolean =
        ports.recordListenerRemovalReturn(port)

    internal fun applyListenerRemovalSettlement(port: TargetPorts.AndroidListenerRemovalPort, operation: OperationOccurrence<*>): Boolean =
        ports.applyListenerRemovalSettlement(port, operation)

    internal fun applyProducerDetachReceipt(receipt: TargetProducerDetachReceipt): Boolean =
        ports.applyProducerDetachReceipt(receipt)

    internal fun beginSurfaceReleaseSubmission(): Boolean =
        retirement.beginSurfaceReleaseSubmission()

    internal fun beginUninstalledSurfaceReleaseSubmission(constructionSettled: Boolean): Boolean =
        retirement.beginUninstalledSurfaceReleaseSubmission(constructionSettled)

    internal fun detachedSurfaceReleasePort(): TargetRetirement.SurfaceReleasePort? =
        retirement.detachedSurfaceReleasePort()

    internal fun commitSurfaceReleasePort(port: TargetRetirement.SurfaceReleasePort): Boolean =
        retirement.commitSurfaceReleasePort(port)

    internal fun enterSurfaceRelease(port: TargetRetirement.SurfaceReleasePort): OperationEntryResult =
        retirement.enterSurfaceRelease(port)

    internal fun releaseEnteredSurface(port: TargetRetirement.SurfaceReleasePort): Boolean =
        retirement.releaseEnteredSurface(port)

    internal fun publishSurfaceReleaseNormalReturn(): Boolean =
        retirement.publishSurfaceReleaseNormalReturn()

    internal fun publishSurfaceReleaseThrownReturn(thrown: Throwable): Boolean =
        retirement.publishSurfaceReleaseThrownReturn(thrown)

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
        return true
    }

    internal fun canRegisterProducerPortLocked(portAlreadyRegistered: Boolean): Boolean {
        check(targetGate.isHeldByCurrentThread)
        return retirement.installedResources && listenerInstalled && !generationFenced && !portAlreadyRegistered &&
                producerState == TargetProducerState.AwaitingEvidence
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
            evidence.operationIdentity != portOperationIdentity ||
            evidence.operationKind != portOperationKind ||
            evidence.provenance !== portProvenance ||
            generationFenced ||
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
            evidence.operationIdentity != portOperationIdentity ||
            evidence.operationKind != portOperationKind ||
            evidence.provenance !== portProvenance ||
            producerState != TargetProducerState.AwaitingEvidence ||
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
        return true
    }

    internal fun applyListenerRemovalSettlementLocked(exactPort: Boolean): Boolean {
        check(targetGate.isHeldByCurrentThread)
        if (!exactPort || !listenerRemoved || listenerRemovalSettled) return false
        listenerRemovalSettled = true
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

    private fun publishSourceAvailable() {
        val published = targetGate.withLock {
            if (!retirement.installedResources || !sourceSignalsAccepted || generationFenced) {
                return@withLock false
            }
            latestPendingSource.set(true)
            true
        }
        if (published) sourceSignal.signal(generation)
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
