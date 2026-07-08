package dev.dmkr.screencaptureengine.internal.encoding.provider

import android.graphics.Bitmap
import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.EncodedImageSink
import dev.dmkr.screencaptureengine.ImageEncodeResult
import dev.dmkr.screencaptureengine.ImageEncoder
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInput
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.ImageEncoderUnavailableException
import dev.dmkr.screencaptureengine.JpegEncoderBackend
import java.io.OutputStream
import java.nio.ByteBuffer

internal fun createFrameworkBitmapCompressEncoder(request: ImageEncoderRequest, quality: Int): ImageEncoder {
    validateRequestForFrameworkBackend(request)
    val requiredByteCount = checkedRequiredByteCount(
        height = request.height,
        rowStrideBytes = request.rowStrideBytes,
        width = request.width,
    ) ?: throw ImageEncoderUnavailableException("Framework JPEG raw input byte span overflowed.", null)
    if (requiredByteCount > Int.MAX_VALUE) {
        throw ImageEncoderUnavailableException("Framework JPEG raw input byte span exceeds ByteBuffer limits.", null)
    }
    val pixelCount = checkedMultiplyLong(request.width.toLong(), request.height.toLong())
        ?: throw ImageEncoderUnavailableException("Framework JPEG pixel count overflowed.", null)
    if (pixelCount > Int.MAX_VALUE) {
        throw ImageEncoderUnavailableException("Framework JPEG pixel count exceeds supported retained scratch size.", null)
    }

    var bitmap: Bitmap? = null
    return try {
        val argbPixels = allocateFrameworkJpegResource("Framework JPEG ARGB scratch allocation failed.") {
            IntArray(pixelCount.toInt())
        }
        val createdBitmap = allocateFrameworkJpegResource("Framework JPEG bitmap allocation failed.") {
            Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888)
        }
        bitmap = createdBitmap
        createdBitmap.setHasAlpha(false)
        FrameworkBitmapCompressJpegEncoder(
            request = request,
            quality = quality,
            bitmap = createdBitmap,
            argbPixels = argbPixels,
        )
    } catch (throwable: Throwable) {
        bitmap?.recycle()
        if (throwable is ImageEncoderUnavailableException) {
            throw throwable
        }
        throw ImageEncoderUnavailableException("Framework JPEG backend preparation failed.", throwable)
    }
}

private fun validateRequestForFrameworkBackend(request: ImageEncoderRequest) {
    if (request.inputFormat != ImageEncoderInputFormat.Rgba8888SrgbOpaque) {
        throw ImageEncoderUnavailableException("Framework JPEG requires RGBA8888 sRGB opaque input.", null)
    }
    if (request.rowStrideBytes.toLong() < request.width.toLong() * RGBA_8888_BYTES_PER_PIXEL) {
        throw ImageEncoderUnavailableException("Framework JPEG row stride is incompatible with width.", null)
    }
}

private const val RGBA_8888_BYTES_PER_PIXEL: Long = 4L

private class FrameworkBitmapCompressJpegEncoder(
    request: ImageEncoderRequest,
    private val quality: Int,
    private val bitmap: Bitmap,
    private val argbPixels: IntArray,
) : ImageEncoder {
    private val lock = Any()
    private var closed = false

    override val info: ImageEncoderInfo = ImageEncoderInfo(
        providerId = "jpeg",
        outputFormat = EncodedImageFormats.Jpeg,
        backendName = JpegEncoderBackend.FrameworkBitmapCompress.name,
    )

    private val width = request.width
    private val height = request.height
    private val rowStrideBytes = request.rowStrideBytes
    private val requiredByteCount = checkedRequiredByteCount(
        height = request.height,
        rowStrideBytes = request.rowStrideBytes,
        width = request.width,
    )

    override fun encode(input: ImageEncoderInput, output: EncodedImageSink): ImageEncodeResult =
        synchronized(lock) {
            if (closed) {
                return@synchronized ImageEncodeResult.Failed("Framework JPEG encoder is closed.", null)
            }
            validateInput(input)?.let { message ->
                return@synchronized ImageEncodeResult.Failed(message, null)
            }

            try {
                copyRgbaToOpaqueArgb(input.buffer)
                bitmap.setPixels(argbPixels, 0, width, 0, 0, width, height)
                val stream = EncodedImageSinkOutputStream(output)
                val compressed = try {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                } catch (throwable: Throwable) {
                    val sinkFailure = stream.failure
                    if (sinkFailure != null) {
                        return@synchronized ImageEncodeResult.Failed("Framework JPEG sink write failed.", sinkFailure)
                    }
                    throw throwable
                }
                val streamFailure = stream.failure
                when {
                    streamFailure != null -> ImageEncodeResult.Failed("Framework JPEG sink write failed.", streamFailure)
                    compressed -> ImageEncodeResult.Success
                    else -> ImageEncodeResult.Failed("Framework JPEG compression failed.", null)
                }
            } catch (throwable: Throwable) {
                ImageEncodeResult.Failed("Framework JPEG compression failed.", throwable)
            }
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            bitmap.recycle()
        }
    }

    private fun validateInput(input: ImageEncoderInput): String? {
        if (input.width != width || input.height != height) {
            return "ImageEncoderInput dimensions do not match the prepared JPEG encoder."
        }
        if (input.rowStrideBytes != rowStrideBytes) {
            return "ImageEncoderInput row stride does not match the prepared JPEG encoder."
        }
        if (input.format != ImageEncoderInputFormat.Rgba8888SrgbOpaque) {
            return "ImageEncoderInput format is not supported by framework JPEG."
        }
        if (input.buffer.position() != 0) {
            return "ImageEncoderInput buffer position must be zero."
        }
        val byteCount = requiredByteCount ?: return "ImageEncoderInput byte span overflowed."
        if (byteCount > input.buffer.limit().toLong()) {
            return "ImageEncoderInput buffer limit is smaller than the required image span."
        }
        return null
    }

    private fun copyRgbaToOpaqueArgb(buffer: ByteBuffer) {
        var destinationIndex = 0
        repeat(height) { y ->
            var sourceIndex = y * rowStrideBytes
            repeat(width) {
                val r = buffer.get(sourceIndex).toInt() and 0xFF
                val g = buffer.get(sourceIndex + 1).toInt() and 0xFF
                val b = buffer.get(sourceIndex + 2).toInt() and 0xFF
                argbPixels[destinationIndex] = OPAQUE_ALPHA_MASK or (r shl 16) or (g shl 8) or b
                destinationIndex += 1
                sourceIndex += RGBA_8888_BYTES_PER_PIXEL_INT
            }
        }
    }
}

private class EncodedImageSinkOutputStream(
    private val sink: EncodedImageSink,
) : OutputStream() {
    private val singleByte = ByteArray(1)
    var failure: Throwable? = null
        private set

    override fun write(oneByte: Int) {
        singleByte[0] = oneByte.toByte()
        write(singleByte, 0, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        val existingFailure = failure
        if (existingFailure != null) {
            throw EncodedImageSinkWriteException("Encoded sink was already rejected.", existingFailure)
        }

        try {
            if (!sink.write(buffer, offset, count)) {
                throw EncodedImageSinkWriteException("Encoded sink rejected framework JPEG bytes.", null)
            }
        } catch (throwable: Throwable) {
            val failureToRecord = throwable as? EncodedImageSinkWriteException
                ?: EncodedImageSinkWriteException("Encoded sink threw while writing framework JPEG bytes.", throwable)
            failure = failureToRecord
            throw failureToRecord
        }
    }
}

private class EncodedImageSinkWriteException(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)

private inline fun <T> allocateFrameworkJpegResource(message: String, allocate: () -> T): T =
    try {
        allocate()
    } catch (error: OutOfMemoryError) {
        throw ImageEncoderUnavailableException(message, error)
    }

private fun checkedRequiredByteCount(height: Int, rowStrideBytes: Int, width: Int): Long? {
    val lastRowOffset = checkedMultiplyLong((height - 1).toLong(), rowStrideBytes.toLong()) ?: return null
    val rowPayloadBytes = checkedMultiplyLong(width.toLong(), RGBA_8888_BYTES_PER_PIXEL) ?: return null
    return checkedAddLong(lastRowOffset, rowPayloadBytes)
}

private fun checkedMultiplyLong(left: Long, right: Long): Long? =
    try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

private fun checkedAddLong(left: Long, right: Long): Long? =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

private const val RGBA_8888_BYTES_PER_PIXEL_INT: Int = 4
private const val OPAQUE_ALPHA_MASK: Int = -0x1000000
