package io.screenstream.capture.internal.encoding

import java.nio.ByteBuffer

/** JNI enters [copyNativeSegment] with a borrowed native view, which is copied synchronously and never retained. */
internal class NativeSegmentSink(private val transaction: NativeEncodedTransaction) {
    internal fun copySegment(nativeSegmentView: ByteBuffer, segmentByteCount: Int) {
        transaction.copyNativeSegment(nativeSegmentView, segmentByteCount)
    }

    @Suppress("unused")
    private fun copyNativeSegment(nativeSegmentView: ByteBuffer, segmentByteCount: Int) {
        copySegment(nativeSegmentView, segmentByteCount)
    }
}
