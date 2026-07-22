package io.screenstream.engine.internal.jpeg

import android.graphics.Bitmap
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import kotlin.concurrent.withLock

internal fun claimFrameworkEncode(
    owner: FrameworkJpegOwner,
    occurrence: FrameworkEncodeOccurrence,
): FrameworkEncodeClaimFact? {
    if (!owner.ownsEncode(occurrence)) return null
    val claim = occurrence.claim
    if (claim.frameworkOwnerIdentity !== owner || claim.storageIdentity !== occurrence.ownerBag.storageOwner ||
        claim.transactionIdentity !== occurrence.ownerBag.transaction
    ) {
        return null
    }
    if (claim.isPublished) return claim
    if (!owner.jpegRuntimeOwner.releaseJpegIoOperation(occurrence)) {
        return null
    }
    val operation = occurrence.operation
    val gate = operation.settlementGate
    var settledResult = FrameworkEncodeSettlement.NotSettled
    var bitmapUseResolved = false
    val published = gate.withLock {
        val ownerBag = occurrence.ownerBag
        if (ownerBag.bitmapUseOwner !== owner || ownerBag.claim !== claim || claim.isPublished) {
            return@withLock claim.isPublished
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

                else -> return@withLock false
            }
        } else {
            val evidence = operation.returnCell.evidence
            val raw = operation.returnCell.throwable
            settledResult = if (raw != null && raw !is Exception) {
                FrameworkEncodeSettlement.DirectFatal
            } else {
                evidence.result
            }
            bitmapUseResolved = evidence.bitmapUseResolved
        }
        if (settledResult == FrameworkEncodeSettlement.NotSettled) return@withLock false

        val transaction = ownerBag.transaction ?: return@withLock false
        val payload = transaction.committedPayloadIdentity
        var claimFailure = operation.returnCell.evidence.failureCause ?: operation.submissionFailure
        if (settledResult == FrameworkEncodeSettlement.Success &&
            (payload == null || payload.effectiveParameters !== claim.effectiveParameters ||
                    payload.payload.byteCount != transaction.byteCount)
        ) {
            settledResult = FrameworkEncodeSettlement.InternalFailure
            claimFailure = FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
        } else if (settledResult == FrameworkEncodeSettlement.InternalFailure && claimFailure == null) {
            claimFailure = FrameworkJpegOwner.FRAMEWORK_ENCODE_ADMISSION_FAILED
        }
        claim.publishLocked(
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
            carrierUseResolved = bitmapUseResolved,
        )
    }
    return if (published || claim.isPublished) claim else null
}

internal fun executeFrameworkEncodeFinalization(
    command: FrameworkEncodeFinalizationCommand,
    disposition: JpegEncodeFinalizationDisposition,
): JpegEncodeFinalizationReceipt {
    val receipt = command.receipt
    if (receipt.result != JpegEncodeFinalizationResult.Pending) return receipt
    val owner = command.owner
    val occurrence = command.occurrence
    val claim = command.claim
    if (!claim.isPublished || occurrence.claim !== claim || claim.frameworkOwnerIdentity !== owner) {
        receipt.complete(
            result = JpegEncodeFinalizationResult.UnsafeResidue,
            carrierLeaseReleased = false,
            storageRetired = false,
            producerUseReleased = false,
            failure = FRAMEWORK_CLAIM_NOT_PUBLISHED,
        )
        return receipt
    }
    if (claim.result == FrameworkEncodeSettlement.DirectFatal &&
        (claim.rawThrowable == null || claim.rawThrowable is Exception ||
                claim.rawThrowable !== owner.jpegRuntimeOwner.jpegFatal)
    ) {
        receipt.complete(
            result = JpegEncodeFinalizationResult.UnsafeResidue,
            carrierLeaseReleased = false,
            storageRetired = false,
            producerUseReleased = false,
            failure = FRAMEWORK_FATAL_EVIDENCE_MISMATCH,
        )
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
        receipt.complete(
            result = JpegEncodeFinalizationResult.UnsafeResidue,
            carrierLeaseReleased = false,
            storageRetired = false,
            producerUseReleased = false,
            failure = FRAMEWORK_STORAGE_ROLE_NOT_TRANSFERRED,
        )
        return receipt
    }
    if (!claim.carrierUseResolved) {
        receipt.complete(
            result = JpegEncodeFinalizationResult.UnsafeResidue,
            carrierLeaseReleased = false,
            storageRetired = false,
            producerUseReleased = false,
            failure = FrameworkJpegOwner.BITMAP_USE_RESOLUTION_FAILED,
        )
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
        receipt.complete(
            result = JpegEncodeFinalizationResult.UnsafeResidue,
            carrierLeaseReleased = false,
            storageRetired = false,
            producerUseReleased = false,
            failure = FRAMEWORK_STORAGE_RETIREMENT_FAILED,
        )
        return receipt
    }
    val storageRetired = disposition != JpegEncodeFinalizationDisposition.KeepCommittedUnpublished

    val gate = occurrence.operation.settlementGate
    val exactLease = gate.withLock {
        if (occurrence.ownerBag.transaction !== claim.transactionIdentity ||
            occurrence.ownerBag.storageOwner !== claim.storageIdentity
        ) {
            return@withLock null
        }
        occurrence.ownerBag.transaction = null
        occurrence.ownerBag.storageOwner = null
        occurrence.ownerBag.retainedOperationLease
    }
    if (exactLease == null) {
        receipt.complete(
            result = JpegEncodeFinalizationResult.UnsafeResidue,
            carrierLeaseReleased = false,
            storageRetired = storageRetired,
            producerUseReleased = false,
            failure = FrameworkJpegOwner.BITMAP_USE_RESOLUTION_FAILED,
        )
        return receipt
    }
    val leaseReleased = try {
        exactLease.releaseFromOperation()
    } catch (fatal: Throwable) {
        receipt.complete(
            result = JpegEncodeFinalizationResult.UnsafeResidue,
            carrierLeaseReleased = false,
            storageRetired = storageRetired,
            producerUseReleased = false,
            failure = fatal,
        )
        throw fatal
    }
    if (!leaseReleased) {
        receipt.complete(
            result = JpegEncodeFinalizationResult.UnsafeResidue,
            carrierLeaseReleased = false,
            storageRetired = storageRetired,
            producerUseReleased = false,
            failure = FRAMEWORK_CARRIER_LEASE_RELEASE_FAILED,
        )
        return receipt
    }
    gate.withLock {
        if (occurrence.ownerBag.retainedOperationLease === exactLease) {
            occurrence.ownerBag.retainedOperationLease = null
        }
    }

    val useReleased = owner.releaseEncodeUse(occurrence)
    val aliasesCleared = useReleased && gate.withLock { occurrence.clearSettledReferencesLocked(owner) }
    val occurrenceCleared = aliasesCleared && owner.clearEncode(occurrence)
    val completed = useReleased && aliasesCleared && occurrenceCleared
    receipt.complete(
        result = if (completed) JpegEncodeFinalizationResult.Completed else JpegEncodeFinalizationResult.UnsafeResidue,
        carrierLeaseReleased = true,
        storageRetired = storageRetired,
        producerUseReleased = useReleased,
        failure = if (completed) null else FRAMEWORK_FINALIZATION_FAILED,
    )
    return receipt
}

private fun transferExactRgbaToBitmap(owner: FrameworkJpegOwner, lease: RgbaCarrierLease): Boolean {
    val exactRange = lease.enterExactRange() ?: return false
    var transferred = false
    var transferFailure: Throwable? = null
    var exactRangeUseResolved = false
    var exitFailure: Throwable? = null
    try {
        exactRange.limit(owner.pixelByteCount)
        exactRange.position(0)
        if (exactRange.isDirect && !exactRange.isReadOnly && exactRange.capacity() == owner.pixelByteCount &&
            exactRange.position() == 0 && exactRange.limit() == owner.pixelByteCount && exactRange.remaining() == owner.pixelByteCount
        ) {
            val bitmap = owner.bitmapForUse()
            if (bitmap != null) {
                when (owner.transferMode) {
                    FrameworkTransferMode.TightBufferCopy -> {
                        bitmap.copyPixelsFromBuffer(exactRange)
                        transferred = true
                    }

                    FrameworkTransferMode.PortableRowCopy -> {
                        val row = owner.rowScratchForUse()
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

    if (transferFailure != null && transferFailure !is Exception) throw transferFailure
    if (exitFailure != null && exitFailure !is Exception) throw exitFailure
    if (exitFailure != null || !exactRangeUseResolved) throw FrameworkJpegOwner.CARRIER_USE_RESOLUTION_FAILED
    if (transferFailure != null) throw transferFailure
    return transferred
}

internal fun beginFrameworkEncode(
    owner: FrameworkJpegOwner,
    expectedProduct: JpegRuntimeProduct,
    expectedLease: RgbaCarrierLease,
    storage: EncodedStorageOwner,
    effectiveParameters: ScreenCaptureEffectiveParameters,
    desiredRevision: Long,
    geometryGeneration: Long,
    lifecycleEpoch: Long,
    identity: JpegFiniteOperationIdentity,
): FrameworkEncodeOccurrence? {
    val topology = owner.jpegRuntimeOwner.stableTopologySnapshot() ?: return null
    if (effectiveParameters.appliedParameters.jpegQuality !in 0..100 ||
        expectedProduct.carrier !== expectedLease.carrier ||
        expectedProduct.carrier.byteCount != owner.pixelByteCount || topology.product !== expectedProduct ||
        topology.lease !== expectedLease || effectiveParameters.finalImageSize != owner.imageSize ||
        !owner.hasCompleteResources()
    ) {
        return null
    }

    val transaction = EncodedStorageOwner.FrameworkTransaction()
    val storageCommands = storage.precreateEncodeSettlementCommands(transaction)
    val occurrence = FrameworkEncodeOccurrence.create(
        desiredRevision = desiredRevision,
        geometryGeneration = geometryGeneration,
        lifecycleEpoch = lifecycleEpoch,
        bitmapUseOwner = owner,
        topologyIdentity = topology,
        capturedProduct = expectedProduct,
        carrierLease = expectedLease,
        storage = storage,
        transaction = transaction,
        storageCommands = storageCommands,
        effectiveParameters = effectiveParameters,
        identity = identity,
        clock = owner.clock,
        signal = owner.jpegRuntimeOwner.jpegIoSettlementSignal,
        endpoint = owner.jpegRuntimeOwner.jpegExecutorEndpoint,
        work = ::executeFrameworkEncode,
    )

    if (!owner.reserveEncodeUse(occurrence)) {
        transaction.abort()
        occurrence.operation.settlementGate.withLock {
            occurrence.ownerBag.transaction = null
            occurrence.ownerBag.storageOwner = null
        }
        check(occurrence.operation.settlementGate.withLock { occurrence.clearSettledReferencesLocked(owner) })
        return null
    }

    if (!expectedLease.retainForOperation(expectedProduct)) {
        if (!unwindEncodeAdmission(owner, occurrence)) {
            throw FrameworkJpegOwner.FRAMEWORK_ENCODE_ADMISSION_FAILED
        }
        return null
    }
    occurrence.operation.settlementGate.withLock {
        occurrence.ownerBag.retainedOperationLease = expectedLease
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

    if (!owner.jpegRuntimeOwner.submitJpegIoOperation(occurrence.executorOperation) &&
        occurrence.operation.submissionDisposition == OperationSubmissionDisposition.None
    ) {
        if (!unwindEncodeAdmission(owner, occurrence)) {
            throw FrameworkJpegOwner.FRAMEWORK_ENCODE_ADMISSION_FAILED
        }
        throw FrameworkJpegOwner.FRAMEWORK_ENCODE_ADMISSION_FAILED
    }
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
                exactTransaction.isAborted && exactStorage.rollbackAbortedAdmission(exactTransaction)
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
    if (!mechanicsSettled) return false
    if (!owner.releaseEncodeUse(occurrence)) return false
    if (!gate.withLock { occurrence.clearSettledReferencesLocked(owner) }) return false
    return owner.clearEncode(occurrence)
}

private fun executeFrameworkEncode(occurrence: FrameworkEncodeOccurrence) {
    val owner = occurrence.operation.settlementGate.withLock { occurrence.ownerBag.bitmapUseOwner }
    if (owner == null) return

    val bitmap = owner.bitmapForUse()
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
    var fatalFailure: Throwable? = null
    try {
        val transferred = transferExactRgbaToBitmap(owner, occurrence.ownerBag.carrierLease)
        if (!transferred) {
            resultCause = FrameworkJpegOwner.RGBA_TRANSFER_REJECTED
        } else {
            val transaction = checkNotNull(occurrence.ownerBag.transaction)
            val compressed: Boolean? = try {
                try {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, occurrence.quality, transaction.outputStream)
                } finally {
                    val firstDirectFatal = transaction.firstDirectFatalWriteThrowable
                    if (firstDirectFatal != null) throw firstDirectFatal
                }
            } catch (allocationFailure: OutOfMemoryError) {
                result = FrameworkEncodeSettlement.ResourceExhausted
                resultCause = allocationFailure
                null
            } catch (failure: Exception) {
                result = FrameworkEncodeSettlement.InternalFailure
                resultCause = failure
                unexpectedBoundaryFailure = true
                null
            } catch (fatal: Throwable) {
                fatalFailure = fatal
                null
            }
            if (compressed != null) {
                when {
                    transaction.failure == EncodedStorageOwner.TransactionFailure.InternalFailure -> {
                        result = FrameworkEncodeSettlement.InternalFailure
                        resultCause = transaction.failureCause ?: FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
                    }

                    transaction.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted -> {
                        val storageCause = transaction.failureCause
                        if (storageCause == null) {
                            result = FrameworkEncodeSettlement.InternalFailure
                            resultCause = FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
                        } else {
                            result = FrameworkEncodeSettlement.ResourceExhausted
                            resultCause = storageCause
                        }
                    }

                    !compressed -> {
                        result = FrameworkEncodeSettlement.CompressionRejected
                    }

                    transaction.commit(occurrence.effectiveParameters) -> {
                        result = FrameworkEncodeSettlement.Success
                    }

                    transaction.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted -> {
                        val storageCause = transaction.failureCause
                        if (storageCause == null) {
                            result = FrameworkEncodeSettlement.InternalFailure
                            resultCause = FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
                        } else {
                            result = FrameworkEncodeSettlement.ResourceExhausted
                            resultCause = storageCause
                        }
                    }

                    else -> {
                        result = FrameworkEncodeSettlement.InternalFailure
                        resultCause = transaction.failureCause ?: FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
                    }
                }
            }
        }
    } catch (failure: Exception) {
        result = FrameworkEncodeSettlement.InternalFailure
        resultCause = failure
        unexpectedBoundaryFailure = true
    } catch (fatal: Throwable) {
        fatalFailure = fatal
    } finally {
        occurrence.operation.returnCell.evidence.bitmapUseResolved = true
    }

    val exactFatalFailure = fatalFailure
    if (exactFatalFailure != null) {
        publishFrameworkEncodeFatalAndRethrow(owner, occurrence, exactFatalFailure)
    }

    val transaction = occurrence.ownerBag.transaction
    if (transaction?.failure == EncodedStorageOwner.TransactionFailure.InternalFailure) {
        result = FrameworkEncodeSettlement.InternalFailure
        resultCause = transaction.failureCause ?: FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
    } else if (!unexpectedBoundaryFailure &&
        transaction?.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted
    ) {
        val storageCause = transaction.failureCause
        if (storageCause == null) {
            result = FrameworkEncodeSettlement.InternalFailure
            resultCause = FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
        } else {
            result = FrameworkEncodeSettlement.ResourceExhausted
            resultCause = storageCause
        }
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
            -> {
            val failure = resultCause ?: FrameworkJpegOwner.MALFORMED_FRAMEWORK_TRANSACTION
            when (failure) {
                is Exception -> occurrence.operation.publishThrownReturn(failure)
                is OutOfMemoryError -> {
                    check(result == FrameworkEncodeSettlement.ResourceExhausted)
                    occurrence.operation.publishNormalReturn()
                }

                else -> {
                    occurrence.operation.publishDirectFatalReturn(failure)
                    throw failure
                }
            }
        }

        else -> false
    }
    if (published) owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
}

private fun publishFrameworkEncodeFatalAndRethrow(
    owner: FrameworkJpegOwner,
    occurrence: FrameworkEncodeOccurrence,
    fatal: Throwable,
): Nothing {
    if (occurrence.operation.publishDirectFatalReturn(fatal)) {
        owner.jpegRuntimeOwner.jpegIoSettlementSignal.signal()
    }
    throw fatal
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

private val FRAMEWORK_CLAIM_NOT_PUBLISHED: IllegalStateException =
    IllegalStateException("Framework encode claim is not published")
private val FRAMEWORK_FATAL_EVIDENCE_MISMATCH: IllegalStateException =
    IllegalStateException("Framework fatal encode evidence does not match the endpoint authority")
private val FRAMEWORK_STORAGE_ROLE_NOT_TRANSFERRED: IllegalStateException =
    IllegalStateException("Framework encode storage role was not transferred by Session")
private val FRAMEWORK_STORAGE_RETIREMENT_FAILED: IllegalStateException =
    IllegalStateException("Framework encode storage retirement failed")
private val FRAMEWORK_CARRIER_LEASE_RELEASE_FAILED: IllegalStateException =
    IllegalStateException("Framework encode carrier lease release failed")
private val FRAMEWORK_FINALIZATION_FAILED: IllegalStateException =
    IllegalStateException("Framework encode finalization did not settle every owner")
