package io.screenstream.engine.internal.jpeg

import android.graphics.Bitmap
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import kotlin.concurrent.withLock

internal fun settleFrameworkEncode(
    owner: FrameworkJpegOwner,
    occurrence: FrameworkEncodeOccurrence,
    storage: EncodedStorageOwner,
    retainCommittedFrame: Boolean,
): FrameworkEncodeSettlement {
    if (occurrence.capturedOwner !== owner) return FrameworkEncodeSettlement.NotSettled

    val operation = occurrence.operation
    val gate = operation.settlementGate
    var settledResult = FrameworkEncodeSettlement.NotSettled
    var bitmapUseResolved = false
    var timelyNormal = false
    gate.withLock {
        val ownerBag = occurrence.ownerBag
        if (ownerBag.owner !== owner || ownerBag.storageOwner != null && ownerBag.storageOwner !== storage) {
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
            timelyNormal = operation.returnCell.use == OperationReturnUse.Timely && operation.returnCell.disposition == OperationReturnDisposition.Normal
        }
        if (ownerBag.retainCommittedFrame == null) {
            ownerBag.retainCommittedFrame = retainCommittedFrame && timelyNormal && settledResult == FrameworkEncodeSettlement.Success
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
    if (!mechanicsSettled || !bitmapUseResolved || !owner.bitmapOwner.completeEncode(occurrence)) {
        return FrameworkEncodeSettlement.NotSettled
    }
    return settledResult
}

private fun transferExactRgbaToBitmap(owner: FrameworkJpegOwner, lease: RgbaCarrierLease): Boolean {
    val exactRange = lease.enterExactRange() ?: return false
    var transferred: Boolean = false
    var transferFailure: Throwable? = null
    var exactRangeUseResolved: Boolean = false
    var exitFailure: Throwable? = null
    try {
        exactRange.limit(owner.pixelByteCount)
        exactRange.position(0)
        if (exactRange.isDirect && !exactRange.isReadOnly && exactRange.capacity() == owner.pixelByteCount &&
            exactRange.position() == 0 && exactRange.limit() == owner.pixelByteCount && exactRange.remaining() == owner.pixelByteCount
        ) {
            val bitmap = owner.bitmapOwner.bitmapForTransfer()
            if (bitmap != null) {
                when (owner.transferMode) {
                    FrameworkTransferMode.TightBufferCopy -> {
                        bitmap.copyPixelsFromBuffer(exactRange)
                        transferred = true
                    }

                    FrameworkTransferMode.PortableRowCopy -> {
                        val row = owner.bitmapOwner.rowScratchForTransfer()
                        if (row != null && row.size == owner.imageSize.widthPx) {
                            for (y in 0..<owner.imageSize.heightPx) {
                                val rowOffset = y * owner.rowByteCount
                                for (x in 0..<owner.imageSize.widthPx) {
                                    val pixelOffset = rowOffset + x * 4
                                    val red = exactRange[pixelOffset].toInt() and 0xFF
                                    val green = exactRange[pixelOffset + 1].toInt() and 0xFF
                                    val blue = exactRange[pixelOffset + 2].toInt() and 0xFF
                                    row[x] = -0x1000000 or (red shl 16) or (green shl 8) or blue
                                }
                                bitmap.setPixels(row, 0, owner.imageSize.widthPx, 0, y, owner.imageSize.widthPx, 1)
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
    if (exitFailure != null || !exactRangeUseResolved) throw FrameworkJpegOwner.CARRIER_USE_RESOLUTION_FAILED
    if (transferFailure != null) throw transferFailure
    return transferred
}

internal fun beginFrameworkEncode(
    owner: FrameworkJpegOwner,
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
        expectedProduct.carrier.byteCount != owner.pixelByteCount || owner.jpegRuntimeOwner.product !== expectedProduct ||
        owner.jpegRuntimeOwner.lease !== expectedLease || !owner.bitmapOwner.isInstalledAndHealthy()
    ) {
        return null
    }

    val occurrence = FrameworkEncodeOccurrence.create(
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        capturedOwner = owner,
        capturedProduct = expectedProduct,
        carrierLease = expectedLease,
        quality = quality,
        identity = identity,
        clock = owner.clock,
        signal = owner.jpegRuntimeOwner.jpegIoSettlementSignal,
        work = { executeFrameworkEncode(owner, it) },
    )
    if (!owner.bitmapOwner.admitEncode(occurrence)) return null

    if (!expectedLease.retainForOperation(expectedProduct)) {
        if (!owner.bitmapOwner.cancelEncodeAdmission(occurrence)) {
            throw FrameworkJpegOwner.FRAMEWORK_ENCODE_ADMISSION_FAILED
        }
        return null
    }
    occurrence.operation.settlementGate.withLock {
        occurrence.ownerBag.retainedOperationLease = expectedLease
    }

    val transaction = try {
        EncodedStorageOwner.FrameworkTransaction()
    } catch (failure: Throwable) {
        unwindEncodeAdmission(owner, occurrence)
        throw failure
    }
    occurrence.operation.settlementGate.withLock {
        occurrence.ownerBag.storageOwner = storage
        occurrence.ownerBag.transaction = transaction
    }

    val attached = try {
        storage.attachProduction(transaction)
    } catch (failure: Throwable) {
        unwindEncodeAdmission(owner, occurrence)
        throw failure
    }
    if (!attached) {
        if (!unwindEncodeAdmission(owner, occurrence)) {
            throw FrameworkJpegOwner.FRAMEWORK_ENCODE_ADMISSION_FAILED
        }
        return null
    }

    owner.jpegRuntimeOwner.submitJpegIoOperation(occurrence.ioOperation)
    return occurrence
}

private fun unwindEncodeAdmission(owner: FrameworkJpegOwner, occurrence: FrameworkEncodeOccurrence): Boolean {
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
        val detached = exactStorage?.production !== exactTransaction ||
                exactTransaction.isAborted && exactStorage.detachAbortedProduction(exactTransaction)
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
    return mechanicsSettled && owner.bitmapOwner.cancelEncodeAdmission(occurrence)
}

private fun executeFrameworkEncode(owner: FrameworkJpegOwner, occurrence: FrameworkEncodeOccurrence) {
    val entryResult = occurrence.operation.tryEnter()
    if (entryResult != OperationEntryResult.Entered) {
        if (entryResult == OperationEntryResult.InvalidDeadline) owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
        return
    }
    owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()

    val bitmap = owner.bitmapOwner.enterBitmapUse(occurrence)
    if (bitmap == null) {
        val evidence = occurrence.operation.returnCell.evidence
        evidence.bitmapUseResolved = true
        evidence.result = FrameworkEncodeSettlement.InternalFailure
        evidence.failureCause = FrameworkJpegOwner.BITMAP_USE_NOT_OPERATIONAL
        if (occurrence.operation.publishThrownReturn(FrameworkJpegOwner.BITMAP_USE_NOT_OPERATIONAL)) {
            owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
        }
        return
    }

    var result = FrameworkEncodeSettlement.InternalFailure
    var resultCause: Throwable? = null
    var unexpectedBoundaryFailure = false
    try {
        val transferred = transferExactRgbaToBitmap(owner, occurrence.ownerBag.carrierLease)
        if (!transferred) {
            resultCause = FrameworkJpegOwner.RGBA_TRANSFER_REJECTED
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
                    resultCause = transaction.failureCause ?: FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
                }

                transaction.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted -> {
                    result = FrameworkEncodeSettlement.ResourceExhausted
                    resultCause = transaction.failureCause ?: FrameworkJpegOwner.FRAMEWORK_STORAGE_OUT_OF_MEMORY
                }

                !compressed -> {
                    transaction.abort()
                    result = FrameworkEncodeSettlement.CompressionRejected
                }

                transaction.commit(owner.imageSize) -> {
                    result = FrameworkEncodeSettlement.Success
                }

                transaction.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted -> {
                    result = FrameworkEncodeSettlement.ResourceExhausted
                    resultCause = transaction.failureCause ?: FrameworkJpegOwner.FRAMEWORK_STORAGE_OUT_OF_MEMORY
                }

                else -> {
                    result = FrameworkEncodeSettlement.InternalFailure
                    resultCause = transaction.failureCause ?: FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
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
        occurrence.operation.returnCell.evidence.bitmapUseResolved = owner.bitmapOwner.exitBitmapUse(occurrence)
    }

    val transaction = occurrence.ownerBag.transaction
    if (transaction?.failure == EncodedStorageOwner.TransactionFailure.InternalFailure) {
        result = FrameworkEncodeSettlement.InternalFailure
        resultCause = transaction.failureCause ?: FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
    } else if (!unexpectedBoundaryFailure &&
        transaction?.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted
    ) {
        result = FrameworkEncodeSettlement.ResourceExhausted
        resultCause = transaction.failureCause ?: FrameworkJpegOwner.FRAMEWORK_STORAGE_OUT_OF_MEMORY
    }
    if (result != FrameworkEncodeSettlement.Success && transaction != null && !transaction.isCommitted && !transaction.isAborted) {
        transaction.abort()
    }
    if (!occurrence.operation.returnCell.evidence.bitmapUseResolved) {
        result = FrameworkEncodeSettlement.InternalFailure
        resultCause = FrameworkJpegOwner.BITMAP_USE_RESOLUTION_FAILED
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
            -> occurrence.operation.publishThrownReturn(resultCause ?: FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION)

        else -> false
    }
    if (published) owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
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
