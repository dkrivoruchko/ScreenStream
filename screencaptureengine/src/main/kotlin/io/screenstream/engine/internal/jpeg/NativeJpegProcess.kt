package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.EncodedStorageOwner
import java.nio.ByteBuffer

internal class NativeJpegProcess private constructor() {
    internal enum class State {
        Unattempted,
        Available,
        CleanUnavailable,
        LoadOome,
        Poisoned,
    }

    private external fun nativeBootstrap(): Int

    private external fun nativeAllocateCarrier(byteCount: Long): ByteBuffer

    private external fun nativeFreeCarrier(pixels: ByteBuffer)

    private external fun nativeHasWeakCompressor(): Boolean

    private external fun nativeCompress(
        pixels: ByteBuffer,
        pixelByteCount: Long,
        width: Int,
        height: Int,
        stride: Int,
        format: Int,
        flags: Long,
        dataspace: Int,
        compressFormat: Int,
        quality: Int,
        sink: EncodedStorageOwner.NativeSegmentSink,
        resultBlock: ByteBuffer,
    )

    internal companion object {
        private val receiver: NativeJpegProcess = NativeJpegProcess()
        private var state: State = State.Unattempted
        private var firstCause: Throwable? = null
        private var publishedFacade: NativeJpegProcess? = null

        @Synchronized
        internal fun ensureAvailable(): State {
            if (state != State.Unattempted) return state

            try {
                System.loadLibrary(LIBRARY_NAME)
            } catch (failure: UnsatisfiedLinkError) {
                return publishFailure(State.CleanUnavailable, failure)
            } catch (failure: SecurityException) {
                return publishFailure(State.CleanUnavailable, failure)
            } catch (failure: OutOfMemoryError) {
                return publishFailure(State.LoadOome, failure)
            } catch (failure: Throwable) {
                return publishFailure(State.Poisoned, failure)
            }

            return try {
                if (receiver.nativeBootstrap() == NATIVE_BOOTSTRAP_SUCCESS) {
                    publishedFacade = receiver
                    state = State.Available
                    state
                } else {
                    publishFailure(State.Poisoned, BOOTSTRAP_REJECTED)
                }
            } catch (failure: Throwable) {
                publishFailure(State.Poisoned, failure)
            }
        }

        @Synchronized
        internal fun cause(): Throwable? = firstCause

        internal fun allocateCarrier(byteCount: Long): ByteBuffer = facade().nativeAllocateCarrier(byteCount)

        internal fun freeCarrier(pixels: ByteBuffer): Unit = facade().nativeFreeCarrier(pixels)

        internal fun hasWeakCompressor(): Boolean = facade().nativeHasWeakCompressor()

        internal fun compress(
            pixels: ByteBuffer,
            pixelByteCount: Long,
            width: Int,
            height: Int,
            stride: Int,
            format: Int,
            flags: Long,
            dataspace: Int,
            compressFormat: Int,
            quality: Int,
            sink: EncodedStorageOwner.NativeSegmentSink,
            resultBlock: ByteBuffer,
        ): Unit = facade().nativeCompress(
            pixels = pixels,
            pixelByteCount = pixelByteCount,
            width = width,
            height = height,
            stride = stride,
            format = format,
            flags = flags,
            dataspace = dataspace,
            compressFormat = compressFormat,
            quality = quality,
            sink = sink,
            resultBlock = resultBlock,
        )

        @Synchronized
        private fun facade(): NativeJpegProcess = publishedFacade ?: throw FACADE_UNAVAILABLE

        private fun publishFailure(result: State, failure: Throwable): State {
            check(state == State.Unattempted)
            firstCause = failure
            state = result
            return result
        }

        private const val LIBRARY_NAME: String = "screencaptureengine"
        private const val NATIVE_BOOTSTRAP_SUCCESS: Int = 0

        private val BOOTSTRAP_REJECTED: IllegalStateException =
            IllegalStateException("native JPEG bootstrap rejected its protocol")
        private val FACADE_UNAVAILABLE: IllegalStateException =
            IllegalStateException("native JPEG facade is unavailable")
    }
}
