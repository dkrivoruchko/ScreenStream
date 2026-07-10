package dev.dmkr.screencaptureengine.internal.encoding.jpeg

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

@Suppress("UseKtx") // This module intentionally has no core-ktx dependency for one Bitmap allocation.
internal fun createFrameworkJpegEncoder(
    request: ImageEncoderRequest,
    quality: Int,
    compress: (bitmap: Bitmap, quality: Int, output: OutputStream) -> Boolean = ::compressFrameworkJpeg,
): ImageEncoder {
    validateFrameworkJpegRequest(request)
    val requiredByteCount = checkedRequiredByteCount(
        height = request.height,
        rowStrideBytes = request.rowStrideBytes,
        width = request.width,
    ) ?: throw ImageEncoderUnavailableException("Framework JPEG raw input byte span overflowed.", null)
    if (requiredByteCount > Int.MAX_VALUE) {
        throw ImageEncoderUnavailableException("Framework JPEG raw input byte span exceeds ByteBuffer limits.", null)
    }

    var bitmap: Bitmap? = null
    var prepared = false
    try {
        val rowPixels = IntArray(request.width)
        val createdBitmap = Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888)
        bitmap = createdBitmap
        createdBitmap.setHasAlpha(false)
        val encoder = FrameworkJpegEncoder(
            request = request,
            quality = quality,
            bitmap = createdBitmap,
            rowPixels = rowPixels,
            compress = compress,
        )
        prepared = true
        return encoder
    } catch (exception: ImageEncoderUnavailableException) {
        throw exception
    } catch (exception: Exception) {
        throw ImageEncoderUnavailableException("Framework JPEG backend preparation failed.", exception)
    } finally {
        if (!prepared) {
            bitmap?.recycle()
        }
    }
}

private fun validateFrameworkJpegRequest(request: ImageEncoderRequest) {
    if (request.inputFormat != ImageEncoderInputFormat.Rgba8888SrgbOpaque) {
        throw ImageEncoderUnavailableException("Framework JPEG requires RGBA8888 sRGB opaque input.", null)
    }
    if (request.rowStrideBytes.toLong() < request.width.toLong() * RGBA_8888_BYTES_PER_PIXEL) {
        throw ImageEncoderUnavailableException("Framework JPEG row stride is incompatible with width.", null)
    }
}

private class FrameworkJpegEncoder(
    request: ImageEncoderRequest,
    private val quality: Int,
    private val bitmap: Bitmap,
    private val rowPixels: IntArray,
    private val compress: (bitmap: Bitmap, quality: Int, output: OutputStream) -> Boolean,
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

            copyRowsToBitmap(input.buffer)
            val stream = EncodedImageSinkOutputStream(output)
            val compressed = try {
                compress(bitmap, quality, stream)
            } catch (throwable: Throwable) {
                stream.resultForSinkOutcome()?.let { result ->
                    return@synchronized result
                }
                throw throwable
            }

            stream.resultForSinkOutcome() ?: when {
                compressed -> ImageEncodeResult.Success
                else -> ImageEncodeResult.Failed("Framework JPEG compression failed.", null)
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

    private fun copyRowsToBitmap(buffer: ByteBuffer) {
        repeat(height) { y ->
            var sourceIndex = y * rowStrideBytes
            repeat(width) { x ->
                val red = buffer.get(sourceIndex).toInt() and 0xFF
                val green = buffer.get(sourceIndex + 1).toInt() and 0xFF
                val blue = buffer.get(sourceIndex + 2).toInt() and 0xFF
                rowPixels[x] = OPAQUE_ALPHA_MASK or (red shl 16) or (green shl 8) or blue
                sourceIndex += RGBA_8888_BYTES_PER_PIXEL_INT
            }
            bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1)
        }
    }
}

private class EncodedImageSinkOutputStream(
    private val sink: EncodedImageSink,
) : OutputStream() {
    private val singleByte = ByteArray(1)
    private var rejected = false
    private var failure: Throwable? = null

    override fun write(oneByte: Int) {
        singleByte[0] = oneByte.toByte()
        write(singleByte, 0, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        if (failure != null) {
            throw EncodedImageSinkWriteException("Encoded sink had already thrown.")
        }
        if (rejected) {
            throw EncodedImageSinkWriteException("Encoded sink was already rejected.")
        }

        val accepted = try {
            sink.write(buffer, offset, count)
        } catch (throwable: Throwable) {
            failure = throwable
            throw EncodedImageSinkWriteException("Encoded sink threw while writing framework JPEG bytes.")
        }
        if (!accepted) {
            rejected = true
            throw EncodedImageSinkWriteException("Encoded sink rejected framework JPEG bytes.")
        }
    }

    fun resultForSinkOutcome(): ImageEncodeResult? {
        val sinkFailure = failure
        if (sinkFailure != null) {
            return if (sinkFailure is Exception) {
                ImageEncodeResult.Failed("Framework JPEG sink write failed.", sinkFailure)
            } else {
                throw sinkFailure
            }
        }
        return if (rejected) {
            ImageEncodeResult.Failed("Framework JPEG sink write failed.", null)
        } else {
            null
        }
    }
}

private class EncodedImageSinkWriteException(
    message: String,
) : RuntimeException(message)

private fun compressFrameworkJpeg(bitmap: Bitmap, quality: Int, output: OutputStream): Boolean =
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)

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

private const val RGBA_8888_BYTES_PER_PIXEL: Long = 4L
private const val RGBA_8888_BYTES_PER_PIXEL_INT: Int = 4
private const val OPAQUE_ALPHA_MASK: Int = -0x1000000
