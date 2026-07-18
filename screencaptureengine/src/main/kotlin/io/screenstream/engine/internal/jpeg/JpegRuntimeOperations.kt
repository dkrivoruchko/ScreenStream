package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.ImageSize
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class JpegRuntimeFailure {
    ResourceExhausted,
    InternalFailure,
}

internal class CarrierValidation {
    var ready: Boolean = false
    var structurallyFreeable: Boolean = false
    var failure: Throwable? = null
}

internal enum class NativeEncodeSettlement {
    NotSettled,
    Success,
    SafeNativeAllocationFailure,
    ResourceExhausted,
    InternalFailure,
    CancelledWithoutReturn,
}

internal enum class NoReturnedCarrierSettlement {
    NotSettled,
    Completed,
    UnsafeResidue,
}

internal class JpegPreparationEvidence internal constructor() : OperationEvidence {
    override var receipt: OperationReceipt? = null
        internal set

    override var returnedOwner: OperationReturnedOwner? = null
        internal set

    internal var failure: JpegRuntimeFailure? = null
    internal var failureCause: Throwable? = null
    internal var cleanNativeUnavailabilityCause: Throwable? = null
}

internal class JpegPreparationOwnerBag internal constructor() : OperationOwnerBag {
    internal var nativeCarrier: NativeMallocCarrier? = null
    internal var managedCarrier: ManagedDirectCarrier? = null
    internal var product: JpegRuntimeProduct? = null
}

internal class JpegPreparationOccurrence private constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val operation: OperationOccurrence<JpegPreparationEvidence>,
    internal val ownerBag: JpegPreparationOwnerBag,
    internal val validation: CarrierValidation,
    internal val ioOperation: JpegIoOperation<JpegPreparationEvidence>,
) {
    internal companion object {
        internal fun create(
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
            clock: EngineClock,
            signal: SettlementSignal,
            work: (JpegPreparationOccurrence) -> Unit,
        ): JpegPreparationOccurrence {
            val evidence = JpegPreparationEvidence()
            val bag = JpegPreparationOwnerBag()
            val validation = CarrierValidation()
            val operation = OperationOccurrence(
                identity = identity.operationIdentity,
                clock = clock,
                returnCell = OperationReturnCell(evidence),
                ownerBag = bag,
                deadlineIdentity = identity.deadlineIdentity,
                deadlineDurationNanos = jpegEnteredOperationSafetyNanos,
                initialWakeGeneration = identity.initialWakeGeneration,
                timeoutCause = identity.timeoutCause,
                wakeSignal = signal,
            )
            lateinit var occurrence: JpegPreparationOccurrence
            val ioOperation = JpegIoOperation(operation, Runnable { work(occurrence) })
            occurrence = JpegPreparationOccurrence(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                operation = operation,
                ownerBag = bag,
                validation = validation,
                ioOperation = ioOperation,
            )
            return occurrence
        }
    }
}

internal class NativeCarrierReplacementAllocationEvidence internal constructor() : OperationEvidence {
    override var receipt: OperationReceipt? = null
        internal set

    override var returnedOwner: OperationReturnedOwner? = null
        internal set

    internal var failure: JpegRuntimeFailure? = null
    internal var failureCause: Throwable? = null
}

internal class NativeCarrierReplacementAllocationOwnerBag internal constructor(
    internal var carrier: NativeMallocCarrier?,
    internal var product: JpegRuntimeProduct?,
) : OperationOwnerBag

internal class NativeCarrierReplacementAllocationOccurrence private constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val sourceProduct: JpegRuntimeProduct,
    internal val carrierCandidate: NativeMallocCarrier,
    internal val operation: OperationOccurrence<NativeCarrierReplacementAllocationEvidence>,
    internal val ownerBag: NativeCarrierReplacementAllocationOwnerBag,
    internal val validation: CarrierValidation,
    internal val ioOperation: JpegIoOperation<NativeCarrierReplacementAllocationEvidence>,
) {
    internal companion object {
        internal fun create(
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            sourceProduct: JpegRuntimeProduct,
            identity: JpegFiniteOperationIdentity,
            carrier: NativeMallocCarrier,
            product: JpegRuntimeProduct,
            clock: EngineClock,
            signal: SettlementSignal,
            work: (NativeCarrierReplacementAllocationOccurrence) -> Unit,
        ): NativeCarrierReplacementAllocationOccurrence {
            val evidence = NativeCarrierReplacementAllocationEvidence()
            val bag = NativeCarrierReplacementAllocationOwnerBag(carrier = null, product = product)
            val validation = CarrierValidation()
            val operation = OperationOccurrence(
                identity = identity.operationIdentity,
                clock = clock,
                returnCell = OperationReturnCell(evidence),
                ownerBag = bag,
                deadlineIdentity = identity.deadlineIdentity,
                deadlineDurationNanos = jpegEnteredOperationSafetyNanos,
                initialWakeGeneration = identity.initialWakeGeneration,
                timeoutCause = identity.timeoutCause,
                wakeSignal = signal,
            )
            lateinit var occurrence: NativeCarrierReplacementAllocationOccurrence
            val ioOperation = JpegIoOperation(operation, Runnable { work(occurrence) })
            occurrence = NativeCarrierReplacementAllocationOccurrence(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                sourceProduct = sourceProduct,
                carrierCandidate = carrier,
                operation = operation,
                ownerBag = bag,
                validation = validation,
                ioOperation = ioOperation,
            )
            return occurrence
        }
    }
}

internal class ManagedDirectCarrierReplacementAllocationEvidence internal constructor() : OperationEvidence {
    override var receipt: OperationReceipt? = null
        internal set

    override var returnedOwner: OperationReturnedOwner? = null
        internal set

    internal var failure: JpegRuntimeFailure? = null
    internal var failureCause: Throwable? = null
}

internal class ManagedDirectCarrierReplacementAllocationOwnerBag internal constructor(
    internal var carrier: ManagedDirectCarrier?,
    internal var product: JpegRuntimeProduct.FrameworkOnManagedCarrier?,
) : OperationOwnerBag

internal class ManagedDirectCarrierReplacementAllocationOccurrence private constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val sourceProduct: JpegRuntimeProduct.FrameworkOnManagedCarrier,
    internal val carrierCandidate: ManagedDirectCarrier,
    internal val operation: OperationOccurrence<ManagedDirectCarrierReplacementAllocationEvidence>,
    internal val ownerBag: ManagedDirectCarrierReplacementAllocationOwnerBag,
    internal val validation: CarrierValidation,
    internal val ioOperation: JpegIoOperation<ManagedDirectCarrierReplacementAllocationEvidence>,
) {
    internal companion object {
        internal fun create(
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            sourceProduct: JpegRuntimeProduct.FrameworkOnManagedCarrier,
            identity: JpegFiniteOperationIdentity,
            carrier: ManagedDirectCarrier,
            product: JpegRuntimeProduct.FrameworkOnManagedCarrier,
            clock: EngineClock,
            signal: SettlementSignal,
            work: (ManagedDirectCarrierReplacementAllocationOccurrence) -> Unit,
        ): ManagedDirectCarrierReplacementAllocationOccurrence {
            val evidence = ManagedDirectCarrierReplacementAllocationEvidence()
            val bag = ManagedDirectCarrierReplacementAllocationOwnerBag(carrier = null, product = product)
            val validation = CarrierValidation()
            val operation = OperationOccurrence(
                identity = identity.operationIdentity,
                clock = clock,
                returnCell = OperationReturnCell(evidence),
                ownerBag = bag,
                deadlineIdentity = identity.deadlineIdentity,
                deadlineDurationNanos = jpegEnteredOperationSafetyNanos,
                initialWakeGeneration = identity.initialWakeGeneration,
                timeoutCause = identity.timeoutCause,
                wakeSignal = signal,
            )
            lateinit var occurrence: ManagedDirectCarrierReplacementAllocationOccurrence
            val ioOperation = JpegIoOperation(operation, Runnable { work(occurrence) })
            occurrence = ManagedDirectCarrierReplacementAllocationOccurrence(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                sourceProduct = sourceProduct,
                carrierCandidate = carrier,
                operation = operation,
                ownerBag = bag,
                validation = validation,
                ioOperation = ioOperation,
            )
            return occurrence
        }
    }
}

internal class NativeFrameDescriptor internal constructor(
    internal val width: Int,
    internal val height: Int,
    internal val stride: Int,
    internal val pixelByteCount: Long,
    internal val quality: Int,
    internal val imageSize: ImageSize,
)

internal enum class NativeEncodeAdmissionDisposition {
    Preparing,
    Attached,
    CleanupResidue,
}

internal enum class NativeDisableStage {
    Candidate,
    Authorized,
    Transitioning,
    CarrierBound,
    TransitionRejected,
    Finalized,
}

internal enum class NativeDisableResult {
    NotReady,
    Authorized,
    CarrierBound,
    TransitionRejected,
    Committed,
    TerminalFinalized,
}

internal class NativeEncodeEvidence internal constructor() : OperationEvidence {
    override var receipt: OperationReceipt? = null
        internal set

    override var returnedOwner: OperationReturnedOwner? = null
        internal set

    internal var result: NativeEncodeSettlement? = null
    internal var failureCause: Throwable? = null
    internal var nativeStatus: Long = NATIVE_RESULT_PENDING
    internal var nativeProducedByteCount: Long = NATIVE_RESULT_PENDING
    internal var managedAdoptedByteCount: Int = 0
    internal var resultChannelArmed: Boolean = false
    internal var carrierUseResolved: Boolean = false
}

internal class NativeEncodeOwnerBag internal constructor(
    internal val product: JpegRuntimeProduct.NativeEnabled,
    internal val descriptor: NativeFrameDescriptor,
    internal val carrierLease: NativeMallocCarrierLease,
    internal val resultBlock: ByteBuffer,
    internal var nativeDisableCandidate: JpegRuntimeProduct.FrameworkOnNativeCarrier?,
    internal var retainedOperationLease: NativeMallocCarrierLease? = null,
    internal var storageOwner: EncodedStorageOwner? = null,
    internal var transaction: EncodedStorageOwner.NativeTransaction? = null,
    internal var segmentSink: EncodedStorageOwner.NativeSegmentSink? = null,
    internal var unpublishedToRetire: EncodedStorageOwner.UnpublishedEncodedPayload? = null,
    internal var retainCommittedFrame: Boolean? = null,
    internal var admissionDisposition: NativeEncodeAdmissionDisposition = NativeEncodeAdmissionDisposition.Preparing,
    internal var admissionFailureCause: Throwable? = null,
    internal var nativeDisableStage: NativeDisableStage = NativeDisableStage.Candidate,
) : OperationOwnerBag

internal class NativeEncodeOccurrence private constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val capturedProduct: JpegRuntimeProduct.NativeEnabled,
    internal val operation: OperationOccurrence<NativeEncodeEvidence>,
    internal val ownerBag: NativeEncodeOwnerBag,
    internal val ioOperation: JpegIoOperation<NativeEncodeEvidence>,
) {
    internal companion object {
        internal fun create(
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
            capturedProduct: JpegRuntimeProduct.NativeEnabled,
            carrierLease: NativeMallocCarrierLease,
            descriptor: NativeFrameDescriptor,
            nativeDisableCandidate: JpegRuntimeProduct.FrameworkOnNativeCarrier,
            clock: EngineClock,
            signal: SettlementSignal,
            work: (NativeEncodeOccurrence) -> Unit,
        ): NativeEncodeOccurrence {
            val evidence = NativeEncodeEvidence()
            val resultBlock = ByteBuffer.allocateDirect(NATIVE_RESULT_BLOCK_BYTE_COUNT).order(ByteOrder.nativeOrder())
            resultBlock.putLong(NATIVE_STATUS_OFFSET, NATIVE_RESULT_PENDING)
            resultBlock.putLong(NATIVE_PRODUCED_BYTE_COUNT_OFFSET, NATIVE_RESULT_PENDING)
            val bag = NativeEncodeOwnerBag(
                product = capturedProduct,
                descriptor = descriptor,
                carrierLease = carrierLease,
                resultBlock = resultBlock,
                nativeDisableCandidate = nativeDisableCandidate,
            )
            val operation = OperationOccurrence(
                identity = identity.operationIdentity,
                clock = clock,
                returnCell = OperationReturnCell(evidence),
                ownerBag = bag,
                deadlineIdentity = identity.deadlineIdentity,
                deadlineDurationNanos = jpegEnteredOperationSafetyNanos,
                initialWakeGeneration = identity.initialWakeGeneration,
                timeoutCause = identity.timeoutCause,
                wakeSignal = signal,
            )
            lateinit var occurrence: NativeEncodeOccurrence
            val ioOperation = JpegIoOperation(operation, Runnable { work(occurrence) })
            occurrence = NativeEncodeOccurrence(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                capturedProduct = capturedProduct,
                operation = operation,
                ownerBag = bag,
                ioOperation = ioOperation,
            )
            return occurrence
        }
    }
}

internal const val NATIVE_RESULT_PENDING: Long = -1L
internal const val NATIVE_RESULT_BLOCK_BYTE_COUNT: Int = Long.SIZE_BYTES * 2
internal const val NATIVE_STATUS_OFFSET: Int = 0
internal const val NATIVE_PRODUCED_BYTE_COUNT_OFFSET: Int = Long.SIZE_BYTES
