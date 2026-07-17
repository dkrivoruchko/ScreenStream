package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.JpegRuntimeOwner

internal fun decodeNativeEncodeResult(
    owner: JpegRuntimeOwner,
    occurrence: NativeEncodeOccurrence,
    compressorResult: Int,
    thrown: Throwable?,
): Boolean {
    val bag = occurrence.ownerBag
    val evidence = occurrence.operation.returnCell.evidence
    val loan = checkNotNull(bag.callBlockLoan)
    val transaction = checkNotNull(bag.transaction)

    var blockFailure: Throwable? = null
    try {
        evidence.compressorResult = compressorResult
        evidence.writerStatus = loan.resultBlock.getLong(WRITER_STATUS_OFFSET)
        evidence.totalBytes = loan.resultBlock.getLong(TOTAL_BYTES_OFFSET)
        evidence.adoptedBytes = loan.resultBlock.getLong(ADOPTED_BYTES_OFFSET)
        evidence.remainingBytes = loan.resultBlock.getLong(REMAINING_BYTES_OFFSET)
        evidence.remainingSegmentCount = loan.resultBlock.getLong(REMAINING_SEGMENTS_OFFSET)
    } catch (failure: Throwable) {
        blockFailure = failure
    }

    var tokenRead = false
    val tokenClearOnReturn = try {
        val clear = loan.writerBlock.getLong(WRITER_TOKEN_OFFSET) == 0L
        tokenRead = true
        clear
    } catch (failure: Throwable) {
        if (blockFailure == null) blockFailure = failure
        false
    }
    var residueFailure: Throwable? = null
    if (tokenClearOnReturn) {
        if (!loan.markClearOnReturn()) residueFailure = MALFORMED_NATIVE_RESULT
    } else if (tokenRead) {
        try {
            if (owner.releaseNativeWriterResidue(loan.writerBlock) == NATIVE_RESIDUE_RELEASE_SUCCESS) {
                val tokenClearedAfterRelease = loan.writerBlock.getLong(WRITER_TOKEN_OFFSET) == 0L
                if (tokenClearedAfterRelease && !loan.markReleasedAndCleared()) {
                    residueFailure = MALFORMED_NATIVE_RESULT
                }
            }
        } catch (failure: Throwable) {
            residueFailure = failure
        }
    }
    evidence.writerResidueDisposition = loan.writerResidueDisposition

    val writerMalformed = evidence.writerStatus != WRITER_STATUS_SAFE &&
            evidence.writerStatus != WRITER_STATUS_OUT_OF_MEMORY &&
            evidence.writerStatus != WRITER_STATUS_INTERNAL
    val countsNonnegative = evidence.totalBytes >= 0L && evidence.adoptedBytes >= 0L &&
            evidence.remainingBytes >= 0L && evidence.remainingSegmentCount >= 0L
    val sumRepresentable = countsNonnegative && evidence.adoptedBytes <= Long.MAX_VALUE - evidence.remainingBytes
    val countsMalformed = !countsNonnegative || evidence.adoptedBytes > evidence.totalBytes ||
            evidence.remainingBytes > evidence.totalBytes || !sumRepresentable ||
            evidence.totalBytes != evidence.adoptedBytes + evidence.remainingBytes ||
            (evidence.remainingBytes == 0L) != (evidence.remainingSegmentCount == 0L) ||
            evidence.remainingSegmentCount > evidence.remainingBytes ||
            evidence.adoptedBytes != transaction.byteCount.toLong()
    val transactionFailure = transaction.failure
    val nonOomBlockFailure = blockFailure != null && blockFailure !is OutOfMemoryError
    val nonOomResidueFailure = residueFailure != null && residueFailure !is OutOfMemoryError
    val nonOomThrown = thrown != null && thrown !is OutOfMemoryError
    val residueResolved = evidence.writerResidueDisposition == NativeWriterResidueDisposition.ClearOnReturn ||
            evidence.writerResidueDisposition == NativeWriterResidueDisposition.ReleasedAndCleared
    val internalFault = !evidence.carrierUseResolved || !residueResolved || nonOomBlockFailure ||
            nonOomResidueFailure || nonOomThrown || writerMalformed || countsMalformed ||
            evidence.writerStatus == WRITER_STATUS_INTERNAL ||
            transactionFailure == EncodedStorageOwner.TransactionFailure.InternalFailure
    val outOfMemory = blockFailure is OutOfMemoryError || residueFailure is OutOfMemoryError ||
            thrown is OutOfMemoryError || evidence.writerStatus == WRITER_STATUS_OUT_OF_MEMORY ||
            transactionFailure == EncodedStorageOwner.TransactionFailure.ResourceExhausted

    if (internalFault) {
        if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
        return publishNativeEncodeFailure(
            occurrence,
            NativeEncodeSettlement.InternalFailure,
            selectInternalFailureCause(
                blockFailure = blockFailure,
                residueFailure = residueFailure,
                thrown = thrown,
                transactionFailure = transactionFailure,
                transactionCause = transaction.failureCause,
            ),
        )
    }
    if (outOfMemory) {
        if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
        return publishNativeEncodeFailure(
            occurrence,
            NativeEncodeSettlement.ResourceExhausted,
            selectResourceExhaustedCause(
                blockFailure = blockFailure,
                residueFailure = residueFailure,
                thrown = thrown,
                transactionFailure = transactionFailure,
                transactionCause = transaction.failureCause,
            ),
        )
    }

    val nativeRemainderEmpty = evidence.remainingBytes == 0L && evidence.remainingSegmentCount == 0L
    when (compressorResult) {
        ANDROID_BITMAP_RESULT_SUCCESS -> {
            val successful = evidence.writerStatus == WRITER_STATUS_SAFE && nativeRemainderEmpty &&
                    evidence.totalBytes > 0L && evidence.totalBytes == evidence.adoptedBytes &&
                    transaction.commit(bag.descriptor.imageSize)
            if (successful) {
                evidence.result = NativeEncodeSettlement.Success
                return occurrence.operation.publishNormalReturn()
            } else {
                if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
                val transactionResult = classifyTransactionFailure(transaction)
                return publishNativeEncodeFailure(
                    occurrence,
                    transactionResult,
                    selectTransactionFailureCause(transaction, transactionResult),
                )
            }
        }

        ANDROID_BITMAP_RESULT_ALLOCATION_FAILED -> {
            val safeAllocationFailure = evidence.writerStatus == WRITER_STATUS_SAFE && evidence.totalBytes == 0L &&
                    evidence.adoptedBytes == 0L && nativeRemainderEmpty &&
                    transaction.byteCount == 0 && transaction.failure == null
            if (safeAllocationFailure) {
                transaction.abort()
                evidence.result = NativeEncodeSettlement.SafeNativeAllocationFailure
                return occurrence.operation.publishNormalReturn()
            } else {
                if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
                return publishNativeEncodeFailure(
                    occurrence,
                    NativeEncodeSettlement.InternalFailure,
                    MALFORMED_NATIVE_RESULT,
                )
            }
        }

        ANDROID_BITMAP_RESULT_BAD_PARAMETER,
        ANDROID_BITMAP_RESULT_JNI_EXCEPTION,
            -> {
            if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
            return publishNativeEncodeFailure(
                occurrence,
                NativeEncodeSettlement.InternalFailure,
                NATIVE_COMPRESSOR_FAILURE,
            )
        }

        else -> {
            if (!transaction.isCommitted && !transaction.isAborted) transaction.abort()
            return publishNativeEncodeFailure(
                occurrence,
                NativeEncodeSettlement.InternalFailure,
                UNKNOWN_NATIVE_COMPRESSOR_RESULT,
            )
        }
    }
}

internal fun publishNativeEncodeFailure(
    occurrence: NativeEncodeOccurrence,
    result: NativeEncodeSettlement,
    failure: Throwable,
): Boolean {
    val evidence = occurrence.operation.returnCell.evidence
    evidence.result = result
    evidence.failureCause = failure
    return occurrence.operation.publishThrownReturn(failure)
}

private fun classifyTransactionFailure(transaction: EncodedStorageOwner.NativeTransaction): NativeEncodeSettlement =
    if (transaction.failure == EncodedStorageOwner.TransactionFailure.ResourceExhausted) {
        NativeEncodeSettlement.ResourceExhausted
    } else {
        NativeEncodeSettlement.InternalFailure
    }

private fun selectTransactionFailureCause(
    transaction: EncodedStorageOwner.NativeTransaction,
    result: NativeEncodeSettlement,
): Throwable = when (result) {
    NativeEncodeSettlement.ResourceExhausted -> selectResourceExhaustedCause(
        blockFailure = null,
        residueFailure = null,
        thrown = null,
        transactionFailure = transaction.failure,
        transactionCause = transaction.failureCause,
    )

    NativeEncodeSettlement.InternalFailure -> selectInternalFailureCause(
        blockFailure = null,
        residueFailure = null,
        thrown = null,
        transactionFailure = transaction.failure,
        transactionCause = transaction.failureCause,
    )

    else -> MALFORMED_NATIVE_RESULT
}

private fun selectInternalFailureCause(
    blockFailure: Throwable?,
    residueFailure: Throwable?,
    thrown: Throwable?,
    transactionFailure: EncodedStorageOwner.TransactionFailure?,
    transactionCause: Throwable?,
): Throwable {
    if (blockFailure != null && blockFailure !is OutOfMemoryError) return blockFailure
    if (residueFailure != null && residueFailure !is OutOfMemoryError) return residueFailure
    if (thrown != null && thrown !is OutOfMemoryError) return thrown
    if (transactionFailure == EncodedStorageOwner.TransactionFailure.InternalFailure &&
        transactionCause != null && transactionCause !is OutOfMemoryError
    ) {
        return transactionCause
    }
    return MALFORMED_NATIVE_RESULT
}

private fun selectResourceExhaustedCause(
    blockFailure: Throwable?,
    residueFailure: Throwable?,
    thrown: Throwable?,
    transactionFailure: EncodedStorageOwner.TransactionFailure?,
    transactionCause: Throwable?,
): Throwable {
    if (blockFailure is OutOfMemoryError) return blockFailure
    if (residueFailure is OutOfMemoryError) return residueFailure
    if (thrown is OutOfMemoryError) return thrown
    if (transactionFailure == EncodedStorageOwner.TransactionFailure.ResourceExhausted && transactionCause != null) {
        return transactionCause
    }
    return NATIVE_WRITER_OUT_OF_MEMORY
}

private const val NATIVE_RESIDUE_RELEASE_SUCCESS: Int = 0

private const val ANDROID_BITMAP_RESULT_SUCCESS: Int = 0
private const val ANDROID_BITMAP_RESULT_BAD_PARAMETER: Int = -1
private const val ANDROID_BITMAP_RESULT_JNI_EXCEPTION: Int = -2
private const val ANDROID_BITMAP_RESULT_ALLOCATION_FAILED: Int = -3

private const val WRITER_STATUS_SAFE: Long = 0L
private const val WRITER_STATUS_OUT_OF_MEMORY: Long = 1L
private const val WRITER_STATUS_INTERNAL: Long = 2L

private const val WRITER_TOKEN_OFFSET: Int = 0
private const val WRITER_STATUS_OFFSET: Int = 0
private const val TOTAL_BYTES_OFFSET: Int = Long.SIZE_BYTES
private const val ADOPTED_BYTES_OFFSET: Int = Long.SIZE_BYTES * 2
private const val REMAINING_BYTES_OFFSET: Int = Long.SIZE_BYTES * 3
private const val REMAINING_SEGMENTS_OFFSET: Int = Long.SIZE_BYTES * 4

private val MALFORMED_NATIVE_RESULT: IllegalStateException =
    IllegalStateException("native JPEG returned malformed writer evidence")
private val NATIVE_WRITER_OUT_OF_MEMORY: OutOfMemoryError =
    OutOfMemoryError("native JPEG writer exhausted storage")
private val NATIVE_COMPRESSOR_FAILURE: IllegalStateException =
    IllegalStateException("native JPEG compressor rejected the frame")
private val UNKNOWN_NATIVE_COMPRESSOR_RESULT: IllegalStateException =
    IllegalStateException("native JPEG compressor returned an unknown result")
