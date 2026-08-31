package io.screenstream.capture.internal.encoding

import java.nio.ByteBuffer

internal class NativeSegmentSink(private val transaction: NativeEncodedTransaction) {
    internal fun adoptSegment(nativeSegmentView: ByteBuffer, segmentByteCount: Int) {
        transaction.adoptNativeSegment(nativeSegmentView, segmentByteCount)
    }

    @Suppress("unused")
    private fun adoptNativeSegment(nativeSegmentView: ByteBuffer, segmentByteCount: Int) {
        adoptSegment(nativeSegmentView, segmentByteCount)
    }
}
