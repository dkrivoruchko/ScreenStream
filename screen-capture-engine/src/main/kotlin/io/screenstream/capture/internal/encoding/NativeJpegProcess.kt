package io.screenstream.capture.internal.encoding

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface NativeJpegFacade {
    fun resolveAvailability(): NativeJpegProcess.Availability

    fun hasWeakCompressor(): Boolean

    fun newResultBlock(): ByteBuffer

    fun allocateCarrier(carrierByteCount: Long): ByteBuffer

    fun freeCarrier(carrierBuffer: ByteBuffer)

    fun compress(
        carrierBuffer: ByteBuffer,
        pixelByteCount: Long,
        width: Int,
        height: Int,
        stride: Int,
        quality: Int,
        sink: NativeSegmentSink,
        resultBlock: ByteBuffer,
    )
}

/**
 * Process-lifetime facade for the frozen Native JPEG JNI and wire ABI.
 *
 * A classified library-load result is published once. An exact [UnsatisfiedLinkError] is clean unavailability; later
 * JNI failures retain their normal propagation. The facade retains no Session state and the library is never unloaded.
 */
internal class NativeJpegProcess private constructor() {
    internal enum class Availability { Available, CleanUnavailable, Poisoned, }

    internal enum class NativeWireStatus { NativeTransferComplete, SafeCompressorRejection, NativeOutOfMemory, InternalFailure, JavaThrowable, Unknown }

    private external fun nativeAllocateCarrier(carrierByteCount: Long): ByteBuffer

    private external fun nativeFreeCarrier(carrierBuffer: ByteBuffer)

    private external fun nativeHasWeakCompressor(): Boolean

    private external fun nativeCompress(
        carrierBuffer: ByteBuffer,
        pixelByteCount: Long,
        width: Int,
        height: Int,
        stride: Int,
        format: Int,
        flags: Long,
        dataspace: Int,
        compressFormat: Int,
        quality: Int,
        sink: NativeSegmentSink,
        resultBlock: ByteBuffer,
    )

    internal companion object : NativeJpegFacade {
        private val receiver: NativeJpegProcess = NativeJpegProcess()
        private val availabilityCell: NativeJpegAvailabilityCell = NativeJpegAvailabilityCell(
            initialLoad = { System.loadLibrary(LIBRARY_NAME) },
        )

        override fun resolveAvailability(): Availability = availabilityCell.resolve()

        override fun allocateCarrier(carrierByteCount: Long): ByteBuffer = facade().nativeAllocateCarrier(carrierByteCount)

        override fun freeCarrier(carrierBuffer: ByteBuffer): Unit = facade().nativeFreeCarrier(carrierBuffer)

        override fun hasWeakCompressor(): Boolean = facade().nativeHasWeakCompressor()

        override fun compress(
            carrierBuffer: ByteBuffer,
            pixelByteCount: Long,
            width: Int,
            height: Int,
            stride: Int,
            quality: Int,
            sink: NativeSegmentSink,
            resultBlock: ByteBuffer,
        ): Unit = facade().nativeCompress(
            carrierBuffer = carrierBuffer,
            pixelByteCount = pixelByteCount,
            width = width,
            height = height,
            stride = stride,
            format = ANDROID_BITMAP_FORMAT_RGBA_8888,
            flags = ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE,
            dataspace = ADATASPACE_SRGB,
            compressFormat = ANDROID_BITMAP_COMPRESS_FORMAT_JPEG,
            quality = quality,
            sink = sink,
            resultBlock = resultBlock,
        )

        override fun newResultBlock(): ByteBuffer =
            ByteBuffer.allocateDirect(NATIVE_RESULT_BLOCK_BYTE_COUNT)
                .order(ByteOrder.nativeOrder())
                .apply {
                    putLong(NATIVE_PRODUCED_BYTE_COUNT_OFFSET, NATIVE_RESULT_PENDING)
                    putLong(NATIVE_WIRE_STATUS_OFFSET, NATIVE_RESULT_PENDING)
                }

        internal fun hasExactResultShape(resultBlock: ByteBuffer): Boolean =
            ((resultBlock.isDirect) &&
                    (!resultBlock.isReadOnly) &&
                    (resultBlock.capacity() == NATIVE_RESULT_BLOCK_BYTE_COUNT) &&
                    (resultBlock.limit() == NATIVE_RESULT_BLOCK_BYTE_COUNT) &&
                    (resultBlock.order() == ByteOrder.nativeOrder()))

        internal fun readProducedByteCount(resultBlock: ByteBuffer): Long =
            resultBlock.getLong(NATIVE_PRODUCED_BYTE_COUNT_OFFSET)

        internal fun readNativeWireStatus(resultBlock: ByteBuffer): NativeWireStatus =
            when (resultBlock.getLong(NATIVE_WIRE_STATUS_OFFSET)) {
                NATIVE_WIRE_STATUS_TRANSFER_COMPLETE -> NativeWireStatus.NativeTransferComplete
                NATIVE_WIRE_STATUS_SAFE_COMPRESSOR_REJECTION -> NativeWireStatus.SafeCompressorRejection
                NATIVE_WIRE_STATUS_OUT_OF_MEMORY -> NativeWireStatus.NativeOutOfMemory
                NATIVE_WIRE_STATUS_INTERNAL_FAILURE -> NativeWireStatus.InternalFailure
                NATIVE_WIRE_STATUS_JAVA_THROWABLE -> NativeWireStatus.JavaThrowable
                else -> NativeWireStatus.Unknown
            }

        private fun facade(): NativeJpegProcess {
            availabilityCell.requireAvailable()
            return receiver
        }

        private const val LIBRARY_NAME: String = "screen_capture_engine"
        private const val NATIVE_RESULT_BLOCK_BYTE_COUNT: Int = 16
        private const val NATIVE_PRODUCED_BYTE_COUNT_OFFSET: Int = 0
        private const val NATIVE_WIRE_STATUS_OFFSET: Int = 8
        internal const val NATIVE_RESULT_PENDING: Long = -1L

        private const val NATIVE_WIRE_STATUS_TRANSFER_COMPLETE: Long = 0L
        private const val NATIVE_WIRE_STATUS_SAFE_COMPRESSOR_REJECTION: Long = 1L
        private const val NATIVE_WIRE_STATUS_OUT_OF_MEMORY: Long = 2L
        private const val NATIVE_WIRE_STATUS_INTERNAL_FAILURE: Long = 3L
        private const val NATIVE_WIRE_STATUS_JAVA_THROWABLE: Long = 4L

        private const val ANDROID_BITMAP_FORMAT_RGBA_8888: Int = 1
        private const val ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE: Long = 1L
        private const val ADATASPACE_SRGB: Int = 142_671_872
        private const val ANDROID_BITMAP_COMPRESS_FORMAT_JPEG: Int = 0
    }
}

internal class NativeJpegAvailabilityCell(
    private val initialLoad: () -> Unit,
) {
    private var publishedAvailability: NativeJpegProcess.Availability? = null

    @Synchronized
    internal fun resolve(): NativeJpegProcess.Availability {
        publishedAvailability?.let { return it }
        val availability = try {
            initialLoad()
            NativeJpegProcess.Availability.Available
        } catch (failure: UnsatisfiedLinkError) {
            if (failure.javaClass != UnsatisfiedLinkError::class.java) throw failure
            NativeJpegProcess.Availability.CleanUnavailable
        } catch (_: SecurityException) {
            NativeJpegProcess.Availability.CleanUnavailable
        } catch (_: Exception) {
            NativeJpegProcess.Availability.Poisoned
        }
        return publish(availability)
    }

    @Synchronized
    internal fun requireAvailable(): Unit =
        check(publishedAvailability == NativeJpegProcess.Availability.Available) {
            "Native JPEG facade is unavailable"
        }

    private fun publish(availability: NativeJpegProcess.Availability): NativeJpegProcess.Availability {
        check(publishedAvailability == null)
        publishedAvailability = availability
        return availability
    }
}
