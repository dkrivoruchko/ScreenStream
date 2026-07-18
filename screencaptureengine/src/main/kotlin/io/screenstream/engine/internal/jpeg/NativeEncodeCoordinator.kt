package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import kotlin.concurrent.withLock

internal fun executeCoordinatedNativeEncode(owner: JpegRuntimeOwner, occurrence: NativeEncodeOccurrence) {
    val entryResult = occurrence.operation.tryEnter()
    if (entryResult != OperationEntryResult.Entered) {
        if (entryResult == OperationEntryResult.InvalidDeadline) owner.signalJpegIoSettlement()
        return
    }
    owner.signalJpegIoSettlement()

    val bag = occurrence.ownerBag
    val lease = checkNotNull(bag.retainedOperationLease)
    val transaction = checkNotNull(bag.transaction)
    val evidence = occurrence.operation.returnCell.evidence
    val pixels = lease.enterExactRange()
    if (pixels == null) {
        evidence.carrierUseResolved = true
        transaction.abort()
        if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, LEASE_NOT_OPERATIONAL)) {
            owner.signalJpegIoSettlement()
        }
        return
    }

    try {
        bag.resultBlock.putLong(NATIVE_STATUS_OFFSET, NATIVE_RESULT_PENDING)
        bag.resultBlock.putLong(NATIVE_PRODUCED_BYTE_COUNT_OFFSET, NATIVE_RESULT_PENDING)
    } catch (failure: Throwable) {
        evidence.carrierUseResolved = lease.exitExactRange()
        transaction.abort()
        if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, failure)) {
            owner.signalJpegIoSettlement()
        }
        return
    }
    val sink = bag.segmentSink
    if (sink == null) {
        evidence.carrierUseResolved = lease.exitExactRange()
        transaction.abort()
        if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, NATIVE_ENCODE_ADMISSION_FAILED)) {
            owner.signalJpegIoSettlement()
        }
        return
    }

    var thrown: Throwable? = null
    try {
        owner.compressNativeFrame(pixels, bag, sink, bag.resultBlock)
    } catch (failure: Throwable) {
        thrown = failure
    } finally {
        evidence.carrierUseResolved = lease.exitExactRange()
    }

    val published = try {
        decodeNativeEncodeResult(occurrence, thrown)
    } catch (fatal: Throwable) {
        publishAndRethrowFatalNativeEncode(owner, occurrence, fatal)
    }
    if (published) owner.signalJpegIoSettlement()
}

private fun publishAndRethrowFatalNativeEncode(
    owner: JpegRuntimeOwner,
    occurrence: NativeEncodeOccurrence,
    fatal: Throwable,
): Nothing {
    try {
        val operation = occurrence.operation
        val published = operation.settlementGate.withLock {
            val returnCell = operation.returnCell
            if (returnCell.disposition != OperationReturnDisposition.Empty) {
                false
            } else {
                returnCell.evidence.result = NativeEncodeSettlement.InternalFailure
                returnCell.evidence.failureCause = fatal
                operation.publishThrownReturn(fatal)
            }
        }
        if (published) owner.signalJpegIoSettlement()
    } finally {
        throw fatal
    }
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
            operation.returnCell.use == OperationReturnUse.Unclaimed
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
            operation.returnCell.use == OperationReturnUse.Unclaimed
}

internal fun nativeEncodeMechanicsSettledLocked(bag: NativeEncodeOwnerBag): Boolean =
    bag.retainedOperationLease == null && bag.storageOwner == null && bag.transaction == null && bag.segmentSink == null && bag.unpublishedToRetire == null

private val LEASE_NOT_OPERATIONAL: IllegalStateException =
    IllegalStateException("native carrier lease is not operational")
internal val NATIVE_ENCODE_ADMISSION_FAILED: IllegalStateException =
    IllegalStateException("native JPEG encode admission could not preserve its owners")
