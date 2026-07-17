package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition

internal fun executeCoordinatedNativeEncode(owner: JpegRuntimeOwner, occurrence: NativeEncodeOccurrence) {
    val entryResult = occurrence.operation.tryEnter()
    if (entryResult != OperationEntryResult.Entered) {
        if (entryResult == OperationEntryResult.InvalidDeadline) owner.signalJpegIoSettlement()
        return
    }
    owner.signalJpegIoSettlement()

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
            owner.signalJpegIoSettlement()
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
            owner.signalJpegIoSettlement()
        }
        return
    }
    val sink = bag.segmentSink
    if (sink == null || !loan.markNativeEntryAttempted()) {
        evidence.carrierUseResolved = lease.exitExactRange()
        evidence.writerResidueDisposition = loan.writerResidueDisposition
        transaction.abort()
        if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, NATIVE_ENCODE_ADMISSION_FAILED)) {
            owner.signalJpegIoSettlement()
        }
        return
    }

    var compressorResult = Int.MIN_VALUE
    var thrown: Throwable? = null
    try {
        compressorResult = owner.compressNativeFrame(loan, pixels, bag, sink)
    } catch (failure: Throwable) {
        thrown = failure
    } finally {
        evidence.carrierUseResolved = lease.exitExactRange()
    }

    if (decodeNativeEncodeResult(owner, occurrence, compressorResult, thrown)) owner.signalJpegIoSettlement()
}

internal fun cancelledNativeEncodeWithoutReturnLocked(occurrence: NativeEncodeOccurrence): Boolean {
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

internal fun terminalizedUnenteredEncodeFailureLocked(occurrence: NativeEncodeOccurrence): Boolean {
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

internal fun nativeEncodeMechanicsSettledLocked(bag: NativeEncodeOwnerBag): Boolean =
    bag.retainedOperationLease == null && bag.callBlocksOwner == null && bag.callBlockLoan == null &&
            bag.storageOwner == null && bag.transaction == null && bag.segmentSink == null &&
            bag.unpublishedToRetire == null

private val LEASE_NOT_OPERATIONAL: IllegalStateException =
    IllegalStateException("native carrier lease is not operational")
internal val NATIVE_ENCODE_ADMISSION_FAILED: IllegalStateException =
    IllegalStateException("native JPEG encode admission could not preserve its owners")
