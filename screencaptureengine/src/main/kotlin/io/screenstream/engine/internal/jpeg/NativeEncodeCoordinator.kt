package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.JpegRuntimeOwner
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnUse
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition

internal fun executeCoordinatedNativeEncode(owner: JpegRuntimeOwner, occurrence: NativeEncodeOccurrence) {
    val bag = occurrence.ownerBag
    val lease = checkNotNull(bag.retainedOperationLease)
    val evidence = occurrence.operation.returnCell.evidence
    val pixels = try {
        lease.enterExactRange()
    } catch (failure: Exception) {
        if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, failure)) {
            owner.signalJpegIoSettlement()
        }
        return
    } catch (fatal: Throwable) {
        publishAndRethrowFatalNativeEncode(owner, occurrence, fatal)
    }
    if (pixels == null) {
        evidence.carrierUseResolved = true
        if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, LEASE_NOT_OPERATIONAL)) {
            owner.signalJpegIoSettlement()
        }
        return
    }

    try {
        bag.resultBlock.putLong(NATIVE_STATUS_OFFSET, NATIVE_RESULT_PENDING)
        bag.resultBlock.putLong(NATIVE_PRODUCED_BYTE_COUNT_OFFSET, NATIVE_RESULT_PENDING)
    } catch (failure: Exception) {
        val exitFailure = try {
            evidence.carrierUseResolved = lease.exitExactRange()
            null
        } catch (fatal: Throwable) {
            publishAndRethrowFatalNativeEncode(owner, occurrence, fatal)
        } catch (releaseFailure: Exception) {
            releaseFailure
        }
        val exactFailure = exitFailure ?: failure
        if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, exactFailure)) {
            owner.signalJpegIoSettlement()
        }
        return
    } catch (fatal: Throwable) {
        publishAndRethrowFatalNativeEncode(owner, occurrence, fatal)
    }
    val sink = bag.segmentSink
    if (sink == null) {
        val exitFailure = try {
            evidence.carrierUseResolved = lease.exitExactRange()
            null
        } catch (failure: Throwable) {
            failure
        }
        if (exitFailure != null && exitFailure !is Exception) {
            publishAndRethrowFatalNativeEncode(owner, occurrence, exitFailure)
        }
        val exactFailure = exitFailure ?: NATIVE_ENCODE_ADMISSION_FAILED
        if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, exactFailure)) {
            owner.signalJpegIoSettlement()
        }
        return
    }

    var thrown: Throwable? = null
    try {
        owner.compressNativeFrame(pixels, bag, sink, bag.resultBlock)
    } catch (failure: Throwable) {
        thrown = failure
    }
    evidence.nativeCallReturned = true
    val exitFailure = try {
        evidence.carrierUseResolved = lease.exitExactRange()
        null
    } catch (failure: Throwable) {
        failure
    }
    if (exitFailure != null || !evidence.carrierUseResolved) {
        val fatal = when {
            thrown != null && thrown !is Exception -> thrown
            exitFailure != null && exitFailure !is Exception -> exitFailure
            else -> null
        }
        if (fatal != null) publishAndRethrowFatalNativeEncode(owner, occurrence, fatal)

        val exactFailure = exitFailure ?: LEASE_NOT_OPERATIONAL
        if (publishNativeEncodeFailure(occurrence, NativeEncodeSettlement.InternalFailure, exactFailure)) {
            owner.signalJpegIoSettlement()
        }
        return
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
    if (occurrence.operation.publishDirectFatalReturn(fatal)) {
        owner.signalJpegIoSettlement()
    }
    throw fatal
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
    bag.retainedOperationLease == null && bag.storageOwner == null && bag.transaction == null && bag.segmentSink == null

private val LEASE_NOT_OPERATIONAL: IllegalStateException =
    IllegalStateException("native carrier lease is not operational")
internal val NATIVE_ENCODE_ADMISSION_FAILED: IllegalStateException =
    IllegalStateException("native JPEG encode admission could not preserve its owners")
