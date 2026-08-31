package io.screenstream.capture.internal.encoding

import java.nio.ByteBuffer

internal class NativeEncodedTransaction : ManagedEncodedTransaction() {
    internal val segmentSink: NativeSegmentSink = NativeSegmentSink(this)

    internal fun adoptNativeSegment(nativeSegmentView: ByteBuffer, segmentByteCount: Int) {
        requireOpenProducer()
        if ((segmentByteCount <= 0) ||
            (!nativeSegmentView.isDirect) ||
            (nativeSegmentView.position() != 0) ||
            (nativeSegmentView.limit() != segmentByteCount) ||
            (nativeSegmentView.remaining() != segmentByteCount) ||
            (nativeSegmentView.capacity() != segmentByteCount)
        ) {
            recordFaultAndThrow(kind = FailureKind.InternalFailure)
        }

        val finalByteCount = checkedTotalAfter(segmentByteCount)
        val managedSegment = try {
            ByteArray(segmentByteCount)
        } catch (allocationFailure: OutOfMemoryError) {
            recordFaultAndThrow(kind = FailureKind.ResourceExhausted, cause = allocationFailure)
        }

        try {
            nativeSegmentView.get(managedSegment)
        } catch (failure: Exception) {
            recordFaultAndThrow(kind = FailureKind.InternalFailure, cause = failure)
        }
        try {
            appendSegment(managedSegment)
        } catch (allocationFailure: OutOfMemoryError) {
            recordFaultAndThrow(kind = FailureKind.ResourceExhausted, cause = allocationFailure)
        } catch (failure: Exception) {
            recordFaultAndThrow(kind = FailureKind.InternalFailure, cause = failure)
        }
        recordAcceptedByteCount(finalByteCount)
    }

    internal fun closeNativeProducer() {
        closeProducer()
    }

    override fun freezeExactSegments(segments: List<ByteArray>): Array<ByteArray>? = try {
        segments.toTypedArray()
    } catch (allocationFailure: OutOfMemoryError) {
        recordFault(FailureKind.ResourceExhausted, allocationFailure)
        null
    }
}
