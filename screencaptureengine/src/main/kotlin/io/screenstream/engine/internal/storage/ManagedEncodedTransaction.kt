package io.screenstream.engine.internal.storage

import io.screenstream.engine.internal.EncodedStorageOwner
import java.io.OutputStream
import java.nio.ByteBuffer

internal enum class EncodedProducerKind {
    Framework,
    Native,
}

internal enum class EncodedStorageFailureKind {
    ResourceExhausted,
    InternalFailure,
}

internal enum class EncodedTransactionState {
    Open,
    ProducerClosed,
    Faulted,
    Committed,
    Aborted,
}

/** One segmented transaction family shared by Framework writes and Native managed adoption. */
internal sealed class ManagedEncodedTransaction(
    internal val producerKind: EncodedProducerKind,
) {
    private val tentativeSegments: ArrayList<ByteArray> = ArrayList()

    private var transactionState: EncodedTransactionState = EncodedTransactionState.Open
    private var acceptedByteCount: Int = 0
    private var failureKindSlot: EncodedStorageFailureKind? = null
    private var failureCauseSlot: Throwable? = null
    private var committedPayloadSlot: ImmutableEncodedPayload? = null

    internal val state: EncodedTransactionState
        get() = transactionState

    internal val byteCount: Int
        get() = acceptedByteCount

    internal val failureKind: EncodedStorageFailureKind?
        get() = failureKindSlot

    internal val failureCause: Throwable?
        get() = failureCauseSlot

    internal val isFreshOpen: Boolean
        get() = transactionState == EncodedTransactionState.Open && acceptedByteCount == 0

    internal fun commit(imageWidthPx: Int, imageHeightPx: Int): Boolean {
        when (transactionState) {
            EncodedTransactionState.Open,
            EncodedTransactionState.ProducerClosed,
                -> Unit

            EncodedTransactionState.Faulted -> return false
            EncodedTransactionState.Committed -> error("encoded transaction is already committed")
            EncodedTransactionState.Aborted -> error("encoded transaction is aborted")
        }

        if (imageWidthPx <= 0 || imageHeightPx <= 0) {
            recordFault(
                kind = EncodedStorageFailureKind.InternalFailure,
                cause = IllegalArgumentException("encoded image dimensions must be positive"),
            )
            return false
        }
        if (acceptedByteCount <= 0) {
            recordFault(
                kind = EncodedStorageFailureKind.InternalFailure,
                cause = IllegalStateException("encoded payload must not be empty"),
            )
            return false
        }

        val payload = try {
            ImmutableEncodedPayload(
                segments = freezeExactSegments(tentativeSegments),
                byteCount = acceptedByteCount,
            )
        } catch (allocationFailure: OutOfMemoryError) {
            recordFault(EncodedStorageFailureKind.ResourceExhausted, allocationFailure)
            return false
        } catch (failure: Exception) {
            recordFault(EncodedStorageFailureKind.InternalFailure, failure)
            return false
        }

        committedPayloadSlot = payload
        tentativeSegments.clear()
        clearProducerPointers()
        transactionState = EncodedTransactionState.Committed
        return true
    }

    internal fun committedPayload(): ImmutableEncodedPayload? =
        if (transactionState == EncodedTransactionState.Committed) committedPayloadSlot else null

    internal fun transferCommittedPayload(expectedPayload: ImmutableEncodedPayload): Boolean {
        if (transactionState != EncodedTransactionState.Committed || committedPayloadSlot !== expectedPayload) {
            return false
        }
        committedPayloadSlot = null
        return true
    }

    internal fun abort(): Boolean {
        when (transactionState) {
            EncodedTransactionState.Committed -> error("a committed encoded transaction cannot be aborted")
            EncodedTransactionState.Aborted -> return false
            EncodedTransactionState.Open,
            EncodedTransactionState.ProducerClosed,
            EncodedTransactionState.Faulted,
                -> Unit
        }

        tentativeSegments.clear()
        clearProducerPointers()
        transactionState = EncodedTransactionState.Aborted
        return true
    }

    protected fun requireOpenProducer() {
        when (transactionState) {
            EncodedTransactionState.Open -> Unit
            EncodedTransactionState.Faulted -> throw checkNotNull(failureCauseSlot)
            EncodedTransactionState.ProducerClosed -> failAndThrow(
                kind = EncodedStorageFailureKind.InternalFailure,
                cause = IllegalStateException("encoded producer is closed"),
            )

            EncodedTransactionState.Committed -> error("encoded transaction is committed")
            EncodedTransactionState.Aborted -> error("encoded transaction is aborted")
        }
    }

    protected fun closeProducer() {
        when (transactionState) {
            EncodedTransactionState.Open -> transactionState = EncodedTransactionState.ProducerClosed
            EncodedTransactionState.ProducerClosed -> Unit
            EncodedTransactionState.Faulted -> throw checkNotNull(failureCauseSlot)
            EncodedTransactionState.Committed -> error("encoded transaction is committed")
            EncodedTransactionState.Aborted -> error("encoded transaction is aborted")
        }
    }

    protected fun checkedTotalAfter(additionalByteCount: Int): Int {
        if (additionalByteCount < 0) {
            failAndThrow(
                kind = EncodedStorageFailureKind.InternalFailure,
                cause = IllegalArgumentException("additional encoded byte count must not be negative"),
            )
        }
        if (additionalByteCount > Int.MAX_VALUE - acceptedByteCount) {
            failAndThrow(
                kind = EncodedStorageFailureKind.ResourceExhausted,
                cause = OutOfMemoryError("encoded byte count exceeds Int.MAX_VALUE"),
            )
        }
        return acceptedByteCount + additionalByteCount
    }

    protected fun acceptedByteCount(): Int = acceptedByteCount

    protected fun recordAcceptedByteCount(newByteCount: Int) {
        check(newByteCount in acceptedByteCount..Int.MAX_VALUE)
        acceptedByteCount = newByteCount
    }

    protected fun appendSegment(segment: ByteArray) {
        check(segment.isNotEmpty())
        tentativeSegments.add(segment)
    }

    protected fun failAndThrow(kind: EncodedStorageFailureKind, cause: Throwable): Nothing {
        recordFault(kind, cause)
        throw checkNotNull(failureCauseSlot)
    }

    protected abstract fun freezeExactSegments(segments: ArrayList<ByteArray>): Array<ByteArray>

    protected abstract fun clearProducerPointers()

    private fun recordFault(kind: EncodedStorageFailureKind, cause: Throwable) {
        if (transactionState == EncodedTransactionState.Faulted) return
        check(
            transactionState == EncodedTransactionState.Open ||
                    transactionState == EncodedTransactionState.ProducerClosed,
        )
        failureKindSlot = kind
        failureCauseSlot = cause
        transactionState = EncodedTransactionState.Faulted
    }
}

internal class FrameworkEncodedTransaction : ManagedEncodedTransaction(EncodedProducerKind.Framework) {
    private var tail: ByteArray? = null
    private var usedTailByteCount: Int = 0

    internal val outputStream: OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            writeSingleByte(value)
        }

        override fun write(source: ByteArray, offset: Int, length: Int) {
            writeByteRange(source, offset, length)
        }

        override fun flush() {
            requireOpenProducer()
        }

        override fun close() {
            closeProducer()
        }
    }

    private fun writeSingleByte(value: Int) {
        requireOpenProducer()
        val finalByteCount = checkedTotalAfter(1)

        var writableTail = tail
        if (writableTail == null || usedTailByteCount == writableTail.size) {
            val capacity = maxOf(acceptedByteCount(), 1)
            writableTail = allocateSegment(capacity)
            appendAllocatedSegment(writableTail)
            tail = writableTail
            usedTailByteCount = 0
        }

        writableTail[usedTailByteCount] = value.toByte()
        usedTailByteCount += 1
        recordAcceptedByteCount(finalByteCount)
    }

    private fun writeByteRange(source: ByteArray, offset: Int, length: Int) {
        requireOpenProducer()
        if (offset < 0 || length < 0 || offset > source.size - length) {
            failAndThrow(
                kind = EncodedStorageFailureKind.InternalFailure,
                cause = IndexOutOfBoundsException(
                    "offset=$offset, length=$length, sourceSize=${source.size}",
                ),
            )
        }

        val finalByteCount = checkedTotalAfter(length)
        if (length == 0) return

        var sourceOffset = offset
        var remaining = length
        val writableTail = tail
        if (writableTail != null && usedTailByteCount < writableTail.size) {
            val copied = minOf(remaining, writableTail.size - usedTailByteCount)
            copyOrFault(source, sourceOffset, writableTail, usedTailByteCount, copied)
            usedTailByteCount += copied
            sourceOffset += copied
            remaining -= copied
            recordAcceptedByteCount(acceptedByteCount() + copied)
        }

        if (remaining == 0) return

        val newTail = allocateSegment(maxOf(acceptedByteCount(), remaining))
        copyOrFault(source, sourceOffset, newTail, 0, remaining)
        appendAllocatedSegment(newTail)
        tail = newTail
        usedTailByteCount = remaining
        recordAcceptedByteCount(finalByteCount)
    }

    private fun allocateSegment(capacity: Int): ByteArray = try {
        ByteArray(capacity)
    } catch (allocationFailure: OutOfMemoryError) {
        failAndThrow(EncodedStorageFailureKind.ResourceExhausted, allocationFailure)
    }

    private fun appendAllocatedSegment(segment: ByteArray) {
        try {
            appendSegment(segment)
        } catch (allocationFailure: OutOfMemoryError) {
            failAndThrow(EncodedStorageFailureKind.ResourceExhausted, allocationFailure)
        } catch (failure: Exception) {
            failAndThrow(EncodedStorageFailureKind.InternalFailure, failure)
        }
    }

    private fun copyOrFault(
        source: ByteArray,
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ) {
        try {
            System.arraycopy(source, sourceOffset, destination, destinationOffset, length)
        } catch (allocationFailure: OutOfMemoryError) {
            failAndThrow(EncodedStorageFailureKind.ResourceExhausted, allocationFailure)
        } catch (failure: Exception) {
            failAndThrow(EncodedStorageFailureKind.InternalFailure, failure)
        }
    }

    override fun freezeExactSegments(segments: ArrayList<ByteArray>): Array<ByteArray> {
        val finalTail = checkNotNull(tail)
        check(segments.isNotEmpty() && segments.last() === finalTail)
        check(usedTailByteCount in 1..finalTail.size)

        val normalizedTail = if (usedTailByteCount == finalTail.size) {
            finalTail
        } else {
            ByteArray(usedTailByteCount).also { exactTail ->
                System.arraycopy(finalTail, 0, exactTail, 0, usedTailByteCount)
            }
        }
        return Array(segments.size) { index ->
            if (index == segments.lastIndex) normalizedTail else segments[index]
        }
    }

    override fun clearProducerPointers() {
        tail = null
        usedTailByteCount = 0
    }
}

internal class NativeEncodedTransaction : ManagedEncodedTransaction(EncodedProducerKind.Native) {
    internal val segmentSink: EncodedStorageOwner.NativeSegmentSink = EncodedStorageOwner.NativeSegmentSink(this)

    internal fun adoptNativeSegment(segment: ByteBuffer, byteCount: Int) {
        requireOpenProducer()
        if (byteCount <= 0 ||
            !segment.isDirect ||
            segment.position() != 0 ||
            segment.limit() != byteCount ||
            segment.remaining() != byteCount ||
            segment.capacity() != byteCount
        ) {
            failAndThrow(
                kind = EncodedStorageFailureKind.InternalFailure,
                cause = IllegalArgumentException("native segment must be one exact positive direct range"),
            )
        }

        val finalByteCount = checkedTotalAfter(byteCount)
        val managedSegment = try {
            ByteArray(byteCount)
        } catch (allocationFailure: OutOfMemoryError) {
            failAndThrow(EncodedStorageFailureKind.ResourceExhausted, allocationFailure)
        }

        try {
            segment.get(managedSegment)
            appendSegment(managedSegment)
        } catch (allocationFailure: OutOfMemoryError) {
            failAndThrow(EncodedStorageFailureKind.ResourceExhausted, allocationFailure)
        } catch (failure: Exception) {
            failAndThrow(EncodedStorageFailureKind.InternalFailure, failure)
        }
        recordAcceptedByteCount(finalByteCount)
    }

    internal fun closeNativeProducer() {
        closeProducer()
    }

    override fun freezeExactSegments(segments: ArrayList<ByteArray>): Array<ByteArray> = segments.toTypedArray()

    override fun clearProducerPointers() = Unit
}
