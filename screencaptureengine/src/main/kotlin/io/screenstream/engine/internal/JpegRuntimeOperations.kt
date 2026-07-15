package io.screenstream.engine.internal

import io.screenstream.engine.ImageSize
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.settlement.jpegEnteredOperationSafetyNanos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    internal var nativeCallBlocks: NativeCallBlocks? = null
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

internal enum class NativeWriterResidueDisposition {
    NoNativeEntry,
    Unresolved,
    ClearOnReturn,
    ReleasedAndCleared,
}

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
    internal var compressorResult: Int = Int.MIN_VALUE
    internal var writerStatus: Long = Long.MIN_VALUE
    internal var totalBytes: Long = Long.MIN_VALUE
    internal var adoptedBytes: Long = Long.MIN_VALUE
    internal var remainingBytes: Long = Long.MIN_VALUE
    internal var remainingSegmentCount: Long = Long.MIN_VALUE
    internal var writerResidueDisposition: NativeWriterResidueDisposition = NativeWriterResidueDisposition.Unresolved
    internal var carrierUseResolved: Boolean = false
}

internal class NativeEncodeOwnerBag internal constructor(
    internal val product: JpegRuntimeProduct.NativeEnabled,
    internal val descriptor: NativeFrameDescriptor,
    internal val carrierLease: NativeMallocCarrierLease,
    internal var nativeDisableCandidate: JpegRuntimeProduct.FrameworkOnNativeCarrier?,
    internal var retainedOperationLease: NativeMallocCarrierLease? = null,
    internal var callBlocksOwner: NativeCallBlocks? = null,
    internal var callBlockLoan: NativeCallBlockLoan? = null,
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
            val bag = NativeEncodeOwnerBag(
                product = capturedProduct,
                descriptor = descriptor,
                carrierLease = carrierLease,
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

internal class NativeCallBlocks private constructor(
    internal val writerBlock: ByteBuffer,
    internal val resultBlock: ByteBuffer,
) {
    private val gate: ReentrantLock = ReentrantLock(false)
    private val reusableLoan: NativeCallBlockLoan = NativeCallBlockLoan.create(writerBlock, resultBlock)
    private var activeLoan: NativeCallBlockLoan? = null

    internal fun acquire(): NativeCallBlockLoan? = gate.withLock {
        if (activeLoan != null || !reusableLoan.acquire()) return@withLock null

        activeLoan = reusableLoan
        reusableLoan
    }

    internal fun release(loan: NativeCallBlockLoan): Boolean = gate.withLock {
        if (activeLoan !== loan || !loan.release()) return@withLock false

        activeLoan = null
        true
    }

    internal companion object {
        internal fun create(): NativeCallBlocks = NativeCallBlocks(
            writerBlock = ByteBuffer.allocateDirect(Long.SIZE_BYTES).order(ByteOrder.nativeOrder()),
            resultBlock = ByteBuffer.allocateDirect(Long.SIZE_BYTES * RESULT_FIELD_COUNT).order(ByteOrder.nativeOrder()),
        )

        private const val RESULT_FIELD_COUNT: Int = 5
    }
}

internal class NativeCallBlockLoan private constructor(
    internal val writerBlock: ByteBuffer,
    internal val resultBlock: ByteBuffer,
) {
    private enum class LoanState {
        Available,
        Acquired,
    }

    private var state: LoanState = LoanState.Available
    internal var writerResidueDisposition: NativeWriterResidueDisposition = NativeWriterResidueDisposition.NoNativeEntry
        private set

    internal fun acquire(): Boolean {
        if (state != LoanState.Available || writerResidueDisposition != NativeWriterResidueDisposition.NoNativeEntry) {
            return false
        }

        state = LoanState.Acquired
        return true
    }

    internal fun reset() {
        check(state == LoanState.Acquired && writerResidueDisposition == NativeWriterResidueDisposition.NoNativeEntry)
        writerBlock.putLong(0, 0L)
        var offset = 0
        while (offset < resultBlock.capacity()) {
            resultBlock.putLong(offset, 0L)
            offset += Long.SIZE_BYTES
        }
    }

    internal fun markNativeEntryAttempted(): Boolean {
        if (state != LoanState.Acquired || writerResidueDisposition != NativeWriterResidueDisposition.NoNativeEntry) return false

        writerResidueDisposition = NativeWriterResidueDisposition.Unresolved
        return true
    }

    internal fun markClearOnReturn(): Boolean {
        if (state != LoanState.Acquired || writerResidueDisposition != NativeWriterResidueDisposition.Unresolved) return false

        writerResidueDisposition = NativeWriterResidueDisposition.ClearOnReturn
        return true
    }

    internal fun markReleasedAndCleared(): Boolean {
        if (state != LoanState.Acquired || writerResidueDisposition != NativeWriterResidueDisposition.Unresolved) return false

        writerResidueDisposition = NativeWriterResidueDisposition.ReleasedAndCleared
        return true
    }

    internal fun release(): Boolean {
        if (state != LoanState.Acquired || writerResidueDisposition == NativeWriterResidueDisposition.Unresolved) return false

        writerResidueDisposition = NativeWriterResidueDisposition.NoNativeEntry
        state = LoanState.Available
        return true
    }

    internal companion object {
        internal fun create(writerBlock: ByteBuffer, resultBlock: ByteBuffer): NativeCallBlockLoan =
            NativeCallBlockLoan(writerBlock = writerBlock, resultBlock = resultBlock)
    }
}
