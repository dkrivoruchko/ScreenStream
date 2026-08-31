package io.screenstream.capture

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Immutable parameters requested for screen capture and JPEG output.
 *
 * Source selection, crop, rotation, mirror, and output sizing are applied in that order. Instances
 * use structural equality across every property, and all nested parameter values are immutable.
 * Pre-JPEG output is opaque, top-down SDR/sRGB RGBA, and JPEG rows retain that top-down orientation.
 *
 * @property sourceRegion source area selected before crop and transforms. Defaults to
 *     [SourceRegion.Full].
 * @property crop nonnegative pixel insets in the unrotated selected-region coordinate space.
 *     Defaults to [CropInsetsPx.ZERO].
 * @property outputSize requested final-image sizing policy. Defaults to a factor of `0.5`.
 * @property rotation clockwise rotation applied after crop. Defaults to [Rotation.Degrees0].
 * @property mirror reflection in the already-rotated image. Defaults to [Mirror.None].
 * @property colorMode output color conversion. Defaults to [ColorMode.Color].
 * @property frameRate fresh-frame admission policy. Defaults to [FrameRate.Auto].
 * @property frameRepeatInterval optional best-effort maximum-silence interval for republishing the
 *     cached JPEG payload. `null`, the default, disables repeat output. A non-null value must be in
 *     [FRAME_REPEAT_INTERVAL_RANGE]. Repeat is not a deadline and may be delayed by [FrameRate.MaxFps].
 * @property jpegQuality JPEG encoder quality hint in [JPEG_QUALITY_RANGE]. Defaults to `80`.
 *     Changing it invalidates payload bytes encoded at the previous quality. Different encoders or
 *     devices need not produce identical bytes for the same value.
 * @throws IllegalArgumentException if [frameRepeatInterval] or [jpegQuality] is outside its valid
 *     range.
 */
public class ScreenCaptureParameters(
    public val sourceRegion: SourceRegion = SourceRegion.Full,
    public val crop: CropInsetsPx = CropInsetsPx.ZERO,
    public val outputSize: OutputSize = OutputSize.ScaleFactor(0.5),
    public val rotation: Rotation = Rotation.Degrees0,
    public val mirror: Mirror = Mirror.None,
    public val colorMode: ColorMode = ColorMode.Color,
    public val frameRate: FrameRate = FrameRate.Auto,
    public val frameRepeatInterval: Duration? = null,
    public val jpegQuality: Int = 80,
) {
    init {
        require((frameRepeatInterval == null) || (frameRepeatInterval in FRAME_REPEAT_INTERVAL_RANGE)) {
            "frameRepeatInterval must be null or in $FRAME_REPEAT_INTERVAL_RANGE"
        }
        require(jpegQuality in JPEG_QUALITY_RANGE) { "jpegQuality must be in $JPEG_QUALITY_RANGE" }
    }

    /** Compares every parameter property for structural equality. */
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureParameters) return false

        return (sourceRegion == other.sourceRegion) &&
                (crop == other.crop) &&
                (outputSize == other.outputSize) &&
                (rotation == other.rotation) &&
                (mirror == other.mirror) &&
                (colorMode == other.colorMode) &&
                (frameRate == other.frameRate) &&
                (frameRepeatInterval == other.frameRepeatInterval) &&
                (jpegQuality == other.jpegQuality)
    }

    /** Returns a hash code derived from every parameter property. */
    public override fun hashCode(): Int {
        var result: Int = sourceRegion.hashCode()
        result = (31 * result) + crop.hashCode()
        result = (31 * result) + outputSize.hashCode()
        result = (31 * result) + rotation.hashCode()
        result = (31 * result) + mirror.hashCode()
        result = (31 * result) + colorMode.hashCode()
        result = (31 * result) + frameRate.hashCode()
        result = (31 * result) + (frameRepeatInterval?.hashCode() ?: 0)
        result = (31 * result) + jpegQuality.hashCode()
        return result
    }

    /** Returns a bounded, non-sensitive debug representation whose format is unspecified. */
    public override fun toString(): String =
        "ScreenCaptureParameters(" +
                "sourceRegion=$sourceRegion, " +
                "crop=$crop, " +
                "outputSize=$outputSize, " +
                "rotation=$rotation, " +
                "mirror=$mirror, " +
                "colorMode=$colorMode, " +
                "frameRate=$frameRate, " +
                "frameRepeatInterval=$frameRepeatInterval, " +
                "jpegQuality=$jpegQuality)"

    /**
     * Creates a new validated instance with selected properties replaced.
     *
     * Omitted properties retain their current values. The copy reuses immutable component values;
     * it does not recursively duplicate them.
     *
     * @param sourceRegion replacement source region.
     * @param crop replacement crop insets.
     * @param outputSize replacement output-sizing policy.
     * @param rotation replacement clockwise rotation.
     * @param mirror replacement oriented-image mirror.
     * @param colorMode replacement color mode.
     * @param frameRate replacement fresh-frame admission policy.
     * @param frameRepeatInterval replacement repeat interval, or `null` to disable repeat output.
     * @param jpegQuality replacement JPEG quality hint.
     * @return a new [ScreenCaptureParameters] containing the supplied and retained values.
     * @throws IllegalArgumentException if a replacement value violates a locally validated range.
     */
    public fun copy(
        sourceRegion: SourceRegion = this.sourceRegion,
        crop: CropInsetsPx = this.crop,
        outputSize: OutputSize = this.outputSize,
        rotation: Rotation = this.rotation,
        mirror: Mirror = this.mirror,
        colorMode: ColorMode = this.colorMode,
        frameRate: FrameRate = this.frameRate,
        frameRepeatInterval: Duration? = this.frameRepeatInterval,
        jpegQuality: Int = this.jpegQuality,
    ): ScreenCaptureParameters = ScreenCaptureParameters(
        sourceRegion = sourceRegion,
        crop = crop,
        outputSize = outputSize,
        rotation = rotation,
        mirror = mirror,
        colorMode = colorMode,
        frameRate = frameRate,
        frameRepeatInterval = frameRepeatInterval,
        jpegQuality = jpegQuality,
    )

    /** Shared parameter ranges and the default parameter value. */
    public companion object {
        /** Inclusive valid range for [ScreenCaptureParameters.jpegQuality]. */
        public val JPEG_QUALITY_RANGE: IntRange = 0..100

        /** Inclusive valid range, `1,000` through `3,600,000` milliseconds, for repeat output. */
        public val FRAME_REPEAT_INTERVAL_RANGE: ClosedRange<Duration> = 1_000.milliseconds..3_600_000.milliseconds

        /**
         * Deeply immutable default value, structurally equal to [ScreenCaptureParameters] constructed
         * with no arguments.
         */
        public val DEFAULT: ScreenCaptureParameters = ScreenCaptureParameters()
    }
}

/** Source area selected in authoritative capture coordinates before crop and other transforms. */
public enum class SourceRegion {
    /** The complete capture area. */
    Full,

    /**
     * The left half, spanning `x = 0` until `width / 2`.
     *
     * A half-width selection requires an authoritative width of at least two pixels.
     */
    LeftHalf,

    /**
     * The right half, spanning `x = width / 2` until `width`.
     *
     * For an odd width this region owns the final column. A half-width selection requires an
     * authoritative width of at least two pixels.
     */
    RightHalf,
}

/**
 * Nonnegative crop insets measured in pixels in the unrotated selected-region coordinate space.
 *
 * Insets select content; they are not a privacy-redaction boundary. Whether the insets leave
 * nonempty content is validated later against authoritative capture geometry.
 *
 * @property left pixels removed from the selected region's left edge.
 * @property top pixels removed from the selected region's top edge.
 * @property right pixels removed from the selected region's right edge.
 * @property bottom pixels removed from the selected region's bottom edge.
 * @throws IllegalArgumentException if any inset is negative.
 */
public class CropInsetsPx(
    public val left: Int,
    public val top: Int,
    public val right: Int,
    public val bottom: Int,
) {
    init {
        require(left >= 0) { "left must be non-negative" }
        require(top >= 0) { "top must be non-negative" }
        require(right >= 0) { "right must be non-negative" }
        require(bottom >= 0) { "bottom must be non-negative" }
    }

    /** Compares all four insets for structural equality. */
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CropInsetsPx) return false

        return (left == other.left) && (top == other.top) && (right == other.right) && (bottom == other.bottom)
    }

    /** Returns a hash code derived from all four insets. */
    public override fun hashCode(): Int {
        var result: Int = left.hashCode()
        result = (31 * result) + top.hashCode()
        result = (31 * result) + right.hashCode()
        result = (31 * result) + bottom.hashCode()
        return result
    }

    /** Returns a bounded, non-sensitive debug representation whose format is unspecified. */
    public override fun toString(): String = "CropInsetsPx(left=$left, top=$top, right=$right, bottom=$bottom)"

    /** Shared crop-inset values. */
    public companion object {
        /** No crop on any edge. */
        public val ZERO: CropInsetsPx = CropInsetsPx(left = 0, top = 0, right = 0, bottom = 0)
    }
}

/**
 * Final-image sizing policy applied after source selection, crop, rotation, and mirror.
 *
 * Implementations are immutable structural values. Geometry-dependent arithmetic that cannot
 * produce valid dimensions is reported by the session as [ScreenCaptureProblem.InvalidRequest].
 */
public sealed interface OutputSize {

    /**
     * Scales both oriented dimensions by [factor].
     *
     * Each dimension is calculated in binary64 as `floor(dimension * factor + 0.5)`. A finite result
     * in `0..Int.MAX_VALUE` is clamped only to a minimum of one pixel; a nonfinite or out-of-range
     * result is reported by the session as [ScreenCaptureProblem.InvalidRequest].
     *
     * @property factor finite factor strictly greater than zero.
     * @throws IllegalArgumentException if [factor] is non-finite or not positive.
     */
    public class ScaleFactor(public val factor: Double) : OutputSize {
        init {
            require(factor.isFinite() && (factor > 0.0)) { "factor must be finite and positive" }
        }

        /** Compares [factor] for structural equality. */
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ScaleFactor) return false

            return factor == other.factor
        }

        /** Returns the hash code of [factor]. */
        public override fun hashCode(): Int = factor.hashCode()

        /** Returns a bounded, non-sensitive debug representation whose format is unspecified. */
        public override fun toString(): String = "ScaleFactor(factor=$factor)"
    }

    /**
     * Sizes output relative to positive target dimensions.
     *
     * @property widthPx positive target width in pixels.
     * @property heightPx positive target height in pixels.
     * @property contentMode policy for fitting content into the target. Defaults to
     *     [ContentMode.AspectFit].
     * @throws IllegalArgumentException if [widthPx] or [heightPx] is not positive.
     */
    public class TargetSize(
        public val widthPx: Int,
        public val heightPx: Int,
        public val contentMode: ContentMode = ContentMode.AspectFit,
    ) : OutputSize {
        init {
            require(widthPx > 0) { "widthPx must be positive" }
            require(heightPx > 0) { "heightPx must be positive" }
        }

        /** Compares target dimensions and [contentMode] for structural equality. */
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TargetSize) return false

            return (widthPx == other.widthPx) && (heightPx == other.heightPx) && (contentMode == other.contentMode)
        }

        /** Returns a hash code derived from target dimensions and [contentMode]. */
        public override fun hashCode(): Int {
            var result: Int = widthPx.hashCode()
            result = (31 * result) + heightPx.hashCode()
            result = (31 * result) + contentMode.hashCode()
            return result
        }

        /** Returns a bounded, non-sensitive debug representation whose format is unspecified. */
        public override fun toString(): String =
            "TargetSize(widthPx=$widthPx, heightPx=$heightPx, contentMode=$contentMode)"
    }

    /** Policy for placing the oriented image within [TargetSize] dimensions. */
    public enum class ContentMode {
        /** Uses the target width and height exactly, allowing aspect-ratio distortion. */
        Stretch,

        /**
         * Preserves the oriented aspect ratio, subject to integer-pixel rounding, within the target
         * bounds and without padding.
         *
         * A dimension not fixed at its bound is rounded to the nearest pixel and clamped to at
         * least one pixel.
         */
        AspectFit,
    }
}

/** Clockwise rotation applied after source selection and crop. */
public enum class Rotation {
    /** No rotation. */
    Degrees0,

    /** A 90-degree clockwise rotation; exchanges oriented width and height. */
    Degrees90,

    /** A 180-degree clockwise rotation. */
    Degrees180,

    /** A 270-degree clockwise rotation; exchanges oriented width and height. */
    Degrees270,
}

/** Reflection applied in the coordinate space of the already-rotated image. */
public enum class Mirror {
    /** No reflection. */
    None,

    /** Reflects left and right in the rotated image. */
    Horizontal,

    /** Reflects top and bottom in the rotated image. */
    Vertical,
}

/**
 * Color conversion applied to the SDR/sRGB pre-JPEG image after source handling and output sizing.
 *
 * Shader precision and lossy JPEG encoding do not promise bit-exact decoded channel values.
 */
public enum class ColorMode {
    /** Leaves the quantized gamma-coded pre-JPEG RGB channels unchanged. */
    Color,

    /**
     * Applies opaque gamma-coded grayscale to the pre-JPEG image.
     *
     * For quantized pre-JPEG channels `R`, `G`, and `B`, the reference grayscale RGB value is
     * `(77 * R + 150 * G + 29 * B + 128) shr 8`.
     */
    Grayscale,
}

/** Fresh-frame admission policy whose variants are immutable structural values. */
public sealed interface FrameRate {

    /** Admits fresh frames at the available source and processing-capacity pace. */
    public data object Auto : FrameRate

    /**
     * Caps fresh-frame admission and all produced output, including repeats, to at most [fps].
     *
     * The cap does not guarantee that frames are produced at that rate.
     *
     * @property fps maximum frames per second in [MAX_FPS_RANGE].
     * @throws IllegalArgumentException if [fps] is outside [MAX_FPS_RANGE].
     */
    public class MaxFps(public val fps: Int) : FrameRate {
        init {
            require(fps in MAX_FPS_RANGE) { "fps must be in $MAX_FPS_RANGE" }
        }

        /** Compares [fps] for structural equality. */
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MaxFps) return false

            return fps == other.fps
        }

        /** Returns the hash code of [fps]. */
        public override fun hashCode(): Int = fps.hashCode()

        /** Returns a bounded, non-sensitive debug representation whose format is unspecified. */
        public override fun toString(): String = "MaxFps(fps=$fps)"
    }

    /**
     * Samples fresh source frames no more often than [interval].
     *
     * The first eligible source frame is admitted immediately; later fresh samples follow the
     * interval.
     *
     * @property interval sampling interval in [SAMPLING_INTERVAL_RANGE].
     * @throws IllegalArgumentException if [interval] is outside [SAMPLING_INTERVAL_RANGE].
     */
    public class SamplingInterval(public val interval: Duration) : FrameRate {
        init {
            require(interval in SAMPLING_INTERVAL_RANGE) {
                "interval must be in $SAMPLING_INTERVAL_RANGE"
            }
        }

        /** Compares [interval] for structural equality. */
        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SamplingInterval) return false

            return interval == other.interval
        }

        /** Returns the hash code of [interval]. */
        public override fun hashCode(): Int = interval.hashCode()

        /** Returns a bounded, non-sensitive debug representation whose format is unspecified. */
        public override fun toString(): String = "SamplingInterval(interval=$interval)"
    }

    /** Shared valid ranges for explicit frame-rate policies. */
    public companion object {
        /** Inclusive valid range for [MaxFps.fps]. */
        public val MAX_FPS_RANGE: IntRange = 1..120

        /** Inclusive valid range, `1,001` through `3,600,000` milliseconds, for fresh sampling. */
        public val SAMPLING_INTERVAL_RANGE: ClosedRange<Duration> = 1_001.milliseconds..3_600_000.milliseconds
    }
}
