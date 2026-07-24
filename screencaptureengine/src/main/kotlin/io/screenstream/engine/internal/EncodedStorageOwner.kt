package io.screenstream.engine.internal

import io.screenstream.engine.internal.storage.NativeEncodedTransaction
import java.nio.ByteBuffer

/**
 * Binary anchor for the one managed sink called by the native JPEG bridge.
 *
 * Storage ownership lives in [io.screenstream.engine.internal.storage]. This nesting and the private method
 * are intentionally retained because their JVM binary names are part of the frozen JNI contract.
 */
internal class EncodedStorageOwner private constructor() {
    internal class NativeSegmentSink internal constructor(
        private val transaction: NativeEncodedTransaction,
    ) {
        @Suppress("unused") // Called from JNI by its frozen binary name and descriptor.
        private fun adoptNativeSegment(segment: ByteBuffer, byteCount: Int) {
            transaction.adoptNativeSegment(segment, byteCount)
        }
    }
}
