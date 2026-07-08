package dev.dmkr.screencaptureengine

import java.nio.ByteBuffer

/**
 * Public synchronous encoder provider SPI.
 *
 * Providers are lightweight, immutable, and thread-safe. Created [ImageEncoder] instances are scoped to a session/output plan and are called only by the
 * engine encoder context. Provider output is opaque to the engine except for byte count and declared [outputFormat].
 */
public interface ImageEncoderProvider {
    /** Stable provider identifier used in diagnostics and effective parameters. */
    public val id: String

    /** Encoded image format produced by encoders from this provider. */
    public val outputFormat: EncodedImageFormat

    /**
     * Creates a plan-scoped encoder for the requested raw input shape and encoded cap.
     *
     * Throws [ImageEncoderUnavailableException] when the provider cannot create a usable encoder
     * for the request. Provider implementations may also throw runtime exceptions; the engine
     * maps provider failures to session problem surfaces.
     */
    public fun createEncoder(request: ImageEncoderRequest): ImageEncoder
}

/**
 * Plan-scoped synchronous image encoder.
 *
 * Implementations must consume [ImageEncoderInput] fully during [encode] and must not retain raw buffers, sinks, or row pointers after the call returns.
 * Providers must not call engine public APIs from [encode] and must not require the engine GL context.
 */
public interface ImageEncoder {
    /** Provider/backend information exposed in effective parameters. */
    public val info: ImageEncoderInfo

    /** Encodes one borrowed raw input into [output]. */
    public fun encode(input: ImageEncoderInput, output: EncodedImageSink): ImageEncodeResult

    /** Releases encoder-owned resources. */
    public fun close()
}

/**
 * Request used to create an encoder for one output plan.
 *
 * The values describe the raw RGBA input shape that the engine will pass to [ImageEncoder.encode] and the encoded-size cap the provider must respect.
 */
public class ImageEncoderRequest public constructor(
    /** Final image width in pixels. */
    public val width: Int,

    /** Final image height in pixels. */
    public val height: Int,

    /** Raw input row stride in bytes. */
    public val rowStrideBytes: Int,

    /** Maximum encoded bytes accepted by the engine for one frame. */
    public val maxEncodedBytes: Int,

    /** Raw input pixel format supplied to the provider. */
    public val inputFormat: ImageEncoderInputFormat,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(rowStrideBytes > 0) { "rowStrideBytes must be positive, was $rowStrideBytes" }
        require(maxEncodedBytes in MAX_ENCODED_BYTES_RANGE) {
            "maxEncodedBytes must be in $MAX_ENCODED_BYTES_RANGE, was $maxEncodedBytes"
        }
        require(rowStrideBytes.toLong() >= width.toLong() * RGBA_8888_BYTES_PER_PIXEL) {
            "rowStrideBytes must be at least width * 4"
        }
    }

    public override fun equals(other: Any?): Boolean =
        other is ImageEncoderRequest && width == other.width && height == other.height && rowStrideBytes == other.rowStrideBytes &&
                maxEncodedBytes == other.maxEncodedBytes && inputFormat == other.inputFormat

    public override fun hashCode(): Int = 31 * (31 * (31 * (31 * width + height) + rowStrideBytes) + maxEncodedBytes) + inputFormat.hashCode()
}

/**
 * Borrowed raw image input visible only to encoder providers.
 *
 * Normal frame consumers never receive this raw RGBA view. The [buffer] contents are sensitive screen data and are valid only during the synchronous
 * [ImageEncoder.encode] call.
 */
public interface ImageEncoderInput {
    /** Raw image width in pixels. */
    public val width: Int

    /** Raw image height in pixels. */
    public val height: Int

    /** Raw input row stride in bytes. */
    public val rowStrideBytes: Int

    /** Borrowed raw pixel buffer valid only during [ImageEncoder.encode]. */
    public val buffer: ByteBuffer

    /** Raw pixel format of [buffer]. */
    public val format: ImageEncoderInputFormat
}

/**
 * Bounded encoded-output sink owned by the engine.
 *
 * A write returning false means the output would exceed [maxByteCount]; providers should stop encoding and return [ImageEncodeResult.Failed]. Providers must
 * write encoded bytes only through this sink.
 */
public interface EncodedImageSink {
    /** Encoded bytes written so far. */
    public val byteCount: Int

    /** Maximum encoded bytes accepted by this sink. */
    public val maxByteCount: Int

    /** Writes bytes from an array, returning false if the cap would be exceeded. */
    public fun write(source: ByteArray, offset: Int, byteCount: Int): Boolean

    /** Writes bytes from a [ByteBuffer], returning false if the cap would be exceeded. */
    public fun write(source: ByteBuffer, byteCount: Int): Boolean
}

/** Result of one synchronous encode attempt. Provider exceptions are mapped by the engine. */
public sealed interface ImageEncodeResult {
    /** Encoding completed and bytes were written within the sink cap. */
    public data object Success : ImageEncodeResult

    /** Encoding failed without throwing through the engine boundary. */
    public class Failed public constructor(public val message: String? = null, public val cause: Throwable? = null) : ImageEncodeResult {
        public override fun equals(other: Any?): Boolean = other is Failed && message == other.message && cause == other.cause

        public override fun hashCode(): Int = 31 * (message?.hashCode() ?: 0) + (cause?.hashCode() ?: 0)
    }
}

/** Public description of the provider/backend selected for an output plan. */
public class ImageEncoderInfo public constructor(
    /** Stable provider identifier. */
    public val providerId: String,

    /** Encoded image format produced by the provider. */
    public val outputFormat: EncodedImageFormat,

    /** Optional concrete backend name, such as a framework or native JPEG backend. */
    public val backendName: String?,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(outputFormat.name.isNotBlank()) { "outputFormat.name must not be blank" }
        require(outputFormat.mimeType.isNotBlank()) { "outputFormat.mimeType must not be blank" }
    }

    public override fun equals(other: Any?): Boolean =
        other is ImageEncoderInfo && providerId == other.providerId && outputFormat == other.outputFormat && backendName == other.backendName

    public override fun hashCode(): Int = 31 * (31 * providerId.hashCode() + outputFormat.hashCode()) + (backendName?.hashCode() ?: 0)
}

private const val RGBA_8888_BYTES_PER_PIXEL: Long = 4L
private val MAX_ENCODED_BYTES_RANGE: IntRange = 1_024..268_435_456
