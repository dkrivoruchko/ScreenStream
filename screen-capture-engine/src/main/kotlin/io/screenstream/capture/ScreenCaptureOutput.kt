package io.screenstream.capture

/**
 * Nonempty rectangle in authoritative, unrotated capture-image coordinates.
 *
 * Left and top coordinates are inclusive; right and bottom coordinates are exclusive. Instances
 * use structural equality across all coordinates.
 *
 * @property leftPx inclusive horizontal start, at least zero.
 * @property topPx inclusive vertical start, at least zero.
 * @property rightPx exclusive horizontal end, greater than [leftPx].
 * @property bottomPx exclusive vertical end, greater than [topPx].
 */
public class ImageRect private constructor(
    public val leftPx: Int,
    public val topPx: Int,
    public val rightPx: Int,
    public val bottomPx: Int,
) {
    init {
        require(leftPx >= 0) { "leftPx must be non-negative" }
        require(topPx >= 0) { "topPx must be non-negative" }
        require(rightPx > leftPx) { "rightPx must be greater than leftPx" }
        require(bottomPx > topPx) { "bottomPx must be greater than topPx" }
    }

    /** Compares all four coordinates for structural equality. */
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageRect) return false

        return (leftPx == other.leftPx) && (topPx == other.topPx) && (rightPx == other.rightPx) && (bottomPx == other.bottomPx)
    }

    /** Returns a hash code derived from all four coordinates. */
    public override fun hashCode(): Int {
        var result: Int = leftPx.hashCode()
        result = (31 * result) + topPx.hashCode()
        result = (31 * result) + rightPx.hashCode()
        result = (31 * result) + bottomPx.hashCode()
        return result
    }

    /** Returns a bounded, non-sensitive debug representation whose format is unspecified. */
    public override fun toString(): String =
        "ImageRect(leftPx=$leftPx, topPx=$topPx, rightPx=$rightPx, bottomPx=$bottomPx)"

    internal companion object {
        @JvmSynthetic
        internal fun create(leftPx: Int, topPx: Int, rightPx: Int, bottomPx: Int): ImageRect =
            ImageRect(leftPx, topPx, rightPx, bottomPx)
    }
}

/**
 * Authoritative committed capture geometry used to produce an output frame.
 *
 * Dimensions describe the unrotated capture-image coordinate space. Instances use structural
 * equality across all properties.
 *
 * @property widthPx positive capture width in pixels.
 * @property heightPx positive capture height in pixels.
 * @property densityDpi positive capture density in dots per inch.
 */
public class CaptureGeometry private constructor(
    public val widthPx: Int,
    public val heightPx: Int,
    public val densityDpi: Int,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive" }
        require(heightPx > 0) { "heightPx must be positive" }
        require(densityDpi > 0) { "densityDpi must be positive" }
    }

    /** Compares width, height, and density for structural equality. */
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureGeometry) return false

        return (widthPx == other.widthPx) && (heightPx == other.heightPx) && (densityDpi == other.densityDpi)
    }

    /** Returns a hash code derived from width, height, and density. */
    public override fun hashCode(): Int {
        var result: Int = widthPx.hashCode()
        result = (31 * result) + heightPx.hashCode()
        result = (31 * result) + densityDpi.hashCode()
        return result
    }

    /** Returns a bounded, non-sensitive debug representation whose format is unspecified. */
    public override fun toString(): String =
        "CaptureGeometry(widthPx=$widthPx, heightPx=$heightPx, densityDpi=$densityDpi)"

    internal companion object {
        @JvmSynthetic
        internal fun create(widthPx: Int, heightPx: Int, densityDpi: Int): CaptureGeometry =
            CaptureGeometry(widthPx, heightPx, densityDpi)
    }
}

/**
 * Positive final encoded-image dimensions.
 *
 * Instances use structural equality across both dimensions.
 *
 * @property widthPx final image width in pixels.
 * @property heightPx final image height in pixels.
 */
public class ImageSize private constructor(
    public val widthPx: Int,
    public val heightPx: Int,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive" }
        require(heightPx > 0) { "heightPx must be positive" }
    }

    /** Compares both dimensions for structural equality. */
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageSize) return false

        return (widthPx == other.widthPx) && (heightPx == other.heightPx)
    }

    /** Returns a hash code derived from both dimensions. */
    public override fun hashCode(): Int {
        var result: Int = widthPx.hashCode()
        result = (31 * result) + heightPx.hashCode()
        return result
    }

    /** Returns a bounded, non-sensitive debug representation whose format is unspecified. */
    public override fun toString(): String = "ImageSize(widthPx=$widthPx, heightPx=$heightPx)"

    internal companion object {
        @JvmSynthetic
        internal fun create(widthPx: Int, heightPx: Int): ImageSize = ImageSize(widthPx, heightPx)
    }
}

/**
 * Immutable description of the parameters and geometry committed for an output frame.
 *
 * This value observes actual output; it is not reusable configuration. Instances use structural
 * equality across all properties.
 *
 * @property appliedParameters requested parameters applied to this output.
 * @property captureGeometry authoritative unrotated capture geometry.
 * @property appliedSourceRect selected and cropped rectangle in [captureGeometry] coordinates,
 *     before rotation and mirror. It is entirely contained within [captureGeometry]: its right and
 *     bottom coordinates do not exceed the capture width and height.
 * @property finalImageSize encoded dimensions after rotation, mirror, and output sizing.
 */
public class ScreenCaptureEffectiveParameters private constructor(
    public val appliedParameters: ScreenCaptureParameters,
    public val captureGeometry: CaptureGeometry,
    public val appliedSourceRect: ImageRect,
    public val finalImageSize: ImageSize,
) {
    init {
        require(appliedSourceRect.rightPx <= captureGeometry.widthPx) {
            "appliedSourceRect.rightPx must not exceed capture width"
        }
        require(appliedSourceRect.bottomPx <= captureGeometry.heightPx) {
            "appliedSourceRect.bottomPx must not exceed capture height"
        }
    }

    /** Compares every effective-output property for structural equality. */
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureEffectiveParameters) return false

        return (appliedParameters == other.appliedParameters) &&
                (captureGeometry == other.captureGeometry) &&
                (appliedSourceRect == other.appliedSourceRect) &&
                (finalImageSize == other.finalImageSize)
    }

    /** Returns a hash code derived from every effective-output property. */
    public override fun hashCode(): Int {
        var result: Int = appliedParameters.hashCode()
        result = (31 * result) + captureGeometry.hashCode()
        result = (31 * result) + appliedSourceRect.hashCode()
        result = (31 * result) + finalImageSize.hashCode()
        return result
    }

    /** Returns a bounded, non-sensitive debug representation whose format is unspecified. */
    public override fun toString(): String =
        "ScreenCaptureEffectiveParameters(" +
                "appliedParameters=$appliedParameters, " +
                "captureGeometry=$captureGeometry, " +
                "appliedSourceRect=$appliedSourceRect, " +
                "finalImageSize=$finalImageSize)"

    internal companion object {
        @JvmSynthetic
        internal fun create(
            appliedParameters: ScreenCaptureParameters,
            captureGeometry: CaptureGeometry,
            appliedSourceRect: ImageRect,
            finalImageSize: ImageSize,
        ): ScreenCaptureEffectiveParameters = ScreenCaptureEffectiveParameters(
            appliedParameters = appliedParameters,
            captureGeometry = captureGeometry,
            appliedSourceRect = appliedSourceRect,
            finalImageSize = finalImageSize,
        )
    }
}

/**
 * Callback-scoped access to one complete encoded JPEG frame and its immutable metadata.
 *
 * This object is borrowed only for the body of the frame-consumer callback that receives it and
 * only on the exact thread running that callback. Every public frame property and copy function
 * throws [IllegalStateException] from another thread or after the callback returns. The frame has
 * identity semantics and must not be retained for later access; retain a caller-owned byte copy
 * from [toByteArray] or [copyTo] instead.
 */
public class EncodedImageFrame private constructor(private val access: Access) {
    internal interface Access {
        fun byteCount(): Int

        fun effectiveParameters(): ScreenCaptureEffectiveParameters

        fun sequence(): Long

        fun timestampElapsedRealtimeNanos(): Long

        fun copyTo(destination: ByteArray, destinationOffset: Int): Int

        fun toByteArray(): ByteArray
    }

    /**
     * Positive byte count of the complete JPEG payload.
     *
     * @throws IllegalStateException if accessed outside the receiving callback body or from a
     *     different thread.
     */
    public val byteCount: Int
        get() = access.byteCount()

    /**
     * Immutable parameters and geometry committed with this payload.
     *
     * Cached delivery retains the original value; repeat output receives the descriptor current at
     * that repeat commit.
     *
     * @throws IllegalStateException if accessed outside the receiving callback body or from a
     *     different thread.
     */
    public val effectiveParameters: ScreenCaptureEffectiveParameters
        get() = access.effectiveParameters()

    /**
     * Positive sequence number local to the capture session, starting at one and never wrapping or
     * repeating.
     *
     * Fresh and repeat commits receive new values; cached delivery preserves the original value.
     * Sequence exhaustion is reported by the session as [ScreenCaptureProblem.InternalFailure].
     *
     * @throws IllegalStateException if accessed outside the receiving callback body or from a
     *     different thread.
     */
    public val sequence: Long
        get() = access.sequence()

    /**
     * Nonnegative commit timestamp from the elapsed-realtime clock, in nanoseconds.
     *
     * Equal timestamps are valid. Fresh and repeat commits receive a new timestamp; cached delivery
     * preserves the original timestamp.
     *
     * @throws IllegalStateException if accessed outside the receiving callback body or from a
     *     different thread.
     */
    public val timestampElapsedRealtimeNanos: Long
        get() = access.timestampElapsedRealtimeNanos()

    /**
     * Copies the complete JPEG payload into [destination] starting at [destinationOffset].
     *
     * The entire destination range is validated before writing. On success this writes and returns
     * exactly [byteCount] bytes; on an invalid range [destination] remains unchanged.
     *
     * @param destination caller-owned array that receives the payload.
     * @param destinationOffset first destination index, defaulting to zero.
     * @return the number of bytes copied, equal to [byteCount].
     * @throws IllegalStateException if called outside the receiving callback body or from a
     *     different thread.
     * @throws IndexOutOfBoundsException if [destinationOffset] is negative or the complete payload
     *     does not fit in [destination].
     */
    public fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int =
        access.copyTo(destination, destinationOffset)

    /**
     * Returns an exact caller-owned copy of the complete JPEG payload.
     *
     * The returned array has size [byteCount] and may outlive the callback. Allocation or copy
     * failure does not mutate the engine-owned payload bytes.
     *
     * @return a newly allocated byte array containing the frame payload.
     * @throws IllegalStateException if called outside the receiving callback body or from a
     *     different thread.
     */
    public fun toByteArray(): ByteArray = access.toByteArray()

    /** Encoded-frame constants. */
    public companion object {
        /** MIME type of every [EncodedImageFrame] payload. */
        public const val JPEG_MIME_TYPE: String = "image/jpeg"

        @JvmSynthetic
        internal fun create(access: Access): EncodedImageFrame = EncodedImageFrame(access)
    }
}
