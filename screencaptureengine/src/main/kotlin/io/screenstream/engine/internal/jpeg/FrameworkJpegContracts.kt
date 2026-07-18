package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.SettlementSignal

internal enum class FrameworkTransferMode {
    TightBufferCopy,
    PortableRowCopy,
}

internal enum class FrameworkResourceCreationResult {
    NotSettled,
    Complete,
    ResourceExhausted,
    InternalFailure,
}

internal class FrameworkResourceCreationEvidence internal constructor() : OperationEvidence {
    override var receipt: OperationReceipt? = null
        internal set

    override var returnedOwner: OperationReturnedOwner? = null
        internal set

    internal var result: FrameworkResourceCreationResult = FrameworkResourceCreationResult.NotSettled
    internal var failureCause: Throwable? = null
    internal var actualWidth: Int = 0
    internal var actualHeight: Int = 0
    internal var actualRowByteCount: Int = 0
    internal var actualBitmapByteCount: Int = 0
    internal var transferMode: FrameworkTransferMode? = null
}

internal class FrameworkResourceCreationOwnerBag internal constructor(
    internal var candidateOwner: FrameworkJpegOwner?,
) : OperationOwnerBag

internal class FrameworkResourceCreationOccurrence private constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    expectedProduct: JpegRuntimeProduct,
    internal val operation: OperationOccurrence<FrameworkResourceCreationEvidence>,
    internal val ownerBag: FrameworkResourceCreationOwnerBag,
    internal val ioOperation: JpegIoOperation<FrameworkResourceCreationEvidence>,
) {
    private var expectedProductSlot: JpegRuntimeProduct? = expectedProduct

    internal val expectedProduct: JpegRuntimeProduct
        get() = checkNotNull(expectedProductSlot)

    internal fun clearSettledReferencesLocked(expectedOwner: FrameworkJpegOwner): Boolean {
        check(operation.settlementGate.isHeldByCurrentThread)
        val returnedOwner = operation.returnCell.evidence.returnedOwner
        if (expectedProductSlot == null || ownerBag.candidateOwner !== expectedOwner || returnedOwner != null && returnedOwner !== expectedOwner) {
            return false
        }

        ownerBag.candidateOwner = null
        operation.returnCell.evidence.returnedOwner = null
        expectedProductSlot = null
        return true
    }

    internal companion object {
        internal fun create(
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            expectedProduct: JpegRuntimeProduct,
            identity: JpegFiniteOperationIdentity,
            candidateOwner: FrameworkJpegOwner,
            clock: EngineClock,
            signal: SettlementSignal,
            work: (FrameworkResourceCreationOccurrence) -> Unit,
        ): FrameworkResourceCreationOccurrence {
            val evidence = FrameworkResourceCreationEvidence()
            val ownerBag = FrameworkResourceCreationOwnerBag(candidateOwner)
            val operation = OperationOccurrence(
                identity = identity.operationIdentity,
                clock = clock,
                returnCell = OperationReturnCell(evidence),
                ownerBag = ownerBag,
                deadlineIdentity = identity.deadlineIdentity,
                deadlineDurationNanos = jpegEnteredOperationSafetyNanos,
                initialWakeGeneration = identity.initialWakeGeneration,
                timeoutCause = identity.timeoutCause,
                wakeSignal = signal,
            )
            lateinit var occurrence: FrameworkResourceCreationOccurrence
            val ioOperation = JpegIoOperation(operation) { work(occurrence) }
            occurrence = FrameworkResourceCreationOccurrence(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                expectedProduct = expectedProduct,
                operation = operation,
                ownerBag = ownerBag,
                ioOperation = ioOperation,
            )
            return occurrence
        }
    }
}

internal enum class FrameworkEncodeSettlement {
    NotSettled,
    Success,
    CompressionRejected,
    ResourceExhausted,
    InternalFailure,
    CancelledWithoutReturn,
}

internal class FrameworkEncodeEvidence internal constructor() : OperationEvidence {
    override var receipt: OperationReceipt? = null
        internal set

    override var returnedOwner: OperationReturnedOwner? = null
        internal set

    internal var result: FrameworkEncodeSettlement = FrameworkEncodeSettlement.NotSettled
    internal var failureCause: Throwable? = null
    internal var bitmapUseResolved: Boolean = false
}

internal class FrameworkEncodeOwnerBag internal constructor(
    internal var bitmapUseOwner: FrameworkJpegOwner?,
    product: JpegRuntimeProduct,
    carrierLease: RgbaCarrierLease,
    internal var retainedOperationLease: RgbaCarrierLease?,
    internal var storageOwner: EncodedStorageOwner?,
    internal var transaction: EncodedStorageOwner.FrameworkTransaction?,
    internal var unpublishedToRetire: EncodedStorageOwner.UnpublishedEncodedPayload?,
    internal var retainCommittedFrame: Boolean?,
) : OperationOwnerBag {
    private var productSlot: JpegRuntimeProduct? = product
    private var carrierLeaseSlot: RgbaCarrierLease? = carrierLease

    internal val product: JpegRuntimeProduct
        get() = checkNotNull(productSlot)

    internal val carrierLease: RgbaCarrierLease
        get() = checkNotNull(carrierLeaseSlot)

    internal fun clearSettledReferences(expectedOwner: FrameworkJpegOwner, expectedProduct: JpegRuntimeProduct): Boolean {
        if (bitmapUseOwner !== expectedOwner || productSlot !== expectedProduct || carrierLeaseSlot == null ||
            retainedOperationLease != null || storageOwner != null || transaction != null || unpublishedToRetire != null
        ) {
            return false
        }

        bitmapUseOwner = null
        productSlot = null
        carrierLeaseSlot = null
        return true
    }
}

internal class FrameworkEncodeOccurrence private constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    capturedProduct: JpegRuntimeProduct,
    internal val quality: Int,
    internal val operation: OperationOccurrence<FrameworkEncodeEvidence>,
    internal val ownerBag: FrameworkEncodeOwnerBag,
    internal val ioOperation: JpegIoOperation<FrameworkEncodeEvidence>,
) {
    private var capturedProductSlot: JpegRuntimeProduct? = capturedProduct

    internal val capturedProduct: JpegRuntimeProduct
        get() = checkNotNull(capturedProductSlot)

    internal fun clearSettledReferencesLocked(expectedOwner: FrameworkJpegOwner): Boolean {
        check(operation.settlementGate.isHeldByCurrentThread)
        val exactProduct = capturedProductSlot ?: return false
        if (!ownerBag.clearSettledReferences(expectedOwner, exactProduct)) return false
        capturedProductSlot = null
        return true
    }

    internal companion object {
        internal fun create(
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            bitmapUseOwner: FrameworkJpegOwner,
            capturedProduct: JpegRuntimeProduct,
            carrierLease: RgbaCarrierLease,
            quality: Int,
            identity: JpegFiniteOperationIdentity,
            clock: EngineClock,
            signal: SettlementSignal,
            work: (FrameworkEncodeOccurrence) -> Unit,
        ): FrameworkEncodeOccurrence {
            val evidence = FrameworkEncodeEvidence()
            val ownerBag = FrameworkEncodeOwnerBag(
                bitmapUseOwner = bitmapUseOwner,
                product = capturedProduct,
                carrierLease = carrierLease,
                retainedOperationLease = null,
                storageOwner = null,
                transaction = null,
                unpublishedToRetire = null,
                retainCommittedFrame = null,
            )
            val operation = OperationOccurrence(
                identity = identity.operationIdentity,
                clock = clock,
                returnCell = OperationReturnCell(evidence),
                ownerBag = ownerBag,
                deadlineIdentity = identity.deadlineIdentity,
                deadlineDurationNanos = jpegEnteredOperationSafetyNanos,
                initialWakeGeneration = identity.initialWakeGeneration,
                timeoutCause = identity.timeoutCause,
                wakeSignal = signal,
            )
            lateinit var occurrence: FrameworkEncodeOccurrence
            val ioOperation = JpegIoOperation(operation) { work(occurrence) }
            occurrence = FrameworkEncodeOccurrence(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                capturedProduct = capturedProduct,
                quality = quality,
                operation = operation,
                ownerBag = ownerBag,
                ioOperation = ioOperation,
            )
            return occurrence
        }
    }
}

internal class FrameworkBitmapRecycleReceipt internal constructor() : OperationReceipt

internal enum class FrameworkBitmapRecycleOrigin {
    IncompatibleReplacement,
    ReturnedOwnerCleanup,
    TerminalRetirement,
}

internal enum class FrameworkBitmapRecycleSettlement {
    NotSettled,
    ReplacementAuthorized,
    CleanupCompleted,
    UnsafeResidue,
}

internal class FrameworkBitmapRecycleEvidence internal constructor() : OperationEvidence {
    internal val normalReceipt: FrameworkBitmapRecycleReceipt = FrameworkBitmapRecycleReceipt()

    override var receipt: OperationReceipt? = null
        internal set

    override var returnedOwner: OperationReturnedOwner? = null
        internal set
}

internal class FrameworkBitmapRecycleOwnerBag internal constructor(
    internal var owner: FrameworkJpegOwner?,
) : OperationOwnerBag

internal class FrameworkBitmapRecycleOccurrence private constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val origin: FrameworkBitmapRecycleOrigin,
    internal val operation: OperationOccurrence<FrameworkBitmapRecycleEvidence>,
    internal val ownerBag: FrameworkBitmapRecycleOwnerBag,
    internal val ioOperation: JpegIoOperation<FrameworkBitmapRecycleEvidence>,
) {
    internal companion object {
        internal fun create(
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            origin: FrameworkBitmapRecycleOrigin,
            owner: FrameworkJpegOwner?,
            identity: JpegFiniteOperationIdentity?,
            operationIdentity: Long,
            clock: EngineClock,
            signal: SettlementSignal,
            work: (FrameworkBitmapRecycleOccurrence) -> Unit,
        ): FrameworkBitmapRecycleOccurrence {
            val evidence = FrameworkBitmapRecycleEvidence()
            val ownerBag = FrameworkBitmapRecycleOwnerBag(owner)
            val operation = OperationOccurrence(
                identity = operationIdentity,
                clock = clock,
                returnCell = OperationReturnCell(evidence),
                ownerBag = ownerBag,
                deadlineIdentity = identity?.deadlineIdentity,
                deadlineDurationNanos = identity?.let { jpegEnteredOperationSafetyNanos },
                initialWakeGeneration = identity?.initialWakeGeneration ?: 0L,
                timeoutCause = identity?.timeoutCause,
                wakeSignal = identity?.let { signal },
            )
            lateinit var occurrence: FrameworkBitmapRecycleOccurrence
            val ioOperation = JpegIoOperation(operation) { work(occurrence) }
            occurrence = FrameworkBitmapRecycleOccurrence(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                origin = origin,
                operation = operation,
                ownerBag = ownerBag,
                ioOperation = ioOperation,
            )
            return occurrence
        }
    }
}
