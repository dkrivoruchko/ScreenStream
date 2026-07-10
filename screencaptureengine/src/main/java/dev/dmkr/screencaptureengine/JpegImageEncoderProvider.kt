package dev.dmkr.screencaptureengine

import dev.dmkr.screencaptureengine.internal.encoding.jpeg.createFrameworkJpegEncoder

/**
 * JPEG encoder provider API.
 *
 * JPEG output is opaque 8-bit SDR/sRGB; source alpha is not preserved. The built-in framework
 * backend accepts canonical top-to-bottom [ImageEncoderInputFormat.Rgba8888SrgbOpaque] input and
 * converts RGBA bytes to opaque ARGB pixels before framework compression.
 */
public class JpegImageEncoderProvider : ImageEncoderProvider {
    /** JPEG quality in the Android/Bitmap-style 0..100 range. */
    public val quality: Int

    /** Backend selection policy for the built-in JPEG provider. */
    public val backendPolicy: JpegEncoderBackendPolicy

    public constructor(
        quality: Int = 80,
        backendPolicy: JpegEncoderBackendPolicy = JpegEncoderBackendPolicy.Auto,
    ) {
        this.quality = quality
        this.backendPolicy = backendPolicy
        require(quality in JPEG_QUALITY_RANGE) { "quality must be in $JPEG_QUALITY_RANGE, was $quality" }
    }

    public override val id: String = "jpeg"
    public override val outputFormat: EncodedImageFormat = EncodedImageFormats.Jpeg

    public override fun createEncoder(request: ImageEncoderRequest): ImageEncoder {
        val backend = when (backendPolicy) {
            JpegEncoderBackendPolicy.Auto,
            JpegEncoderBackendPolicy.FrameworkOnly,
                -> JpegEncoderBackend.FrameworkBitmapCompress
        }
        return when (backend) {
            JpegEncoderBackend.FrameworkBitmapCompress -> createFrameworkJpegEncoder(request, quality)
            JpegEncoderBackend.NdkAndroidBitmapCompress -> throw ImageEncoderUnavailableException(
                "Native Android Bitmap JPEG backend is unavailable.",
                null,
            )
        }
    }

    public override fun equals(other: Any?): Boolean =
        other is JpegImageEncoderProvider && quality == other.quality && backendPolicy == other.backendPolicy

    public override fun hashCode(): Int = 31 * quality + backendPolicy.hashCode()

}

/** Backend selection policy for [JpegImageEncoderProvider]. */
public enum class JpegEncoderBackendPolicy {
    /** Request the best validated backend. */
    Auto,

    /** Request only the framework Bitmap-compress backend. */
    FrameworkOnly,
}

/** Concrete JPEG backend selected for diagnostics/effective parameters. */
public enum class JpegEncoderBackend {
    /** Android framework Bitmap compression path. */
    FrameworkBitmapCompress,

    /** Native Android Bitmap compression path. */
    NdkAndroidBitmapCompress,
}

private val JPEG_QUALITY_RANGE: IntRange = 0..100
