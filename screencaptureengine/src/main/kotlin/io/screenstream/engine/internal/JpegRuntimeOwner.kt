package io.screenstream.engine.internal

import io.screenstream.engine.JpegBackendPolicy
import io.screenstream.engine.internal.jpeg.CarrierValidation
import io.screenstream.engine.internal.jpeg.JpegCarrierMode
import io.screenstream.engine.internal.jpeg.JpegEndpointConstructionFact
import io.screenstream.engine.internal.jpeg.JpegEndpointIdentity
import io.screenstream.engine.internal.jpeg.JpegEndpointOccurrence
import io.screenstream.engine.internal.jpeg.JpegEndpointRootSettlement
import io.screenstream.engine.internal.jpeg.JpegFiniteOperationIdentity
import io.screenstream.engine.internal.jpeg.JpegEncodeClaimFact
import io.screenstream.engine.internal.jpeg.JpegEncodeFinalizationDisposition
import io.screenstream.engine.internal.jpeg.JpegEncodeFinalizationReceipt
import io.screenstream.engine.internal.jpeg.JpegEncodeFinalizationResult
import io.screenstream.engine.internal.jpeg.JpegLaneOwner
import io.screenstream.engine.internal.jpeg.JpegOwnerRootReadiness
import io.screenstream.engine.internal.jpeg.JpegPhysicalDomainIdentity
import io.screenstream.engine.internal.jpeg.JpegPreparationEvidence
import io.screenstream.engine.internal.jpeg.JpegPreparationOccurrence
import io.screenstream.engine.internal.jpeg.JpegRuntimeFailure
import io.screenstream.engine.internal.jpeg.JpegRuntimeIdentity
import io.screenstream.engine.internal.jpeg.JpegRuntimeProduct
import io.screenstream.engine.internal.jpeg.JpegRuntimeTopologySnapshot
import io.screenstream.engine.internal.jpeg.JpegRuntimeTopologyState
import io.screenstream.engine.internal.jpeg.ManagedDirectCarrier
import io.screenstream.engine.internal.jpeg.ManagedDirectCarrierReplacementAllocationOccurrence
import io.screenstream.engine.internal.jpeg.NATIVE_ENCODE_ADMISSION_FAILED
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeOccurrence
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeOutcomeFact
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeOrigin
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeQuarantineCause
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeResidueFact
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeReturnFact
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeReturnKind
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeReceipt
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeSettlement
import io.screenstream.engine.internal.jpeg.NativeCarrierReplacementAllocationOccurrence
import io.screenstream.engine.internal.jpeg.NativeEncodeAdmissionDisposition
import io.screenstream.engine.internal.jpeg.NativeEncodeClaimFact
import io.screenstream.engine.internal.jpeg.NativeEncodeFinalizationCommand
import io.screenstream.engine.internal.jpeg.NativeEncodeOccurrence
import io.screenstream.engine.internal.jpeg.NativeEncodeOwnerBag
import io.screenstream.engine.internal.jpeg.NativeEncodeSettlement
import io.screenstream.engine.internal.jpeg.NativeFrameDescriptor
import io.screenstream.engine.internal.jpeg.NativeJpegHealth
import io.screenstream.engine.internal.jpeg.NativeJpegProcess
import io.screenstream.engine.internal.jpeg.NativeMallocCarrier
import io.screenstream.engine.internal.jpeg.NativeMallocCarrierLease
import io.screenstream.engine.internal.jpeg.NoReturnedCarrierSettlement
import io.screenstream.engine.internal.jpeg.RgbaCarrier
import io.screenstream.engine.internal.jpeg.RgbaCarrierLease
import io.screenstream.engine.internal.jpeg.cancelledNativeEncodeWithoutReturnLocked
import io.screenstream.engine.internal.jpeg.executeCoordinatedNativeEncode
import io.screenstream.engine.internal.jpeg.executeJpegPreparation
import io.screenstream.engine.internal.jpeg.executeManagedCarrierReplacement
import io.screenstream.engine.internal.jpeg.executeNativeCarrierFree
import io.screenstream.engine.internal.jpeg.executeNativeCarrierReplacement
import io.screenstream.engine.internal.jpeg.nativeEncodeMechanicsSettledLocked
import io.screenstream.engine.internal.jpeg.terminalizedUnenteredEncodeFailureLocked
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration
import io.screenstream.engine.internal.settlement.PrivateExecutorOperation
import io.screenstream.engine.internal.settlement.PrivateExecutorRuntime
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.settlement.isHandedOff
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

internal class JpegRuntimeOwner internal constructor(
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
) {
    private val runtimeIdentity: JpegRuntimeIdentity = JpegRuntimeIdentity()
    private val endpointIdentity: JpegEndpointIdentity = JpegEndpointIdentity(runtimeIdentity)
    private val endpointConstructionFact: JpegEndpointConstructionFact =
        JpegEndpointConstructionFact(runtimeIdentity, endpointIdentity)
    private val physicalDomainIdentity = JpegPhysicalDomainIdentity(runtimeIdentity, endpointIdentity)
    private val jpegLane = JpegLaneOwner(
        endpointIdentity = endpointIdentity,
        constructionFact = endpointConstructionFact,
        physicalDomainIdentity = physicalDomainIdentity,
        settlementSignal = settlementSignal,
    )
    private val jpegEndpoint: PrivateExecutorRuntime
        get() = jpegLane.endpoint
    private val topologyState = AtomicReference<JpegRuntimeTopologyState>(JpegRuntimeTopologySnapshot.Empty)

    private var preparationOccurrence: JpegPreparationOccurrence? = null
    private var nativeFreeOccurrence: NativeCarrierFreeOccurrence? = null
    private var nativeReplacementOccurrence: NativeCarrierReplacementAllocationOccurrence? = null
    private var managedReplacementOccurrence: ManagedDirectCarrierReplacementAllocationOccurrence? = null
    private var nativeEncodeOccurrence: NativeEncodeOccurrence? = null
    private var settledPhysicalDomainRoots: JpegOwnerRootReadiness.PhysicalDomainRootsSettled? = null
    internal fun stableTopologySnapshot(): JpegRuntimeTopologySnapshot? =
        topologyState.get() as? JpegRuntimeTopologySnapshot

    internal fun createNativeCarrierCandidate(byteCount: Int): NativeMallocCarrier =
        NativeMallocCarrier.create(byteCount, topologyState)

    internal fun createManagedCarrierCandidate(byteCount: Int): ManagedDirectCarrier =
        ManagedDirectCarrier.create(byteCount, topologyState)

    internal val jpegIoSettlementSignal: SettlementSignal
        get() = jpegLane.settlementSignal

    internal val jpegExecutorEndpoint: PrivateExecutorRuntime
        get() = jpegLane.endpoint

    internal fun prestartJpegEndpoint(): PrivateExecutorStartupDisposition = jpegLane.prestart()

    internal fun prepare(
        policy: JpegBackendPolicy,
        byteCount: Int,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): JpegPreparationOccurrence? {
        val topology = stableTopologySnapshot() ?: return null
        if (byteCount <= 0 || topology.product != null || preparationOccurrence != null) return null

        val occurrence = JpegPreparationOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            identity = identity,
            clock = clock,
            signal = settlementSignal,
            endpoint = jpegEndpoint,
            work = { executeJpegPreparation(it, policy, byteCount) },
        )
        preparationOccurrence = occurrence
        submitJpegIoOperation(occurrence.executorOperation)
        return occurrence
    }

    internal fun installPrepared(occurrence: JpegPreparationOccurrence): Boolean {
        val sourceTopology = stableTopologySnapshot() ?: return false
        if (preparationOccurrence !== occurrence ||
            occurrence.operation.arbitrate() != OperationArbitration.TimelyNormal
        ) {
            return false
        }
        if (!releaseJpegIoOperation(occurrence)) return false

        val product = occurrence.ownerBag.product ?: return false
        val carrier = product.carrier
        val installedTopology = JpegRuntimeTopologySnapshot(
            carrierMode = when (product) {
                is JpegRuntimeProduct.NativeEnabled,
                is JpegRuntimeProduct.FrameworkOnNativeCarrier,
                    -> JpegCarrierMode.NativeMalloc

                is JpegRuntimeProduct.FrameworkOnManagedCarrier -> JpegCarrierMode.ManagedDirect
            },
            nativeHealth = product.nativeHealth,
            product = product,
            lease = null,
            replacementSource = null,
        )
        if (!carrier.install(product, sourceTopology)) return false
        if (!topologyState.compareAndSet(sourceTopology, installedTopology)) return false
        occurrence.operation.settlementGate.withLock {
            occurrence.ownerBag.product = null
            occurrence.ownerBag.nativeCarrier = null
            occurrence.ownerBag.managedCarrier = null
            occurrence.operation.returnCell.evidence.returnedOwner = null
        }
        preparationOccurrence = null
        return true
    }

    internal fun acquireInitialLease(expectedProduct: JpegRuntimeProduct): RgbaCarrierLease? {
        val sourceTopology = stableTopologySnapshot() ?: return null
        if (sourceTopology.product !== expectedProduct || sourceTopology.lease != null) return null

        val candidate = expectedProduct.initialLease
        val installedTopology = JpegRuntimeTopologySnapshot(
            carrierMode = sourceTopology.carrierMode,
            nativeHealth = sourceTopology.nativeHealth,
            product = expectedProduct,
            lease = candidate,
            replacementSource = sourceTopology.replacementSource,
        )
        if (!expectedProduct.carrier.acquireLease(sourceTopology, installedTopology, expectedProduct, candidate)) return null
        if (!topologyState.compareAndSet(sourceTopology, installedTopology)) return null
        return candidate
    }

    internal fun releaseCurrentLease(expectedLease: RgbaCarrierLease): Boolean {
        val sourceTopology = stableTopologySnapshot() ?: return false
        if (sourceTopology.lease !== expectedLease) return false
        val releasedTopology = JpegRuntimeTopologySnapshot(
            carrierMode = sourceTopology.carrierMode,
            nativeHealth = sourceTopology.nativeHealth,
            product = sourceTopology.product,
            lease = null,
            replacementSource = sourceTopology.replacementSource,
        )
        return expectedLease.release() && topologyState.compareAndSet(sourceTopology, releasedTopology)
    }

    internal fun beginNativeCarrierFree(
        expectedProduct: JpegRuntimeProduct,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): NativeCarrierFreeOccurrence? {
        val topology = stableTopologySnapshot() ?: return null
        if (topology.carrierMode != JpegCarrierMode.NativeMalloc ||
            topology.product !== expectedProduct || topology.lease != null || nativeFreeOccurrence != null ||
            nativeReplacementOccurrence != null || topology.replacementSource != null
        ) {
            return null
        }

        val carrier = when (expectedProduct) {
            is JpegRuntimeProduct.NativeEnabled -> expectedProduct.nativeCarrier
            is JpegRuntimeProduct.FrameworkOnNativeCarrier -> expectedProduct.nativeCarrier
            is JpegRuntimeProduct.FrameworkOnManagedCarrier -> return null
        }
        if (!carrier.closeAdmissionAndCheckDrained(topology, expectedProduct)) return null
        val buffer = proveExactWritableBuffer(carrier.freeCandidate(topology, expectedProduct), carrier.byteCount) ?: return null

        val occurrence = NativeCarrierFreeOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            expectedProduct = expectedProduct,
            origin = NativeCarrierFreeOrigin.IncompatibleReplacement,
            carrier = carrier,
            buffer = buffer,
            identity = identity,
            operationIdentity = identity.operationIdentity,
            clock = clock,
            signal = settlementSignal,
            endpoint = jpegEndpoint,
            work = ::executeNativeCarrierFree,
        )
        if (!carrier.admitInstalledFree(topology, expectedProduct, buffer, occurrence)) return null

        nativeFreeOccurrence = occurrence
        submitJpegIoOperation(occurrence.executorOperation)
        return occurrence
    }

    internal fun beginTerminalNativeCarrierFree(
        expectedProduct: JpegRuntimeProduct,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        operationIdentity: Long,
    ): NativeCarrierFreeOccurrence? {
        val topology = stableTopologySnapshot() ?: return null
        if (topology.product !== expectedProduct || topology.lease != null || nativeFreeOccurrence != null ||
            nativeReplacementOccurrence != null
        ) {
            return null
        }

        val carrier = when (expectedProduct) {
            is JpegRuntimeProduct.NativeEnabled -> expectedProduct.nativeCarrier
            is JpegRuntimeProduct.FrameworkOnNativeCarrier -> expectedProduct.nativeCarrier
            is JpegRuntimeProduct.FrameworkOnManagedCarrier -> return null
        }
        if (!carrier.closeAdmissionAndCheckDrained(topology, expectedProduct)) return null
        val buffer = proveExactWritableBuffer(carrier.freeCandidate(topology, expectedProduct), carrier.byteCount) ?: return null
        val occurrence = NativeCarrierFreeOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            expectedProduct = expectedProduct,
            origin = NativeCarrierFreeOrigin.TerminalRetirement,
            carrier = carrier,
            buffer = buffer,
            identity = null,
            operationIdentity = operationIdentity,
            clock = clock,
            signal = settlementSignal,
            endpoint = jpegEndpoint,
            work = ::executeNativeCarrierFree,
        )
        if (occurrence.operation.arbitrateTerminal(mandatoryCleanup = true) != OperationTerminalArbitration.Transferred ||
            !carrier.admitInstalledFree(topology, expectedProduct, buffer, occurrence)
        ) {
            return null
        }

        nativeFreeOccurrence = occurrence
        submitJpegIoOperation(occurrence.executorOperation)
        return occurrence
    }

    internal fun settleNativeCarrierFree(occurrence: NativeCarrierFreeOccurrence): NativeCarrierFreeSettlement {
        occurrence.outcomeFact?.let { return it.settlement }
        if (stableTopologySnapshot() == null || nativeFreeOccurrence !== occurrence || !releaseJpegIoOperation(occurrence)) {
            return NativeCarrierFreeSettlement.NotSettled
        }
        val gate = occurrence.operation.settlementGate
        var carrier: NativeMallocCarrier? = null
        var timely = false
        var returnKind: NativeCarrierFreeReturnKind? = null
        var returnThrowable: Throwable? = null
        var returnReceipt: NativeCarrierFreeReceipt? = null
        var buffer: ByteBuffer? = null
        var expectedProduct: JpegRuntimeProduct? = null
        gate.withLock {
            if (nativeFreeOccurrence !== occurrence) return NativeCarrierFreeSettlement.NotSettled
            if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
            if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) {
                return NativeCarrierFreeSettlement.NotSettled
            }

            timely = occurrence.operation.returnCell.use == OperationReturnUse.Timely
            val evidence = occurrence.operation.returnCell.evidence
            when (occurrence.operation.returnCell.disposition) {
                OperationReturnDisposition.Empty -> Unit
                OperationReturnDisposition.Normal -> {
                    returnKind = NativeCarrierFreeReturnKind.Normal
                    returnReceipt = if (evidence.receipt === evidence.normalReceipt) evidence.normalReceipt else null
                }

                OperationReturnDisposition.Thrown -> {
                    returnKind = NativeCarrierFreeReturnKind.Thrown
                    returnThrowable = occurrence.operation.returnCell.throwable
                    returnReceipt = if (evidence.receipt === evidence.normalReceipt) evidence.normalReceipt else null
                }
            }
            carrier = occurrence.ownerBag.carrier
            buffer = occurrence.ownerBag.buffer
            expectedProduct = occurrence.expectedProduct
        }

        val exactReturnKind = returnKind ?: return NativeCarrierFreeSettlement.NotSettled
        val exactReturned = NativeCarrierFreeReturnFact(
            kind = exactReturnKind,
            throwable = returnThrowable,
            receipt = returnReceipt,
        )
        val exactCarrier = carrier
        val exactBuffer = buffer
        val exactExpectedProduct = expectedProduct
        if (exactReturned.kind == NativeCarrierFreeReturnKind.Thrown) {
            val outcome = NativeCarrierFreeOutcomeFact(
                attempt = occurrence.attemptFact,
                returned = exactReturned,
                physicalRelease = null,
                topologyRetired = null,
                settlement = NativeCarrierFreeSettlement.UnsafeResidue,
                quarantineCause = NativeCarrierFreeQuarantineCause.Thrown,
            )
            return gate.withLock {
                publishNativeCarrierFreeOutcomeLocked(occurrence, outcome)
            }
        }
        val receipt = exactReturned.receipt
        if (receipt == null) {
            val outcome = NativeCarrierFreeOutcomeFact(
                attempt = occurrence.attemptFact,
                returned = exactReturned,
                physicalRelease = null,
                topologyRetired = null,
                settlement = NativeCarrierFreeSettlement.UnsafeResidue,
                quarantineCause = NativeCarrierFreeQuarantineCause.MissingReceipt,
            )
            return gate.withLock {
                publishNativeCarrierFreeOutcomeLocked(occurrence, outcome)
            }
        }
        if (exactCarrier == null || exactBuffer == null || exactExpectedProduct == null) {
            val outcome = NativeCarrierFreeOutcomeFact(
                attempt = occurrence.attemptFact,
                returned = exactReturned,
                physicalRelease = null,
                topologyRetired = null,
                settlement = NativeCarrierFreeSettlement.UnsafeResidue,
                quarantineCause = NativeCarrierFreeQuarantineCause.MissingOwnerEvidence,
            )
            return gate.withLock {
                publishNativeCarrierFreeOutcomeLocked(occurrence, outcome)
            }
        }
        val installedOrigin = occurrence.origin != NativeCarrierFreeOrigin.ReturnedOwnerCleanup
        val replacementAuthorized = timely && occurrence.origin == NativeCarrierFreeOrigin.IncompatibleReplacement
        val completedSettlement = if (replacementAuthorized) {
            NativeCarrierFreeSettlement.ReplacementAuthorized
        } else {
            NativeCarrierFreeSettlement.CleanupCompleted
        }
        val physicalReleaseCandidate = occurrence.attemptFact.physicalReleaseCandidate
        val physicalReleaseResidueOutcome = NativeCarrierFreeOutcomeFact(
            attempt = occurrence.attemptFact,
            returned = exactReturned,
            physicalRelease = null,
            topologyRetired = null,
            settlement = NativeCarrierFreeSettlement.UnsafeResidue,
            quarantineCause = NativeCarrierFreeQuarantineCause.PhysicalReleaseUnproven,
        )
        val missingOwnerOutcome = NativeCarrierFreeOutcomeFact(
            attempt = occurrence.attemptFact,
            returned = exactReturned,
            physicalRelease = physicalReleaseCandidate,
            topologyRetired = null,
            settlement = NativeCarrierFreeSettlement.UnsafeResidue,
            quarantineCause = NativeCarrierFreeQuarantineCause.MissingOwnerEvidence,
        )
        val topologyResidueOutcome = NativeCarrierFreeOutcomeFact(
            attempt = occurrence.attemptFact,
            returned = exactReturned,
            physicalRelease = physicalReleaseCandidate,
            topologyRetired = false,
            settlement = NativeCarrierFreeSettlement.UnsafeResidue,
            quarantineCause = NativeCarrierFreeQuarantineCause.TopologyRetirementUnproven,
        )
        val postRetirementOwnerResidueOutcome = NativeCarrierFreeOutcomeFact(
            attempt = occurrence.attemptFact,
            returned = exactReturned,
            physicalRelease = physicalReleaseCandidate,
            topologyRetired = if (installedOrigin) true else null,
            settlement = NativeCarrierFreeSettlement.UnsafeResidue,
            quarantineCause = NativeCarrierFreeQuarantineCause.MissingOwnerEvidence,
        )
        val completedOutcome = NativeCarrierFreeOutcomeFact(
            attempt = occurrence.attemptFact,
            returned = exactReturned,
            physicalRelease = physicalReleaseCandidate,
            topologyRetired = if (installedOrigin) true else null,
            settlement = completedSettlement,
            quarantineCause = null,
        )
        val physicalRelease = exactCarrier.markPhysicallyFreed(exactBuffer, occurrence, receipt)
        if (physicalRelease == null) {
            return gate.withLock {
                publishNativeCarrierFreeOutcomeLocked(occurrence, physicalReleaseResidueOutcome)
            }
        }
        if (physicalRelease !== physicalReleaseCandidate) {
            return gate.withLock {
                publishNativeCarrierFreeOutcomeLocked(occurrence, physicalReleaseResidueOutcome)
            }
        }

        return gate.withLock {
            if (nativeFreeOccurrence !== occurrence || occurrence.ownerBag.carrier !== exactCarrier) {
                return@withLock publishNativeCarrierFreeOutcomeLocked(occurrence, missingOwnerOutcome)
            }

            val sourceTopology = stableTopologySnapshot()
                ?: return@withLock publishNativeCarrierFreeOutcomeLocked(occurrence, topologyResidueOutcome)
            val exactInstalledProduct = sourceTopology.product === exactExpectedProduct && sourceTopology.lease == null
            if (installedOrigin && !exactInstalledProduct) {
                return@withLock publishNativeCarrierFreeOutcomeLocked(occurrence, topologyResidueOutcome)
            }

            if (installedOrigin) {
                val detachedTopology = JpegRuntimeTopologySnapshot(
                    carrierMode = sourceTopology.carrierMode,
                    nativeHealth = sourceTopology.nativeHealth,
                    product = null,
                    lease = null,
                    replacementSource = if (replacementAuthorized) exactExpectedProduct else null,
                )
                if (!topologyState.compareAndSet(sourceTopology, detachedTopology)) {
                    return@withLock publishNativeCarrierFreeOutcomeLocked(occurrence, topologyResidueOutcome)
                }
            }
            if (!occurrence.ownerBag.clearBufferLocked(gate, exactBuffer) || !occurrence.clearExpectedProductLocked(gate, exactExpectedProduct)) {
                return@withLock publishNativeCarrierFreeOutcomeLocked(occurrence, postRetirementOwnerResidueOutcome)
            }
            occurrence.ownerBag.carrier = null
            nativeFreeOccurrence = null
            publishNativeCarrierFreeOutcomeLocked(occurrence, completedOutcome)
        }
    }

    internal fun nativeCarrierFreeResidueFact(occurrence: NativeCarrierFreeOccurrence): NativeCarrierFreeResidueFact? {
        val outcome = occurrence.outcomeFact
        if (outcome != null) {
            val cause = outcome.quarantineCause ?: return null
            return NativeCarrierFreeResidueFact(
                attempt = outcome.attempt,
                submissionDisposition = occurrence.operation.submissionDisposition,
                entryDisposition = occurrence.operation.entryDisposition,
                returned = outcome.returned,
                physicalRelease = outcome.physicalRelease,
                quarantineCause = cause,
            )
        }

        val operation = occurrence.operation
        var submissionDisposition: OperationSubmissionDisposition? = null
        var entryDisposition: OperationEntryDisposition? = null
        var returnKind: NativeCarrierFreeReturnKind? = null
        var returnThrowable: Throwable? = null
        var returnReceipt: NativeCarrierFreeReceipt? = null
        operation.settlementGate.withLock {
            submissionDisposition = operation.submissionDisposition
            entryDisposition = operation.entryDisposition
            val evidence = operation.returnCell.evidence
            when (operation.returnCell.disposition) {
                OperationReturnDisposition.Empty -> Unit
                OperationReturnDisposition.Normal -> {
                    returnKind = NativeCarrierFreeReturnKind.Normal
                    returnReceipt = if (evidence.receipt === evidence.normalReceipt) evidence.normalReceipt else null
                }

                OperationReturnDisposition.Thrown -> {
                    returnKind = NativeCarrierFreeReturnKind.Thrown
                    returnThrowable = operation.returnCell.throwable
                    returnReceipt = if (evidence.receipt === evidence.normalReceipt) evidence.normalReceipt else null
                }
            }
        }
        val exactSubmissionDisposition = submissionDisposition ?: return null
        val exactEntryDisposition = entryDisposition ?: return null
        val returned = returnKind?.let {
            NativeCarrierFreeReturnFact(
                kind = it,
                throwable = returnThrowable,
                receipt = returnReceipt,
            )
        }
        val physicalRelease = occurrence.attemptFact.carrier.physicalReleaseFactFor(occurrence)
        val cause = when {
            returned == null && exactEntryDisposition == OperationEntryDisposition.Entered ->
                NativeCarrierFreeQuarantineCause.Nonreturn

            returned == null -> NativeCarrierFreeQuarantineCause.UnsettledTicket
            returned.kind == NativeCarrierFreeReturnKind.Thrown -> NativeCarrierFreeQuarantineCause.Thrown
            returned.receipt == null -> NativeCarrierFreeQuarantineCause.MissingReceipt
            physicalRelease == null -> NativeCarrierFreeQuarantineCause.PhysicalReleaseUnproven
            else -> NativeCarrierFreeQuarantineCause.TopologyRetirementUnproven
        }
        return NativeCarrierFreeResidueFact(
            attempt = occurrence.attemptFact,
            submissionDisposition = exactSubmissionDisposition,
            entryDisposition = exactEntryDisposition,
            returned = returned,
            physicalRelease = physicalRelease,
            quarantineCause = cause,
        )
    }

    internal fun settlePreparationWithoutReturnedCarrier(
        occurrence: JpegPreparationOccurrence,
    ): NoReturnedCarrierSettlement {
        if (stableTopologySnapshot() == null || preparationOccurrence !== occurrence || !releaseJpegIoOperation(occurrence)) {
            return NoReturnedCarrierSettlement.NotSettled
        }
        val gate = occurrence.operation.settlementGate
        return gate.withLock {
            if (preparationOccurrence !== occurrence) return@withLock NoReturnedCarrierSettlement.NotSettled

            val product = occurrence.ownerBag.product
            val settlement = noReturnedCarrierSettlementLocked(occurrence.operation, product?.carrier)
            if (settlement != NoReturnedCarrierSettlement.Completed) return@withLock settlement
            if (occurrence.ownerBag.nativeCarrier != null || occurrence.ownerBag.managedCarrier != null) {
                return@withLock NoReturnedCarrierSettlement.UnsafeResidue
            }

            occurrence.ownerBag.product = null
            preparationOccurrence = null
            NoReturnedCarrierSettlement.Completed
        }
    }

    internal fun settleNativeReplacementWithoutReturnedCarrier(
        occurrence: NativeCarrierReplacementAllocationOccurrence,
    ): NoReturnedCarrierSettlement {
        if (stableTopologySnapshot() == null || nativeReplacementOccurrence !== occurrence || !releaseJpegIoOperation(occurrence)) {
            return NoReturnedCarrierSettlement.NotSettled
        }
        val gate = occurrence.operation.settlementGate
        return gate.withLock {
            if (nativeReplacementOccurrence !== occurrence) return@withLock NoReturnedCarrierSettlement.NotSettled

            val carrierCandidate = occurrence.carrierCandidate
            val settlement = noReturnedCarrierSettlementLocked(occurrence.operation, carrierCandidate)
            if (settlement != NoReturnedCarrierSettlement.Completed) return@withLock settlement
            if (occurrence.ownerBag.carrier != null) return@withLock NoReturnedCarrierSettlement.UnsafeResidue

            occurrence.ownerBag.product = null
            nativeReplacementOccurrence = null
            NoReturnedCarrierSettlement.Completed
        }
    }

    internal fun settleManagedReplacementWithoutReturnedCarrier(
        occurrence: ManagedDirectCarrierReplacementAllocationOccurrence,
    ): NoReturnedCarrierSettlement {
        if (stableTopologySnapshot() == null || managedReplacementOccurrence !== occurrence || !releaseJpegIoOperation(occurrence)) {
            return NoReturnedCarrierSettlement.NotSettled
        }
        val gate = occurrence.operation.settlementGate
        return gate.withLock {
            if (managedReplacementOccurrence !== occurrence) return@withLock NoReturnedCarrierSettlement.NotSettled

            val carrierCandidate = occurrence.carrierCandidate
            val settlement = noReturnedCarrierSettlementLocked(occurrence.operation, carrierCandidate)
            if (settlement != NoReturnedCarrierSettlement.Completed) return@withLock settlement
            if (occurrence.ownerBag.carrier != null) return@withLock NoReturnedCarrierSettlement.UnsafeResidue

            occurrence.ownerBag.product = null
            managedReplacementOccurrence = null
            NoReturnedCarrierSettlement.Completed
        }
    }

    internal fun beginPreparationNativeCleanup(
        occurrence: JpegPreparationOccurrence,
        identity: JpegFiniteOperationIdentity,
    ): NativeCarrierFreeOccurrence? = beginPreparationNativeCleanup(
        occurrence = occurrence,
        identity = identity,
        operationIdentity = identity.operationIdentity,
    )

    internal fun beginTerminalPreparationNativeCleanup(
        occurrence: JpegPreparationOccurrence,
        operationIdentity: Long,
    ): NativeCarrierFreeOccurrence? = beginPreparationNativeCleanup(
        occurrence = occurrence,
        identity = null,
        operationIdentity = operationIdentity,
    )

    internal fun beginNativeReplacementCleanup(
        occurrence: NativeCarrierReplacementAllocationOccurrence,
        identity: JpegFiniteOperationIdentity,
    ): NativeCarrierFreeOccurrence? = beginNativeReplacementCleanup(
        occurrence = occurrence,
        identity = identity,
        operationIdentity = identity.operationIdentity,
    )

    internal fun beginTerminalNativeReplacementCleanup(
        occurrence: NativeCarrierReplacementAllocationOccurrence,
        operationIdentity: Long,
    ): NativeCarrierFreeOccurrence? = beginNativeReplacementCleanup(
        occurrence = occurrence,
        identity = null,
        operationIdentity = operationIdentity,
    )

    internal fun dropPreparationManagedCleanup(occurrence: JpegPreparationOccurrence): Boolean {
        if (stableTopologySnapshot() == null || preparationOccurrence !== occurrence ||
            !releaseJpegIoOperation(occurrence)
        ) {
            return false
        }
        val gate = occurrence.operation.settlementGate
        var carrier: ManagedDirectCarrier? = null
        var product: JpegRuntimeProduct.FrameworkOnManagedCarrier? = null
        gate.withLock {
            if (preparationOccurrence !== occurrence) return false
            val bagCarrier = occurrence.ownerBag.managedCarrier ?: return false
            val bagProduct = occurrence.ownerBag.product
            if (bagProduct !is JpegRuntimeProduct.FrameworkOnManagedCarrier || bagProduct.managedCarrier !== bagCarrier ||
                !returnedCarrierCanBeCleanedLocked(occurrence.operation, bagCarrier)
            ) {
                return false
            }
            carrier = bagCarrier
            product = bagProduct
        }

        val exactCarrier = checkNotNull(carrier)
        val exactProduct = checkNotNull(product)
        if (!exactCarrier.logicallyDropUninstalled(exactProduct)) return false

        return gate.withLock {
            if (preparationOccurrence !== occurrence || occurrence.ownerBag.managedCarrier !== exactCarrier ||
                occurrence.ownerBag.product !== exactProduct
            ) {
                return@withLock false
            }
            occurrence.ownerBag.managedCarrier = null
            occurrence.ownerBag.product = null
            occurrence.operation.returnCell.evidence.returnedOwner = null
            preparationOccurrence = null
            true
        }
    }

    internal fun dropManagedReplacementCleanup(occurrence: ManagedDirectCarrierReplacementAllocationOccurrence): Boolean {
        if (stableTopologySnapshot() == null || managedReplacementOccurrence !== occurrence ||
            !releaseJpegIoOperation(occurrence)
        ) {
            return false
        }
        val gate = occurrence.operation.settlementGate
        var carrier: ManagedDirectCarrier? = null
        var product: JpegRuntimeProduct.FrameworkOnManagedCarrier? = null
        gate.withLock {
            if (managedReplacementOccurrence !== occurrence) return false
            val bagCarrier = occurrence.ownerBag.carrier ?: return false
            val bagProduct = occurrence.ownerBag.product ?: return false
            if (bagProduct.managedCarrier !== bagCarrier || !returnedCarrierCanBeCleanedLocked(occurrence.operation, bagCarrier)) {
                return false
            }
            carrier = bagCarrier
            product = bagProduct
        }

        val exactCarrier = checkNotNull(carrier)
        val exactProduct = checkNotNull(product)
        if (!exactCarrier.logicallyDropUninstalled(exactProduct)) return false

        return gate.withLock {
            if (managedReplacementOccurrence !== occurrence || occurrence.ownerBag.carrier !== exactCarrier ||
                occurrence.ownerBag.product !== exactProduct
            ) {
                return@withLock false
            }
            val sourceProduct = occurrence.sourceProduct
            val carrierCandidate = occurrence.carrierCandidate
            if (carrierCandidate !== exactCarrier) return@withLock false

            occurrence.ownerBag.carrier = null
            occurrence.ownerBag.product = null
            occurrence.operation.returnCell.evidence.returnedOwner = null
            check(occurrence.clearSourceAliasesLocked(gate, sourceProduct, carrierCandidate))
            managedReplacementOccurrence = null
            true
        }
    }

    internal fun beginNativeReplacement(
        health: NativeJpegHealth,
        byteCount: Int,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): NativeCarrierReplacementAllocationOccurrence? {
        val sourceTopology = stableTopologySnapshot() ?: return null
        val sourceProduct = sourceTopology.replacementSource
        if (sourceTopology.carrierMode != JpegCarrierMode.NativeMalloc || byteCount <= 0 ||
            sourceProduct == null || sourceTopology.nativeHealth != health || sourceTopology.product != null ||
            nativeFreeOccurrence != null || nativeReplacementOccurrence != null
        ) {
            return null
        }

        val carrier = NativeMallocCarrier.create(byteCount, topologyState)
        val product = when (health) {
            NativeJpegHealth.Enabled -> JpegRuntimeProduct.NativeEnabled.create(carrier)
            NativeJpegHealth.Disabled -> JpegRuntimeProduct.FrameworkOnNativeCarrier.create(carrier)
        }
        val occurrence = NativeCarrierReplacementAllocationOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            sourceProduct = sourceProduct,
            sourceTopology = sourceTopology,
            topologyState = topologyState,
            identity = identity,
            carrier = carrier,
            product = product,
            clock = clock,
            signal = settlementSignal,
            endpoint = jpegEndpoint,
            work = { executeNativeCarrierReplacement(it, byteCount) },
        )
        nativeReplacementOccurrence = occurrence
        submitJpegIoOperation(occurrence.executorOperation)
        return occurrence
    }

    internal fun installNativeReplacement(occurrence: NativeCarrierReplacementAllocationOccurrence): Boolean {
        val sourceTopology = stableTopologySnapshot() ?: return false
        if (sourceTopology !== occurrence.transitionClaim.source || nativeReplacementOccurrence !== occurrence ||
            !releaseJpegIoOperation(occurrence)
        ) {
            return false
        }
        val gate = occurrence.operation.settlementGate
        var product: JpegRuntimeProduct? = null
        var carrier: NativeMallocCarrier? = null
        val claim = occurrence.transitionClaim
        val claimed = gate.withLock {
            if (nativeReplacementOccurrence !== occurrence || sourceTopology.product != null) return@withLock false
            val sourceProduct = occurrence.sourceProduct
            val carrierCandidate = occurrence.carrierCandidate
            if (sourceTopology.replacementSource !== sourceProduct ||
                occurrence.operation.arbitrate() != OperationArbitration.TimelyNormal
            ) {
                return@withLock false
            }

            val bagProduct = occurrence.ownerBag.product ?: return@withLock false
            val bagCarrier = occurrence.ownerBag.carrier ?: return@withLock false
            if (bagCarrier !== carrierCandidate || bagProduct.carrier !== bagCarrier ||
                !topologyState.compareAndSet(sourceTopology, claim.transitioning)
            ) {
                return@withLock false
            }
            product = bagProduct
            carrier = bagCarrier
            occurrence.ownerBag.product = null
            occurrence.ownerBag.carrier = null
            occurrence.operation.returnCell.evidence.returnedOwner = null
            occurrence.clearTransitionAliasesLocked(gate)
            nativeReplacementOccurrence = null
            true
        }
        if (!claimed) return false

        val exactProduct = checkNotNull(product)
        val exactCarrier = checkNotNull(carrier)
        if (!exactCarrier.installTransition(exactProduct, claim)) return false
        topologyState.set(claim.committed)
        return true
    }

    internal fun detachManagedForReplacement(expectedProduct: JpegRuntimeProduct.FrameworkOnManagedCarrier): Boolean {
        val sourceTopology = stableTopologySnapshot() ?: return false
        if (sourceTopology.product !== expectedProduct || sourceTopology.lease != null ||
            managedReplacementOccurrence != null
        ) {
            return false
        }
        val detachedTopology = JpegRuntimeTopologySnapshot(
            carrierMode = sourceTopology.carrierMode,
            nativeHealth = sourceTopology.nativeHealth,
            product = null,
            lease = null,
            replacementSource = expectedProduct,
        )
        if (!expectedProduct.managedCarrier.closeAdmissionAndCheckDrained(sourceTopology, expectedProduct)) return false
        if (!expectedProduct.managedCarrier.logicallyDetach(sourceTopology, expectedProduct)) return false
        return topologyState.compareAndSet(sourceTopology, detachedTopology)
    }

    internal fun beginManagedReplacement(
        byteCount: Int,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): ManagedDirectCarrierReplacementAllocationOccurrence? {
        val sourceTopology = stableTopologySnapshot() ?: return null
        val sourceProduct = sourceTopology.replacementSource as? JpegRuntimeProduct.FrameworkOnManagedCarrier
        if (sourceTopology.carrierMode != JpegCarrierMode.ManagedDirect ||
            sourceTopology.nativeHealth != NativeJpegHealth.Disabled || byteCount <= 0 ||
            sourceProduct == null || sourceTopology.product != null || managedReplacementOccurrence != null
        ) {
            return null
        }

        val carrier = ManagedDirectCarrier.create(byteCount, topologyState)
        val product = JpegRuntimeProduct.FrameworkOnManagedCarrier.create(carrier)
        val occurrence = ManagedDirectCarrierReplacementAllocationOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            sourceProduct = sourceProduct,
            sourceTopology = sourceTopology,
            topologyState = topologyState,
            identity = identity,
            carrier = carrier,
            product = product,
            clock = clock,
            signal = settlementSignal,
            endpoint = jpegEndpoint,
            work = { executeManagedCarrierReplacement(it, byteCount) },
        )
        managedReplacementOccurrence = occurrence
        submitJpegIoOperation(occurrence.executorOperation)
        return occurrence
    }

    internal fun installManagedReplacement(occurrence: ManagedDirectCarrierReplacementAllocationOccurrence): Boolean {
        val sourceTopology = stableTopologySnapshot() ?: return false
        if (sourceTopology !== occurrence.transitionClaim.source || managedReplacementOccurrence !== occurrence ||
            !releaseJpegIoOperation(occurrence)
        ) {
            return false
        }
        val gate = occurrence.operation.settlementGate
        var product: JpegRuntimeProduct.FrameworkOnManagedCarrier? = null
        var carrier: ManagedDirectCarrier? = null
        val claim = occurrence.transitionClaim
        val claimed = gate.withLock {
            if (managedReplacementOccurrence !== occurrence || sourceTopology.product != null) return@withLock false
            val sourceProduct = occurrence.sourceProduct
            val carrierCandidate = occurrence.carrierCandidate
            if (sourceTopology.replacementSource !== sourceProduct ||
                occurrence.operation.arbitrate() != OperationArbitration.TimelyNormal
            ) {
                return@withLock false
            }

            val bagProduct = occurrence.ownerBag.product ?: return@withLock false
            val bagCarrier = occurrence.ownerBag.carrier ?: return@withLock false
            if (bagCarrier !== carrierCandidate || bagProduct.carrier !== bagCarrier ||
                !topologyState.compareAndSet(sourceTopology, claim.transitioning)
            ) {
                return@withLock false
            }
            product = bagProduct
            carrier = bagCarrier
            occurrence.ownerBag.product = null
            occurrence.ownerBag.carrier = null
            occurrence.operation.returnCell.evidence.returnedOwner = null
            occurrence.clearTransitionAliasesLocked(gate)
            managedReplacementOccurrence = null
            true
        }
        if (!claimed) return false

        val exactProduct = checkNotNull(product)
        val exactCarrier = checkNotNull(carrier)
        if (!exactCarrier.installTransition(exactProduct, claim)) return false
        topologyState.set(claim.committed)
        return true
    }

    internal fun discardReplacementAuthorizationForTerminal(sourceProduct: JpegRuntimeProduct): Boolean {
        val sourceTopology = stableTopologySnapshot() ?: return false
        if (sourceTopology.replacementSource !== sourceProduct) return false
        val discardedTopology = JpegRuntimeTopologySnapshot(
            carrierMode = sourceTopology.carrierMode,
            nativeHealth = sourceTopology.nativeHealth,
            product = sourceTopology.product,
            lease = sourceTopology.lease,
            replacementSource = null,
        )
        return topologyState.compareAndSet(sourceTopology, discardedTopology)
    }

    internal fun beginNativeEncode(
        expectedProduct: JpegRuntimeProduct.NativeEnabled,
        expectedLease: NativeMallocCarrierLease,
        storage: EncodedStorageOwner,
        descriptor: NativeFrameDescriptor,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): NativeEncodeOccurrence? {
        val sourceTopology = stableTopologySnapshot() ?: return null
        if (sourceTopology.carrierMode != JpegCarrierMode.NativeMalloc ||
            sourceTopology.nativeHealth != NativeJpegHealth.Enabled ||
            sourceTopology.product !== expectedProduct || sourceTopology.lease !== expectedLease || nativeEncodeOccurrence != null
        ) return null
        val expectedStride = descriptor.width.toLong() * RGBA_PIXEL_BYTE_COUNT
        val geometryRepresentable = descriptor.width > 0 && descriptor.height > 0 && expectedStride <= Int.MAX_VALUE &&
                expectedStride <= Long.MAX_VALUE / descriptor.height.toLong()
        val expectedPixelByteCount = if (geometryRepresentable) expectedStride * descriptor.height.toLong() else -1L
        if (!geometryRepresentable ||
            descriptor.stride.toLong() != expectedStride || descriptor.pixelByteCount != expectedPixelByteCount ||
            descriptor.pixelByteCount != expectedProduct.nativeCarrier.byteCount.toLong() ||
            descriptor.quality !in MIN_JPEG_QUALITY..MAX_JPEG_QUALITY ||
            descriptor.effectiveParameters.finalImageSize.widthPx != descriptor.width ||
            descriptor.effectiveParameters.finalImageSize.heightPx != descriptor.height
        ) {
            return null
        }

        val transaction = EncodedStorageOwner.NativeTransaction()
        val storageCommands = storage.precreateEncodeSettlementCommands(transaction)
        val occurrence = NativeEncodeOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            identity = identity,
            capturedProduct = expectedProduct,
            carrierLease = expectedLease,
            sourceTopology = sourceTopology,
            topologyState = topologyState,
            descriptor = descriptor,
            storage = storage,
            transaction = transaction,
            storageCommands = storageCommands,
            clock = clock,
            signal = settlementSignal,
            endpoint = jpegEndpoint,
            work = { executeCoordinatedNativeEncode(this, it) },
        )
        occurrence.bindFinalizationCommand(
            NativeEncodeFinalizationCommand(
                owner = this,
                occurrence = occurrence,
                claim = occurrence.claim,
            ),
        )
        nativeEncodeOccurrence = occurrence
        val gate = occurrence.operation.settlementGate

        if (!expectedLease.retainForOperation(expectedProduct)) {
            if (!unwindNativeEncodeAdmission(occurrence, NATIVE_ENCODE_ADMISSION_FAILED)) {
                throw NATIVE_ENCODE_ADMISSION_FAILED
            }
            return null
        }
        gate.withLock {
            occurrence.ownerBag.retainedOperationLease = expectedLease
        }

        val attached = try {
            storage.attachProduction(transaction)
        } catch (failure: Throwable) {
            unwindNativeEncodeAdmission(occurrence, failure)
            throw failure
        }
        if (!attached) {
            if (!unwindNativeEncodeAdmission(occurrence, NATIVE_ENCODE_ADMISSION_FAILED)) {
                throw NATIVE_ENCODE_ADMISSION_FAILED
            }
            return null
        }
        gate.withLock {
            check(nativeEncodeOccurrence === occurrence)
            check(occurrence.ownerBag.storageOwner === storage)
            check(occurrence.ownerBag.transaction === transaction)
            occurrence.ownerBag.admissionDisposition = NativeEncodeAdmissionDisposition.Attached
        }

        if (!submitJpegIoOperation(occurrence.executorOperation)) {
            val rejected = gate.withLock {
                occurrence.operation.submissionDisposition == OperationSubmissionDisposition.Rejected
            }
            if (!rejected) {
                if (!unwindNativeEncodeAdmission(occurrence, NATIVE_ENCODE_ADMISSION_FAILED)) {
                    gate.withLock {
                        occurrence.ownerBag.admissionDisposition = NativeEncodeAdmissionDisposition.CleanupResidue
                        if (occurrence.ownerBag.admissionFailureCause == null) {
                            occurrence.ownerBag.admissionFailureCause = NATIVE_ENCODE_ADMISSION_FAILED
                        }
                    }
                }
                throw NATIVE_ENCODE_ADMISSION_FAILED
            }
        }
        return occurrence
    }

    internal fun claimNativeEncode(occurrence: NativeEncodeOccurrence): NativeEncodeClaimFact? {
        if (nativeEncodeOccurrence !== occurrence) return null
        val claim = occurrence.claim
        if (claim.storageIdentity !== occurrence.ownerBag.storageOwner ||
            claim.transactionIdentity !== occurrence.ownerBag.transaction
        ) {
            return null
        }
        if (claim.isPublished) return claim
        if (!releaseJpegIoOperation(occurrence)) return null

        val operation = occurrence.operation
        val gate = operation.settlementGate
        var settledResult = NativeEncodeSettlement.NotSettled
        var carrierUseResolved = false
        val published = gate.withLock {
            if (nativeEncodeOccurrence !== occurrence || occurrence.ownerBag.claim !== claim || claim.isPublished) {
                return@withLock claim.isPublished
            }
            val bag = occurrence.ownerBag
            if (bag.admissionDisposition == NativeEncodeAdmissionDisposition.CleanupResidue) {
                settledResult = NativeEncodeSettlement.InternalFailure
                carrierUseResolved = true
            } else {
                var arbitration = OperationArbitration.None
                if (operation.returnCell.use == OperationReturnUse.Unclaimed) arbitration = operation.arbitrate()
                if (operation.returnCell.use == OperationReturnUse.Unclaimed) {
                    when {
                        cancelledNativeEncodeWithoutReturnLocked(occurrence) -> {
                            settledResult = NativeEncodeSettlement.CancelledWithoutReturn
                            carrierUseResolved = true
                        }

                        terminalizedUnenteredEncodeFailureLocked(occurrence) -> {
                            settledResult = NativeEncodeSettlement.InternalFailure
                            carrierUseResolved = true
                        }

                        arbitration == OperationArbitration.SchedulerRejected ||
                                arbitration == OperationArbitration.DeadlineGuardFailed -> {
                            bag.admissionDisposition = NativeEncodeAdmissionDisposition.CleanupResidue
                            if (bag.admissionFailureCause == null) {
                                bag.admissionFailureCause = operation.submissionFailure ?: NATIVE_ENCODE_ADMISSION_FAILED
                            }
                            settledResult = NativeEncodeSettlement.InternalFailure
                            carrierUseResolved = true
                        }

                        else -> return@withLock false
                    }
                } else {
                    val evidence = operation.returnCell.evidence
                    val raw = operation.returnCell.throwable
                    settledResult = if (raw != null && raw !is Exception) {
                        NativeEncodeSettlement.DirectFatal
                    } else {
                        evidence.result ?: return@withLock false
                    }
                    carrierUseResolved = evidence.carrierUseResolved
                }
            }

            val transaction = bag.transaction ?: return@withLock false
            val payload = transaction.committedPayloadIdentity
            var claimFailure = operation.returnCell.evidence.failureCause ?: bag.admissionFailureCause ?: operation.submissionFailure
            if (settledResult == NativeEncodeSettlement.Success &&
                (payload == null || payload.effectiveParameters !== claim.effectiveParameters ||
                        payload.payload.byteCount != transaction.byteCount)
            ) {
                settledResult = NativeEncodeSettlement.InternalFailure
                claimFailure = NATIVE_CLAIM_PAYLOAD_MISMATCH
            } else if (settledResult == NativeEncodeSettlement.InternalFailure && claimFailure == null) {
                claimFailure = NATIVE_ENCODE_ADMISSION_FAILED
            }
            claim.publishNativeEvidenceLocked(
                result = settledResult,
                returnUse = operation.returnCell.use,
                returnDisposition = operation.returnCell.disposition,
                settlementElapsedRealtimeNanos = if (operation.returnCell.disposition == OperationReturnDisposition.Empty) {
                    JpegEncodeClaimFact.NO_RETURN_SETTLEMENT_NANOS
                } else {
                    operation.returnCell.settlementNanos
                },
                encodedByteCount = transaction.byteCount,
                payloadIdentity = payload,
                failureCause = claimFailure,
                rawThrowable = operation.returnCell.throwable,
                carrierUseResolved = carrierUseResolved,
                evidence = operation.returnCell.evidence,
            )
        }
        return if (published || claim.isPublished) claim else null
    }

    internal fun executeNativeEncodeFinalization(
        command: NativeEncodeFinalizationCommand,
        disposition: JpegEncodeFinalizationDisposition,
    ): JpegEncodeFinalizationReceipt {
        val receipt = command.receipt
        if (receipt.result != JpegEncodeFinalizationResult.Pending) return receipt
        val occurrence = command.occurrence
        val claim = command.claim
        if (command.owner !== this || nativeEncodeOccurrence !== occurrence || occurrence.claim !== claim || !claim.isPublished) {
            receipt.complete(JpegEncodeFinalizationResult.UnsafeResidue, false, false, false, NATIVE_CLAIM_NOT_PUBLISHED)
            return receipt
        }
        if (claim.result == NativeEncodeSettlement.DirectFatal &&
            (claim.rawThrowable == null || claim.rawThrowable is Exception || claim.rawThrowable !== jpegEndpoint.observedFatal ||
                    !occurrence.operation.returnCell.evidence.nativeCallReturned)
        ) {
            receipt.complete(JpegEncodeFinalizationResult.UnsafeResidue, false, false, false, NATIVE_FATAL_EVIDENCE_MISMATCH)
            return receipt
        }

        val commands = claim.storageCommands
        val roleApplied = when (disposition) {
            JpegEncodeFinalizationDisposition.KeepCommittedUnpublished,
            JpegEncodeFinalizationDisposition.RetireCommittedUnpublished,
                -> commands.claimCommittedProduction.receipt.let {
                it.disposition == EncodedStorageOwner.RoleTransitionDisposition.Applied &&
                        it.unpublishedPayload === claim.payloadIdentity
            }

            JpegEncodeFinalizationDisposition.RetireTransaction ->
                commands.claimProductionForRetirement.receipt.let {
                    it.disposition == EncodedStorageOwner.RoleTransitionDisposition.Applied &&
                            it.transaction === claim.transactionIdentity
                }
        }
        if (!roleApplied) {
            receipt.complete(JpegEncodeFinalizationResult.UnsafeResidue, false, false, false, NATIVE_STORAGE_ROLE_NOT_TRANSFERRED)
            return receipt
        }
        if (!claim.carrierUseResolved) {
            receipt.complete(JpegEncodeFinalizationResult.UnsafeResidue, false, false, false, NATIVE_CARRIER_USE_UNRESOLVED)
            return receipt
        }

        val storageFinalized = when (disposition) {
            JpegEncodeFinalizationDisposition.KeepCommittedUnpublished -> true
            JpegEncodeFinalizationDisposition.RetireCommittedUnpublished ->
                commands.retireClaimedUnpublished.executeUnlocked().disposition == EncodedStorageOwner.RetirementDisposition.Retired

            JpegEncodeFinalizationDisposition.RetireTransaction ->
                commands.retireClaimedTransaction.executeUnlocked().disposition == EncodedStorageOwner.RetirementDisposition.Retired
        }
        if (!storageFinalized) {
            receipt.complete(JpegEncodeFinalizationResult.UnsafeResidue, false, false, false, NATIVE_STORAGE_RETIREMENT_FAILED)
            return receipt
        }
        val storageRetired = disposition != JpegEncodeFinalizationDisposition.KeepCommittedUnpublished

        val gate = occurrence.operation.settlementGate
        val exactLease = gate.withLock {
            if (nativeEncodeOccurrence !== occurrence || occurrence.ownerBag.transaction !== claim.transactionIdentity ||
                occurrence.ownerBag.storageOwner !== claim.storageIdentity
            ) {
                return@withLock null
            }
            occurrence.ownerBag.transaction = null
            occurrence.ownerBag.segmentSink = null
            occurrence.ownerBag.storageOwner = null
            occurrence.ownerBag.retainedOperationLease
        }
        if (exactLease == null) {
            receipt.complete(JpegEncodeFinalizationResult.UnsafeResidue, false, storageRetired, false, NATIVE_CARRIER_USE_UNRESOLVED)
            return receipt
        }
        val leaseReleased = try {
            exactLease.releaseFromOperation()
        } catch (fatal: Throwable) {
            receipt.complete(JpegEncodeFinalizationResult.UnsafeResidue, false, storageRetired, false, fatal)
            throw fatal
        }
        if (!leaseReleased) {
            receipt.complete(JpegEncodeFinalizationResult.UnsafeResidue, false, storageRetired, false, NATIVE_CARRIER_LEASE_RELEASE_FAILED)
            return receipt
        }
        val aliasesReduced = gate.withLock {
            if (nativeEncodeOccurrence !== occurrence || occurrence.ownerBag.retainedOperationLease !== exactLease) {
                return@withLock false
            }
            occurrence.ownerBag.retainedOperationLease = null
            occurrence.ownerBag.clearResultBlockLocked(gate)
        }
        if (!aliasesReduced) {
            receipt.complete(JpegEncodeFinalizationResult.UnsafeResidue, true, storageRetired, false, NATIVE_RESULT_OWNER_REDUCTION_FAILED)
            return receipt
        }

        val safeHealthDecision = claim.result == NativeEncodeSettlement.SafeNativeAllocationFailure &&
                claim.claimUse == io.screenstream.engine.internal.jpeg.JpegEncodeClaimUse.Timely &&
                claim.returnDisposition == OperationReturnDisposition.Normal
        if (safeHealthDecision) {
            receipt.complete(JpegEncodeFinalizationResult.ReadyForNativeHealthDecision, true, storageRetired, false, null)
            return receipt
        }

        if (!retireNativeEncodeTransitionCandidate(occurrence)) {
            receipt.complete(JpegEncodeFinalizationResult.UnsafeResidue, true, storageRetired, false, NATIVE_TRANSITION_RETIREMENT_FAILED)
            return receipt
        }
        val occurrenceCleared = gate.withLock { clearNativeEncodeOccurrenceIfSettledLocked(occurrence) }
        receipt.complete(
            result = if (occurrenceCleared) JpegEncodeFinalizationResult.Completed else JpegEncodeFinalizationResult.UnsafeResidue,
            carrierLeaseReleased = true,
            storageRetired = storageRetired,
            producerUseReleased = occurrenceCleared,
            failure = if (occurrenceCleared) null else NATIVE_FINALIZATION_FAILED,
        )
        return receipt
    }

    internal fun commitSafeNativeDisable(occurrence: NativeEncodeOccurrence): Boolean {
        val sourceTopology = stableTopologySnapshot() ?: return false
        if (sourceTopology !== occurrence.ownerBag.transitionClaim.source || nativeEncodeOccurrence !== occurrence) {
            return false
        }
        val gate = occurrence.operation.settlementGate
        var sourceProduct: JpegRuntimeProduct.NativeEnabled? = null
        var sourceLease: NativeMallocCarrierLease? = null
        var transitionProduct: JpegRuntimeProduct.FrameworkOnNativeCarrier? = null
        var transitionLease: NativeMallocCarrierLease? = null
        val claim = occurrence.ownerBag.transitionClaim
        val claimed = gate.withLock {
            if (nativeEncodeOccurrence !== occurrence) return@withLock false
            val bag = occurrence.ownerBag
            val capturedProduct = occurrence.capturedProduct
            val timelySafeFailure = occurrence.operation.returnCell.use == OperationReturnUse.Timely &&
                    occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal &&
                    occurrence.operation.returnCell.evidence.result == NativeEncodeSettlement.SafeNativeAllocationFailure
            if (!timelySafeFailure || sourceTopology.nativeHealth != NativeJpegHealth.Enabled ||
                sourceTopology.product !== capturedProduct || sourceTopology.lease !== bag.carrierLease ||
                !occurrence.endpointReleased || !nativeEncodeMechanicsSettledLocked(bag) ||
                bag.transitionProduct?.nativeCarrier !== capturedProduct.nativeCarrier ||
                bag.transitionLease?.carrier !== capturedProduct.nativeCarrier ||
                !topologyState.compareAndSet(sourceTopology, claim.transitioning)
            ) {
                return@withLock false
            }

            sourceProduct = capturedProduct
            sourceLease = bag.carrierLease
            transitionProduct = bag.transitionProduct
            transitionLease = bag.transitionLease
            bag.clearTransitionAliasesLocked(gate)
            occurrence.clearTransitionAliasesLocked(gate)
            nativeEncodeOccurrence = null
            true
        }
        if (!claimed) {
            retireAndClearNativeEncodeTransition(occurrence)
            return false
        }

        val exactSourceProduct = checkNotNull(sourceProduct)
        val exactSourceLease = checkNotNull(sourceLease)
        val exactTransitionProduct = checkNotNull(transitionProduct)
        val exactTransitionLease = checkNotNull(transitionLease)
        val committed = exactSourceProduct.nativeCarrier.commitInstalledProductAndLeaseTransition(
            claim = claim,
            expectedProduct = exactSourceProduct,
            replacementProduct = exactTransitionProduct,
            expectedLease = exactSourceLease,
            replacementLease = exactTransitionLease,
        )
        if (!committed) return false
        topologyState.set(claim.committed)
        return true
    }

    internal fun retireSafeNativeDisable(occurrence: NativeEncodeOccurrence): Boolean {
        if (stableTopologySnapshot() == null || nativeEncodeOccurrence !== occurrence) return false
        val safelySettled = occurrence.operation.settlementGate.withLock {
            occurrence.operation.returnCell.use == OperationReturnUse.Timely &&
                    occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal &&
                    occurrence.operation.returnCell.evidence.result == NativeEncodeSettlement.SafeNativeAllocationFailure &&
                    occurrence.endpointReleased && nativeEncodeMechanicsSettledLocked(occurrence.ownerBag)
        }
        return safelySettled && retireAndClearNativeEncodeTransition(occurrence)
    }

    private fun clearNativeEncodeOccurrenceIfSettledLocked(occurrence: NativeEncodeOccurrence): Boolean {
        check(occurrence.operation.settlementGate.isHeldByCurrentThread)
        val bag = occurrence.ownerBag
        if (nativeEncodeOccurrence !== occurrence || !nativeEncodeMechanicsSettledLocked(bag)) return false

        val capturedProduct = occurrence.capturedProduct
        val product = bag.product
        val carrierLease = bag.carrierLease
        if (product !== capturedProduct) return false
        check(bag.clearFinalAliasesLocked(occurrence.operation.settlementGate, product, carrierLease))
        check(occurrence.clearCapturedProductLocked(occurrence.operation.settlementGate, capturedProduct))

        nativeEncodeOccurrence = null
        return true
    }

    private fun retireNativeEncodeTransitionCandidate(occurrence: NativeEncodeOccurrence): Boolean {
        val gate = occurrence.operation.settlementGate
        var transitionProduct: JpegRuntimeProduct.FrameworkOnNativeCarrier? = null
        var transitionLease: NativeMallocCarrierLease? = null
        gate.withLock {
            if (nativeEncodeOccurrence !== occurrence) return false
            transitionProduct = occurrence.ownerBag.transitionProduct
            transitionLease = occurrence.ownerBag.transitionLease
        }
        val exactProduct = transitionProduct ?: return false
        val exactLease = transitionLease ?: return false
        if (!exactProduct.nativeCarrier.retirePreparedTransition(exactProduct, exactLease)) return false

        return gate.withLock {
            if (nativeEncodeOccurrence !== occurrence || occurrence.ownerBag.transitionProduct !== exactProduct ||
                occurrence.ownerBag.transitionLease !== exactLease
            ) {
                return@withLock false
            }
            occurrence.ownerBag.transitionProduct = null
            occurrence.ownerBag.transitionLease = null
            true
        }
    }

    private fun retireAndClearNativeEncodeTransition(occurrence: NativeEncodeOccurrence): Boolean {
        val gate = occurrence.operation.settlementGate
        val canRetire = gate.withLock {
            nativeEncodeOccurrence === occurrence && nativeEncodeMechanicsSettledLocked(occurrence.ownerBag)
        }
        if (!canRetire || !retireNativeEncodeTransitionCandidate(occurrence)) return false
        return gate.withLock { clearNativeEncodeOccurrenceIfSettledLocked(occurrence) }
    }

    internal fun transferTerminal(
        occurrence: OperationOccurrence<*>,
        mandatoryCleanup: Boolean,
    ): OperationTerminalArbitration? =
        if (stableTopologySnapshot() == null) null else occurrence.arbitrateTerminal(mandatoryCleanup)

    private fun publishNativeCarrierFreeOutcomeLocked(
        occurrence: NativeCarrierFreeOccurrence,
        fact: NativeCarrierFreeOutcomeFact,
    ): NativeCarrierFreeSettlement {
        val gate = occurrence.operation.settlementGate
        check(gate.isHeldByCurrentThread)
        val existing = occurrence.outcomeFact
        if (existing != null) return existing.settlement

        check(occurrence.publishOutcomeFactLocked(gate, fact))
        return fact.settlement
    }

    private fun <E : OperationEvidence> noReturnedCarrierSettlementLocked(
        operation: OperationOccurrence<E>,
        carrierCandidate: RgbaCarrier?,
    ): NoReturnedCarrierSettlement {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (operation.returnCell.evidence.returnedOwner != null) return NoReturnedCarrierSettlement.NotSettled
        if (operation.returnCell.use == OperationReturnUse.Unclaimed) operation.arbitrate()
        if (operation.returnCell.evidence.returnedOwner != null) return NoReturnedCarrierSettlement.NotSettled
        if (carrierCandidate != null && !carrierCandidate.hasNoReturnedBufferLocked(operation.settlementGate)) {
            return NoReturnedCarrierSettlement.UnsafeResidue
        }

        if (operation.returnCell.disposition == OperationReturnDisposition.Thrown &&
            operation.returnCell.use != OperationReturnUse.Unclaimed
        ) {
            return NoReturnedCarrierSettlement.Completed
        }
        if (operation.returnCell.disposition == OperationReturnDisposition.Normal) {
            return NoReturnedCarrierSettlement.UnsafeResidue
        }

        val terminalSubmissionResolved = operation.submissionDisposition == OperationSubmissionDisposition.Cancelled ||
                operation.submissionDisposition == OperationSubmissionDisposition.Accepted ||
                operation.submissionDisposition == OperationSubmissionDisposition.Rejected
        val terminalizedWithoutEntry = operation.domain == OperationDomain.Cleanup &&
                operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                terminalSubmissionResolved &&
                (operation.disposition == OperationDisposition.Cancelled ||
                        operation.disposition == OperationDisposition.SchedulerRejected ||
                        operation.disposition == OperationDisposition.DeadlineGuardFailed)
        val schedulerRejectedWithoutEntry = operation.entryDisposition == OperationEntryDisposition.Unentered &&
                operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                operation.disposition == OperationDisposition.SchedulerRejected
        val deadlineGuardFailedWithoutEntry = operation.entryDisposition == OperationEntryDisposition.Unentered &&
                operation.disposition == OperationDisposition.DeadlineGuardFailed
        if (operation.returnCell.use == OperationReturnUse.Unclaimed &&
            (terminalizedWithoutEntry || schedulerRejectedWithoutEntry || deadlineGuardFailedWithoutEntry)
        ) {
            return NoReturnedCarrierSettlement.Completed
        }

        return if (operation.entryDisposition == OperationEntryDisposition.Entered) {
            NoReturnedCarrierSettlement.UnsafeResidue
        } else {
            NoReturnedCarrierSettlement.NotSettled
        }
    }

    private fun <E : OperationEvidence> returnedCarrierCanBeCleanedLocked(operation: OperationOccurrence<E>, carrier: RgbaCarrier): Boolean {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (operation.returnCell.evidence.returnedOwner !== carrier || operation.returnCell.disposition == OperationReturnDisposition.Empty) {
            return false
        }
        if (operation.returnCell.use == OperationReturnUse.Unclaimed) operation.arbitrate()
        return operation.returnCell.use != OperationReturnUse.Unclaimed
    }

    private fun beginPreparationNativeCleanup(
        occurrence: JpegPreparationOccurrence,
        identity: JpegFiniteOperationIdentity?,
        operationIdentity: Long,
    ): NativeCarrierFreeOccurrence? {
        if (stableTopologySnapshot() == null || preparationOccurrence !== occurrence ||
            !releaseJpegIoOperation(occurrence)
        ) {
            return null
        }
        val sourceGate = occurrence.operation.settlementGate
        var carrier: NativeMallocCarrier? = null
        var product: JpegRuntimeProduct? = null
        var buffer: ByteBuffer? = null
        sourceGate.withLock {
            if (preparationOccurrence !== occurrence || nativeFreeOccurrence != null) return null
            val bagCarrier = occurrence.ownerBag.nativeCarrier ?: return null
            val bagProduct = occurrence.ownerBag.product ?: return null
            if (bagProduct.carrier !== bagCarrier || !returnedCarrierCanBeCleanedLocked(occurrence.operation, bagCarrier)) {
                return null
            }
            carrier = bagCarrier
            product = bagProduct
            buffer = bagCarrier.cleanupFreeCandidateLocked(sourceGate)
        }

        val exactCarrier = checkNotNull(carrier)
        val exactProduct = checkNotNull(product)
        val provenBuffer = proveExactWritableBuffer(buffer, exactCarrier.byteCount) ?: return null
        val cleanup = createNativeCleanupFree(
            desiredRevision = occurrence.desiredRevision,
            geometryGeneration = occurrence.geometryGeneration,
            lifecycleEpoch = occurrence.lifecycleEpoch,
            expectedProduct = exactProduct,
            carrier = exactCarrier,
            buffer = provenBuffer,
            identity = identity,
            operationIdentity = operationIdentity,
        ) ?: return null
        if (!exactCarrier.admitUninstalledFree(provenBuffer, cleanup)) return null

        sourceGate.withLock {
            if (preparationOccurrence !== occurrence || occurrence.ownerBag.nativeCarrier !== exactCarrier || occurrence.ownerBag.product !== exactProduct) {
                return null
            }
            occurrence.ownerBag.nativeCarrier = null
            occurrence.ownerBag.product = null
            occurrence.operation.returnCell.evidence.returnedOwner = null
            preparationOccurrence = null
            nativeFreeOccurrence = cleanup
        }
        submitJpegIoOperation(cleanup.executorOperation)
        return cleanup
    }

    private fun beginNativeReplacementCleanup(
        occurrence: NativeCarrierReplacementAllocationOccurrence,
        identity: JpegFiniteOperationIdentity?,
        operationIdentity: Long,
    ): NativeCarrierFreeOccurrence? {
        if (stableTopologySnapshot() == null || nativeReplacementOccurrence !== occurrence ||
            !releaseJpegIoOperation(occurrence)
        ) {
            return null
        }
        val sourceGate = occurrence.operation.settlementGate
        var carrier: NativeMallocCarrier? = null
        var product: JpegRuntimeProduct? = null
        var buffer: ByteBuffer? = null
        sourceGate.withLock {
            if (nativeReplacementOccurrence !== occurrence || nativeFreeOccurrence != null) return null
            val bagCarrier = occurrence.ownerBag.carrier ?: return null
            val bagProduct = occurrence.ownerBag.product ?: return null
            if (bagProduct.carrier !== bagCarrier || !returnedCarrierCanBeCleanedLocked(occurrence.operation, bagCarrier)) {
                return null
            }
            carrier = bagCarrier
            product = bagProduct
            buffer = bagCarrier.cleanupFreeCandidateLocked(sourceGate)
        }

        val exactCarrier = checkNotNull(carrier)
        val exactProduct = checkNotNull(product)
        val provenBuffer = proveExactWritableBuffer(buffer, exactCarrier.byteCount) ?: return null
        val cleanup = createNativeCleanupFree(
            desiredRevision = occurrence.desiredRevision,
            geometryGeneration = occurrence.geometryGeneration,
            lifecycleEpoch = occurrence.lifecycleEpoch,
            expectedProduct = exactProduct,
            carrier = exactCarrier,
            buffer = provenBuffer,
            identity = identity,
            operationIdentity = operationIdentity,
        ) ?: return null
        if (!exactCarrier.admitUninstalledFree(provenBuffer, cleanup)) return null

        sourceGate.withLock {
            if (nativeReplacementOccurrence !== occurrence || occurrence.ownerBag.carrier !== exactCarrier || occurrence.ownerBag.product !== exactProduct) {
                return null
            }
            val sourceProduct = occurrence.sourceProduct
            val carrierCandidate = occurrence.carrierCandidate
            if (carrierCandidate !== exactCarrier) return null
            check(occurrence.clearSourceAliasesLocked(sourceGate, sourceProduct, carrierCandidate))
            occurrence.ownerBag.carrier = null
            occurrence.ownerBag.product = null
            occurrence.operation.returnCell.evidence.returnedOwner = null
            nativeReplacementOccurrence = null
            nativeFreeOccurrence = cleanup
        }
        submitJpegIoOperation(cleanup.executorOperation)
        return cleanup
    }

    private fun createNativeCleanupFree(
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        expectedProduct: JpegRuntimeProduct,
        carrier: NativeMallocCarrier,
        buffer: ByteBuffer,
        identity: JpegFiniteOperationIdentity?,
        operationIdentity: Long,
    ): NativeCarrierFreeOccurrence? {
        val cleanup = NativeCarrierFreeOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            expectedProduct = expectedProduct,
            origin = NativeCarrierFreeOrigin.ReturnedOwnerCleanup,
            carrier = carrier,
            buffer = buffer,
            identity = identity,
            operationIdentity = operationIdentity,
            clock = clock,
            signal = settlementSignal,
            endpoint = jpegEndpoint,
            work = ::executeNativeCarrierFree,
        )
        if (identity != null) return cleanup
        return when (cleanup.operation.arbitrateTerminal(mandatoryCleanup = true)) {
            OperationTerminalArbitration.Transferred -> cleanup
            else -> null
        }
    }

    private fun unwindNativeEncodeAdmission(occurrence: NativeEncodeOccurrence, failure: Throwable): Boolean {
        val gate = occurrence.operation.settlementGate
        var storage: EncodedStorageOwner? = null
        var transaction: EncodedStorageOwner.NativeTransaction? = null
        gate.withLock {
            if (nativeEncodeOccurrence !== occurrence) return false
            storage = occurrence.ownerBag.storageOwner
            transaction = occurrence.ownerBag.transaction
        }

        val exactStorage = storage
        val exactTransaction = transaction
        if (exactTransaction != null) {
            if (!exactTransaction.isCommitted && !exactTransaction.isAborted) exactTransaction.abort()
            val detached = exactStorage?.production !== exactTransaction ||
                    exactTransaction.isAborted && exactStorage.rollbackAbortedAdmission(exactTransaction)
            if (exactTransaction.isAborted && detached) {
                gate.withLock {
                    if (nativeEncodeOccurrence === occurrence && occurrence.ownerBag.transaction === exactTransaction &&
                        occurrence.ownerBag.storageOwner === exactStorage
                    ) {
                        occurrence.ownerBag.transaction = null
                        occurrence.ownerBag.segmentSink = null
                        occurrence.ownerBag.storageOwner = null
                    }
                }
            }
        }

        var lease: NativeMallocCarrierLease? = null
        gate.withLock {
            if (nativeEncodeOccurrence === occurrence) lease = occurrence.ownerBag.retainedOperationLease
        }
        val exactLease = lease
        if (exactLease != null && exactLease.releaseFromOperation()) {
            gate.withLock {
                if (nativeEncodeOccurrence === occurrence &&
                    occurrence.ownerBag.retainedOperationLease === exactLease
                ) {
                    occurrence.ownerBag.retainedOperationLease = null
                }
            }
        }

        val mechanicsSettled = gate.withLock {
            val bag = occurrence.ownerBag
            if (nativeEncodeOccurrence !== occurrence) return@withLock false
            if (nativeEncodeMechanicsSettledLocked(bag)) {
                true
            } else {
                bag.admissionDisposition = NativeEncodeAdmissionDisposition.CleanupResidue
                if (bag.admissionFailureCause == null) bag.admissionFailureCause = failure
                false
            }
        }
        return mechanicsSettled && retireAndClearNativeEncodeTransition(occurrence)
    }

    internal fun compressNativeFrame(
        pixels: ByteBuffer,
        bag: NativeEncodeOwnerBag,
        sink: EncodedStorageOwner.NativeSegmentSink,
        resultBlock: ByteBuffer,
    ): Unit = NativeJpegProcess.compress(
        pixels = pixels,
        pixelByteCount = bag.descriptor.pixelByteCount,
        width = bag.descriptor.width,
        height = bag.descriptor.height,
        stride = bag.descriptor.stride,
        format = ANDROID_BITMAP_FORMAT_RGBA_8888,
        flags = ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE,
        dataspace = ANDROID_DATASPACE_SRGB,
        compressFormat = ANDROID_BITMAP_COMPRESS_FORMAT_JPEG,
        quality = bag.descriptor.quality,
        sink = sink,
        resultBlock = resultBlock,
    )

    internal fun signalJpegIoSettlement() {
        jpegLane.settlementSignal.signal()
    }

    internal fun submitJpegIoOperation(operation: PrivateExecutorOperation<*>): Boolean {
        if (stableTopologySnapshot() == null) return false
        return jpegLane.submit(operation)
    }

    internal fun releaseJpegIoOperation(occurrence: JpegEndpointOccurrence): Boolean {
        topologyState.get()
        return jpegLane.release(occurrence)
    }

    internal fun requestJpegShutdown(): Boolean {
        val terminalLatch = jpegLane.latchTerminalMode()
        return jpegLane.requestShutdown(terminalLatch, currentPhysicalRootReadiness())
    }

    internal val jpegTerminationReceipt: PrivateExecutorTerminationReceipt?
        get() = jpegLane.terminationReceipt

    internal fun jpegCleanupSettlement(
        receipt: PrivateExecutorTerminationReceipt,
    ): JpegEndpointRootSettlement = jpegLane.cleanupComplete(currentPhysicalRootReadiness(), receipt)

    internal val jpegFatal: Throwable?
        get() = jpegLane.fatal

    private fun currentPhysicalRootReadiness(): JpegOwnerRootReadiness {
        val topology = stableTopologySnapshot() ?: return physicalDomainIdentity.retained()
        if (topology.product != null || topology.lease != null || topology.replacementSource != null ||
            preparationOccurrence != null || nativeFreeOccurrence != null || nativeReplacementOccurrence != null ||
            managedReplacementOccurrence != null || nativeEncodeOccurrence != null || topologyState.get() !== topology
        ) {
            return physicalDomainIdentity.retained()
        }
        val existing = settledPhysicalDomainRoots
        if (existing?.emptyTopology === topology) return existing
        return physicalDomainIdentity.settled(topology).also { settledPhysicalDomainRoots = it }
    }

    private fun proveExactWritableBuffer(buffer: ByteBuffer?, expectedByteCount: Int): ByteBuffer? {
        if (buffer == null) return null
        return try {
            buffer.position(0)
            buffer.limit(expectedByteCount)
            val direct = buffer.isDirect
            val readOnly = buffer.isReadOnly
            val capacity = buffer.capacity()
            val position = buffer.position()
            val limit = buffer.limit()
            val remaining = buffer.remaining()
            if (direct && !readOnly && capacity == expectedByteCount && position == 0 && limit == expectedByteCount && remaining == expectedByteCount) {
                buffer
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        private const val RGBA_PIXEL_BYTE_COUNT: Long = 4L
        private const val MIN_JPEG_QUALITY: Int = 0
        private const val MAX_JPEG_QUALITY: Int = 100

        private const val ANDROID_BITMAP_FORMAT_RGBA_8888: Int = 1
        private const val ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE: Long = 1L
        private const val ANDROID_DATASPACE_SRGB: Int = 142_671_872
        private const val ANDROID_BITMAP_COMPRESS_FORMAT_JPEG: Int = 0

        private val INVALID_CARRIER_BUFFER: IllegalStateException =
            IllegalStateException("carrier allocator returned a malformed direct buffer")
        private val MISSING_LOADER_FAILURE: IllegalStateException =
            IllegalStateException("native JPEG loader failed without a cause")
        private val ATTACHMENT_STATE_VIOLATION: IllegalStateException =
            IllegalStateException("carrier attachment transaction is not current")
        private val NATIVE_CLAIM_NOT_PUBLISHED: IllegalStateException =
            IllegalStateException("Native encode claim is not published")
        private val NATIVE_CLAIM_PAYLOAD_MISMATCH: IllegalStateException =
            IllegalStateException("Native encode claim payload evidence is malformed")
        private val NATIVE_FATAL_EVIDENCE_MISMATCH: IllegalStateException =
            IllegalStateException("Native fatal encode evidence does not match the endpoint authority")
        private val NATIVE_STORAGE_ROLE_NOT_TRANSFERRED: IllegalStateException =
            IllegalStateException("Native encode storage role was not transferred by Session")
        private val NATIVE_STORAGE_RETIREMENT_FAILED: IllegalStateException =
            IllegalStateException("Native encode storage retirement failed")
        private val NATIVE_CARRIER_USE_UNRESOLVED: IllegalStateException =
            IllegalStateException("Native encode carrier use remains unresolved")
        private val NATIVE_CARRIER_LEASE_RELEASE_FAILED: IllegalStateException =
            IllegalStateException("Native encode carrier lease release failed")
        private val NATIVE_RESULT_OWNER_REDUCTION_FAILED: IllegalStateException =
            IllegalStateException("Native encode result ownership could not be reduced")
        private val NATIVE_TRANSITION_RETIREMENT_FAILED: IllegalStateException =
            IllegalStateException("Native encode fallback transition retirement failed")
        private val NATIVE_FINALIZATION_FAILED: IllegalStateException =
            IllegalStateException("Native encode finalization did not settle every owner")
    }
}
