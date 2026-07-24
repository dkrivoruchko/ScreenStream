package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.storage.EncodedStorageFailureKind
import io.screenstream.engine.internal.storage.EncodedTransactionState
import io.screenstream.engine.internal.storage.NativeEncodedTransaction
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val NATIVE_RESULT_BLOCK_BYTE_COUNT: Int = 16
internal const val NATIVE_STATUS_OFFSET: Int = 0
internal const val NATIVE_PRODUCED_BYTE_COUNT_OFFSET: Int = 8
internal const val NATIVE_RESULT_PENDING: Long = -1L

private const val NATIVE_STATUS_TRANSFER_COMPLETE: Long = 0L
private const val NATIVE_STATUS_SAFE_COMPRESSOR_ALLOCATION_FAILURE: Long = 1L
private const val NATIVE_STATUS_OUT_OF_MEMORY: Long = 2L
private const val NATIVE_STATUS_INTERNAL_FAILURE: Long = 3L
private const val NATIVE_STATUS_JAVA_THROWABLE: Long = 4L

/** JPEG-entry-only allocation and arming for the exact block consumed by that production's native call. */
internal fun newNativeResultBlock(): ByteBuffer =
    ByteBuffer.allocateDirect(NATIVE_RESULT_BLOCK_BYTE_COUNT)
        .order(ByteOrder.nativeOrder())
        .apply {
            putLong(NATIVE_STATUS_OFFSET, NATIVE_RESULT_PENDING)
            putLong(NATIVE_PRODUCED_BYTE_COUNT_OFFSET, NATIVE_RESULT_PENDING)
        }

/**
 * Decodes the result of an actually returned native call.
 *
 * A recognized status is the native call's assertion that its call-scoped writer is closed. This function
 * only inspects wire and managed-adoption evidence; the caller still owns transaction close, commit/abort,
 * currentness, fallback, health, publication, and Session outcome.
 */
internal fun decodeReturnedNativeResult(
    resultBlock: ByteBuffer,
    transaction: NativeEncodedTransaction,
    pendingThrowable: Throwable?,
): NativeReturnedResult {
    val adoptedByteCount = transaction.byteCount
    val transactionFailureKind = transaction.failureKind
    val transactionFailure = transaction.failureCause

    var malformedReason = resultBlockShapeFailure(resultBlock)
    var inspectionFailure: Exception? = null
    var wireWasRead = false
    var status = NATIVE_RESULT_PENDING
    var producedByteCountLong = NATIVE_RESULT_PENDING
    if (malformedReason == null) {
        try {
            status = resultBlock.getLong(NATIVE_STATUS_OFFSET)
            producedByteCountLong = resultBlock.getLong(NATIVE_PRODUCED_BYTE_COUNT_OFFSET)
            wireWasRead = true
        } catch (failure: Exception) {
            malformedReason = NativeMalformedReason.ResultBlockReadFailure
            inspectionFailure = failure
        }
    }

    if (malformedReason == null) {
        malformedReason = when {
            (status == NATIVE_RESULT_PENDING) || (producedByteCountLong == NATIVE_RESULT_PENDING) ->
                NativeMalformedReason.PendingWord

            !isKnownFinalStatus(status) -> NativeMalformedReason.UnknownStatus
            (producedByteCountLong !in 0L..Int.MAX_VALUE.toLong()) ||
                    (adoptedByteCount < 0) ||
                    (adoptedByteCount.toLong() > producedByteCountLong) -> NativeMalformedReason.InvalidCount

            (transactionFailureKind == null) != (transactionFailure == null) ->
                NativeMalformedReason.ContradictoryTransactionEvidence

            !transactionStateMatchesEvidence(
                state = transaction.state,
                failureKind = transactionFailureKind,
                failureCause = transactionFailure,
            ) -> NativeMalformedReason.ContradictoryTransactionEvidence

            else -> null
        }
    }

    var producedByteCount = 0
    if (malformedReason == null) {
        producedByteCount = producedByteCountLong.toInt()
        val transactionHealthy = transactionFailureKind == null
        malformedReason = when (status) {
            NATIVE_STATUS_TRANSFER_COMPLETE -> if (
                pendingThrowable != null ||
                producedByteCount <= 0 ||
                adoptedByteCount != producedByteCount ||
                !transactionHealthy
            ) {
                NativeMalformedReason.TransferCompleteContradiction
            } else {
                null
            }

            NATIVE_STATUS_SAFE_COMPRESSOR_ALLOCATION_FAILURE -> if (
                pendingThrowable != null || adoptedByteCount != 0 || !transactionHealthy
            ) {
                NativeMalformedReason.SafeAllocationFailureContradiction
            } else {
                null
            }

            NATIVE_STATUS_OUT_OF_MEMORY -> if (
                pendingThrowable != null || adoptedByteCount != 0 || !transactionHealthy
            ) {
                NativeMalformedReason.NativeOutOfMemoryContradiction
            } else {
                null
            }

            NATIVE_STATUS_INTERNAL_FAILURE -> if (!transactionHealthy) {
                NativeMalformedReason.InternalFailureContradiction
            } else {
                null
            }

            NATIVE_STATUS_JAVA_THROWABLE -> if (
                pendingThrowable == null ||
                producedByteCount <= 0 ||
                !transactionMatchesJavaThrowable(
                    failureKind = transactionFailureKind,
                    failureCause = transactionFailure,
                    throwable = pendingThrowable,
                )
            ) {
                NativeMalformedReason.JavaThrowableContradiction
            } else {
                null
            }

            else -> NativeMalformedReason.UnknownStatus
        }
    }

    if (malformedReason != null) {
        return malformedResult(
            reason = malformedReason,
            pendingThrowable = pendingThrowable,
            wireWasRead = wireWasRead,
            status = status,
            producedByteCount = producedByteCountLong,
            adoptedByteCount = adoptedByteCount,
            inspectionFailure = inspectionFailure,
            transactionFailure = transactionFailure,
        )
    }

    return when (status) {
        NATIVE_STATUS_TRANSFER_COMPLETE -> NativeReturnedResult.TransferComplete(producedByteCount)
        NATIVE_STATUS_SAFE_COMPRESSOR_ALLOCATION_FAILURE ->
            NativeReturnedResult.SafeCompressorAllocationFailure(producedByteCount)

        NATIVE_STATUS_OUT_OF_MEMORY -> NativeReturnedResult.NativeOutOfMemory(producedByteCount)
        NATIVE_STATUS_INTERNAL_FAILURE -> internalFailure(
            pendingThrowable = pendingThrowable,
            status = status,
            producedByteCount = producedByteCountLong,
            adoptedByteCount = adoptedByteCount,
        )

        NATIVE_STATUS_JAVA_THROWABLE -> javaThrowableResult(
            throwable = checkNotNull(pendingThrowable),
            producedByteCount = producedByteCount,
            adoptedByteCount = adoptedByteCount,
        )

        else -> malformedResult(
            reason = NativeMalformedReason.UnknownStatus,
            pendingThrowable = pendingThrowable,
            wireWasRead = true,
            status = status,
            producedByteCount = producedByteCountLong,
            adoptedByteCount = adoptedByteCount,
            transactionFailure = transactionFailure,
        )
    }
}

internal sealed interface NativeReturnedResult {
    data class TransferComplete(
        val producedByteCount: Int,
    ) : NativeReturnedResult

    data class SafeCompressorAllocationFailure(
        val producedByteCount: Int,
    ) : NativeReturnedResult

    data class NativeOutOfMemory(
        val producedByteCount: Int,
    ) : NativeReturnedResult

    data class RequiredAllocationExhaustion(
        val producedByteCount: Int,
        val adoptedByteCount: Int,
        val cause: OutOfMemoryError,
    ) : NativeReturnedResult

    data class InternalFailure(
        val cause: Exception?,
        val wireEvidence: NativeWireEvidence?,
        val malformed: NativeMalformedEvidence?,
    ) : NativeReturnedResult
}

internal data class NativeWireEvidence(
    val status: Long,
    val producedByteCount: Long,
    val adoptedByteCount: Int,
)

internal data class NativeMalformedEvidence(
    val reason: NativeMalformedReason,
    val inspectionFailure: Exception?,
    val transactionFailure: Throwable?,
)

internal enum class NativeMalformedReason {
    ResultBlockNotDirect,
    ResultBlockReadOnly,
    ResultBlockCapacity,
    ResultBlockLimit,
    ResultBlockByteOrder,
    ResultBlockReadFailure,
    PendingWord,
    UnknownStatus,
    InvalidCount,
    ContradictoryTransactionEvidence,
    TransferCompleteContradiction,
    SafeAllocationFailureContradiction,
    NativeOutOfMemoryContradiction,
    InternalFailureContradiction,
    JavaThrowableContradiction,
}

private fun resultBlockShapeFailure(resultBlock: ByteBuffer): NativeMalformedReason? {
    if (!resultBlock.isDirect) return NativeMalformedReason.ResultBlockNotDirect
    if (resultBlock.isReadOnly) return NativeMalformedReason.ResultBlockReadOnly
    if (resultBlock.capacity() != NATIVE_RESULT_BLOCK_BYTE_COUNT) return NativeMalformedReason.ResultBlockCapacity
    if (resultBlock.limit() != NATIVE_RESULT_BLOCK_BYTE_COUNT) return NativeMalformedReason.ResultBlockLimit
    if (resultBlock.order() != ByteOrder.nativeOrder()) return NativeMalformedReason.ResultBlockByteOrder
    return null
}

private fun isKnownFinalStatus(status: Long): Boolean =
    status == NATIVE_STATUS_TRANSFER_COMPLETE ||
            status == NATIVE_STATUS_SAFE_COMPRESSOR_ALLOCATION_FAILURE ||
            status == NATIVE_STATUS_OUT_OF_MEMORY ||
            status == NATIVE_STATUS_INTERNAL_FAILURE ||
            status == NATIVE_STATUS_JAVA_THROWABLE

private fun transactionMatchesJavaThrowable(
    failureKind: EncodedStorageFailureKind?,
    failureCause: Throwable?,
    throwable: Throwable,
): Boolean {
    if (failureKind == null && failureCause == null) return true
    if (failureCause !== throwable) return false
    return when (throwable) {
        is OutOfMemoryError -> failureKind == EncodedStorageFailureKind.ResourceExhausted
        is Exception -> failureKind == EncodedStorageFailureKind.InternalFailure
        else -> false
    }
}

private fun transactionStateMatchesEvidence(
    state: EncodedTransactionState,
    failureKind: EncodedStorageFailureKind?,
    failureCause: Throwable?,
): Boolean = if (failureKind == null && failureCause == null) {
    state == EncodedTransactionState.Open
} else {
    state == EncodedTransactionState.Faulted
}

private fun javaThrowableResult(
    throwable: Throwable,
    producedByteCount: Int,
    adoptedByteCount: Int,
): NativeReturnedResult = when (throwable) {
    is OutOfMemoryError -> NativeReturnedResult.RequiredAllocationExhaustion(
        producedByteCount = producedByteCount,
        adoptedByteCount = adoptedByteCount,
        cause = throwable,
    )

    is Exception -> NativeReturnedResult.InternalFailure(
        cause = throwable,
        wireEvidence = NativeWireEvidence(
            status = NATIVE_STATUS_JAVA_THROWABLE,
            producedByteCount = producedByteCount.toLong(),
            adoptedByteCount = adoptedByteCount,
        ),
        malformed = null,
    )

    else -> throw throwable
}

private fun malformedResult(
    reason: NativeMalformedReason,
    pendingThrowable: Throwable?,
    wireWasRead: Boolean,
    status: Long,
    producedByteCount: Long,
    adoptedByteCount: Int,
    inspectionFailure: Exception? = null,
    transactionFailure: Throwable? = null,
): NativeReturnedResult {
    val selectedCause = pendingThrowable ?: inspectionFailure ?: transactionFailure
    if (selectedCause != null && selectedCause !is Exception) throw selectedCause

    val wireEvidence = if (wireWasRead) NativeWireEvidence(status, producedByteCount, adoptedByteCount) else null
    return NativeReturnedResult.InternalFailure(
        cause = selectedCause,
        wireEvidence = wireEvidence,
        malformed = NativeMalformedEvidence(reason, inspectionFailure, transactionFailure),
    )
}

private fun internalFailure(
    pendingThrowable: Throwable?,
    status: Long,
    producedByteCount: Long,
    adoptedByteCount: Int,
): NativeReturnedResult {
    if (pendingThrowable != null && pendingThrowable !is Exception) throw pendingThrowable
    return NativeReturnedResult.InternalFailure(
        cause = pendingThrowable,
        wireEvidence = NativeWireEvidence(status, producedByteCount, adoptedByteCount),
        malformed = null,
    )
}
