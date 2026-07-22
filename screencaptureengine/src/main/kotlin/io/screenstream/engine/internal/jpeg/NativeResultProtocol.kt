package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.EncodedStorageOwner

internal fun decodeNativeEncodeResult(occurrence: NativeEncodeOccurrence, thrown: Throwable?): Boolean {
    val bag = occurrence.ownerBag
    val evidence = occurrence.operation.returnCell.evidence
    val transaction = checkNotNull(bag.transaction)

    val blockFailure = try {
        evidence.nativeStatus = bag.resultBlock.getLong(NATIVE_STATUS_OFFSET)
        evidence.nativeProducedByteCount = bag.resultBlock.getLong(NATIVE_PRODUCED_BYTE_COUNT_OFFSET)
        evidence.managedAdoptedByteCount = transaction.byteCount
        evidence.resultChannelArmed = evidence.nativeStatus != NATIVE_RESULT_PENDING &&
                evidence.nativeProducedByteCount != NATIVE_RESULT_PENDING
        null
    } catch (failure: Throwable) {
        failure
    }
    if (blockFailure != null) {
        val selectedFailure = when {
            thrown != null && thrown !is Exception -> thrown
            blockFailure !is Exception -> blockFailure
            thrown != null -> thrown
            else -> blockFailure
        }
        return classifyMalformedThrowable(occurrence, selectedFailure)
    }

    val status = evidence.nativeStatus
    val produced = evidence.nativeProducedByteCount
    val adopted = evidence.managedAdoptedByteCount.toLong()
    val knownStatus = status == NATIVE_STATUS_TRANSFER_COMPLETE ||
            status == NATIVE_STATUS_SAFE_COMPRESSOR_ALLOCATION_FAILURE ||
            status == NATIVE_STATUS_OUT_OF_MEMORY ||
            status == NATIVE_STATUS_INTERNAL_FAILURE ||
            status == NATIVE_STATUS_JAVA_THROWABLE
    val commonCountsValid = produced in 0L..Int.MAX_VALUE.toLong() && adopted in 0L..produced

    if (!evidence.carrierUseResolved || !evidence.resultChannelArmed || !knownStatus || !commonCountsValid) {
        return classifyMalformedThrowable(occurrence, thrown)
    }

    return when (status) {
        NATIVE_STATUS_TRANSFER_COMPLETE -> {
            val valid = thrown == null && produced > 0L && adopted == produced && transaction.failure == null
            if (!valid) {
                classifyMalformedThrowable(occurrence, thrown)
            } else if (transaction.commit(bag.descriptor.effectiveParameters)) {
                publishReturnedResult(occurrence, NativeEncodeSettlement.Success, null)
            } else {
                val transactionCause = transaction.failureCause
                when (transaction.failure) {
                    EncodedStorageOwner.TransactionFailure.ResourceExhausted -> if (transactionCause == null) {
                        publishReturnedResult(occurrence, NativeEncodeSettlement.InternalFailure, MALFORMED_NATIVE_RESULT)
                    } else {
                        publishReturnedResult(occurrence, NativeEncodeSettlement.ResourceExhausted, transactionCause)
                    }

                    else -> publishReturnedResult(
                        occurrence,
                        NativeEncodeSettlement.InternalFailure,
                        transactionCause ?: MALFORMED_NATIVE_RESULT,
                    )
                }
            }
        }

        NATIVE_STATUS_SAFE_COMPRESSOR_ALLOCATION_FAILURE -> {
            val valid = thrown == null && adopted == 0L && transaction.failure == null
            if (valid) {
                publishReturnedResult(occurrence, NativeEncodeSettlement.SafeNativeAllocationFailure, null)
            } else {
                classifyMalformedThrowable(occurrence, thrown)
            }
        }

        NATIVE_STATUS_OUT_OF_MEMORY -> {
            val valid = thrown == null && adopted == 0L && transaction.failure == null
            if (valid) {
                publishReturnedResult(occurrence, NativeEncodeSettlement.ResourceExhausted, null)
            } else {
                classifyMalformedThrowable(occurrence, thrown)
            }
        }

        NATIVE_STATUS_INTERNAL_FAILURE -> {
            classifyInternalThrowable(occurrence, thrown)
        }

        NATIVE_STATUS_JAVA_THROWABLE -> {
            val throwableEvidenceValid = thrown != null && produced > 0L &&
                    (transaction.failureCause == null || transaction.failureCause === thrown)
            if (throwableEvidenceValid) {
                classifyJavaThrowable(occurrence, thrown)
            } else {
                classifyMalformedThrowable(occurrence, thrown)
            }
        }

        else -> error("known native status branch is exhaustive")
    }
}

internal fun publishNativeEncodeFailure(occurrence: NativeEncodeOccurrence, result: NativeEncodeSettlement, failure: Throwable): Boolean {
    val evidence = occurrence.operation.returnCell.evidence
    evidence.result = result
    evidence.failureCause = failure
    return when (failure) {
        is Exception -> occurrence.operation.publishThrownReturn(failure)
        else -> {
            occurrence.operation.publishDirectFatalReturn(failure)
            throw failure
        }
    }
}

private fun classifyJavaThrowable(occurrence: NativeEncodeOccurrence, thrown: Throwable): Boolean = when (thrown) {
    is OutOfMemoryError -> publishThrownResult(occurrence, NativeEncodeSettlement.ResourceExhausted, thrown)
    is Exception -> publishThrownResult(occurrence, NativeEncodeSettlement.InternalFailure, thrown)
    else -> rethrowFatal(occurrence, thrown)
}

private fun classifyInternalThrowable(occurrence: NativeEncodeOccurrence, thrown: Throwable?): Boolean = when (thrown) {
    null -> publishReturnedResult(occurrence, NativeEncodeSettlement.InternalFailure, NATIVE_INTERNAL_FAILURE)
    is Exception -> publishThrownResult(occurrence, NativeEncodeSettlement.InternalFailure, thrown)
    else -> rethrowFatal(occurrence, thrown)
}

private fun classifyMalformedThrowable(occurrence: NativeEncodeOccurrence, thrown: Throwable?): Boolean = when (thrown) {
    null -> publishReturnedResult(occurrence, NativeEncodeSettlement.InternalFailure, MALFORMED_NATIVE_RESULT)
    is Exception -> publishThrownResult(occurrence, NativeEncodeSettlement.InternalFailure, thrown)
    else -> rethrowFatal(occurrence, thrown)
}

private fun publishReturnedResult(occurrence: NativeEncodeOccurrence, result: NativeEncodeSettlement, cause: Throwable?): Boolean {
    val evidence = occurrence.operation.returnCell.evidence
    evidence.result = result
    evidence.failureCause = cause
    return occurrence.operation.publishNormalReturn()
}

private fun publishThrownResult(occurrence: NativeEncodeOccurrence, result: NativeEncodeSettlement, cause: Throwable): Boolean {
    val evidence = occurrence.operation.returnCell.evidence
    evidence.result = result
    evidence.failureCause = cause
    return when (cause) {
        is Exception -> occurrence.operation.publishThrownReturn(cause)
        is OutOfMemoryError -> {
            check(result == NativeEncodeSettlement.ResourceExhausted)
            occurrence.operation.publishNormalReturn()
        }

        else -> {
            occurrence.operation.publishDirectFatalReturn(cause)
            throw cause
        }
    }
}

private fun rethrowFatal(occurrence: NativeEncodeOccurrence, fatal: Throwable): Nothing {
    occurrence.operation.publishDirectFatalReturn(fatal)
    throw fatal
}

private const val NATIVE_STATUS_TRANSFER_COMPLETE: Long = 0L
private const val NATIVE_STATUS_SAFE_COMPRESSOR_ALLOCATION_FAILURE: Long = 1L
private const val NATIVE_STATUS_OUT_OF_MEMORY: Long = 2L
private const val NATIVE_STATUS_INTERNAL_FAILURE: Long = 3L
private const val NATIVE_STATUS_JAVA_THROWABLE: Long = 4L

private val MALFORMED_NATIVE_RESULT: IllegalStateException =
    IllegalStateException("native JPEG returned malformed result evidence")
private val NATIVE_INTERNAL_FAILURE: IllegalStateException =
    IllegalStateException("native JPEG reported an internal failure")
