package io.screenstream.engine.internal

import android.graphics.Bitmap
import android.os.Build
import io.screenstream.engine.ImageSize
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.settlement.jpegEnteredOperationSafetyNanos
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    internal val expectedProduct: JpegRuntimeProduct,
    internal val sdkInt: Int,
    internal val operation: OperationOccurrence<FrameworkResourceCreationEvidence>,
    internal val ownerBag: FrameworkResourceCreationOwnerBag,
    internal val ioOperation: JpegIoOperation<FrameworkResourceCreationEvidence>,
) {
    internal companion object {
        internal fun create(
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            expectedProduct: JpegRuntimeProduct,
            sdkInt: Int,
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
                sdkInt = sdkInt,
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
    internal val owner: FrameworkJpegOwner,
    internal val product: JpegRuntimeProduct,
    internal val carrierLease: RgbaCarrierLease,
    internal var retainedOperationLease: RgbaCarrierLease?,
    internal var storageOwner: EncodedStorageOwner?,
    internal var transaction: EncodedStorageOwner.FrameworkTransaction?,
    internal var unpublishedToRetire: EncodedStorageOwner.UnpublishedEncodedPayload?,
    internal var retainCommittedFrame: Boolean?,
) : OperationOwnerBag

internal class FrameworkEncodeOccurrence private constructor(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
    internal val capturedOwner: FrameworkJpegOwner,
    internal val capturedProduct: JpegRuntimeProduct,
    internal val quality: Int,
    internal val operation: OperationOccurrence<FrameworkEncodeEvidence>,
    internal val ownerBag: FrameworkEncodeOwnerBag,
    internal val ioOperation: JpegIoOperation<FrameworkEncodeEvidence>,
) {
    internal companion object {
        internal fun create(
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            capturedOwner: FrameworkJpegOwner,
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
                owner = capturedOwner,
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
                capturedOwner = capturedOwner,
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
            owner: FrameworkJpegOwner,
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

internal class FrameworkJpegOwner private constructor(
    internal val imageSize: ImageSize,
    internal val rowByteCount: Int,
    internal val pixelByteCount: Int,
    private val jpegRuntimeOwner: JpegRuntimeOwner,
    private val clock: EngineClock,
    private val bitmapOwner: BitmapReturnedOwner,
) {
    private class BitmapReturnedOwner : OperationReturnedOwner {
        private enum class State {
            Empty,
            BitmapOwned,
            CompleteUninstalled,
            Installed,
            Recycling,
            Recycled,
        }

        private val gate: ReentrantLock = ReentrantLock(false)

        private var state: State = State.Empty
        private var bitmap: Bitmap? = null
        private var rowScratch: IntArray? = null
        private var selectedTransferMode: FrameworkTransferMode? = null
        private var actualWidth: Int = 0
        private var actualHeight: Int = 0
        private var actualConfig: Bitmap.Config? = null
        private var actualMutable: Boolean = false
        private var actualRecycled: Boolean = true
        private var actualRowByteCount: Int = 0
        private var actualBitmapByteCount: Int = 0
        private var actualApiMetadataValid: Boolean = false
        private var admissionOpen: Boolean = false
        private var activeEncode: FrameworkEncodeOccurrence? = null
        private var bitmapUses: Int = 0
        private var recycleOccurrence: FrameworkBitmapRecycleOccurrence? = null

        fun adoptBitmap(returnedBitmap: Bitmap): Boolean = gate.withLock {
            if (state != State.Empty || bitmap != null) return@withLock false

            bitmap = returnedBitmap
            state = State.BitmapOwned
            true
        }

        fun recordActualMetadata(
            width: Int,
            height: Int,
            config: Bitmap.Config?,
            mutable: Boolean,
            recycled: Boolean,
            rowByteCount: Int,
            bitmapByteCount: Int,
            apiMetadataValid: Boolean,
        ): Boolean = gate.withLock {
            if (state != State.BitmapOwned || bitmap == null || actualConfig != null || actualWidth != 0 ||
                actualHeight != 0 || actualRowByteCount != 0 || actualBitmapByteCount != 0
            ) {
                return@withLock false
            }

            actualWidth = width
            actualHeight = height
            actualConfig = config
            actualMutable = mutable
            actualRecycled = recycled
            actualRowByteCount = rowByteCount
            actualBitmapByteCount = bitmapByteCount
            actualApiMetadataValid = apiMetadataValid
            true
        }

        fun completeResources(transferMode: FrameworkTransferMode, scratch: IntArray?): Boolean = gate.withLock {
            if (state != State.BitmapOwned || bitmap == null || selectedTransferMode != null || rowScratch != null ||
                (transferMode == FrameworkTransferMode.TightBufferCopy && scratch != null) ||
                (transferMode == FrameworkTransferMode.PortableRowCopy && scratch == null) || actualWidth <= 0 ||
                actualHeight <= 0 || actualConfig != Bitmap.Config.ARGB_8888 || !actualMutable || actualRecycled ||
                actualRowByteCount <= 0 || actualBitmapByteCount <= 0 || !actualApiMetadataValid
            ) {
                return@withLock false
            }

            selectedTransferMode = transferMode
            rowScratch = scratch
            state = State.CompleteUninstalled
            true
        }

        fun install(): Boolean = gate.withLock {
            if (state != State.CompleteUninstalled || bitmap == null || selectedTransferMode == null ||
                actualWidth <= 0 || actualHeight <= 0 || actualConfig != Bitmap.Config.ARGB_8888 || !actualMutable ||
                actualRecycled || actualRowByteCount <= 0 || actualBitmapByteCount <= 0 || !actualApiMetadataValid ||
                recycleOccurrence != null
            ) {
                return@withLock false
            }

            state = State.Installed
            admissionOpen = true
            true
        }

        fun transferMode(): FrameworkTransferMode? = gate.withLock {
            if (state != State.Installed) null else selectedTransferMode
        }

        fun isInstalledAndHealthy(): Boolean = gate.withLock {
            state == State.Installed && bitmap != null && selectedTransferMode != null && recycleOccurrence == null
        }

        fun admitEncode(occurrence: FrameworkEncodeOccurrence): Boolean = gate.withLock {
            if (state != State.Installed || !admissionOpen || bitmap == null || activeEncode != null || recycleOccurrence != null) {
                return@withLock false
            }

            activeEncode = occurrence
            true
        }

        fun cancelEncodeAdmission(occurrence: FrameworkEncodeOccurrence): Boolean = gate.withLock {
            if (activeEncode !== occurrence || bitmapUses != 0) return@withLock false

            activeEncode = null
            true
        }

        fun enterBitmapUse(occurrence: FrameworkEncodeOccurrence): Bitmap? = gate.withLock {
            if (state != State.Installed || activeEncode !== occurrence || bitmapUses != 0 || recycleOccurrence != null) {
                return@withLock null
            }

            val currentBitmap = bitmap ?: return@withLock null
            bitmapUses = 1
            currentBitmap
        }

        fun exitBitmapUse(occurrence: FrameworkEncodeOccurrence): Boolean = gate.withLock {
            if (activeEncode !== occurrence || bitmapUses != 1) return@withLock false

            bitmapUses = 0
            true
        }

        fun completeEncode(occurrence: FrameworkEncodeOccurrence): Boolean = gate.withLock {
            if (activeEncode !== occurrence || bitmapUses != 0) return@withLock false

            activeEncode = null
            true
        }

        fun closeAdmissionAndCheckDrained(): Boolean = gate.withLock {
            if (state != State.Installed) return@withLock false

            admissionOpen = false
            activeEncode == null && bitmapUses == 0
        }

        fun hasNoBitmap(): Boolean = gate.withLock {
            state == State.Empty && bitmap == null
        }

        fun ownsBitmapForCleanup(): Boolean = gate.withLock {
            (state == State.BitmapOwned || state == State.CompleteUninstalled) && bitmap != null &&
                    activeEncode == null && bitmapUses == 0 && recycleOccurrence == null
        }

        fun admitRecycle(occurrence: FrameworkBitmapRecycleOccurrence, installedOwner: Boolean): Boolean = gate.withLock {
            val ready = if (installedOwner) {
                state == State.Installed && !admissionOpen && activeEncode == null && bitmapUses == 0
            } else {
                (state == State.BitmapOwned || state == State.CompleteUninstalled) && activeEncode == null && bitmapUses == 0
            }
            if (!ready || bitmap == null || recycleOccurrence != null) return@withLock false

            recycleOccurrence = occurrence
            state = State.Recycling
            true
        }

        fun bitmapForRecycle(occurrence: FrameworkBitmapRecycleOccurrence): Bitmap? = gate.withLock {
            if (state != State.Recycling || recycleOccurrence !== occurrence) null else bitmap
        }

        fun completeRecycle(occurrence: FrameworkBitmapRecycleOccurrence): Boolean = gate.withLock {
            if (state != State.Recycling || recycleOccurrence !== occurrence || bitmap == null) return@withLock false

            bitmap = null
            rowScratch = null
            selectedTransferMode = null
            actualWidth = 0
            actualHeight = 0
            actualConfig = null
            actualMutable = false
            actualRecycled = true
            actualRowByteCount = 0
            actualBitmapByteCount = 0
            actualApiMetadataValid = false
            admissionOpen = false
            recycleOccurrence = null
            state = State.Recycled
            true
        }

        fun bitmapForTransfer(): Bitmap? = gate.withLock {
            if (state != State.Installed || bitmapUses != 1 || recycleOccurrence != null) null else bitmap
        }

        fun rowScratchForTransfer(): IntArray? = gate.withLock {
            if (state != State.Installed || bitmapUses != 1 || recycleOccurrence != null) null else rowScratch
        }
    }

    internal val transferMode: FrameworkTransferMode
        get() = checkNotNull(bitmapOwner.transferMode())

    internal fun isShapeCompatible(expectedImageSize: ImageSize, expectedCarrierByteCount: Int): Boolean =
        expectedCarrierByteCount == pixelByteCount && expectedImageSize == imageSize && bitmapOwner.isInstalledAndHealthy()

    internal fun beginEncode(
        expectedProduct: JpegRuntimeProduct.FrameworkOnNativeCarrier,
        expectedLease: NativeMallocCarrierLease,
        storage: EncodedStorageOwner,
        quality: Int,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): FrameworkEncodeOccurrence? = beginEncodeCommon(
        expectedProduct = expectedProduct,
        expectedLease = expectedLease,
        storage = storage,
        quality = quality,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        identity = identity,
    )

    internal fun beginEncode(
        expectedProduct: JpegRuntimeProduct.FrameworkOnManagedCarrier,
        expectedLease: ManagedDirectCarrierLease,
        storage: EncodedStorageOwner,
        quality: Int,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): FrameworkEncodeOccurrence? = beginEncodeCommon(
        expectedProduct = expectedProduct,
        expectedLease = expectedLease,
        storage = storage,
        quality = quality,
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        identity = identity,
    )

    internal fun settleEncode(occurrence: FrameworkEncodeOccurrence, storage: EncodedStorageOwner, retainCommittedFrame: Boolean): FrameworkEncodeSettlement {
        if (occurrence.capturedOwner !== this) return FrameworkEncodeSettlement.NotSettled

        val operation = occurrence.operation
        val gate = operation.settlementGate
        var settledResult = FrameworkEncodeSettlement.NotSettled
        var bitmapUseResolved = false
        var timelyNormal = false
        gate.withLock {
            val ownerBag = occurrence.ownerBag
            if (ownerBag.owner !== this || ownerBag.storageOwner != null && ownerBag.storageOwner !== storage) {
                return FrameworkEncodeSettlement.NotSettled
            }

            if (operation.returnCell.use == OperationReturnUse.Unclaimed) operation.arbitrate()
            if (operation.returnCell.use == OperationReturnUse.Unclaimed) {
                when {
                    cancelledEncodeWithoutReturnLocked(occurrence) -> {
                        settledResult = FrameworkEncodeSettlement.CancelledWithoutReturn
                        bitmapUseResolved = true
                    }

                    terminalizedUnenteredEncodeFailureLocked(occurrence) || unenteredEncodeFailureLocked(occurrence) -> {
                        settledResult = FrameworkEncodeSettlement.InternalFailure
                        bitmapUseResolved = true
                    }

                    else -> return FrameworkEncodeSettlement.NotSettled
                }
            } else {
                val evidence = operation.returnCell.evidence
                settledResult = evidence.result
                bitmapUseResolved = evidence.bitmapUseResolved
                timelyNormal = operation.returnCell.use == OperationReturnUse.Timely &&
                        operation.returnCell.disposition == OperationReturnDisposition.Normal
            }
            if (ownerBag.retainCommittedFrame == null) {
                ownerBag.retainCommittedFrame = retainCommittedFrame && timelyNormal &&
                        settledResult == FrameworkEncodeSettlement.Success
            }
        }

        var retainedLease: RgbaCarrierLease? = null
        gate.withLock {
            if (bitmapUseResolved) retainedLease = occurrence.ownerBag.retainedOperationLease
        }
        val exactRetainedLease = retainedLease
        if (exactRetainedLease != null && exactRetainedLease.releaseFromOperation()) {
            gate.withLock {
                if (occurrence.ownerBag.retainedOperationLease === exactRetainedLease) {
                    occurrence.ownerBag.retainedOperationLease = null
                }
            }
        }

        var transaction: EncodedStorageOwner.FrameworkTransaction? = null
        var storageOwner: EncodedStorageOwner? = null
        var retainPayload = false
        gate.withLock {
            transaction = occurrence.ownerBag.transaction
            storageOwner = occurrence.ownerBag.storageOwner
            retainPayload = occurrence.ownerBag.retainCommittedFrame == true
        }
        val exactTransaction = transaction
        val exactStorage = storageOwner
        if (exactTransaction != null && exactStorage != null) {
            if (exactTransaction.isCommitted && settledResult == FrameworkEncodeSettlement.Success) {
                val unpublished = exactStorage.replaceCommittedProduction(exactTransaction)
                if (unpublished != null) {
                    gate.withLock {
                        if (occurrence.ownerBag.transaction === exactTransaction && occurrence.ownerBag.storageOwner === exactStorage) {
                            if (!retainPayload) occurrence.ownerBag.unpublishedToRetire = unpublished
                            occurrence.ownerBag.transaction = null
                        }
                    }
                }
            } else {
                if (!exactTransaction.isCommitted && !exactTransaction.isAborted) exactTransaction.abort()
                if (exactTransaction.isAborted && exactStorage.detachAbortedProduction(exactTransaction)) {
                    gate.withLock {
                        if (occurrence.ownerBag.transaction === exactTransaction && occurrence.ownerBag.storageOwner === exactStorage) {
                            occurrence.ownerBag.transaction = null
                        }
                    }
                }
            }
        }

        var unpublished: EncodedStorageOwner.UnpublishedEncodedPayload? = null
        gate.withLock {
            unpublished = occurrence.ownerBag.unpublishedToRetire
        }
        val exactUnpublished = unpublished
        if (exactUnpublished != null && exactStorage != null && exactStorage.retireUnpublished(exactUnpublished)) {
            gate.withLock {
                if (occurrence.ownerBag.unpublishedToRetire === exactUnpublished && occurrence.ownerBag.storageOwner === exactStorage) {
                    occurrence.ownerBag.unpublishedToRetire = null
                }
            }
        }

        val mechanicsSettled = gate.withLock {
            val ownerBag = occurrence.ownerBag
            if (ownerBag.transaction == null && ownerBag.unpublishedToRetire == null) ownerBag.storageOwner = null
            ownerBag.retainedOperationLease == null && ownerBag.storageOwner == null && ownerBag.transaction == null && ownerBag.unpublishedToRetire == null
        }
        if (!mechanicsSettled || !bitmapUseResolved || !bitmapOwner.completeEncode(occurrence)) {
            return FrameworkEncodeSettlement.NotSettled
        }
        return settledResult
    }

    internal fun closeAdmissionAndCheckDrained(): Boolean = bitmapOwner.closeAdmissionAndCheckDrained()

    internal fun beginIncompatibleRecycle(
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): FrameworkBitmapRecycleOccurrence? {
        if (!bitmapOwner.closeAdmissionAndCheckDrained()) return null

        val occurrence = createRecycleOccurrence(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            origin = FrameworkBitmapRecycleOrigin.IncompatibleReplacement,
            identity = identity,
            operationIdentity = identity.operationIdentity,
        )
        if (!bitmapOwner.admitRecycle(occurrence, installedOwner = true)) return null

        jpegRuntimeOwner.submitJpegIoOperation(occurrence.ioOperation)
        return occurrence
    }

    internal fun beginTerminalRecycle(
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        operationIdentity: Long,
    ): FrameworkBitmapRecycleOccurrence? {
        if (!bitmapOwner.closeAdmissionAndCheckDrained()) return null

        val occurrence = createRecycleOccurrence(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            origin = FrameworkBitmapRecycleOrigin.TerminalRetirement,
            identity = null,
            operationIdentity = operationIdentity,
        )
        if (occurrence.operation.arbitrateTerminal(mandatoryCleanup = true) != OperationTerminalArbitration.Transferred ||
            !bitmapOwner.admitRecycle(occurrence, installedOwner = true)
        ) {
            return null
        }

        jpegRuntimeOwner.submitJpegIoOperation(occurrence.ioOperation)
        return occurrence
    }

    internal fun settleRecycle(occurrence: FrameworkBitmapRecycleOccurrence): FrameworkBitmapRecycleSettlement {
        val gate = occurrence.operation.settlementGate
        var timely = false
        var normal = false
        var exactReceipt = false
        var exactOwner: FrameworkJpegOwner? = null
        gate.withLock {
            if (occurrence.ownerBag.owner !== this) return FrameworkBitmapRecycleSettlement.NotSettled
            val arbitration = if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) {
                occurrence.operation.arbitrate()
            } else {
                OperationArbitration.None
            }
            if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) {
                if (arbitration == OperationArbitration.SchedulerRejected ||
                    arbitration == OperationArbitration.DeadlineGuardFailed ||
                    arbitration == OperationArbitration.ExpiredEmpty ||
                    unenteredRecycleFailureLocked(occurrence)
                ) {
                    return FrameworkBitmapRecycleSettlement.UnsafeResidue
                }
                return FrameworkBitmapRecycleSettlement.NotSettled
            }

            val evidence = occurrence.operation.returnCell.evidence
            timely = occurrence.operation.returnCell.use == OperationReturnUse.Timely
            normal = occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal
            exactReceipt = evidence.receipt === evidence.normalReceipt
            exactOwner = occurrence.ownerBag.owner
        }

        if (!normal || !exactReceipt || exactOwner !== this || !bitmapOwner.completeRecycle(occurrence)) {
            return FrameworkBitmapRecycleSettlement.UnsafeResidue
        }

        return gate.withLock {
            if (occurrence.ownerBag.owner !== this) return@withLock FrameworkBitmapRecycleSettlement.UnsafeResidue
            occurrence.ownerBag.owner = null
            if (timely && occurrence.origin == FrameworkBitmapRecycleOrigin.IncompatibleReplacement) {
                FrameworkBitmapRecycleSettlement.ReplacementAuthorized
            } else {
                FrameworkBitmapRecycleSettlement.CleanupCompleted
            }
        }
    }

    private fun transferExactRgbaToBitmap(lease: RgbaCarrierLease): Boolean {
        val exactRange = lease.enterExactRange() ?: return false
        var transferred: Boolean = false
        var transferFailure: Throwable? = null
        var exactRangeUseResolved: Boolean = false
        var exitFailure: Throwable? = null
        try {
            exactRange.limit(pixelByteCount)
            exactRange.position(0)
            if (exactRange.isDirect && !exactRange.isReadOnly && exactRange.capacity() == pixelByteCount &&
                exactRange.position() == 0 && exactRange.limit() == pixelByteCount && exactRange.remaining() == pixelByteCount
            ) {
                val bitmap = bitmapOwner.bitmapForTransfer()
                if (bitmap != null) {
                    when (transferMode) {
                        FrameworkTransferMode.TightBufferCopy -> {
                            bitmap.copyPixelsFromBuffer(exactRange)
                            transferred = true
                        }

                        FrameworkTransferMode.PortableRowCopy -> {
                            val row = bitmapOwner.rowScratchForTransfer()
                            if (row != null && row.size == imageSize.widthPx) {
                                for (y in 0..<imageSize.heightPx) {
                                    val rowOffset = y * rowByteCount
                                    for (x in 0..<imageSize.widthPx) {
                                        val pixelOffset = rowOffset + x * 4
                                        val red = exactRange[pixelOffset].toInt() and 0xFF
                                        val green = exactRange[pixelOffset + 1].toInt() and 0xFF
                                        val blue = exactRange[pixelOffset + 2].toInt() and 0xFF
                                        row[x] = -0x1000000 or (red shl 16) or (green shl 8) or blue
                                    }
                                    bitmap.setPixels(row, 0, imageSize.widthPx, 0, y, imageSize.widthPx, 1)
                                }
                                transferred = true
                            }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            transferFailure = failure
        } finally {
            try {
                exactRangeUseResolved = lease.exitExactRange()
            } catch (failure: Throwable) {
                exitFailure = failure
            }
        }

        if (transferFailure is Error && transferFailure !is OutOfMemoryError) throw transferFailure
        if (exitFailure is Error && exitFailure !is OutOfMemoryError) throw exitFailure
        if (exitFailure != null || !exactRangeUseResolved) throw CARRIER_USE_RESOLUTION_FAILED
        if (transferFailure != null) throw transferFailure
        return transferred
    }

    private fun beginEncodeCommon(
        expectedProduct: JpegRuntimeProduct,
        expectedLease: RgbaCarrierLease,
        storage: EncodedStorageOwner,
        quality: Int,
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        identity: JpegFiniteOperationIdentity,
    ): FrameworkEncodeOccurrence? {
        if (quality !in 0..100 || expectedProduct.carrier !== expectedLease.carrier ||
            expectedProduct.carrier.byteCount != pixelByteCount || jpegRuntimeOwner.product !== expectedProduct ||
            jpegRuntimeOwner.lease !== expectedLease || !bitmapOwner.isInstalledAndHealthy()
        ) {
            return null
        }

        val occurrence = FrameworkEncodeOccurrence.create(
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            capturedOwner = this,
            capturedProduct = expectedProduct,
            carrierLease = expectedLease,
            quality = quality,
            identity = identity,
            clock = clock,
            signal = jpegRuntimeOwner.jpegIoSettlementSignal,
            work = ::executeEncode,
        )
        if (!bitmapOwner.admitEncode(occurrence)) return null

        if (!expectedLease.retainForOperation(expectedProduct)) {
            if (!bitmapOwner.cancelEncodeAdmission(occurrence)) throw FRAMEWORK_ENCODE_ADMISSION_FAILED
            return null
        }
        occurrence.operation.settlementGate.withLock {
            occurrence.ownerBag.retainedOperationLease = expectedLease
        }

        val transaction = try {
            EncodedStorageOwner.FrameworkTransaction()
        } catch (failure: Throwable) {
            unwindEncodeAdmission(occurrence)
            throw failure
        }
        occurrence.operation.settlementGate.withLock {
            occurrence.ownerBag.storageOwner = storage
            occurrence.ownerBag.transaction = transaction
        }

        val attached = try {
            storage.attachProduction(transaction)
        } catch (failure: Throwable) {
            unwindEncodeAdmission(occurrence)
            throw failure
        }
        if (!attached) {
            if (!unwindEncodeAdmission(occurrence)) throw FRAMEWORK_ENCODE_ADMISSION_FAILED
            return null
        }

        jpegRuntimeOwner.submitJpegIoOperation(occurrence.ioOperation)
        return occurrence
    }

    private fun unwindEncodeAdmission(occurrence: FrameworkEncodeOccurrence): Boolean {
        val gate = occurrence.operation.settlementGate
        var storage: EncodedStorageOwner? = null
        var transaction: EncodedStorageOwner.FrameworkTransaction? = null
        gate.withLock {
            storage = occurrence.ownerBag.storageOwner
            transaction = occurrence.ownerBag.transaction
        }

        val exactStorage = storage
        val exactTransaction = transaction
        if (exactTransaction != null) {
            if (!exactTransaction.isCommitted && !exactTransaction.isAborted) exactTransaction.abort()
            val detached = exactStorage?.production !== exactTransaction || exactTransaction.isAborted && exactStorage.detachAbortedProduction(exactTransaction)
            if (exactTransaction.isAborted && detached) {
                gate.withLock {
                    if (occurrence.ownerBag.transaction === exactTransaction && occurrence.ownerBag.storageOwner === exactStorage) {
                        occurrence.ownerBag.transaction = null
                        occurrence.ownerBag.storageOwner = null
                    }
                }
            }
        }

        var retainedLease: RgbaCarrierLease? = null
        gate.withLock {
            retainedLease = occurrence.ownerBag.retainedOperationLease
        }
        val exactRetainedLease = retainedLease
        if (exactRetainedLease != null && exactRetainedLease.releaseFromOperation()) {
            gate.withLock {
                if (occurrence.ownerBag.retainedOperationLease === exactRetainedLease) {
                    occurrence.ownerBag.retainedOperationLease = null
                }
            }
        }

        val mechanicsSettled = gate.withLock {
            occurrence.ownerBag.retainedOperationLease == null && occurrence.ownerBag.storageOwner == null && occurrence.ownerBag.transaction == null
        }
        return mechanicsSettled && bitmapOwner.cancelEncodeAdmission(occurrence)
    }

    private fun executeEncode(occurrence: FrameworkEncodeOccurrence) {
        val entryResult = occurrence.operation.tryEnter()
        if (entryResult != OperationEntryResult.Entered) {
            if (entryResult == OperationEntryResult.InvalidDeadline) jpegRuntimeOwner.jpegIoSettlementSignal.signal()
            return
        }

        val bitmap = bitmapOwner.enterBitmapUse(occurrence)
        if (bitmap == null) {
            val evidence = occurrence.operation.returnCell.evidence
            evidence.bitmapUseResolved = true
            evidence.result = FrameworkEncodeSettlement.InternalFailure
            evidence.failureCause = BITMAP_USE_NOT_OPERATIONAL
            if (occurrence.operation.publishThrownReturn(BITMAP_USE_NOT_OPERATIONAL)) {
                jpegRuntimeOwner.jpegIoSettlementSignal.signal()
            }
            return
        }

        var result = FrameworkEncodeSettlement.InternalFailure
        var resultCause: Throwable? = null
        var unexpectedBoundaryFailure = false
        try {
            val transferred = transferExactRgbaToBitmap(occurrence.ownerBag.carrierLease)
            if (!transferred) {
                resultCause = RGBA_TRANSFER_REJECTED
            } else {
                val transaction = checkNotNull(occurrence.ownerBag.transaction)
                val compressed = try {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, occurrence.quality, transaction.outputStream)
                } finally {
                    val firstFatalWriteError = transaction.firstFatalWriteError
                    if (firstFatalWriteError != null) throw firstFatalWriteError
                }
                when {
                    transaction.failure == EncodedStorageOwner.TransactionFailure.InternalFailure -> {
                        result = FrameworkEncodeSettlement.InternalFailure
                        resultCause = transaction.failureCause ?: MALFORMED_FRAMEWORK_TRANSACTION
                    }

                    transaction.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted -> {
                        result = FrameworkEncodeSettlement.ResourceExhausted
                        resultCause = transaction.failureCause ?: FRAMEWORK_STORAGE_OUT_OF_MEMORY
                    }

                    !compressed -> {
                        transaction.abort()
                        result = FrameworkEncodeSettlement.CompressionRejected
                    }

                    transaction.commit(imageSize) -> {
                        result = FrameworkEncodeSettlement.Success
                    }

                    transaction.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted -> {
                        result = FrameworkEncodeSettlement.ResourceExhausted
                        resultCause = transaction.failureCause ?: FRAMEWORK_STORAGE_OUT_OF_MEMORY
                    }

                    else -> {
                        result = FrameworkEncodeSettlement.InternalFailure
                        resultCause = transaction.failureCause ?: MALFORMED_FRAMEWORK_TRANSACTION
                    }
                }
            }
        } catch (allocationFailure: OutOfMemoryError) {
            result = FrameworkEncodeSettlement.ResourceExhausted
            resultCause = allocationFailure
        } catch (failure: Exception) {
            result = FrameworkEncodeSettlement.InternalFailure
            resultCause = failure
            unexpectedBoundaryFailure = true
        } finally {
            occurrence.operation.returnCell.evidence.bitmapUseResolved = bitmapOwner.exitBitmapUse(occurrence)
        }

        val transaction = occurrence.ownerBag.transaction
        if (transaction?.failure == EncodedStorageOwner.TransactionFailure.InternalFailure) {
            result = FrameworkEncodeSettlement.InternalFailure
            resultCause = transaction.failureCause ?: MALFORMED_FRAMEWORK_TRANSACTION
        } else if (!unexpectedBoundaryFailure &&
            transaction?.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted
        ) {
            result = FrameworkEncodeSettlement.ResourceExhausted
            resultCause = transaction.failureCause ?: FRAMEWORK_STORAGE_OUT_OF_MEMORY
        }
        if (result != FrameworkEncodeSettlement.Success && transaction != null && !transaction.isCommitted && !transaction.isAborted) {
            transaction.abort()
        }
        if (!occurrence.operation.returnCell.evidence.bitmapUseResolved) {
            result = FrameworkEncodeSettlement.InternalFailure
            resultCause = BITMAP_USE_RESOLUTION_FAILED
        }

        val evidence = occurrence.operation.returnCell.evidence
        evidence.result = result
        evidence.failureCause = resultCause
        val published = when (result) {
            FrameworkEncodeSettlement.Success,
            FrameworkEncodeSettlement.CompressionRejected,
                -> occurrence.operation.publishNormalReturn()

            FrameworkEncodeSettlement.ResourceExhausted,
            FrameworkEncodeSettlement.InternalFailure,
                -> occurrence.operation.publishThrownReturn(resultCause ?: MALFORMED_FRAMEWORK_TRANSACTION)

            else -> false
        }
        if (published) jpegRuntimeOwner.jpegIoSettlementSignal.signal()
    }

    private fun createRecycleOccurrence(
        desiredRevision: Long,
        geometryGeneration: Long,
        lifecycleEpoch: Long,
        origin: FrameworkBitmapRecycleOrigin,
        identity: JpegFiniteOperationIdentity?,
        operationIdentity: Long,
    ): FrameworkBitmapRecycleOccurrence = FrameworkBitmapRecycleOccurrence.create(
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        origin = origin,
        owner = this,
        identity = identity,
        operationIdentity = operationIdentity,
        clock = clock,
        signal = jpegRuntimeOwner.jpegIoSettlementSignal,
        work = ::executeRecycle,
    )

    private fun executeRecycle(occurrence: FrameworkBitmapRecycleOccurrence) {
        val entryResult = occurrence.operation.tryEnter()
        if (entryResult != OperationEntryResult.Entered) {
            if (entryResult == OperationEntryResult.InvalidDeadline) jpegRuntimeOwner.jpegIoSettlementSignal.signal()
            return
        }

        val bitmap = bitmapOwner.bitmapForRecycle(occurrence)
        if (bitmap == null) {
            if (occurrence.operation.publishThrownReturn(BITMAP_RECYCLE_OWNER_MISSING)) {
                jpegRuntimeOwner.jpegIoSettlementSignal.signal()
            }
            return
        }

        val published = try {
            bitmap.recycle()
            occurrence.operation.returnCell.evidence.receipt = occurrence.operation.returnCell.evidence.normalReceipt
            occurrence.operation.publishNormalReturn()
        } catch (failure: Throwable) {
            occurrence.operation.publishThrownReturn(failure)
        }
        if (published) jpegRuntimeOwner.jpegIoSettlementSignal.signal()
    }

    private fun cancelledEncodeWithoutReturnLocked(occurrence: FrameworkEncodeOccurrence): Boolean {
        val operation = occurrence.operation
        check(operation.settlementGate.isHeldByCurrentThread)
        val submissionResolved = operation.submissionDisposition == OperationSubmissionDisposition.Cancelled ||
                operation.submissionDisposition == OperationSubmissionDisposition.Accepted ||
                operation.submissionDisposition == OperationSubmissionDisposition.Rejected
        return operation.domain == OperationDomain.Cleanup &&
                operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                submissionResolved && operation.disposition == OperationDisposition.Cancelled &&
                operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                operation.returnCell.use == OperationReturnUse.Unclaimed
    }

    private fun terminalizedUnenteredEncodeFailureLocked(occurrence: FrameworkEncodeOccurrence): Boolean {
        val operation = occurrence.operation
        check(operation.settlementGate.isHeldByCurrentThread)
        val terminalizedFailure = operation.disposition == OperationDisposition.SchedulerRejected ||
                operation.disposition == OperationDisposition.DeadlineGuardFailed
        return operation.domain == OperationDomain.Cleanup && terminalizedFailure &&
                operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                operation.submissionDisposition == OperationSubmissionDisposition.Cancelled &&
                operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                operation.returnCell.use == OperationReturnUse.Unclaimed
    }

    private fun unenteredEncodeFailureLocked(occurrence: FrameworkEncodeOccurrence): Boolean {
        val operation = occurrence.operation
        check(operation.settlementGate.isHeldByCurrentThread)
        val submissionRejected = operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                operation.disposition == OperationDisposition.SchedulerRejected
        return operation.entryDisposition == OperationEntryDisposition.Unentered &&
                (submissionRejected || operation.disposition == OperationDisposition.DeadlineGuardFailed) &&
                operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                operation.returnCell.use == OperationReturnUse.Unclaimed
    }

    private fun unenteredRecycleFailureLocked(occurrence: FrameworkBitmapRecycleOccurrence): Boolean {
        val operation = occurrence.operation
        check(operation.settlementGate.isHeldByCurrentThread)
        val submissionRejected = operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                (operation.disposition == OperationDisposition.SchedulerRejected ||
                        operation.domain == OperationDomain.Cleanup)
        return operation.entryDisposition == OperationEntryDisposition.Unentered &&
                (submissionRejected || operation.disposition == OperationDisposition.DeadlineGuardFailed) &&
                operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                operation.returnCell.use == OperationReturnUse.Unclaimed
    }

    internal companion object {
        internal fun beginResourceCreation(
            jpegRuntimeOwner: JpegRuntimeOwner,
            clock: EngineClock,
            sdkInt: Int,
            expectedProduct: JpegRuntimeProduct.FrameworkOnNativeCarrier,
            imageSize: ImageSize,
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkResourceCreationOccurrence? = beginResourceCreationCommon(
            jpegRuntimeOwner = jpegRuntimeOwner,
            clock = clock,
            sdkInt = sdkInt,
            expectedProduct = expectedProduct,
            imageSize = imageSize,
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            identity = identity,
        )

        internal fun beginResourceCreation(
            jpegRuntimeOwner: JpegRuntimeOwner,
            clock: EngineClock,
            sdkInt: Int,
            expectedProduct: JpegRuntimeProduct.FrameworkOnManagedCarrier,
            imageSize: ImageSize,
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkResourceCreationOccurrence? = beginResourceCreationCommon(
            jpegRuntimeOwner = jpegRuntimeOwner,
            clock = clock,
            sdkInt = sdkInt,
            expectedProduct = expectedProduct,
            imageSize = imageSize,
            desiredRevision = desiredRevision,
            geometryGeneration = geometryGeneration,
            lifecycleEpoch = lifecycleEpoch,
            identity = identity,
        )

        internal fun settleResourceCreation(
            occurrence: FrameworkResourceCreationOccurrence,
            installAllowed: Boolean,
        ): FrameworkJpegOwner? {
            val gate = occurrence.operation.settlementGate
            return gate.withLock {
                if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
                val timelyComplete = occurrence.operation.returnCell.use == OperationReturnUse.Timely &&
                        occurrence.operation.returnCell.disposition == OperationReturnDisposition.Normal &&
                        occurrence.operation.returnCell.evidence.result == FrameworkResourceCreationResult.Complete
                if (!installAllowed || !timelyComplete) return@withLock null

                val exactCandidate = occurrence.ownerBag.candidateOwner ?: return@withLock null
                if (!exactCandidate.bitmapOwner.install()) return@withLock null
                occurrence.ownerBag.candidateOwner = null
                exactCandidate
            }
        }

        internal fun settleResourceCreationWithoutReturnedBitmap(
            occurrence: FrameworkResourceCreationOccurrence,
        ): Boolean {
            val gate = occurrence.operation.settlementGate
            return gate.withLock {
                val candidate = occurrence.ownerBag.candidateOwner ?: return@withLock false
                if (!candidate.bitmapOwner.hasNoBitmap() || occurrence.operation.returnCell.evidence.returnedOwner != null) {
                    return@withLock false
                }
                if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
                if (!creationWithoutReturnedBitmapSettledLocked(occurrence)) return@withLock false

                occurrence.ownerBag.candidateOwner = null
                true
            }
        }

        internal fun beginReturnedOwnerRecycle(
            occurrence: FrameworkResourceCreationOccurrence,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkBitmapRecycleOccurrence? = beginReturnedOwnerRecycleCommon(
            occurrence = occurrence,
            identity = identity,
            operationIdentity = identity.operationIdentity,
        )

        internal fun beginTerminalReturnedOwnerRecycle(
            occurrence: FrameworkResourceCreationOccurrence,
            operationIdentity: Long,
        ): FrameworkBitmapRecycleOccurrence? = beginReturnedOwnerRecycleCommon(
            occurrence = occurrence,
            identity = null,
            operationIdentity = operationIdentity,
        )

        private fun beginResourceCreationCommon(
            jpegRuntimeOwner: JpegRuntimeOwner,
            clock: EngineClock,
            sdkInt: Int,
            expectedProduct: JpegRuntimeProduct,
            imageSize: ImageSize,
            desiredRevision: Long,
            geometryGeneration: Long,
            lifecycleEpoch: Long,
            identity: JpegFiniteOperationIdentity,
        ): FrameworkResourceCreationOccurrence? {
            val width = imageSize.widthPx
            val height = imageSize.heightPx
            if (width <= 0 || height <= 0 || jpegRuntimeOwner.product !== expectedProduct) return null

            val rowLong = 4L * width.toLong()
            if (rowLong !in 1L..Int.MAX_VALUE.toLong() || rowLong > Long.MAX_VALUE / height.toLong()) return null
            val pixelLong = rowLong * height.toLong()
            if (pixelLong !in 1L..Int.MAX_VALUE.toLong() || expectedProduct.carrier.byteCount.toLong() != pixelLong) {
                return null
            }

            val bitmapReturnedOwner = BitmapReturnedOwner()
            val candidateOwner = FrameworkJpegOwner(
                imageSize = imageSize,
                rowByteCount = rowLong.toInt(),
                pixelByteCount = pixelLong.toInt(),
                jpegRuntimeOwner = jpegRuntimeOwner,
                clock = clock,
                bitmapOwner = bitmapReturnedOwner,
            )
            val occurrence = FrameworkResourceCreationOccurrence.create(
                desiredRevision = desiredRevision,
                geometryGeneration = geometryGeneration,
                lifecycleEpoch = lifecycleEpoch,
                expectedProduct = expectedProduct,
                sdkInt = sdkInt,
                identity = identity,
                candidateOwner = candidateOwner,
                clock = clock,
                signal = jpegRuntimeOwner.jpegIoSettlementSignal,
                work = ::executeResourceCreation,
            )
            jpegRuntimeOwner.submitJpegIoOperation(occurrence.ioOperation)
            return occurrence
        }

        private fun executeResourceCreation(occurrence: FrameworkResourceCreationOccurrence) {
            val entryResult = occurrence.operation.tryEnter()
            if (entryResult != OperationEntryResult.Entered) {
                if (entryResult == OperationEntryResult.InvalidDeadline) {
                    occurrence.ownerBag.candidateOwner?.jpegRuntimeOwner?.jpegIoSettlementSignal?.signal()
                }
                return
            }

            val candidate = checkNotNull(occurrence.ownerBag.candidateOwner)
            val evidence = occurrence.operation.returnCell.evidence
            val bitmap = try {
                Bitmap.createBitmap(candidate.imageSize.widthPx, candidate.imageSize.heightPx, Bitmap.Config.ARGB_8888)
            } catch (allocationFailure: OutOfMemoryError) {
                publishCreationFailure(occurrence, FrameworkResourceCreationResult.ResourceExhausted, allocationFailure)
                return
            } catch (failure: Exception) {
                publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, failure)
                return
            }

            if (!candidate.bitmapOwner.adoptBitmap(bitmap)) {
                publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, BITMAP_ADOPTION_FAILED)
                return
            }
            evidence.returnedOwner = candidate.bitmapOwner

            try {
                val actualWidth = bitmap.width
                val actualHeight = bitmap.height
                val actualConfig = bitmap.config
                val actualMutable = bitmap.isMutable
                val actualRecycled = bitmap.isRecycled
                val actualRowByteCount = bitmap.rowBytes
                val actualBitmapByteCount = bitmap.byteCount

                evidence.actualWidth = actualWidth
                evidence.actualHeight = actualHeight
                evidence.actualRowByteCount = actualRowByteCount
                evidence.actualBitmapByteCount = actualBitmapByteCount

                val rowStorageLong = actualRowByteCount.toLong() * actualHeight.toLong()
                val commonMetadataValid = actualWidth == candidate.imageSize.widthPx &&
                        actualHeight == candidate.imageSize.heightPx && actualConfig == Bitmap.Config.ARGB_8888 &&
                        actualMutable && !actualRecycled && actualRowByteCount >= candidate.rowByteCount &&
                        actualBitmapByteCount >= candidate.pixelByteCount &&
                        actualRowByteCount > 0 && actualBitmapByteCount > 0 &&
                        actualHeight > 0 && rowStorageLong in 1L..actualBitmapByteCount.toLong()
                val apiMetadataValid = when {
                    occurrence.sdkInt < Build.VERSION_CODES.O -> true
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                        bitmap.config != Bitmap.Config.HARDWARE && bitmap.colorSpace?.isSrgb == true

                    else -> false
                }
                if (!candidate.bitmapOwner.recordActualMetadata(
                        width = actualWidth,
                        height = actualHeight,
                        config = actualConfig,
                        mutable = actualMutable,
                        recycled = actualRecycled,
                        rowByteCount = actualRowByteCount,
                        bitmapByteCount = actualBitmapByteCount,
                        apiMetadataValid = apiMetadataValid,
                    )
                ) {
                    publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, BITMAP_RESOURCE_COMPLETION_FAILED)
                    return
                }
                if (!commonMetadataValid || !apiMetadataValid) {
                    publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, MALFORMED_BITMAP_METADATA)
                    return
                }

                val transferMode = if (actualRowByteCount == candidate.rowByteCount && actualBitmapByteCount == candidate.pixelByteCount) {
                    FrameworkTransferMode.TightBufferCopy
                } else {
                    FrameworkTransferMode.PortableRowCopy
                }
                val rowScratch = if (transferMode == FrameworkTransferMode.PortableRowCopy) {
                    IntArray(candidate.imageSize.widthPx)
                } else {
                    null
                }
                if (!candidate.bitmapOwner.completeResources(transferMode, rowScratch)) {
                    publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, BITMAP_RESOURCE_COMPLETION_FAILED)
                    return
                }

                evidence.transferMode = transferMode
                evidence.result = FrameworkResourceCreationResult.Complete
                if (occurrence.operation.publishNormalReturn()) candidate.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
            } catch (allocationFailure: OutOfMemoryError) {
                publishCreationFailure(occurrence, FrameworkResourceCreationResult.ResourceExhausted, allocationFailure)
            } catch (failure: Exception) {
                publishCreationFailure(occurrence, FrameworkResourceCreationResult.InternalFailure, failure)
            }
        }

        private fun publishCreationFailure(
            occurrence: FrameworkResourceCreationOccurrence,
            result: FrameworkResourceCreationResult,
            failure: Throwable,
        ) {
            val evidence = occurrence.operation.returnCell.evidence
            evidence.result = result
            evidence.failureCause = failure
            if (occurrence.operation.publishThrownReturn(failure)) {
                occurrence.ownerBag.candidateOwner?.jpegRuntimeOwner?.jpegIoSettlementSignal?.signal()
            }
        }

        private fun beginReturnedOwnerRecycleCommon(
            occurrence: FrameworkResourceCreationOccurrence,
            identity: JpegFiniteOperationIdentity?,
            operationIdentity: Long,
        ): FrameworkBitmapRecycleOccurrence? {
            val sourceGate = occurrence.operation.settlementGate
            var owner: FrameworkJpegOwner? = null
            var recycle: FrameworkBitmapRecycleOccurrence? = null
            sourceGate.withLock {
                val candidate = occurrence.ownerBag.candidateOwner ?: return null
                if (occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed) occurrence.operation.arbitrate()
                if (
                    occurrence.operation.returnCell.use == OperationReturnUse.Unclaimed ||
                    occurrence.operation.returnCell.evidence.returnedOwner !== candidate.bitmapOwner || !candidate.bitmapOwner.ownsBitmapForCleanup()
                ) {
                    return null
                }

                val candidateRecycle = candidate.createRecycleOccurrence(
                    desiredRevision = occurrence.desiredRevision,
                    geometryGeneration = occurrence.geometryGeneration,
                    lifecycleEpoch = occurrence.lifecycleEpoch,
                    origin = FrameworkBitmapRecycleOrigin.ReturnedOwnerCleanup,
                    identity = identity,
                    operationIdentity = operationIdentity,
                )
                if (
                    identity == null &&
                    candidateRecycle.operation.arbitrateTerminal(mandatoryCleanup = true) != OperationTerminalArbitration.Transferred
                ) {
                    return null
                }
                if (!candidate.bitmapOwner.admitRecycle(candidateRecycle, installedOwner = false)) return null

                owner = candidate
                recycle = candidateRecycle
                occurrence.ownerBag.candidateOwner = null
            }

            val exactOwner = checkNotNull(owner)
            val exactRecycle = checkNotNull(recycle)
            exactOwner.jpegRuntimeOwner.submitJpegIoOperation(exactRecycle.ioOperation)
            return exactRecycle
        }

        private fun creationWithoutReturnedBitmapSettledLocked(occurrence: FrameworkResourceCreationOccurrence): Boolean {
            val operation = occurrence.operation
            check(operation.settlementGate.isHeldByCurrentThread)
            if (operation.returnCell.disposition == OperationReturnDisposition.Thrown && operation.returnCell.use != OperationReturnUse.Unclaimed) {
                return true
            }
            if (operation.returnCell.disposition == OperationReturnDisposition.Normal) return false

            val submissionResolved = operation.submissionDisposition == OperationSubmissionDisposition.Cancelled ||
                    operation.submissionDisposition == OperationSubmissionDisposition.Accepted ||
                    operation.submissionDisposition == OperationSubmissionDisposition.Rejected
            val terminalizedWithoutEntry = operation.domain == OperationDomain.Cleanup &&
                    operation.entryDisposition == OperationEntryDisposition.Cancelled && submissionResolved &&
                    (operation.disposition == OperationDisposition.Cancelled ||
                            operation.disposition == OperationDisposition.SchedulerRejected ||
                            operation.disposition == OperationDisposition.DeadlineGuardFailed)
            val schedulerRejectedWithoutEntry = operation.entryDisposition == OperationEntryDisposition.Unentered &&
                    operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                    operation.disposition == OperationDisposition.SchedulerRejected
            val deadlineGuardFailedWithoutEntry = operation.entryDisposition == OperationEntryDisposition.Unentered &&
                    operation.disposition == OperationDisposition.DeadlineGuardFailed
            return operation.returnCell.use == OperationReturnUse.Unclaimed &&
                    (terminalizedWithoutEntry || schedulerRejectedWithoutEntry || deadlineGuardFailedWithoutEntry)
        }

        private val BITMAP_ADOPTION_FAILED: IllegalStateException =
            IllegalStateException("Framework Bitmap ownership could not be adopted")
        private val MALFORMED_BITMAP_METADATA: IllegalStateException =
            IllegalStateException("Framework Bitmap returned malformed metadata")
        private val BITMAP_RESOURCE_COMPLETION_FAILED: IllegalStateException =
            IllegalStateException("Framework Bitmap resources could not be completed")
        private val BITMAP_USE_NOT_OPERATIONAL: IllegalStateException =
            IllegalStateException("Framework Bitmap use is not operational")
        private val BITMAP_USE_RESOLUTION_FAILED: IllegalStateException =
            IllegalStateException("Framework Bitmap use did not resolve")
        private val CARRIER_USE_RESOLUTION_FAILED: IllegalStateException =
            IllegalStateException("Framework carrier use did not resolve")
        private val RGBA_TRANSFER_REJECTED: IllegalStateException =
            IllegalStateException("Framework RGBA transfer rejected its exact input")
        private val MALFORMED_FRAMEWORK_TRANSACTION: IllegalStateException =
            IllegalStateException("Framework JPEG transaction returned malformed evidence")
        private val FRAMEWORK_STORAGE_OUT_OF_MEMORY: OutOfMemoryError =
            OutOfMemoryError("Framework JPEG transaction exhausted storage")
        private val FRAMEWORK_ENCODE_ADMISSION_FAILED: IllegalStateException =
            IllegalStateException("Framework JPEG encode admission could not be unwound")
        private val BITMAP_RECYCLE_OWNER_MISSING: IllegalStateException =
            IllegalStateException("Framework Bitmap recycle owner is missing")
    }
}
