package io.screenstream.capture.internal.encoding

import java.io.OutputStream

internal class FrameworkEncodedTransaction : ManagedEncodedTransaction() {
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

    override fun freezeExactSegments(segments: List<ByteArray>): Array<ByteArray>? {
        val finalTail = checkNotNull(tail)
        check((segments.isNotEmpty()) && (segments.last() === finalTail))
        check((usedTailByteCount in 1..finalTail.size))

        val normalizedTail = if (usedTailByteCount == finalTail.size) {
            finalTail
        } else {
            val exactTail = try {
                ByteArray(usedTailByteCount)
            } catch (allocationFailure: OutOfMemoryError) {
                recordFault(FailureKind.ResourceExhausted, allocationFailure)
                return null
            }
            System.arraycopy(finalTail, 0, exactTail, 0, usedTailByteCount)
            exactTail
        }
        return try {
            Array(segments.size) { index -> if (index == segments.lastIndex) normalizedTail else segments[index] }
        } catch (allocationFailure: OutOfMemoryError) {
            recordFault(FailureKind.ResourceExhausted, allocationFailure)
            null
        }
    }

    override fun releaseProducerReferences() {
        tail = null
        usedTailByteCount = 0
    }

    private fun writeSingleByte(value: Int) {
        requireOpenProducer()
        val finalByteCount = checkedTotalAfter(1)

        var writableTail = tail
        if (writableTail == null || usedTailByteCount == writableTail.size) {
            val capacity = maxOf(byteCount, 1)
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
            recordFaultAndThrow(kind = FailureKind.InternalFailure)
        }

        val finalByteCount = checkedTotalAfter(length)
        if (length == 0) return

        var sourceOffset = offset
        var remaining = length
        val writableTail = tail
        if (writableTail != null && usedTailByteCount < writableTail.size) {
            val copiedByteCount = minOf(remaining, writableTail.size - usedTailByteCount)
            copyOrFault(
                source = source,
                sourceOffset = sourceOffset,
                destination = writableTail,
                destinationOffset = usedTailByteCount,
                byteCount = copiedByteCount,
            )
            usedTailByteCount += copiedByteCount
            sourceOffset += copiedByteCount
            remaining -= copiedByteCount
            recordAcceptedByteCount(byteCount + copiedByteCount)
        }

        if (remaining == 0) return

        val newTail = allocateSegment(capacity = maxOf(byteCount, remaining))
        copyOrFault(
            source = source,
            sourceOffset = sourceOffset,
            destination = newTail,
            destinationOffset = 0,
            byteCount = remaining,
        )
        appendAllocatedSegment(newTail)
        tail = newTail
        usedTailByteCount = remaining
        recordAcceptedByteCount(finalByteCount)
    }

    private fun allocateSegment(capacity: Int): ByteArray = try {
        ByteArray(capacity)
    } catch (allocationFailure: OutOfMemoryError) {
        recordFaultAndThrow(kind = FailureKind.ResourceExhausted, cause = allocationFailure)
    }

    private fun appendAllocatedSegment(segment: ByteArray) {
        try {
            appendSegment(segment)
        } catch (allocationFailure: OutOfMemoryError) {
            recordFaultAndThrow(kind = FailureKind.ResourceExhausted, cause = allocationFailure)
        } catch (failure: Exception) {
            recordFaultAndThrow(kind = FailureKind.InternalFailure, cause = failure)
        }
    }

    private fun copyOrFault(source: ByteArray, sourceOffset: Int, destination: ByteArray, destinationOffset: Int, byteCount: Int) {
        try {
            System.arraycopy(source, sourceOffset, destination, destinationOffset, byteCount)
        } catch (failure: Exception) {
            recordFaultAndThrow(kind = FailureKind.InternalFailure, cause = failure)
        }
    }
}
