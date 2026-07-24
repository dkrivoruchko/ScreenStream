package io.screenstream.engine.internal.jpeg

import io.screenstream.engine.internal.EncodedStorageOwner
import java.nio.ByteBuffer

/** Process-private loader, bootstrap receiver, and sole facade for the frozen five-method JNI surface. */
internal class NativeJpegProcess private constructor() {
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

    internal sealed interface Availability {
        data object Available : Availability

        class CleanUnavailable internal constructor() : Availability {
            private var causeSlot: Throwable? = null
            internal val cause: Throwable get() = checkNotNull(causeSlot)

            internal fun publish(cause: Throwable) {
                check(causeSlot == null)
                causeSlot = cause
            }
        }

        class LoadOome internal constructor() : Availability {
            private var causeSlot: OutOfMemoryError? = null
            internal val cause: OutOfMemoryError get() = checkNotNull(causeSlot)

            internal fun publish(cause: OutOfMemoryError) {
                check(causeSlot == null)
                causeSlot = cause
            }
        }

        class Poisoned internal constructor() : Availability {
            private var causeSlot: Throwable? = null
            internal val cause: Throwable get() = checkNotNull(causeSlot)

            internal fun publish(cause: Throwable) {
                check(causeSlot == null)
                causeSlot = cause
            }
        }
    }

    private enum class LoaderState {
        Unattempted,
        Available,
        CleanUnavailable,
        LoadOome,
        Poisoned,
    }

    internal companion object {
        private val receiver: NativeJpegProcess = NativeJpegProcess()

        // All sticky publication storage exists before the sole load entry.
        private val availableResult: Availability = Availability.Available
        private val cleanUnavailableResult: Availability.CleanUnavailable = Availability.CleanUnavailable()
        private val loadOomeResult: Availability.LoadOome = Availability.LoadOome()
        private val poisonedResult: Availability.Poisoned = Availability.Poisoned()
        private var state: LoaderState = LoaderState.Unattempted
        private var firstCause: Throwable? = null
        private var publishedFacade: NativeJpegProcess? = null
        private var publishedResult: Availability? = null

        @Synchronized
        internal fun ensureAvailable(): Availability {
            publishedResult?.let { return it }
            check(state == LoaderState.Unattempted && firstCause == null && publishedFacade == null)

            try {
                System.loadLibrary(LIBRARY_NAME)
            } catch (failure: UnsatisfiedLinkError) {
                return publishCleanUnavailable(failure)
            } catch (failure: SecurityException) {
                return publishCleanUnavailable(failure)
            } catch (failure: OutOfMemoryError) {
                return publishLoadOome(failure)
            } catch (failure: Exception) {
                return publishPoison(failure)
            } catch (fatal: Throwable) {
                publishPoison(fatal)
                throw fatal
            }

            return try {
                if (receiver.nativeBootstrap() == NATIVE_BOOTSTRAP_SUCCESS) {
                    publishedFacade = receiver
                    state = LoaderState.Available
                    availableResult.also { publishedResult = it }
                } else {
                    publishPoison(BOOTSTRAP_REJECTED)
                }
            } catch (failure: Exception) {
                publishPoison(failure)
            } catch (fatal: Throwable) {
                publishPoison(fatal)
                throw fatal
            }
        }

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

        private fun publishCleanUnavailable(cause: Throwable): Availability.CleanUnavailable {
            check(state == LoaderState.Unattempted)
            firstCause = cause
            cleanUnavailableResult.publish(cause)
            state = LoaderState.CleanUnavailable
            publishedResult = cleanUnavailableResult
            return cleanUnavailableResult
        }

        private fun publishLoadOome(cause: OutOfMemoryError): Availability.LoadOome {
            check(state == LoaderState.Unattempted)
            firstCause = cause
            loadOomeResult.publish(cause)
            state = LoaderState.LoadOome
            publishedResult = loadOomeResult
            return loadOomeResult
        }

        private fun publishPoison(cause: Throwable): Availability.Poisoned {
            check(state == LoaderState.Unattempted)
            firstCause = cause
            poisonedResult.publish(cause)
            state = LoaderState.Poisoned
            publishedResult = poisonedResult
            return poisonedResult
        }

        private const val LIBRARY_NAME: String = "screencaptureengine"
        private const val NATIVE_BOOTSTRAP_SUCCESS: Int = 0

        private val BOOTSTRAP_REJECTED: IllegalStateException =
            IllegalStateException("native JPEG bootstrap rejected its protocol")
        private val FACADE_UNAVAILABLE: IllegalStateException =
            IllegalStateException("native JPEG facade is unavailable")
    }
}
