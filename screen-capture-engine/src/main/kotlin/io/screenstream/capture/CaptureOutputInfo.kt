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

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageRect) return false

        return (leftPx == other.leftPx) && (topPx == other.topPx) && (rightPx == other.rightPx) && (bottomPx == other.bottomPx)
    }

    public override fun hashCode(): Int {
        var result: Int = leftPx.hashCode()
        result = (31 * result) + topPx.hashCode()
        result = (31 * result) + rightPx.hashCode()
        result = (31 * result) + bottomPx.hashCode()
        return result
    }

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

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureGeometry) return false

        return (widthPx == other.widthPx) && (heightPx == other.heightPx) && (densityDpi == other.densityDpi)
    }

    public override fun hashCode(): Int {
        var result: Int = widthPx.hashCode()
        result = (31 * result) + heightPx.hashCode()
        result = (31 * result) + densityDpi.hashCode()
        return result
    }

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

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageSize) return false

        return (widthPx == other.widthPx) && (heightPx == other.heightPx)
    }

    public override fun hashCode(): Int {
        var result: Int = widthPx.hashCode()
        result = (31 * result) + heightPx.hashCode()
        return result
    }

    public override fun toString(): String = "ImageSize(widthPx=$widthPx, heightPx=$heightPx)"

    internal companion object {
        @JvmSynthetic
        internal fun create(widthPx: Int, heightPx: Int): ImageSize = ImageSize(widthPx, heightPx)
    }
}

/**
 * Immutable description of one applied output plan.
 *
 * [ScreenCaptureState.Active] can expose this plan before any JPEG is produced. When read from
 * [EncodedFrame.outputInfo], it describes the plan associated with that frame's exact JPEG bytes.
 * This value is not reusable configuration. Instances use structural equality across all properties.
 *
 * @property parameters requested parameters applied to this output.
 * @property captureGeometry authoritative unrotated capture geometry.
 * @property appliedSourceRect selected and cropped rectangle in [captureGeometry] coordinates,
 *     before rotation and mirror. It is entirely contained within [captureGeometry]: its right and
 *     bottom coordinates do not exceed the capture width and height.
 * @property finalImageSize encoded dimensions after rotation, mirror, and output sizing.
 */
public class CaptureOutputInfo private constructor(
    public val parameters: ScreenCaptureParameters,
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

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureOutputInfo) return false

        return (parameters == other.parameters) &&
                (captureGeometry == other.captureGeometry) &&
                (appliedSourceRect == other.appliedSourceRect) &&
                (finalImageSize == other.finalImageSize)
    }

    public override fun hashCode(): Int {
        var result: Int = parameters.hashCode()
        result = (31 * result) + captureGeometry.hashCode()
        result = (31 * result) + appliedSourceRect.hashCode()
        result = (31 * result) + finalImageSize.hashCode()
        return result
    }

    public override fun toString(): String =
        "CaptureOutputInfo(" +
                "parameters=$parameters, " +
                "captureGeometry=$captureGeometry, " +
                "appliedSourceRect=$appliedSourceRect, " +
                "finalImageSize=$finalImageSize)"

    internal companion object {
        @JvmSynthetic
        internal fun create(
            parameters: ScreenCaptureParameters,
            captureGeometry: CaptureGeometry,
            appliedSourceRect: ImageRect,
            finalImageSize: ImageSize,
        ): CaptureOutputInfo = CaptureOutputInfo(
            parameters = parameters,
            captureGeometry = captureGeometry,
            appliedSourceRect = appliedSourceRect,
            finalImageSize = finalImageSize,
        )
    }
}
