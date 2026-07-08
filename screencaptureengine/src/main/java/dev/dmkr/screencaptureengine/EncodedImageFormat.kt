package dev.dmkr.screencaptureengine

/**
 * Opaque single-image byte format descriptor.
 *
 * Apps may provide formats other than JPEG by declaring stable descriptors, but the engine does not parse, transcode, or guarantee interoperability for those
 * bytes.
 */
public class EncodedImageFormat public constructor(
    /** Stable human-readable format name. */
    public val name: String,

    /** MIME type for encoded bytes of this format. */
    public val mimeType: String,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }
    }

    public override fun equals(other: Any?): Boolean = other is EncodedImageFormat && name == other.name && mimeType == other.mimeType

    public override fun hashCode(): Int = 31 * name.hashCode() + mimeType.hashCode()
}

/** Built-in encoded image formats known to the engine. */
public object EncodedImageFormats {
    /** Baseline JPEG still image format. */
    public val Jpeg: EncodedImageFormat = EncodedImageFormat(name = "JPEG", mimeType = "image/jpeg")
}

/** Raw input formats that the engine may pass to encoder providers. */
public enum class ImageEncoderInputFormat {
    /** Opaque 8-bit RGBA in sRGB space. Alpha is not semantically preserved. */
    Rgba8888SrgbOpaque,
}

/** Thrown when an encoder provider cannot create a usable encoder for a request. */
public class ImageEncoderUnavailableException public constructor(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
