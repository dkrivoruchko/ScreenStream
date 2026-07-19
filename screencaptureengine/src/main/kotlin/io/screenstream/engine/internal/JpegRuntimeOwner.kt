package io.screenstream.engine.internal

import io.screenstream.engine.JpegBackendPolicy
import io.screenstream.engine.internal.JpegEndpointShutdownAction
import io.screenstream.engine.internal.jpeg.CarrierValidation
import io.screenstream.engine.internal.jpeg.JpegCarrierMode
import io.screenstream.engine.internal.jpeg.JpegEndpointOccurrence
import io.screenstream.engine.internal.jpeg.JpegFiniteOperationIdentity
import io.screenstream.engine.internal.jpeg.JpegPreparationEvidence
import io.screenstream.engine.internal.jpeg.JpegPreparationOccurrence
import io.screenstream.engine.internal.jpeg.JpegRuntimeFailure
import io.screenstream.engine.internal.jpeg.JpegRuntimeProduct
import io.screenstream.engine.internal.jpeg.JpegRuntimeTopologySnapshot
import io.screenstream.engine.internal.jpeg.JpegRuntimeTopologyState
import io.screenstream.engine.internal.jpeg.ManagedDirectCarrier
import io.screenstream.engine.internal.jpeg.ManagedDirectCarrierReplacementAllocationOccurrence
import io.screenstream.engine.internal.jpeg.NATIVE_ENCODE_ADMISSION_FAILED
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeOccurrence
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeOrigin
import io.screenstream.engine.internal.jpeg.NativeCarrierFreeSettlement
import io.screenstream.engine.internal.jpeg.NativeCarrierReplacementAllocationOccurrence
import io.screenstream.engine.internal.jpeg.NativeEncodeAdmissionDisposition
import io.screenstream.engine.internal.jpeg.NativeEncodeFatalCleanupSettlement
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
    internal val cleanupShutdownAction: JpegEndpointShutdownAction = JpegEndpointShutdownAction(this)
    private val jpegEndpoint = PrivateExecutorRuntime("ScreenCapture-JPEG", settlementSignal)
    private val topologyState = AtomicReference<JpegRuntimeTopologyState>(JpegRuntimeTopologySnapshot.Empty)

    private var preparationOccurrence: JpegPreparationOccurrence? = null
    private var nativeFreeOccurrence: NativeCarrierFreeOccurrence? = null
    private var nativeReplacementOccurrence: NativeCarrierReplacementAllocationOccurrence? = null
    private var managedReplacementOccurrence: ManagedDirectCarrierReplacementAllocationOccurrence? = null
    private var nativeEncodeOccurrence: NativeEncodeOccurrence? = null
    internal fun stableTopologySnapshot(): JpegRuntimeTopologySnapshot? =
        topologyState.get() as? JpegRuntimeTopologySnapshot

    internal val installedCarrierMode: JpegCarrierMode?
        get() = stableTopologySnapshot()?.carrierMode

    internal val installedNativeHealth: NativeJpegHealth?
        get() = stableTopologySnapshot()?.nativeHealth

    internal val jpegIoSettlementSignal: SettlementSignal
        get() = settlementSignal

    internal val jpegExecutorEndpoint: PrivateExecutorRuntime
        get() = jpegEndpoint

    internal val jpegEndpointStartupState: PrivateExecutorStartupDisposition
        get() = jpegEndpoint.startupState

    internal val jpegEndpointStartupFailure: Throwable?
        get() = jpegEndpoint.observedStartupFailure

    internal fun prestartJpegEndpoint(): PrivateExecutorStartupDisposition = jpegEndpoint.prestart()

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
            work = { executePreparation(it, policy, byteCount) },
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
            work = ::executeNativeFree,
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
            work = ::executeNativeFree,
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
        if (stableTopologySnapshot() == null || nativeFreeOccurrence !== occurrence || !releaseJpegIoOperation(occurrence)) {
            return NativeCarrierFreeSettlement.NotSettled
        }
        val gate = occurrence.operation.settlementGate
        var carrier: NativeMallocCarrier? = null
        var timely = false
        var normal = false
        var exactReceipt = false
        var buffer: ByteBuffer? = null
        var expectedProduct: JpegRuntimeProduct? = null
        gate.withLock {
            if (nativeFreeOccurrence !== occurrence) return NativeCarrierFreeSettlement.NotSettled
            if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
            if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) {
                return NativeCarrierFreeSettlement.NotSettled
            }

            val evidence = occurrence.operation.returnCell.evidence
            timely = occurrence.operation.returnCell.use == OperationReturnUse.Timely
            normal = occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal
            exactReceipt = evidence.receipt === evidence.normalReceipt
            carrier = occurrence.ownerBag.carrier
            buffer = occurrence.ownerBag.buffer
            expectedProduct = occurrence.expectedProduct
        }

        val exactCarrier = carrier
        val exactBuffer = buffer
        val exactExpectedProduct = expectedProduct
        if (!normal || !exactReceipt || exactCarrier == null || exactBuffer == null || exactExpectedProduct == null) {
            return NativeCarrierFreeSettlement.UnsafeResidue
        }
        if (!exactCarrier.markPhysicallyFreed(exactBuffer, occurrence)) {
            return NativeCarrierFreeSettlement.UnsafeResidue
        }

        return gate.withLock {
            if (nativeFreeOccurrence !== occurrence || occurrence.ownerBag.carrier !== exactCarrier) {
                return@withLock NativeCarrierFreeSettlement.UnsafeResidue
            }

            val installedOrigin = occurrence.origin != NativeCarrierFreeOrigin.ReturnedOwnerCleanup
            val sourceTopology = stableTopologySnapshot() ?: return@withLock NativeCarrierFreeSettlement.NotSettled
            val exactInstalledProduct = sourceTopology.product === exactExpectedProduct && sourceTopology.lease == null
            if (installedOrigin && !exactInstalledProduct) return@withLock NativeCarrierFreeSettlement.UnsafeResidue

            val replacementAuthorized = timely && occurrence.origin == NativeCarrierFreeOrigin.IncompatibleReplacement
            if (!occurrence.ownerBag.clearBufferLocked(gate, exactBuffer) || !occurrence.clearExpectedProductLocked(gate, exactExpectedProduct)) {
                return@withLock NativeCarrierFreeSettlement.UnsafeResidue
            }
            occurrence.ownerBag.carrier = null
            nativeFreeOccurrence = null
            if (installedOrigin) {
                val detachedTopology = JpegRuntimeTopologySnapshot(
                    carrierMode = sourceTopology.carrierMode,
                    nativeHealth = sourceTopology.nativeHealth,
                    product = null,
                    lease = null,
                    replacementSource = if (replacementAuthorized) exactExpectedProduct else null,
                )
                if (!topologyState.compareAndSet(sourceTopology, detachedTopology)) {
                    return@withLock NativeCarrierFreeSettlement.UnsafeResidue
                }
            }
            if (replacementAuthorized) {
                NativeCarrierFreeSettlement.ReplacementAuthorized
            } else {
                NativeCarrierFreeSettlement.CleanupCompleted
            }
        }
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
        if (!exactCarrier.logicallyDropUninstalled()) return false

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
        if (!exactCarrier.logicallyDropUninstalled()) return false

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
            work = { executeNativeReplacement(it, byteCount) },
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
            work = { executeManagedReplacement(it, byteCount) },
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
            descriptor.imageSize.widthPx != descriptor.width || descriptor.imageSize.heightPx != descriptor.height
        ) {
            return null
        }

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
            clock = clock,
            signal = settlementSignal,
            endpoint = jpegEndpoint,
            work = ::executeNativeEncode,
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

        val transaction = try {
            EncodedStorageOwner.NativeTransaction()
        } catch (failure: Throwable) {
            unwindNativeEncodeAdmission(occurrence, failure)
            throw failure
        }
        gate.withLock {
            occurrence.ownerBag.storageOwner = storage
            occurrence.ownerBag.transaction = transaction
            occurrence.ownerBag.segmentSink = transaction.segmentSink
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

    internal fun settleNativeEncode(
        occurrence: NativeEncodeOccurrence,
        storage: EncodedStorageOwner,
        retainCommittedFrame: Boolean,
    ): NativeEncodeSettlement {
        if (stableTopologySnapshot() == null || nativeEncodeOccurrence !== occurrence) {
            return NativeEncodeSettlement.NotSettled
        }
        val gate = occurrence.operation.settlementGate
        if (!releaseJpegIoOperation(occurrence)) return NativeEncodeSettlement.NotSettled
        var settledResult = NativeEncodeSettlement.NotSettled
        var timelyNormal = false
        var carrierUseResolved = false
        gate.withLock {
            if (nativeEncodeOccurrence !== occurrence) return NativeEncodeSettlement.NotSettled
            val bag = occurrence.ownerBag
            if (bag.storageOwner != null && bag.storageOwner !== storage) return NativeEncodeSettlement.NotSettled

            if (bag.admissionDisposition == NativeEncodeAdmissionDisposition.CleanupResidue) {
                settledResult = NativeEncodeSettlement.InternalFailure
                carrierUseResolved = true
                if (bag.retainCommittedFrame == null) bag.retainCommittedFrame = false
            } else {
                var arbitration = OperationArbitration.None
                if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) {
                    arbitration = occurrence.operation.arbitrate()
                }
                if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) {
                    if (cancelledNativeEncodeWithoutReturnLocked(occurrence)) {
                        settledResult = NativeEncodeSettlement.CancelledWithoutReturn
                        carrierUseResolved = true
                        if (bag.retainCommittedFrame == null) bag.retainCommittedFrame = false
                    } else if (terminalizedUnenteredEncodeFailureLocked(occurrence)) {
                        settledResult = NativeEncodeSettlement.InternalFailure
                        carrierUseResolved = true
                        if (bag.retainCommittedFrame == null) bag.retainCommittedFrame = false
                    } else if (arbitration != OperationArbitration.SchedulerRejected &&
                        arbitration != OperationArbitration.DeadlineGuardFailed
                    ) {
                        return NativeEncodeSettlement.NotSettled
                    } else {
                        bag.admissionDisposition = NativeEncodeAdmissionDisposition.CleanupResidue
                        if (bag.admissionFailureCause == null) {
                            bag.admissionFailureCause = occurrence.operation.submissionFailure
                                ?: NATIVE_ENCODE_ADMISSION_FAILED
                        }
                        settledResult = NativeEncodeSettlement.InternalFailure
                        carrierUseResolved = true
                        if (bag.retainCommittedFrame == null) bag.retainCommittedFrame = false
                    }
                } else {
                    val evidence = occurrence.operation.returnCell.evidence
                    settledResult = evidence.result ?: return NativeEncodeSettlement.NotSettled
                    timelyNormal = occurrence.operation.returnCell.use == OperationReturnUse.Timely &&
                            occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal
                    carrierUseResolved = evidence.carrierUseResolved
                    if (bag.retainCommittedFrame == null) {
                        bag.retainCommittedFrame = retainCommittedFrame && timelyNormal
                    }
                    bag.clearResultBlockLocked(gate)
                }
            }
        }

        var lease: NativeMallocCarrierLease? = null
        gate.withLock {
            if (nativeEncodeOccurrence === occurrence && carrierUseResolved) {
                lease = occurrence.ownerBag.retainedOperationLease
            }
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

        var transaction: EncodedStorageOwner.NativeTransaction? = null
        var storageOwner: EncodedStorageOwner? = null
        var retainPayload = false
        gate.withLock {
            if (nativeEncodeOccurrence === occurrence) {
                transaction = occurrence.ownerBag.transaction
                storageOwner = occurrence.ownerBag.storageOwner
                retainPayload = occurrence.ownerBag.retainCommittedFrame == true
            }
        }
        val exactTransaction = transaction
        val exactStorage = storageOwner
        if (exactTransaction != null && exactStorage != null) {
            if (exactTransaction.isCommitted && settledResult == NativeEncodeSettlement.Success) {
                val unpublished = exactStorage.replaceCommittedProduction(exactTransaction)
                if (unpublished != null) {
                    gate.withLock {
                        if (nativeEncodeOccurrence === occurrence &&
                            occurrence.ownerBag.transaction === exactTransaction &&
                            occurrence.ownerBag.storageOwner === exactStorage
                        ) {
                            if (!retainPayload) occurrence.ownerBag.unpublishedToRetire = unpublished
                            occurrence.ownerBag.transaction = null
                            occurrence.ownerBag.segmentSink = null
                        }
                    }
                }
            } else {
                if (!exactTransaction.isCommitted && !exactTransaction.isAborted) exactTransaction.abort()
                if (exactTransaction.isAborted && exactStorage.detachAbortedProduction(exactTransaction)) {
                    gate.withLock {
                        if (nativeEncodeOccurrence === occurrence &&
                            occurrence.ownerBag.transaction === exactTransaction &&
                            occurrence.ownerBag.storageOwner === exactStorage
                        ) {
                            occurrence.ownerBag.transaction = null
                            occurrence.ownerBag.segmentSink = null
                        }
                    }
                }
            }
        }

        var unpublished: EncodedStorageOwner.UnpublishedEncodedPayload? = null
        gate.withLock {
            if (nativeEncodeOccurrence === occurrence) unpublished = occurrence.ownerBag.unpublishedToRetire
        }
        val exactUnpublished = unpublished
        if (exactUnpublished != null && exactStorage != null && exactStorage.retireUnpublished(exactUnpublished)) {
            gate.withLock {
                if (nativeEncodeOccurrence === occurrence &&
                    occurrence.ownerBag.unpublishedToRetire === exactUnpublished &&
                    occurrence.ownerBag.storageOwner === exactStorage
                ) {
                    occurrence.ownerBag.unpublishedToRetire = null
                }
            }
        }

        val safeDisable = timelyNormal && settledResult == NativeEncodeSettlement.SafeNativeAllocationFailure
        val mechanicsSettled = gate.withLock {
            val bag = occurrence.ownerBag
            if (nativeEncodeOccurrence !== occurrence) return@withLock false
            if (bag.transaction == null && bag.unpublishedToRetire == null) bag.storageOwner = null
            nativeEncodeMechanicsSettledLocked(bag)
        }
        if (!mechanicsSettled) return NativeEncodeSettlement.NotSettled
        if (safeDisable) return settledResult
        if (!retireNativeEncodeTransitionCandidate(occurrence)) return NativeEncodeSettlement.NotSettled
        return gate.withLock {
            clearNativeEncodeOccurrenceIfSettledLocked(occurrence)
            if (nativeEncodeOccurrence === occurrence) return@withLock NativeEncodeSettlement.NotSettled
            settledResult
        }
    }

    internal fun settleReturnedFatalNativeEncodeCleanup(
        occurrence: NativeEncodeOccurrence,
        storage: EncodedStorageOwner,
    ): NativeEncodeFatalCleanupSettlement {
        topologyState.get()
        val gate = occurrence.operation.settlementGate
        val alreadyReduced = gate.withLock { occurrence.operation.returnCell.evidence.fatalCleanupReduced }
        if (alreadyReduced) return NativeEncodeFatalCleanupSettlement.Reduced
        if (nativeEncodeOccurrence !== occurrence) return NativeEncodeFatalCleanupSettlement.NotReady

        val readyForPhysicalRelease = gate.withLock {
            val operation = occurrence.operation
            val bag = occurrence.ownerBag
            val evidence = operation.returnCell.evidence
            if (bag.storageOwner != null && bag.storageOwner !== storage) {
                return@withLock NativeEncodeFatalCleanupSettlement.UnsafeResidue
            }
            if (operation.domain != OperationDomain.Cleanup || operation.entryDisposition != OperationEntryDisposition.Entered ||
                operation.returnCell.disposition != OperationReturnDisposition.Thrown
            ) {
                return@withLock NativeEncodeFatalCleanupSettlement.NotReady
            }
            if (operation.returnCell.use == OperationReturnUse.Unclaimed) operation.arbitrate()
            val raw = operation.returnCell.throwable
            when {
                operation.returnCell.use != OperationReturnUse.Cleanup -> NativeEncodeFatalCleanupSettlement.NotReady
                raw == null || raw is Exception || raw !== jpegEndpoint.observedFatal ->
                    NativeEncodeFatalCleanupSettlement.UnsafeResidue

                !evidence.nativeCallReturned -> NativeEncodeFatalCleanupSettlement.UnsafeResidue
                !evidence.carrierUseResolved -> NativeEncodeFatalCleanupSettlement.UnsafeResidue
                evidence.result != null -> NativeEncodeFatalCleanupSettlement.UnsafeResidue
                else -> null
            }
        }
        if (readyForPhysicalRelease != null) return readyForPhysicalRelease
        if (!releaseJpegIoOperation(occurrence)) return NativeEncodeFatalCleanupSettlement.NotReady

        var retainedLease: NativeMallocCarrierLease? = null
        gate.withLock {
            if (nativeEncodeOccurrence === occurrence) retainedLease = occurrence.ownerBag.retainedOperationLease
        }
        val exactRetainedLease = retainedLease
        if (exactRetainedLease != null) {
            if (!exactRetainedLease.releaseFromOperation()) return NativeEncodeFatalCleanupSettlement.UnsafeResidue
            gate.withLock {
                if (nativeEncodeOccurrence !== occurrence ||
                    occurrence.ownerBag.retainedOperationLease !== exactRetainedLease
                ) {
                    return NativeEncodeFatalCleanupSettlement.UnsafeResidue
                }
                occurrence.ownerBag.retainedOperationLease = null
            }
        }

        var transaction: EncodedStorageOwner.NativeTransaction? = null
        gate.withLock {
            if (nativeEncodeOccurrence === occurrence) transaction = occurrence.ownerBag.transaction
        }
        val exactTransaction = transaction
        if (exactTransaction != null) {
            try {
                if (!exactTransaction.isCommitted && !exactTransaction.isAborted) exactTransaction.abort()
            } catch (_: Throwable) {
                return NativeEncodeFatalCleanupSettlement.UnsafeResidue
            }
            if (exactTransaction.isCommitted || !exactTransaction.isAborted ||
                !storage.detachAbortedProduction(exactTransaction)
            ) {
                return NativeEncodeFatalCleanupSettlement.UnsafeResidue
            }
            gate.withLock {
                if (nativeEncodeOccurrence !== occurrence || occurrence.ownerBag.transaction !== exactTransaction ||
                    occurrence.ownerBag.storageOwner !== storage
                ) {
                    return NativeEncodeFatalCleanupSettlement.UnsafeResidue
                }
                occurrence.ownerBag.transaction = null
                occurrence.ownerBag.segmentSink = null
            }
        }

        var unpublished: EncodedStorageOwner.UnpublishedEncodedPayload? = null
        gate.withLock {
            if (nativeEncodeOccurrence === occurrence) unpublished = occurrence.ownerBag.unpublishedToRetire
        }
        val exactUnpublished = unpublished
        if (exactUnpublished != null) {
            if (!storage.retireUnpublished(exactUnpublished)) return NativeEncodeFatalCleanupSettlement.UnsafeResidue
            gate.withLock {
                if (nativeEncodeOccurrence !== occurrence ||
                    occurrence.ownerBag.unpublishedToRetire !== exactUnpublished
                ) {
                    return NativeEncodeFatalCleanupSettlement.UnsafeResidue
                }
                occurrence.ownerBag.unpublishedToRetire = null
            }
        }

        gate.withLock {
            val bag = occurrence.ownerBag
            if (nativeEncodeOccurrence !== occurrence) return NativeEncodeFatalCleanupSettlement.UnsafeResidue
            if (bag.transaction == null && bag.unpublishedToRetire == null) bag.storageOwner = null
            if (!nativeEncodeMechanicsSettledLocked(bag)) return NativeEncodeFatalCleanupSettlement.UnsafeResidue
            if (!bag.clearResultBlockLocked(gate)) return NativeEncodeFatalCleanupSettlement.UnsafeResidue
        }
        if (!retireNativeEncodeTransitionCandidate(occurrence)) {
            return NativeEncodeFatalCleanupSettlement.UnsafeResidue
        }
        return gate.withLock {
            if (!clearNativeEncodeOccurrenceIfSettledLocked(occurrence)) {
                return@withLock NativeEncodeFatalCleanupSettlement.UnsafeResidue
            }
            occurrence.operation.returnCell.evidence.fatalCleanupReduced = true
            NativeEncodeFatalCleanupSettlement.Reduced
        }
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
            work = ::executeNativeFree,
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
                    exactTransaction.isAborted && exactStorage.detachAbortedProduction(exactTransaction)
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
        settlementSignal.signal()
    }

    internal fun submitJpegIoOperation(operation: PrivateExecutorOperation<*>): Boolean =
        stableTopologySnapshot() != null && jpegEndpoint.submit(operation).isHandedOff

    internal fun releaseJpegIoOperation(occurrence: JpegEndpointOccurrence): Boolean {
        topologyState.get()
        if (occurrence.endpointReleased) return true
        if (!jpegEndpoint.releaseSettledOperation(occurrence.executorOperation)) return false
        occurrence.endpointReleased = true
        return true
    }

    internal val isJpegShutdownReady: Boolean
        get() {
            val topology = stableTopologySnapshot() ?: return false
            return topology.product == null && topology.lease == null && topology.replacementSource == null &&
                    preparationOccurrence == null && nativeFreeOccurrence == null &&
                    nativeReplacementOccurrence == null && managedReplacementOccurrence == null &&
                    nativeEncodeOccurrence == null && jpegEndpoint.observedFatal == null &&
                    !jpegEndpoint.hasUnsettledOperation
        }

    internal fun requestJpegShutdown(): Boolean =
        isJpegShutdownReady && jpegEndpoint.requestShutdown()

    internal val jpegTerminationReceipt: PrivateExecutorTerminationReceipt?
        get() = jpegEndpoint.terminationReceipt

    internal fun acceptsJpegTerminationReceipt(receipt: PrivateExecutorTerminationReceipt): Boolean =
        jpegEndpoint.acceptsTerminationReceipt(receipt)

    internal fun isJpegCleanupComplete(receipt: PrivateExecutorTerminationReceipt): Boolean =
        isJpegShutdownReady && jpegTerminationReceipt === receipt && acceptsJpegTerminationReceipt(receipt)

    internal val jpegFatal: Throwable?
        get() = jpegEndpoint.observedFatal

    private fun executePreparation(occurrence: JpegPreparationOccurrence, policy: JpegBackendPolicy, byteCount: Int) {
        val loadState = NativeJpegProcess.ensureAvailable()
        when (loadState) {
            NativeJpegProcess.State.LoadOome ->
                publishFailure(occurrence.operation, JpegRuntimeFailure.ResourceExhausted, NativeJpegProcess.cause() ?: MISSING_LOADER_FAILURE)

            NativeJpegProcess.State.Poisoned ->
                publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, NativeJpegProcess.cause() ?: MISSING_LOADER_FAILURE)

            NativeJpegProcess.State.Available -> prepareWithNativeCarrier(occurrence, policy, byteCount)
            NativeJpegProcess.State.CleanUnavailable -> {
                occurrence.operation.returnCell.evidence.cleanNativeUnavailabilityCause = NativeJpegProcess.cause()
                prepareWithManagedCarrier(occurrence, byteCount)
            }

            NativeJpegProcess.State.Unattempted -> error("loader result cannot remain unattempted")
        }
    }

    private fun prepareWithNativeCarrier(occurrence: JpegPreparationOccurrence, policy: JpegBackendPolicy, byteCount: Int) {
        val carrier = try {
            NativeMallocCarrier.create(byteCount, topologyState)
        } catch (failure: Exception) {
            publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
            return
        } catch (fatal: Error) {
            publishPreparationFatalAndRethrow(occurrence, fatal)
        }
        val nativeAvailable = if (policy == JpegBackendPolicy.Auto) {
            try {
                NativeJpegProcess.hasWeakCompressor()
            } catch (failure: Exception) {
                publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
                return
            } catch (fatal: Error) {
                publishPreparationFatalAndRethrow(occurrence, fatal)
            }
        } else {
            false
        }
        val product: JpegRuntimeProduct = try {
            if (nativeAvailable) {
                JpegRuntimeProduct.NativeEnabled.create(carrier)
            } else {
                JpegRuntimeProduct.FrameworkOnNativeCarrier.create(carrier)
            }
        } catch (failure: Exception) {
            publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
            return
        } catch (fatal: Error) {
            publishPreparationFatalAndRethrow(occurrence, fatal)
        }
        occurrence.ownerBag.product = product

        val buffer = try {
            NativeJpegProcess.allocateCarrier(byteCount.toLong())
        } catch (allocationFailure: OutOfMemoryError) {
            publishFailure(occurrence.operation, JpegRuntimeFailure.ResourceExhausted, allocationFailure)
            return
        } catch (failure: Exception) {
            publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
            return
        } catch (fatal: Error) {
            publishPreparationFatalAndRethrow(occurrence, fatal)
        }
        attachAndPublishNativePreparation(occurrence, carrier, buffer)
    }

    private fun prepareWithManagedCarrier(occurrence: JpegPreparationOccurrence, byteCount: Int) {
        val carrier = try {
            ManagedDirectCarrier.create(byteCount, topologyState)
        } catch (failure: Exception) {
            publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
            return
        } catch (fatal: Error) {
            publishPreparationFatalAndRethrow(occurrence, fatal)
        }
        occurrence.ownerBag.product = try {
            JpegRuntimeProduct.FrameworkOnManagedCarrier.create(carrier)
        } catch (failure: Exception) {
            publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
            return
        } catch (fatal: Error) {
            publishPreparationFatalAndRethrow(occurrence, fatal)
        }
        val buffer = try {
            ByteBuffer.allocateDirect(byteCount)
        } catch (allocationFailure: OutOfMemoryError) {
            publishFailure(occurrence.operation, JpegRuntimeFailure.ResourceExhausted, allocationFailure)
            return
        } catch (failure: Exception) {
            publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, failure)
            return
        } catch (fatal: Error) {
            publishPreparationFatalAndRethrow(occurrence, fatal)
        }
        attachAndPublishManagedPreparation(occurrence, carrier, buffer)
    }

    private fun attachAndPublishNativePreparation(occurrence: JpegPreparationOccurrence, carrier: NativeMallocCarrier, buffer: ByteBuffer) {
        val gate = occurrence.operation.settlementGate
        gate.withLock {
            if (!carrier.attachReturnedBufferLocked(gate, buffer)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.ownerBag.nativeCarrier = carrier
        }
        try {
            inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
        } catch (fatal: Error) {
            publishPreparationFatalAndRethrow(occurrence, fatal)
        }
        val validation = occurrence.validation
        val validationFailure = validation.failure
        val published = gate.withLock {
            if (!carrier.completeAttachmentLocked(gate, validation.ready)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.operation.returnCell.evidence.returnedOwner = carrier
            if (validationFailure == null) {
                occurrence.operation.publishNormalReturn()
            } else {
                occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
                occurrence.operation.returnCell.evidence.failureCause = validationFailure
                publishInternalFailure(occurrence.operation, validationFailure)
            }
        }
        if (published) settlementSignal.signal()
    }

    private fun attachAndPublishManagedPreparation(occurrence: JpegPreparationOccurrence, carrier: ManagedDirectCarrier, buffer: ByteBuffer) {
        val gate = occurrence.operation.settlementGate
        gate.withLock {
            if (!carrier.attachReturnedBufferLocked(gate, buffer)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.ownerBag.managedCarrier = carrier
        }
        try {
            inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
        } catch (fatal: Error) {
            publishPreparationFatalAndRethrow(occurrence, fatal)
        }
        val validation = occurrence.validation
        val validationFailure = validation.failure
        val published = gate.withLock {
            if (!carrier.completeAttachmentLocked(gate, validation.ready)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.operation.returnCell.evidence.returnedOwner = carrier
            if (validationFailure == null) {
                occurrence.operation.publishNormalReturn()
            } else {
                occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
                occurrence.operation.returnCell.evidence.failureCause = validationFailure
                publishInternalFailure(occurrence.operation, validationFailure)
            }
        }
        if (published) settlementSignal.signal()
    }

    private fun executeNativeFree(occurrence: NativeCarrierFreeOccurrence) {
        val published = try {
            NativeJpegProcess.freeCarrier(occurrence.ownerBag.buffer)
            occurrence.operation.returnCell.evidence.receipt = occurrence.operation.returnCell.evidence.normalReceipt
            occurrence.operation.publishNormalReturn()
        } catch (failure: Exception) {
            occurrence.operation.publishThrownReturn(failure)
        } catch (fatal: Error) {
            publishNativeFreeFatalAndRethrow(occurrence, fatal)
        }
        if (published) settlementSignal.signal()
    }

    private fun executeNativeReplacement(occurrence: NativeCarrierReplacementAllocationOccurrence, byteCount: Int) {
        val carrier = occurrence.carrierCandidate
        val buffer = try {
            NativeJpegProcess.allocateCarrier(byteCount.toLong())
        } catch (allocationFailure: OutOfMemoryError) {
            occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.ResourceExhausted
            occurrence.operation.returnCell.evidence.failureCause = allocationFailure
            if (occurrence.operation.publishNormalReturn()) settlementSignal.signal()
            return
        } catch (failure: Exception) {
            occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
            occurrence.operation.returnCell.evidence.failureCause = failure
            if (occurrence.operation.publishThrownReturn(failure)) settlementSignal.signal()
            return
        } catch (fatal: Error) {
            publishNativeReplacementFatalAndRethrow(occurrence, fatal)
        }
        attachAndPublishNativeReplacement(occurrence, carrier, buffer)
    }

    private fun attachAndPublishNativeReplacement(
        occurrence: NativeCarrierReplacementAllocationOccurrence,
        carrier: NativeMallocCarrier,
        buffer: ByteBuffer,
    ) {
        val gate = occurrence.operation.settlementGate
        gate.withLock {
            if (!carrier.attachReturnedBufferLocked(gate, buffer)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.ownerBag.carrier = carrier
        }
        try {
            inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
        } catch (fatal: Error) {
            publishNativeReplacementFatalAndRethrow(occurrence, fatal)
        }
        val validation = occurrence.validation
        val validationFailure = validation.failure
        val published = gate.withLock {
            if (!carrier.completeAttachmentLocked(gate, validation.ready)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.operation.returnCell.evidence.returnedOwner = carrier
            if (validationFailure == null) {
                occurrence.operation.publishNormalReturn()
            } else {
                occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
                occurrence.operation.returnCell.evidence.failureCause = validationFailure
                publishInternalFailure(occurrence.operation, validationFailure)
            }
        }
        if (published) settlementSignal.signal()
    }

    private fun executeManagedReplacement(occurrence: ManagedDirectCarrierReplacementAllocationOccurrence, byteCount: Int) {
        val carrier = occurrence.carrierCandidate
        val buffer = try {
            ByteBuffer.allocateDirect(byteCount)
        } catch (allocationFailure: OutOfMemoryError) {
            occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.ResourceExhausted
            occurrence.operation.returnCell.evidence.failureCause = allocationFailure
            if (occurrence.operation.publishNormalReturn()) settlementSignal.signal()
            return
        } catch (failure: Exception) {
            occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
            occurrence.operation.returnCell.evidence.failureCause = failure
            if (occurrence.operation.publishThrownReturn(failure)) settlementSignal.signal()
            return
        } catch (fatal: Error) {
            publishManagedReplacementFatalAndRethrow(occurrence, fatal)
        }
        attachAndPublishManagedReplacement(occurrence, carrier, buffer)
    }

    private fun attachAndPublishManagedReplacement(
        occurrence: ManagedDirectCarrierReplacementAllocationOccurrence,
        carrier: ManagedDirectCarrier,
        buffer: ByteBuffer,
    ) {
        val gate = occurrence.operation.settlementGate
        gate.withLock {
            if (!carrier.attachReturnedBufferLocked(gate, buffer)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.ownerBag.carrier = carrier
        }
        try {
            inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
        } catch (fatal: Error) {
            publishManagedReplacementFatalAndRethrow(occurrence, fatal)
        }
        val validation = occurrence.validation
        val validationFailure = validation.failure
        val published = gate.withLock {
            if (!carrier.completeAttachmentLocked(gate, validation.ready)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.operation.returnCell.evidence.returnedOwner = carrier
            if (validationFailure == null) {
                occurrence.operation.publishNormalReturn()
            } else {
                occurrence.operation.returnCell.evidence.failure = JpegRuntimeFailure.InternalFailure
                occurrence.operation.returnCell.evidence.failureCause = validationFailure
                publishInternalFailure(occurrence.operation, validationFailure)
            }
        }
        if (published) settlementSignal.signal()
    }

    private fun executeNativeEncode(occurrence: NativeEncodeOccurrence) = executeCoordinatedNativeEncode(this, occurrence)

    private fun publishFailure(operation: OperationOccurrence<JpegPreparationEvidence>, failure: JpegRuntimeFailure, cause: Throwable) {
        operation.returnCell.evidence.failure = failure
        operation.returnCell.evidence.failureCause = cause
        val published = when (cause) {
            is Exception -> operation.publishThrownReturn(cause)
            is OutOfMemoryError -> {
                check(failure == JpegRuntimeFailure.ResourceExhausted)
                operation.publishNormalReturn()
            }

            else -> {
                operation.publishDirectFatalReturn(cause)
                throw cause
            }
        }
        if (published) settlementSignal.signal()
    }

    private fun <R : OperationEvidence> publishInternalFailure(
        operation: OperationOccurrence<R>,
        cause: Throwable,
    ): Boolean = when (cause) {
        is Exception -> operation.publishThrownReturn(cause)
        else -> {
            operation.publishDirectFatalReturn(cause)
            throw cause
        }
    }

    private fun publishPreparationFatalAndRethrow(occurrence: JpegPreparationOccurrence, fatal: Error): Nothing {
        if (occurrence.operation.publishDirectFatalReturn(fatal)) settlementSignal.signal()
        throw fatal
    }

    private fun publishNativeFreeFatalAndRethrow(occurrence: NativeCarrierFreeOccurrence, fatal: Error): Nothing {
        if (occurrence.operation.publishDirectFatalReturn(fatal)) settlementSignal.signal()
        throw fatal
    }

    private fun publishNativeReplacementFatalAndRethrow(
        occurrence: NativeCarrierReplacementAllocationOccurrence,
        fatal: Error,
    ): Nothing {
        if (occurrence.operation.publishDirectFatalReturn(fatal)) settlementSignal.signal()
        throw fatal
    }

    private fun publishManagedReplacementFatalAndRethrow(
        occurrence: ManagedDirectCarrierReplacementAllocationOccurrence,
        fatal: Error,
    ): Nothing {
        if (occurrence.operation.publishDirectFatalReturn(fatal)) settlementSignal.signal()
        throw fatal
    }

    private fun inspectBuffer(buffer: ByteBuffer, expectedByteCount: Int, validation: CarrierValidation) {
        try {
            val direct = buffer.isDirect
            val readOnly = buffer.isReadOnly
            val capacity = buffer.capacity()
            val position = buffer.position()
            val limit = buffer.limit()
            val remaining = buffer.remaining()
            val structurallyFreeable = direct && !readOnly && capacity == expectedByteCount
            val ready = structurallyFreeable && position == 0 && limit == expectedByteCount && remaining == expectedByteCount
            validation.structurallyFreeable = structurallyFreeable
            validation.ready = ready
            validation.failure = if (ready) null else INVALID_CARRIER_BUFFER
        } catch (failure: Exception) {
            validation.structurallyFreeable = false
            validation.ready = false
            validation.failure = failure
        } catch (fatal: Error) {
            validation.structurallyFreeable = false
            validation.ready = false
            validation.failure = fatal
            throw fatal
        }
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
    }
}
