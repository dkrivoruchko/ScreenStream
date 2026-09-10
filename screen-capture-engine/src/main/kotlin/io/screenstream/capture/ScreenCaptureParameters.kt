package io.screenstream.capture

import androidx.annotation.CheckResult
import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import io.screenstream.capture.FrameRate.Companion.MAX_FPS_RANGE
import io.screenstream.capture.FrameRate.Companion.SAMPLING_INTERVAL_RANGE
import io.screenstream.capture.ScreenCaptureParameters.Companion.JPEG_QUALITY_RANGE
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Immutable parameters requested for screen capture and JPEG output.
 *
 * Source selection, crop, rotation, mirror, and output sizing are applied in that order. Instances
 * use structural equality across every property, and all nested parameter values are immutable.
 * Pre-JPEG output is opaque, top-down RGBA using a nominal SDR/sRGB interpretation, and JPEG rows
 * retain that top-down orientation. The engine rejects an explicitly observed Display P3 source on
 * API 33 and later; it does not otherwise promise generic gamut or HDR detection or conversion.
 *
 * @property sourceRegion source area selected before crop and transforms. Defaults to
 *     [SourceRegion.Full].
 * @property crop nonnegative pixel insets in the unrotated selected-region coordinate space.
 *     Defaults to [CropInsetsPx.ZERO].
 * @property outputSize requested final-image sizing policy. Defaults to a factor of `0.5`.
 * @property rotation clockwise rotation applied after crop. Defaults to [Rotation.Degrees0].
 * @property mirror reflection in the already-rotated image. Defaults to [Mirror.None].
 * @property colorMode output color conversion. Defaults to [ColorMode.Color].
 * @property frameRate frame-production policy. [FrameRate.MaxFps] limits fresh-frame admission and output commits;
 *     [FrameRate.SamplingInterval] limits fresh-frame admission only. Defaults to [FrameRate.Auto].
 * @property jpegQuality JPEG encoder quality hint in [JPEG_QUALITY_RANGE]. Defaults to `80`.
 *     Changing it invalidates payload bytes encoded at the previous quality. Different encoders or
 *     devices need not produce identical bytes for the same value.
 * @throws IllegalArgumentException if [jpegQuality] is outside its valid range.
 */
public class ScreenCaptureParameters(
    public val sourceRegion: SourceRegion = SourceRegion.Full,
    public val crop: CropInsetsPx = CropInsetsPx.ZERO,
    public val outputSize: OutputSize = OutputSize.ScaleFactor(0.5),
    public val rotation: Rotation = Rotation.Degrees0,
    public val mirror: Mirror = Mirror.None,
    public val colorMode: ColorMode = ColorMode.Color,
    public val frameRate: FrameRate = FrameRate.Auto,
    @param:IntRange(from = 0, to = 100) @get:IntRange(from = 0, to = 100) public val jpegQuality: Int = 80,
) {
    init {
        require(jpegQuality in JPEG_QUALITY_RANGE) { "jpegQuality must be in $JPEG_QUALITY_RANGE" }
    }

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
                (jpegQuality == other.jpegQuality)
    }

    public override fun hashCode(): Int {
        var result: Int = sourceRegion.hashCode()
        result = (31 * result) + crop.hashCode()
        result = (31 * result) + outputSize.hashCode()
        result = (31 * result) + rotation.hashCode()
        result = (31 * result) + mirror.hashCode()
        result = (31 * result) + colorMode.hashCode()
        result = (31 * result) + frameRate.hashCode()
        result = (31 * result) + jpegQuality.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureParameters(" +
                "sourceRegion=$sourceRegion, " +
                "crop=$crop, " +
                "outputSize=$outputSize, " +
                "rotation=$rotation, " +
                "mirror=$mirror, " +
                "colorMode=$colorMode, " +
                "frameRate=$frameRate, " +
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
     * @param frameRate replacement frame-production policy. [FrameRate.MaxFps] limits fresh-frame
     *     admission and output commits; [FrameRate.SamplingInterval] limits fresh-frame admission only.
     * @param jpegQuality replacement JPEG quality hint.
     * @return a new [ScreenCaptureParameters] containing the supplied and retained values.
     * @throws IllegalArgumentException if a replacement value violates a locally validated range.
     */
    @CheckResult
    public fun copy(
        sourceRegion: SourceRegion = this.sourceRegion,
        crop: CropInsetsPx = this.crop,
        outputSize: OutputSize = this.outputSize,
        rotation: Rotation = this.rotation,
        mirror: Mirror = this.mirror,
        colorMode: ColorMode = this.colorMode,
        frameRate: FrameRate = this.frameRate,
        @IntRange(from = 0, to = 100) jpegQuality: Int = this.jpegQuality,
    ): ScreenCaptureParameters = ScreenCaptureParameters(
        sourceRegion = sourceRegion,
        crop = crop,
        outputSize = outputSize,
        rotation = rotation,
        mirror = mirror,
        colorMode = colorMode,
        frameRate = frameRate,
        jpegQuality = jpegQuality,
    )

    public companion object {
        /** Inclusive valid range for [ScreenCaptureParameters.jpegQuality]. */
        public val JPEG_QUALITY_RANGE: kotlin.ranges.IntRange = 0..100

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
 * Immutable structural nonnegative crop insets measured in pixels in the unrotated selected-region coordinate space.
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
    @param:IntRange(from = 0) @get:IntRange(from = 0) public val left: Int,
    @param:IntRange(from = 0) @get:IntRange(from = 0) public val top: Int,
    @param:IntRange(from = 0) @get:IntRange(from = 0) public val right: Int,
    @param:IntRange(from = 0) @get:IntRange(from = 0) public val bottom: Int,
) {
    init {
        require(left >= 0) { "left must be non-negative" }
        require(top >= 0) { "top must be non-negative" }
        require(right >= 0) { "right must be non-negative" }
        require(bottom >= 0) { "bottom must be non-negative" }
    }

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CropInsetsPx) return false

        return (left == other.left) && (top == other.top) && (right == other.right) && (bottom == other.bottom)
    }

    public override fun hashCode(): Int {
        var result: Int = left.hashCode()
        result = (31 * result) + top.hashCode()
        result = (31 * result) + right.hashCode()
        result = (31 * result) + bottom.hashCode()
        return result
    }

    public override fun toString(): String = "CropInsetsPx(left=$left, top=$top, right=$right, bottom=$bottom)"

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
    public class ScaleFactor(
        @param:FloatRange(from = 0.0, fromInclusive = false, to = Double.MAX_VALUE)
        @get:FloatRange(from = 0.0, fromInclusive = false, to = Double.MAX_VALUE)
        public val factor: Double,
    ) : OutputSize {
        init {
            require(factor.isFinite() && (factor > 0.0)) { "factor must be finite and positive" }
        }

        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ScaleFactor) return false

            return factor == other.factor
        }

        public override fun hashCode(): Int = factor.hashCode()

        public override fun toString(): String = "ScaleFactor(factor=$factor)"
    }

    /**
     * Derives final image dimensions from positive target bounds.
     *
     * The default [ContentMode.AspectFit] preserves the oriented aspect ratio without padding, so
     * one final dimension may be smaller than its bound. For example, a `1920×1080` image fitted
     * within `1280×1280` becomes `1280×720`. [ContentMode.Stretch] uses the exact supplied width
     * and height and can distort the image.
     *
     * @property widthPx positive target width in pixels.
     * @property heightPx positive target height in pixels.
     * @property contentMode policy for fitting content into the target. Defaults to
     *     [ContentMode.AspectFit].
     * @throws IllegalArgumentException if [widthPx] or [heightPx] is not positive.
     */
    public class TargetSize(
        @param:IntRange(from = 1) @get:IntRange(from = 1) public val widthPx: Int,
        @param:IntRange(from = 1) @get:IntRange(from = 1) public val heightPx: Int,
        public val contentMode: ContentMode = ContentMode.AspectFit,
    ) : OutputSize {
        init {
            require(widthPx > 0) { "widthPx must be positive" }
            require(heightPx > 0) { "heightPx must be positive" }
        }

        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TargetSize) return false

            return (widthPx == other.widthPx) && (heightPx == other.heightPx) && (contentMode == other.contentMode)
        }

        public override fun hashCode(): Int {
            var result: Int = widthPx.hashCode()
            result = (31 * result) + heightPx.hashCode()
            result = (31 * result) + contentMode.hashCode()
            return result
        }

        public override fun toString(): String =
            "TargetSize(widthPx=$widthPx, heightPx=$heightPx, contentMode=$contentMode)"
    }

    /** Policy for deriving final image dimensions from [TargetSize] bounds. */
    public enum class ContentMode {
        /** Uses the exact target width and height, allowing aspect-ratio distortion. */
        Stretch,

        /**
         * Preserves the oriented aspect ratio within the target bounds and adds no padding, so one
         * final dimension may be smaller than its bound.
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
 * Color conversion applied after source handling and output sizing. The opaque, top-down pre-JPEG
 * RGBA image uses a nominal SDR/sRGB interpretation, and JPEG rows retain that orientation. An
 * explicitly observed Display P3 source is rejected on API 33 and later; other gamut and HDR
 * detection or conversion is outside this contract.
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

/**
 * Frame-production policy whose variants are immutable structural values.
 *
 * [MaxFps] caps fresh-frame admission and output commits. [SamplingInterval] limits only fresh-frame admission.
 */
public sealed interface FrameRate {

    /** Admits fresh frames at the available source and processing-capacity pace. */
    public data object Auto : FrameRate

    /**
     * Caps fresh-frame admission and output commits to at most [fps].
     *
     * The cap does not guarantee that frames are produced at that rate.
     *
     * @property fps maximum frames per second in [MAX_FPS_RANGE].
     * @throws IllegalArgumentException if [fps] is outside [MAX_FPS_RANGE].
     */
    public class MaxFps(
        @param:IntRange(from = 1, to = 120) @get:IntRange(from = 1, to = 120) public val fps: Int,
    ) : FrameRate {
        init {
            require(fps in MAX_FPS_RANGE) { "fps must be in $MAX_FPS_RANGE" }
        }

        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MaxFps) return false

            return fps == other.fps
        }

        public override fun hashCode(): Int = fps.hashCode()

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

        public override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SamplingInterval) return false

            return interval == other.interval
        }

        public override fun hashCode(): Int = interval.hashCode()

        public override fun toString(): String = "SamplingInterval(interval=$interval)"
    }

    public companion object {
        /** Inclusive valid range for [MaxFps.fps]. */
        public val MAX_FPS_RANGE: kotlin.ranges.IntRange = 1..120

        /** Inclusive valid range, `1,000` through `3,600,000` milliseconds, for fresh sampling. */
        public val SAMPLING_INTERVAL_RANGE: ClosedRange<Duration> = 1_000.milliseconds..3_600_000.milliseconds
    }
}
