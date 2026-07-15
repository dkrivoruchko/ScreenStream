package io.screenstream.engine.internal

import io.screenstream.engine.JpegBackendPolicy
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionRejectionResult
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration
import io.screenstream.engine.internal.settlement.SettlementSignal
import kotlinx.coroutines.CoroutineDispatcher
import java.nio.ByteBuffer
import kotlin.concurrent.withLock
import kotlin.coroutines.EmptyCoroutineContext

internal class JpegRuntimeOwner internal constructor(
    private val jpegIoDispatcher: CoroutineDispatcher,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
) {
    private var currentProduct: JpegRuntimeProduct? = null
    private var currentLease: RgbaCarrierLease? = null
    private var nativeCallBlocks: NativeCallBlocks? = null
    private var nativeReplacementAuthorizedSource: JpegRuntimeProduct? = null
    private var managedReplacementAuthorizedSource: JpegRuntimeProduct.FrameworkOnManagedCarrier? = null

    private var preparationOccurrence: JpegPreparationOccurrence? = null
    private var nativeFreeOccurrence: NativeCarrierFreeOccurrence? = null
    private var nativeReplacementOccurrence: NativeCarrierReplacementAllocationOccurrence? = null
    private var managedReplacementOccurrence: ManagedDirectCarrierReplacementAllocationOccurrence? = null
    private var nativeEncodeOccurrence: NativeEncodeOccurrence? = null

    internal val product: JpegRuntimeProduct?
        get() = currentProduct

    internal val lease: RgbaCarrierLease?
        get() = currentLease

    internal val jpegIoSettlementSignal: SettlementSignal
        get() = settlementSignal

    internal fun prepare(
        policy: JpegBackendPolicy,
        byteCount: Int,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): JpegPreparationOccurrence? {
        if (byteCount <= 0 || currentProduct != null || preparationOccurrence != null) return null

        val occurrence = JpegPreparationOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            identity = identity,
            clock = clock,
            signal = settlementSignal,
            work = { executePreparation(it, policy, byteCount) },
        )
        preparationOccurrence = occurrence
        submitJpegIoOperation(occurrence.ioOperation)
        return occurrence
    }

    internal fun installPrepared(occurrence: JpegPreparationOccurrence): Boolean {
        if (preparationOccurrence !== occurrence || occurrence.operation.arbitrate() != OperationArbitration.TimelyNormal) {
            return false
        }

        val product = occurrence.ownerBag.product ?: return false
        val carrier = product.carrier
        if (!carrier.install(product)) return false

        occurrence.ownerBag.product = null
        occurrence.ownerBag.nativeCarrier = null
        occurrence.ownerBag.managedCarrier = null
        currentProduct = product
        nativeCallBlocks = occurrence.ownerBag.nativeCallBlocks
        occurrence.ownerBag.nativeCallBlocks = null
        preparationOccurrence = null
        return true
    }

    internal fun acquireInitialLease(expectedProduct: JpegRuntimeProduct): RgbaCarrierLease? {
        if (currentProduct !== expectedProduct || currentLease != null) return null

        val candidate = expectedProduct.initialLease
        if (!expectedProduct.carrier.acquireLease(expectedProduct, candidate)) return null

        currentLease = candidate
        return candidate
    }

    internal fun releaseCurrentLease(expectedLease: RgbaCarrierLease): Boolean {
        if (currentLease !== expectedLease || !expectedLease.release()) return false

        currentLease = null
        return true
    }

    internal fun beginNativeCarrierFree(
        expectedProduct: JpegRuntimeProduct,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): NativeCarrierFreeOccurrence? {
        if (currentProduct !== expectedProduct || currentLease != null || nativeFreeOccurrence != null ||
            nativeReplacementOccurrence != null || nativeReplacementAuthorizedSource != null
        ) {
            return null
        }

        val carrier = when (expectedProduct) {
            is JpegRuntimeProduct.NativeEnabled -> expectedProduct.nativeCarrier
            is JpegRuntimeProduct.FrameworkOnNativeCarrier -> expectedProduct.nativeCarrier
            is JpegRuntimeProduct.FrameworkOnManagedCarrier -> return null
        }
        if (!carrier.closeAdmissionAndCheckDrained(expectedProduct)) return null
        val buffer = proveExactWritableBuffer(carrier.freeCandidate(expectedProduct), carrier.byteCount) ?: return null

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
            work = ::executeNativeFree,
        )
        if (!carrier.admitInstalledFree(expectedProduct, buffer, occurrence)) return null

        nativeFreeOccurrence = occurrence
        submitJpegIoOperation(occurrence.ioOperation)
        return occurrence
    }

    internal fun beginTerminalNativeCarrierFree(
        expectedProduct: JpegRuntimeProduct,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        operationIdentity: Long,
    ): NativeCarrierFreeOccurrence? {
        if (currentProduct !== expectedProduct || currentLease != null || nativeFreeOccurrence != null ||
            nativeReplacementOccurrence != null
        ) {
            return null
        }

        val carrier = when (expectedProduct) {
            is JpegRuntimeProduct.NativeEnabled -> expectedProduct.nativeCarrier
            is JpegRuntimeProduct.FrameworkOnNativeCarrier -> expectedProduct.nativeCarrier
            is JpegRuntimeProduct.FrameworkOnManagedCarrier -> return null
        }
        if (!carrier.closeAdmissionAndCheckDrained(expectedProduct)) return null
        val buffer = proveExactWritableBuffer(carrier.freeCandidate(expectedProduct), carrier.byteCount) ?: return null
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
            work = ::executeNativeFree,
        )
        if (occurrence.operation.arbitrateTerminal(mandatoryCleanup = true) != OperationTerminalArbitration.Transferred ||
            !carrier.admitInstalledFree(expectedProduct, buffer, occurrence)
        ) {
            return null
        }

        nativeFreeOccurrence = occurrence
        submitJpegIoOperation(occurrence.ioOperation)
        return occurrence
    }

    internal fun settleNativeCarrierFree(occurrence: NativeCarrierFreeOccurrence): NativeCarrierFreeSettlement {
        val gate = occurrence.operation.settlementGate
        var carrier: NativeMallocCarrier? = null
        var timely = false
        var normal = false
        var exactReceipt = false
        var buffer: ByteBuffer? = null
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
        }

        val exactCarrier = carrier
        val exactBuffer = buffer
        if (!normal || !exactReceipt || exactCarrier == null || exactBuffer == null) {
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
            val exactInstalledProduct = currentProduct === occurrence.expectedProduct && currentLease == null
            if (installedOrigin && !exactInstalledProduct) return@withLock NativeCarrierFreeSettlement.UnsafeResidue

            val replacementAuthorized = timely && occurrence.origin == NativeCarrierFreeOrigin.IncompatibleReplacement
            occurrence.ownerBag.carrier = null
            nativeFreeOccurrence = null
            if (installedOrigin) currentProduct = null
            if (replacementAuthorized) {
                nativeReplacementAuthorizedSource = occurrence.expectedProduct
                NativeCarrierFreeSettlement.ReplacementAuthorized
            } else {
                NativeCarrierFreeSettlement.CleanupCompleted
            }
        }
    }

    internal fun settlePreparationWithoutReturnedCarrier(
        occurrence: JpegPreparationOccurrence,
    ): NoReturnedCarrierSettlement {
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
            occurrence.ownerBag.nativeCallBlocks = null
            preparationOccurrence = null
            NoReturnedCarrierSettlement.Completed
        }
    }

    internal fun settleNativeReplacementWithoutReturnedCarrier(
        occurrence: NativeCarrierReplacementAllocationOccurrence,
    ): NoReturnedCarrierSettlement {
        val gate = occurrence.operation.settlementGate
        return gate.withLock {
            if (nativeReplacementOccurrence !== occurrence) return@withLock NoReturnedCarrierSettlement.NotSettled

            val settlement = noReturnedCarrierSettlementLocked(occurrence.operation, occurrence.carrierCandidate)
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
        val gate = occurrence.operation.settlementGate
        return gate.withLock {
            if (managedReplacementOccurrence !== occurrence) return@withLock NoReturnedCarrierSettlement.NotSettled

            val settlement = noReturnedCarrierSettlementLocked(occurrence.operation, occurrence.carrierCandidate)
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
            preparationOccurrence = null
            true
        }
    }

    internal fun dropManagedReplacementCleanup(occurrence: ManagedDirectCarrierReplacementAllocationOccurrence): Boolean {
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
            occurrence.ownerBag.carrier = null
            occurrence.ownerBag.product = null
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
        val sourceProduct = nativeReplacementAuthorizedSource
        if (byteCount <= 0 || sourceProduct == null || sourceProduct.nativeHealth != health || currentProduct != null ||
            nativeFreeOccurrence != null || nativeReplacementOccurrence != null
        ) {
            return null
        }

        val carrier = NativeMallocCarrier.create(byteCount)
        val product = when (health) {
            NativeJpegHealth.Enabled -> JpegRuntimeProduct.NativeEnabled.create(carrier)
            NativeJpegHealth.Disabled -> JpegRuntimeProduct.FrameworkOnNativeCarrier.create(carrier)
        }
        val occurrence = NativeCarrierReplacementAllocationOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            sourceProduct = sourceProduct,
            identity = identity,
            carrier = carrier,
            product = product,
            clock = clock,
            signal = settlementSignal,
            work = { executeNativeReplacement(it, byteCount) },
        )
        nativeReplacementOccurrence = occurrence
        submitJpegIoOperation(occurrence.ioOperation)
        return occurrence
    }

    internal fun installNativeReplacement(occurrence: NativeCarrierReplacementAllocationOccurrence): Boolean {
        if (nativeReplacementOccurrence !== occurrence || currentProduct != null ||
            nativeReplacementAuthorizedSource !== occurrence.sourceProduct ||
            occurrence.operation.arbitrate() != OperationArbitration.TimelyNormal
        ) {
            return false
        }

        val product = occurrence.ownerBag.product ?: return false
        val carrier = occurrence.ownerBag.carrier ?: return false
        if (product.carrier !== carrier || !carrier.install(product)) return false

        occurrence.ownerBag.product = null
        occurrence.ownerBag.carrier = null
        currentProduct = product
        nativeReplacementOccurrence = null
        nativeReplacementAuthorizedSource = null
        return true
    }

    internal fun detachManagedForReplacement(expectedProduct: JpegRuntimeProduct.FrameworkOnManagedCarrier): Boolean {
        if (currentProduct !== expectedProduct || currentLease != null || managedReplacementOccurrence != null) return false
        if (!expectedProduct.managedCarrier.closeAdmissionAndCheckDrained(expectedProduct)) return false
        if (!expectedProduct.managedCarrier.logicallyDetach(expectedProduct)) return false

        currentProduct = null
        managedReplacementAuthorizedSource = expectedProduct
        return true
    }

    internal fun beginManagedReplacement(
        byteCount: Int,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): ManagedDirectCarrierReplacementAllocationOccurrence? {
        val sourceProduct = managedReplacementAuthorizedSource
        if (byteCount <= 0 || sourceProduct == null || currentProduct != null || managedReplacementOccurrence != null) {
            return null
        }

        val carrier = ManagedDirectCarrier.create(byteCount)
        val product = JpegRuntimeProduct.FrameworkOnManagedCarrier.create(carrier)
        val occurrence = ManagedDirectCarrierReplacementAllocationOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            sourceProduct = sourceProduct,
            identity = identity,
            carrier = carrier,
            product = product,
            clock = clock,
            signal = settlementSignal,
            work = { executeManagedReplacement(it, byteCount) },
        )
        managedReplacementOccurrence = occurrence
        submitJpegIoOperation(occurrence.ioOperation)
        return occurrence
    }

    internal fun installManagedReplacement(occurrence: ManagedDirectCarrierReplacementAllocationOccurrence): Boolean {
        if (managedReplacementOccurrence !== occurrence || currentProduct != null ||
            managedReplacementAuthorizedSource !== occurrence.sourceProduct ||
            occurrence.operation.arbitrate() != OperationArbitration.TimelyNormal
        ) {
            return false
        }

        val product = occurrence.ownerBag.product ?: return false
        val carrier = occurrence.ownerBag.carrier ?: return false
        if (product.carrier !== carrier || !carrier.install(product)) return false

        occurrence.ownerBag.product = null
        occurrence.ownerBag.carrier = null
        currentProduct = product
        managedReplacementOccurrence = null
        managedReplacementAuthorizedSource = null
        return true
    }

    internal fun discardReplacementAuthorizationForTerminal(sourceProduct: JpegRuntimeProduct): Boolean {
        return when {
            nativeReplacementAuthorizedSource === sourceProduct -> {
                nativeReplacementAuthorizedSource = null
                true
            }

            managedReplacementAuthorizedSource === sourceProduct -> {
                managedReplacementAuthorizedSource = null
                true
            }

            else -> false
        }
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
        if (currentProduct !== expectedProduct || currentLease !== expectedLease || nativeEncodeOccurrence != null) return null
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

        val blocks = nativeCallBlocks ?: return null
        val nativeDisableCandidate = JpegRuntimeProduct.FrameworkOnNativeCarrier.create(expectedProduct.nativeCarrier)
        val occurrence = NativeEncodeOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            identity = identity,
            capturedProduct = expectedProduct,
            carrierLease = expectedLease,
            descriptor = descriptor,
            nativeDisableCandidate = nativeDisableCandidate,
            clock = clock,
            signal = settlementSignal,
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

        val loan = blocks.acquire()
        if (loan == null) {
            if (!unwindNativeEncodeAdmission(occurrence, NATIVE_ENCODE_ADMISSION_FAILED)) {
                throw NATIVE_ENCODE_ADMISSION_FAILED
            }
            return null
        }
        gate.withLock {
            occurrence.ownerBag.callBlocksOwner = blocks
            occurrence.ownerBag.callBlockLoan = loan
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

        if (!submitJpegIoOperation(occurrence.ioOperation)) {
            val rejected = gate.withLock {
                occurrence.operation.submissionDisposition == OperationSubmissionDisposition.Rejected
            }
            if (!rejected) {
                gate.withLock {
                    occurrence.ownerBag.admissionDisposition = NativeEncodeAdmissionDisposition.CleanupResidue
                    if (occurrence.ownerBag.admissionFailureCause == null) {
                        occurrence.ownerBag.admissionFailureCause = NATIVE_ENCODE_ADMISSION_FAILED
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
        val gate = occurrence.operation.settlementGate
        var settledResult = NativeEncodeSettlement.NotSettled
        var timelyNormal = false
        var residueDisposition = NativeWriterResidueDisposition.Unresolved
        var carrierUseResolved = false
        gate.withLock {
            if (nativeEncodeOccurrence !== occurrence) return NativeEncodeSettlement.NotSettled
            val bag = occurrence.ownerBag
            if (bag.storageOwner != null && bag.storageOwner !== storage) return NativeEncodeSettlement.NotSettled

            if (bag.admissionDisposition == NativeEncodeAdmissionDisposition.CleanupResidue) {
                settledResult = NativeEncodeSettlement.InternalFailure
                residueDisposition = bag.callBlockLoan?.writerResidueDisposition
                    ?: NativeWriterResidueDisposition.NoNativeEntry
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
                        residueDisposition = NativeWriterResidueDisposition.NoNativeEntry
                        carrierUseResolved = true
                        if (bag.retainCommittedFrame == null) bag.retainCommittedFrame = false
                    } else if (terminalizedUnenteredEncodeFailureLocked(occurrence)) {
                        settledResult = NativeEncodeSettlement.InternalFailure
                        residueDisposition = NativeWriterResidueDisposition.NoNativeEntry
                        carrierUseResolved = true
                        if (bag.retainCommittedFrame == null) bag.retainCommittedFrame = false
                    } else if (arbitration != OperationArbitration.SchedulerRejected &&
                        arbitration != OperationArbitration.DeadlineGuardFailed
                    ) {
                        return NativeEncodeSettlement.NotSettled
                    } else {
                        bag.admissionDisposition = NativeEncodeAdmissionDisposition.CleanupResidue
                        if (bag.admissionFailureCause == null) {
                            bag.admissionFailureCause = occurrence.operation.submissionRejection
                                ?: NATIVE_ENCODE_ADMISSION_FAILED
                        }
                        settledResult = NativeEncodeSettlement.InternalFailure
                        residueDisposition = bag.callBlockLoan?.writerResidueDisposition
                            ?: NativeWriterResidueDisposition.NoNativeEntry
                        carrierUseResolved = true
                        if (bag.retainCommittedFrame == null) bag.retainCommittedFrame = false
                    }
                } else {
                    val evidence = occurrence.operation.returnCell.evidence
                    settledResult = evidence.result ?: return NativeEncodeSettlement.NotSettled
                    timelyNormal = occurrence.operation.returnCell.use == OperationReturnUse.Timely &&
                            occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal
                    residueDisposition = evidence.writerResidueDisposition
                    carrierUseResolved = evidence.carrierUseResolved
                    if (bag.retainCommittedFrame == null) {
                        bag.retainCommittedFrame = retainCommittedFrame && timelyNormal
                    }
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

        var callBlocks: NativeCallBlocks? = null
        var loan: NativeCallBlockLoan? = null
        gate.withLock {
            if (nativeEncodeOccurrence === occurrence && residueDisposition != NativeWriterResidueDisposition.Unresolved) {
                callBlocks = occurrence.ownerBag.callBlocksOwner
                loan = occurrence.ownerBag.callBlockLoan
            }
        }
        val exactCallBlocks = callBlocks
        val exactLoan = loan
        if (exactCallBlocks != null && exactLoan != null && exactCallBlocks.release(exactLoan)) {
            gate.withLock {
                if (nativeEncodeOccurrence === occurrence && occurrence.ownerBag.callBlocksOwner === exactCallBlocks &&
                    occurrence.ownerBag.callBlockLoan === exactLoan
                ) {
                    occurrence.ownerBag.callBlockLoan = null
                    occurrence.ownerBag.callBlocksOwner = null
                }
            }
        }

        val preserveDisableCandidate = timelyNormal &&
                settledResult == NativeEncodeSettlement.SafeNativeAllocationFailure
        if (!preserveDisableCandidate) {
            gate.withLock {
                if (nativeEncodeOccurrence === occurrence &&
                    occurrence.ownerBag.nativeDisableStage == NativeDisableStage.Candidate
                ) {
                    occurrence.ownerBag.nativeDisableCandidate = null
                    occurrence.ownerBag.nativeDisableStage = NativeDisableStage.Finalized
                }
            }
        }

        return gate.withLock {
            val bag = occurrence.ownerBag
            if (nativeEncodeOccurrence !== occurrence) return@withLock NativeEncodeSettlement.NotSettled
            if (bag.transaction == null && bag.unpublishedToRetire == null) bag.storageOwner = null
            if (!nativeEncodeMechanicsSettledLocked(bag)) return@withLock NativeEncodeSettlement.NotSettled
            if (preserveDisableCandidate && bag.nativeDisableStage == NativeDisableStage.Candidate &&
                bag.nativeDisableCandidate != null
            ) {
                return@withLock settledResult
            }
            if (bag.nativeDisableCandidate != null || bag.nativeDisableStage != NativeDisableStage.Finalized) {
                return@withLock NativeEncodeSettlement.NotSettled
            }

            nativeEncodeOccurrence = null
            settledResult
        }
    }

    internal fun authorizeNativeDisable(occurrence: NativeEncodeOccurrence): NativeDisableResult {
        val gate = occurrence.operation.settlementGate
        return gate.withLock {
            if (nativeEncodeOccurrence !== occurrence) return@withLock NativeDisableResult.NotReady
            val bag = occurrence.ownerBag
            val timelySafeFailure = occurrence.operation.returnCell.use == OperationReturnUse.Timely &&
                    occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal &&
                    occurrence.operation.returnCell.evidence.result == NativeEncodeSettlement.SafeNativeAllocationFailure
            if (!timelySafeFailure || bag.nativeDisableStage != NativeDisableStage.Candidate ||
                currentProduct !== occurrence.capturedProduct || currentLease !== bag.carrierLease ||
                !nativeEncodeMechanicsSettledLocked(bag) || bag.nativeDisableCandidate == null
            ) {
                return@withLock NativeDisableResult.NotReady
            }

            bag.nativeDisableStage = NativeDisableStage.Authorized
            NativeDisableResult.Authorized
        }
    }

    internal fun performNativeDisableTransition(occurrence: NativeEncodeOccurrence): NativeDisableResult {
        val gate = occurrence.operation.settlementGate
        var candidate: JpegRuntimeProduct.FrameworkOnNativeCarrier? = null
        var carrierLease: NativeMallocCarrierLease? = null
        gate.withLock {
            if (nativeEncodeOccurrence !== occurrence ||
                occurrence.ownerBag.nativeDisableStage != NativeDisableStage.Authorized
            ) {
                return NativeDisableResult.NotReady
            }

            candidate = occurrence.ownerBag.nativeDisableCandidate
            carrierLease = occurrence.ownerBag.carrierLease
            if (candidate == null) return NativeDisableResult.NotReady
            occurrence.ownerBag.nativeDisableStage = NativeDisableStage.Transitioning
        }

        val exactCandidate = checkNotNull(candidate)
        val exactLease = checkNotNull(carrierLease)
        val carrierBound = occurrence.capturedProduct.nativeCarrier.rebindInstalledProductAndLease(
            expectedProduct = occurrence.capturedProduct,
            replacementProduct = exactCandidate,
            expectedLease = exactLease,
        )

        val result = gate.withLock {
            if (nativeEncodeOccurrence !== occurrence ||
                occurrence.ownerBag.nativeDisableStage != NativeDisableStage.Transitioning ||
                occurrence.ownerBag.nativeDisableCandidate !== exactCandidate ||
                occurrence.ownerBag.carrierLease !== exactLease
            ) {
                return@withLock NativeDisableResult.NotReady
            }

            if (carrierBound) {
                occurrence.ownerBag.nativeDisableStage = NativeDisableStage.CarrierBound
                NativeDisableResult.CarrierBound
            } else {
                occurrence.ownerBag.nativeDisableStage = NativeDisableStage.TransitionRejected
                NativeDisableResult.TransitionRejected
            }
        }
        settlementSignal.signal()
        return result
    }

    internal fun commitNativeDisable(occurrence: NativeEncodeOccurrence): NativeDisableResult {
        val gate = occurrence.operation.settlementGate
        return gate.withLock {
            if (nativeEncodeOccurrence !== occurrence) return@withLock NativeDisableResult.NotReady
            val bag = occurrence.ownerBag
            val candidate = bag.nativeDisableCandidate ?: return@withLock NativeDisableResult.NotReady
            if (bag.nativeDisableStage != NativeDisableStage.CarrierBound ||
                currentProduct !== occurrence.capturedProduct || currentLease !== bag.carrierLease ||
                !nativeEncodeMechanicsSettledLocked(bag)
            ) {
                return@withLock NativeDisableResult.NotReady
            }

            currentProduct = candidate
            bag.nativeDisableCandidate = null
            bag.nativeDisableStage = NativeDisableStage.Finalized
            clearNativeEncodeOccurrenceIfSettledLocked(occurrence)
            NativeDisableResult.Committed
        }
    }

    internal fun finalizeTerminalNativeDisable(occurrence: NativeEncodeOccurrence): NativeDisableResult {
        val gate = occurrence.operation.settlementGate
        return gate.withLock {
            if (nativeEncodeOccurrence !== occurrence) return@withLock NativeDisableResult.NotReady
            val bag = occurrence.ownerBag
            when (bag.nativeDisableStage) {
                NativeDisableStage.Candidate,
                NativeDisableStage.Authorized,
                    -> {
                    bag.nativeDisableCandidate = null
                    bag.nativeDisableStage = NativeDisableStage.Finalized
                }

                NativeDisableStage.Transitioning -> return@withLock NativeDisableResult.NotReady
                NativeDisableStage.CarrierBound -> {
                    val candidate = bag.nativeDisableCandidate ?: return@withLock NativeDisableResult.NotReady
                    if (currentProduct !== occurrence.capturedProduct || currentLease !== bag.carrierLease) {
                        return@withLock NativeDisableResult.NotReady
                    }

                    currentProduct = candidate
                    bag.nativeDisableCandidate = null
                    bag.nativeDisableStage = NativeDisableStage.Finalized
                }

                NativeDisableStage.TransitionRejected -> {
                    bag.nativeDisableCandidate = null
                    bag.nativeDisableStage = NativeDisableStage.Finalized
                }

                NativeDisableStage.Finalized -> Unit
            }

            clearNativeEncodeOccurrenceIfSettledLocked(occurrence)
            NativeDisableResult.TerminalFinalized
        }
    }

    private fun cancelledNativeEncodeWithoutReturnLocked(occurrence: NativeEncodeOccurrence): Boolean {
        val operation = occurrence.operation
        check(operation.settlementGate.isHeldByCurrentThread)
        val submissionResolved = operation.submissionDisposition == OperationSubmissionDisposition.Cancelled ||
                operation.submissionDisposition == OperationSubmissionDisposition.Accepted ||
                operation.submissionDisposition == OperationSubmissionDisposition.Rejected
        return operation.domain == OperationDomain.Cleanup &&
                operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                submissionResolved &&
                operation.disposition == OperationDisposition.Cancelled &&
                operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                operation.returnCell.use == OperationReturnUse.Unclaimed &&
                occurrence.ownerBag.callBlockLoan?.writerResidueDisposition == NativeWriterResidueDisposition.NoNativeEntry
    }

    private fun terminalizedUnenteredEncodeFailureLocked(occurrence: NativeEncodeOccurrence): Boolean {
        val operation = occurrence.operation
        check(operation.settlementGate.isHeldByCurrentThread)
        val terminalizedFailure = operation.disposition == OperationDisposition.SchedulerRejected ||
                operation.disposition == OperationDisposition.DeadlineGuardFailed
        return operation.domain == OperationDomain.Cleanup && terminalizedFailure &&
                operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                operation.submissionDisposition == OperationSubmissionDisposition.Cancelled &&
                operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                operation.returnCell.use == OperationReturnUse.Unclaimed &&
                occurrence.ownerBag.callBlockLoan?.writerResidueDisposition == NativeWriterResidueDisposition.NoNativeEntry
    }

    private fun nativeEncodeMechanicsSettledLocked(bag: NativeEncodeOwnerBag): Boolean =
        bag.retainedOperationLease == null && bag.callBlocksOwner == null && bag.callBlockLoan == null &&
                bag.storageOwner == null && bag.transaction == null && bag.segmentSink == null &&
                bag.unpublishedToRetire == null

    private fun clearNativeEncodeOccurrenceIfSettledLocked(occurrence: NativeEncodeOccurrence) {
        check(occurrence.operation.settlementGate.isHeldByCurrentThread)
        val bag = occurrence.ownerBag
        if (nativeEncodeOccurrence === occurrence && nativeEncodeMechanicsSettledLocked(bag) &&
            bag.nativeDisableCandidate == null && bag.nativeDisableStage == NativeDisableStage.Finalized
        ) {
            nativeEncodeOccurrence = null
        }
    }

    internal fun transferTerminal(occurrence: OperationOccurrence<*>, mandatoryCleanup: Boolean): OperationTerminalArbitration =
        occurrence.arbitrateTerminal(mandatoryCleanup)

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
            occurrence.ownerBag.nativeCallBlocks = null
            preparationOccurrence = null
            nativeFreeOccurrence = cleanup
        }
        submitJpegIoOperation(cleanup.ioOperation)
        return cleanup
    }

    private fun beginNativeReplacementCleanup(
        occurrence: NativeCarrierReplacementAllocationOccurrence,
        identity: JpegFiniteOperationIdentity?,
        operationIdentity: Long,
    ): NativeCarrierFreeOccurrence? {
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
            occurrence.ownerBag.carrier = null
            occurrence.ownerBag.product = null
            nativeReplacementOccurrence = null
            nativeFreeOccurrence = cleanup
        }
        submitJpegIoOperation(cleanup.ioOperation)
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

        var blocks: NativeCallBlocks? = null
        var loan: NativeCallBlockLoan? = null
        gate.withLock {
            if (nativeEncodeOccurrence === occurrence) {
                blocks = occurrence.ownerBag.callBlocksOwner
                loan = occurrence.ownerBag.callBlockLoan
            }
        }
        val exactBlocks = blocks
        val exactLoan = loan
        if (exactBlocks != null && exactLoan != null && exactBlocks.release(exactLoan)) {
            gate.withLock {
                if (nativeEncodeOccurrence === occurrence && occurrence.ownerBag.callBlocksOwner === exactBlocks &&
                    occurrence.ownerBag.callBlockLoan === exactLoan
                ) {
                    occurrence.ownerBag.callBlocksOwner = null
                    occurrence.ownerBag.callBlockLoan = null
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

        return gate.withLock {
            val bag = occurrence.ownerBag
            if (nativeEncodeOccurrence !== occurrence) return@withLock false
            if (nativeEncodeMechanicsSettledLocked(bag)) {
                bag.nativeDisableCandidate = null
                bag.nativeDisableStage = NativeDisableStage.Finalized
                nativeEncodeOccurrence = null
                true
            } else {
                bag.admissionDisposition = NativeEncodeAdmissionDisposition.CleanupResidue
                if (bag.admissionFailureCause == null) bag.admissionFailureCause = failure
                false
            }
        }
    }

    internal fun <E : OperationEvidence> submitJpegIoOperation(operation: JpegIoOperation<E>): Boolean {
        if (!operation.occurrence.beginSubmission()) return false

        try {
            jpegIoDispatcher.dispatch(EmptyCoroutineContext, operation.runnable)
        } catch (rejection: Throwable) {
            if (operation.occurrence.publishSubmissionRejected(rejection) != OperationSubmissionRejectionResult.NotCurrent) {
                settlementSignal.signal()
            }
            return false
        }

        if (operation.occurrence.publishSubmissionAccepted()) settlementSignal.signal()
        return true
    }

    private fun executePreparation(occurrence: JpegPreparationOccurrence, policy: JpegBackendPolicy, byteCount: Int) {
        val entryResult = occurrence.operation.tryEnter()
        if (entryResult != OperationEntryResult.Entered) {
            if (entryResult == OperationEntryResult.InvalidDeadline) settlementSignal.signal()
            return
        }

        val loadState = ProcessNativeLoader.ensureAvailable()
        when (loadState) {
            ProcessNativeLoader.State.LoadOome ->
                publishFailure(occurrence.operation, JpegRuntimeFailure.ResourceExhausted, ProcessNativeLoader.cause() ?: MISSING_LOADER_FAILURE)

            ProcessNativeLoader.State.Poisoned ->
                publishFailure(occurrence.operation, JpegRuntimeFailure.InternalFailure, ProcessNativeLoader.cause() ?: MISSING_LOADER_FAILURE)

            ProcessNativeLoader.State.Available -> prepareWithNativeCarrier(occurrence, policy, byteCount)
            ProcessNativeLoader.State.CleanUnavailable -> {
                occurrence.operation.returnCell.evidence.cleanNativeUnavailabilityCause = ProcessNativeLoader.cause()
                prepareWithManagedCarrier(occurrence, byteCount)
            }

            ProcessNativeLoader.State.Unattempted -> error("loader result cannot remain unattempted")
        }
    }

    private fun prepareWithNativeCarrier(occurrence: JpegPreparationOccurrence, policy: JpegBackendPolicy, byteCount: Int) {
        val carrier = NativeMallocCarrier.create(byteCount)
        val nativeAvailable = if (policy == JpegBackendPolicy.Auto) {
            try {
                NativeBridge.nativeHasWeakCompressor()
            } catch (failure: Throwable) {
                publishFailure(occurrence.operation, classifyFailure(failure), failure)
                return
            }
        } else {
            false
        }
        val product: JpegRuntimeProduct = if (nativeAvailable) {
            JpegRuntimeProduct.NativeEnabled.create(carrier)
        } else {
            JpegRuntimeProduct.FrameworkOnNativeCarrier.create(carrier)
        }
        val blocks = try {
            if (nativeAvailable) NativeCallBlocks.create() else null
        } catch (failure: Throwable) {
            publishFailure(occurrence.operation, classifyFailure(failure), failure)
            return
        }
        occurrence.ownerBag.product = product
        occurrence.ownerBag.nativeCallBlocks = blocks

        val buffer = try {
            NativeBridge.nativeAllocateCarrier(byteCount.toLong())
        } catch (failure: Throwable) {
            publishFailure(occurrence.operation, classifyFailure(failure), failure)
            return
        }
        attachAndPublishNativePreparation(occurrence, carrier, buffer)
    }

    private fun prepareWithManagedCarrier(occurrence: JpegPreparationOccurrence, byteCount: Int) {
        val carrier = ManagedDirectCarrier.create(byteCount)
        occurrence.ownerBag.product = JpegRuntimeProduct.FrameworkOnManagedCarrier.create(carrier)
        val buffer = try {
            ByteBuffer.allocateDirect(byteCount)
        } catch (failure: Throwable) {
            publishFailure(occurrence.operation, classifyFailure(failure), failure)
            return
        }
        attachAndPublishManagedPreparation(occurrence, carrier, buffer)
    }

    private fun attachAndPublishNativePreparation(occurrence: JpegPreparationOccurrence, carrier: NativeMallocCarrier, buffer: ByteBuffer) {
        val gate = occurrence.operation.settlementGate
        gate.withLock {
            if (!carrier.attachReturnedBufferLocked(gate, buffer)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.ownerBag.nativeCarrier = carrier
        }
        inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
        val validation = occurrence.validation
        val validationFailure = validation.failure
        val published = gate.withLock {
            if (!carrier.completeAttachmentLocked(gate, validation.ready)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.operation.returnCell.evidence.returnedOwner = carrier
            if (validationFailure == null) {
                occurrence.operation.publishNormalReturn()
            } else {
                occurrence.operation.publishThrownReturn(validationFailure)
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
        inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
        val validation = occurrence.validation
        val validationFailure = validation.failure
        val published = gate.withLock {
            if (!carrier.completeAttachmentLocked(gate, validation.ready)) throw ATTACHMENT_STATE_VIOLATION
            occurrence.operation.returnCell.evidence.returnedOwner = carrier
            if (validationFailure == null) {
                occurrence.operation.publishNormalReturn()
            } else {
                occurrence.operation.publishThrownReturn(validationFailure)
            }
        }
        if (published) settlementSignal.signal()
    }

    private fun executeNativeFree(occurrence: NativeCarrierFreeOccurrence) {
        val entryResult = occurrence.operation.tryEnter()
        if (entryResult != OperationEntryResult.Entered) {
            if (entryResult == OperationEntryResult.InvalidDeadline) settlementSignal.signal()
            return
        }

        val published = try {
            NativeBridge.nativeFreeCarrier(occurrence.ownerBag.buffer)
            occurrence.operation.returnCell.evidence.receipt = occurrence.operation.returnCell.evidence.normalReceipt
            occurrence.operation.publishNormalReturn()
        } catch (failure: Throwable) {
            occurrence.operation.publishThrownReturn(failure)
        }
        if (published) settlementSignal.signal()
    }

    private fun executeNativeReplacement(occurrence: NativeCarrierReplacementAllocationOccurrence, byteCount: Int) {
        val entryResult = occurrence.operation.tryEnter()
        if (entryResult != OperationEntryResult.Entered) {
            if (entryResult == OperationEntryResult.InvalidDeadline) settlementSignal.signal()
            return
        }

        val carrier = occurrence.carrierCandidate
        val buffer = try {
            NativeBridge.nativeAllocateCarrier(byteCount.toLong())
        } catch (failure: Throwable) {
            occurrence.operation.returnCell.evidence.failure = classifyFailure(failure)
            occurrence.operation.returnCell.evidence.failureCause = failure
            if (occurrence.operation.publishThrownReturn(failure)) settlementSignal.signal()
            return
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
        inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
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
                occurrence.operation.publishThrownReturn(validationFailure)
            }
        }
        if (published) settlementSignal.signal()
    }

    private fun executeManagedReplacement(occurrence: ManagedDirectCarrierReplacementAllocationOccurrence, byteCount: Int) {
        val entryResult = occurrence.operation.tryEnter()
        if (entryResult != OperationEntryResult.Entered) {
            if (entryResult == OperationEntryResult.InvalidDeadline) settlementSignal.signal()
            return
        }

        val carrier = occurrence.carrierCandidate
        val buffer = try {
            ByteBuffer.allocateDirect(byteCount)
        } catch (failure: Throwable) {
            occurrence.operation.returnCell.evidence.failure = classifyFailure(failure)
            occurrence.operation.returnCell.evidence.failureCause = failure
            if (occurrence.operation.publishThrownReturn(failure)) settlementSignal.signal()
            return
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
        inspectBuffer(buffer, carrier.byteCount, occurrence.validation)
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
                occurrence.operation.publishThrownReturn(validationFailure)
            }
        }
        if (published) settlementSignal.signal()
    }

    private fun executeNativeEncode(occurrence: NativeEncodeOccurrence) {
        val entryResult = occurrence.operation.tryEnter()
        if (entryResult != OperationEntryResult.Entered) {
            if (entryResult == OperationEntryResult.InvalidDeadline) settlementSignal.signal()
            return
        }

        val bag = occurrence.ownerBag
        val lease = checkNotNull(bag.retainedOperationLease)
        val loan = checkNotNull(bag.callBlockLoan)
        val transaction = checkNotNull(bag.transaction)
        val evidence = occurrence.operation.returnCell.evidence
        val pixels = lease.enterExactRange()
        if (pixels == null) {
            evidence.carrierUseResolved = true
            evidence.writerResidueDisposition = loan.writerResidueDisposition
            transaction.abort()
            if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, LEASE_NOT_OPERATIONAL)) {
                settlementSignal.signal()
            }
            return
        }

        try {
            loan.reset()
        } catch (failure: Throwable) {
            evidence.carrierUseResolved = lease.exitExactRange()
            evidence.writerResidueDisposition = loan.writerResidueDisposition
            transaction.abort()
            if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, failure)) {
                settlementSignal.signal()
            }
            return
        }
        val sink = bag.segmentSink
        if (sink == null || !loan.markNativeEntryAttempted()) {
            evidence.carrierUseResolved = lease.exitExactRange()
            evidence.writerResidueDisposition = loan.writerResidueDisposition
            transaction.abort()
            if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, NATIVE_ENCODE_ADMISSION_FAILED)) {
                settlementSignal.signal()
            }
            return
        }

        var compressorResult = Int.MIN_VALUE
        var thrown: Throwable? = null
        try {
            compressorResult = NativeBridge.nativeCompress(
                writerBlock = loan.writerBlock,
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
                resultBlock = loan.resultBlock,
            )
        } catch (failure: Throwable) {
            thrown = failure
        } finally {
            evidence.carrierUseResolved = lease.exitExactRange()
        }

        if (decodeNativeEncodeReturn(occurrence, compressorResult, thrown)) settlementSignal.signal()
    }

    private fun decodeNativeEncodeReturn(
        occurrence: NativeEncodeOccurrence,
        compressorResult: Int,
        thrown: Throwable?,
    ): Boolean {
        val bag = occurrence.ownerBag
        val evidence = occurrence.operation.returnCell.evidence
        val loan = checkNotNull(bag.callBlockLoan)
        val transaction = checkNotNull(bag.transaction)

        var blockFailure: Throwable? = null
        try {
            evidence.compressorResult = compressorResult
            evidence.writerStatus = loan.resultBlock.getLong(WRITER_STATUS_OFFSET)
            evidence.totalBytes = loan.resultBlock.getLong(TOTAL_BYTES_OFFSET)
            evidence.adoptedBytes = loan.resultBlock.getLong(ADOPTED_BYTES_OFFSET)
            evidence.remainingBytes = loan.resultBlock.getLong(REMAINING_BYTES_OFFSET)
            evidence.remainingSegmentCount = loan.resultBlock.getLong(REMAINING_SEGMENTS_OFFSET)
        } catch (failure: Throwable) {
            blockFailure = failure
        }

        var tokenRead = false
        val tokenClearOnReturn = try {
            val clear = loan.writerBlock.getLong(WRITER_TOKEN_OFFSET) == 0L
            tokenRead = true
            clear
        } catch (failure: Throwable) {
            if (blockFailure == null) blockFailure = failure
            false
        }
        var residueFailure: Throwable? = null
        if (tokenClearOnReturn) {
            if (!loan.markClearOnReturn()) residueFailure = MALFORMED_NATIVE_RESULT
        } else if (tokenRead) {
            try {
                if (NativeBridge.nativeReleaseWriterResidue(loan.writerBlock) == NATIVE_RESIDUE_RELEASE_SUCCESS) {
                    val tokenClearedAfterRelease = loan.writerBlock.getLong(WRITER_TOKEN_OFFSET) == 0L
                    if (tokenClearedAfterRelease && !loan.markReleasedAndCleared()) {
                        residueFailure = MALFORMED_NATIVE_RESULT
                    }
                }
            } catch (failure: Throwable) {
                residueFailure = failure
            }
        }
        evidence.writerResidueDisposition = loan.writerResidueDisposition

        val writerMalformed = evidence.writerStatus != WRITER_STATUS_SAFE &&
                evidence.writerStatus != WRITER_STATUS_OUT_OF_MEMORY &&
                evidence.writerStatus != WRITER_STATUS_INTERNAL
        val countsNonnegative = evidence.totalBytes >= 0L && evidence.adoptedBytes >= 0L &&
                evidence.remainingBytes >= 0L && evidence.remainingSegmentCount >= 0L
        val sumRepresentable = countsNonnegative && evidence.adoptedBytes <= Long.MAX_VALUE - evidence.remainingBytes
        val countsMalformed = !countsNonnegative || evidence.adoptedBytes > evidence.totalBytes ||
                evidence.remainingBytes > evidence.totalBytes || !sumRepresentable ||
                evidence.totalBytes != evidence.adoptedBytes + evidence.remainingBytes ||
                (evidence.remainingBytes == 0L) != (evidence.remainingSegmentCount == 0L) ||
                evidence.remainingSegmentCount > evidence.remainingBytes ||
                evidence.adoptedBytes != transaction.byteCount.toLong()
        val transactionFailure = transaction.failure
        val nonOomBlockFailure = blockFailure != null && blockFailure !is OutOfMemoryError
        val nonOomResidueFailure = residueFailure != null && residueFailure !is OutOfMemoryError
        val nonOomThrown = thrown != null && thrown !is OutOfMemoryError
        val residueResolved = evidence.writerResidueDisposition == NativeWriterResidueDisposition.ClearOnReturn ||
                evidence.writerResidueDisposition == NativeWriterResidueDisposition.ReleasedAndCleared
        val internalFault = !evidence.carrierUseResolved || !residueResolved || nonOomBlockFailure ||
                nonOomResidueFailure || nonOomThrown || writerMalformed || countsMalformed ||
                evidence.writerStatus == WRITER_STATUS_INTERNAL ||
                transactionFailure == EncodedStorageOwner.TransactionFailure.InternalFailure
        val outOfMemory = blockFailure is OutOfMemoryError || residueFailure is OutOfMemoryError ||
                thrown is OutOfMemoryError || evidence.writerStatus == WRITER_STATUS_OUT_OF_MEMORY ||
                transactionFailure == EncodedStorageOwner.TransactionFailure.ResourceExhausted

        if (internalFault) {
            if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
            return publishNativeEncodeFailure(
                occurrence,
                NativeEncodeSettlement.InternalFailure,
                selectInternalFailureCause(
                    blockFailure = blockFailure,
                    residueFailure = residueFailure,
                    thrown = thrown,
                    transactionFailure = transactionFailure,
                    transactionCause = transaction.failureCause,
                ),
            )
        }
        if (outOfMemory) {
            if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
            return publishNativeEncodeFailure(
                occurrence,
                NativeEncodeSettlement.ResourceExhausted,
                selectResourceExhaustedCause(
                    blockFailure = blockFailure,
                    residueFailure = residueFailure,
                    thrown = thrown,
                    transactionFailure = transactionFailure,
                    transactionCause = transaction.failureCause,
                ),
            )
        }

        val nativeRemainderEmpty = evidence.remainingBytes == 0L && evidence.remainingSegmentCount == 0L
        when (compressorResult) {
            ANDROID_BITMAP_RESULT_SUCCESS -> {
                val successful = evidence.writerStatus == WRITER_STATUS_SAFE && nativeRemainderEmpty &&
                        evidence.totalBytes > 0L && evidence.totalBytes == evidence.adoptedBytes &&
                        transaction.commit(bag.descriptor.imageSize)
                if (successful) {
                    evidence.result = NativeEncodeSettlement.Success
                    return occurrence.operation.publishNormalReturn()
                } else {
                    if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
                    val transactionResult = classifyTransactionFailure(transaction)
                    return publishNativeEncodeFailure(
                        occurrence,
                        transactionResult,
                        selectTransactionFailureCause(transaction, transactionResult),
                    )
                }
            }

            ANDROID_BITMAP_RESULT_ALLOCATION_FAILED -> {
                val safeAllocationFailure = evidence.writerStatus == WRITER_STATUS_SAFE && evidence.totalBytes == 0L &&
                        evidence.adoptedBytes == 0L && nativeRemainderEmpty &&
                        transaction.byteCount == 0 && transaction.failure == null
                if (safeAllocationFailure) {
                    transaction.abort()
                    evidence.result = NativeEncodeSettlement.SafeNativeAllocationFailure
                    return occurrence.operation.publishNormalReturn()
                } else {
                    if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
                    return publishNativeEncodeFailure(
                        occurrence,
                        NativeEncodeSettlement.InternalFailure,
                        MALFORMED_NATIVE_RESULT,
                    )
                }
            }

            ANDROID_BITMAP_RESULT_BAD_PARAMETER,
            ANDROID_BITMAP_RESULT_JNI_EXCEPTION,
                -> {
                if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
                return publishNativeEncodeFailure(
                    occurrence,
                    NativeEncodeSettlement.InternalFailure,
                    NATIVE_COMPRESSOR_FAILURE,
                )
            }

            else -> {
                if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
                return publishNativeEncodeFailure(
                    occurrence,
                    NativeEncodeSettlement.InternalFailure,
                    UNKNOWN_NATIVE_COMPRESSOR_RESULT,
                )
            }
        }
    }

    private fun classifyTransactionFailure(transaction: EncodedStorageOwner.NativeTransaction): NativeEncodeSettlement =
        if (transaction.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted) {
            NativeEncodeSettlement.ResourceExhausted
        } else {
            NativeEncodeSettlement.InternalFailure
        }

    private fun selectTransactionFailureCause(
        transaction: EncodedStorageOwner.NativeTransaction,
        result: NativeEncodeSettlement,
    ): Throwable = when (result) {
        NativeEncodeSettlement.ResourceExhausted -> selectResourceExhaustedCause(
            blockFailure = null,
            residueFailure = null,
            thrown = null,
            transactionFailure = transaction.failure,
            transactionCause = transaction.failureCause,
        )

        NativeEncodeSettlement.InternalFailure -> selectInternalFailureCause(
            blockFailure = null,
            residueFailure = null,
            thrown = null,
            transactionFailure = transaction.failure,
            transactionCause = transaction.failureCause,
        )

        else -> MALFORMED_NATIVE_RESULT
    }

    private fun publishNativeEncodeFailure(
        occurrence: NativeEncodeOccurrence,
        result: NativeEncodeSettlement,
        failure: Throwable,
    ): Boolean {
        val evidence = occurrence.operation.returnCell.evidence
        evidence.result = result
        evidence.failureCause = failure
        return occurrence.operation.publishThrownReturn(failure)
    }

    private fun selectInternalFailureCause(
        blockFailure: Throwable?,
        residueFailure: Throwable?,
        thrown: Throwable?,
        transactionFailure: EncodedStorageOwner.TransactionFailure?,
        transactionCause: Throwable?,
    ): Throwable {
        if (blockFailure != null && blockFailure !is OutOfMemoryError) return blockFailure
        if (residueFailure != null && residueFailure !is OutOfMemoryError) return residueFailure
        if (thrown != null && thrown !is OutOfMemoryError) return thrown
        if (transactionFailure == EncodedStorageOwner.TransactionFailure.InternalFailure &&
            transactionCause != null && transactionCause !is OutOfMemoryError
        ) {
            return transactionCause
        }
        return MALFORMED_NATIVE_RESULT
    }

    private fun selectResourceExhaustedCause(
        blockFailure: Throwable?,
        residueFailure: Throwable?,
        thrown: Throwable?,
        transactionFailure: EncodedStorageOwner.TransactionFailure?,
        transactionCause: Throwable?,
    ): Throwable {
        if (blockFailure is OutOfMemoryError) return blockFailure
        if (residueFailure is OutOfMemoryError) return residueFailure
        if (thrown is OutOfMemoryError) return thrown
        if (transactionFailure == EncodedStorageOwner.TransactionFailure.ResourceExhausted && transactionCause != null) {
            return transactionCause
        }
        return NATIVE_WRITER_OUT_OF_MEMORY
    }

    private fun publishFailure(operation: OperationOccurrence<JpegPreparationEvidence>, failure: JpegRuntimeFailure, cause: Throwable) {
        operation.returnCell.evidence.failure = failure
        operation.returnCell.evidence.failureCause = cause
        if (operation.publishThrownReturn(cause)) settlementSignal.signal()
    }

    private fun classifyFailure(failure: Throwable): JpegRuntimeFailure =
        if (failure is OutOfMemoryError) JpegRuntimeFailure.ResourceExhausted else JpegRuntimeFailure.InternalFailure

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
        } catch (failure: Throwable) {
            validation.structurallyFreeable = false
            validation.ready = false
            validation.failure = failure
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
        } catch (_: Throwable) {
            null
        }
    }

    private object ProcessNativeLoader {
        enum class State {
            Unattempted,
            Available,
            CleanUnavailable,
            LoadOome,
            Poisoned,
        }

        private var state: State = State.Unattempted
        private var firstCause: Throwable? = null

        @Synchronized
        fun ensureAvailable(): State {
            if (state != State.Unattempted) return state

            try {
                System.loadLibrary("screencaptureengine")
            } catch (failure: UnsatisfiedLinkError) {
                state = State.CleanUnavailable
                firstCause = failure
                return state
            } catch (failure: SecurityException) {
                state = State.CleanUnavailable
                firstCause = failure
                return state
            } catch (failure: OutOfMemoryError) {
                state = State.LoadOome
                firstCause = failure
                return state
            } catch (failure: Throwable) {
                state = State.Poisoned
                firstCause = failure
                return state
            }

            try {
                if (NativeBridge.nativeBootstrap() == NATIVE_BOOTSTRAP_SUCCESS) {
                    state = State.Available
                } else {
                    state = State.Poisoned
                    firstCause = BOOTSTRAP_REJECTED
                }
            } catch (failure: Throwable) {
                state = State.Poisoned
                firstCause = failure
            }
            return state
        }

        @Synchronized
        fun cause(): Throwable? = firstCause

    }

    private companion object {
        private const val NATIVE_BOOTSTRAP_SUCCESS: Int = 0
        private const val NATIVE_RESIDUE_RELEASE_SUCCESS: Int = 0
        private const val RGBA_PIXEL_BYTE_COUNT: Long = 4L
        private const val MIN_JPEG_QUALITY: Int = 0
        private const val MAX_JPEG_QUALITY: Int = 100

        private const val ANDROID_BITMAP_FORMAT_RGBA_8888: Int = 1
        private const val ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE: Long = 1L
        private const val ANDROID_DATASPACE_SRGB: Int = 142_671_872
        private const val ANDROID_BITMAP_COMPRESS_FORMAT_JPEG: Int = 0

        private const val ANDROID_BITMAP_RESULT_SUCCESS: Int = 0
        private const val ANDROID_BITMAP_RESULT_BAD_PARAMETER: Int = -1
        private const val ANDROID_BITMAP_RESULT_JNI_EXCEPTION: Int = -2
        private const val ANDROID_BITMAP_RESULT_ALLOCATION_FAILED: Int = -3

        private const val WRITER_STATUS_SAFE: Long = 0L
        private const val WRITER_STATUS_OUT_OF_MEMORY: Long = 1L
        private const val WRITER_STATUS_INTERNAL: Long = 2L

        private const val WRITER_TOKEN_OFFSET: Int = 0
        private const val WRITER_STATUS_OFFSET: Int = 0
        private const val TOTAL_BYTES_OFFSET: Int = Long.SIZE_BYTES
        private const val ADOPTED_BYTES_OFFSET: Int = Long.SIZE_BYTES * 2
        private const val REMAINING_BYTES_OFFSET: Int = Long.SIZE_BYTES * 3
        private const val REMAINING_SEGMENTS_OFFSET: Int = Long.SIZE_BYTES * 4

        private val BOOTSTRAP_REJECTED: IllegalStateException =
            IllegalStateException("native JPEG bootstrap rejected its protocol")
        private val INVALID_CARRIER_BUFFER: IllegalStateException =
            IllegalStateException("carrier allocator returned a malformed direct buffer")
        private val MALFORMED_NATIVE_RESULT: IllegalStateException =
            IllegalStateException("native JPEG returned malformed writer evidence")
        private val NATIVE_WRITER_OUT_OF_MEMORY: OutOfMemoryError =
            OutOfMemoryError("native JPEG writer exhausted storage")
        private val NATIVE_COMPRESSOR_FAILURE: IllegalStateException =
            IllegalStateException("native JPEG compressor rejected the frame")
        private val UNKNOWN_NATIVE_COMPRESSOR_RESULT: IllegalStateException =
            IllegalStateException("native JPEG compressor returned an unknown result")
        private val LEASE_NOT_OPERATIONAL: IllegalStateException =
            IllegalStateException("native carrier lease is not operational")
        private val NATIVE_ENCODE_ADMISSION_FAILED: IllegalStateException =
            IllegalStateException("native JPEG encode admission could not preserve its owners")
        private val MISSING_LOADER_FAILURE: IllegalStateException =
            IllegalStateException("native JPEG loader failed without a cause")
        private val ATTACHMENT_STATE_VIOLATION: IllegalStateException =
            IllegalStateException("carrier attachment transaction is not current")
    }

    private object NativeBridge {
        external fun nativeBootstrap(): Int

        external fun nativeAllocateCarrier(byteCount: Long): ByteBuffer

        external fun nativeFreeCarrier(buffer: ByteBuffer)

        external fun nativeHasWeakCompressor(): Boolean

        external fun nativeCompress(
            writerBlock: ByteBuffer,
            pixels: ByteBuffer,
            pixelByteCount: Long,
            width: Int,
            height: Int,
            stride: Int,
            format: Int,
            flags: Long,
            dataspace: Int,
            compressFormat: Int,
            quality: Int,
            sink: EncodedStorageOwner.NativeSegmentSink,
            resultBlock: ByteBuffer,
        ): Int

        external fun nativeReleaseWriterResidue(writerBlock: ByteBuffer): Int
    }
}
