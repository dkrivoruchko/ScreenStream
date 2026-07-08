package dev.dmkr.screencaptureengine

/**
 * Logical source region selected before crop, rotation, and output scaling.
 *
 * Coordinates are expressed in logical captured-content pixels, before any internal capture target downscale. This is a view-selection control, not a
 * privacy/redaction boundary.
 */
public sealed interface SourceRegion {
    /** Full logical captured content. */
    public data object Full : SourceRegion

    /** Left half split at width / 2. */
    public data object LeftHalf : SourceRegion

    /** Right half split at width / 2; receives the extra pixel for odd widths. */
    public data object RightHalf : SourceRegion
}

/** How a target output rectangle is resolved from selected content. */
public sealed interface ContentMode {
    /** Fill the requested target dimensions without preserving aspect ratio. */
    public data object Stretch : ContentMode

    /** Fit within the requested target bounds while preserving aspect ratio and adding no padding. */
    public data object AspectFit : ContentMode
}

/** Clockwise rotation applied after source selection and crop. */
public enum class Rotation {
    Degrees0,
    Degrees90,
    Degrees180,
    Degrees270,
}

/** Mirror transform applied as part of final output rendering. */
public enum class Mirror {
    None,
    Horizontal,
    Vertical,
}

/** Final color transform applied before encoding. */
public enum class ColorMode {
    Original,
    Grayscale,
}

/** Readback implementation selected by the engine for an output plan. */
public enum class ReadbackMode {
    /** Required OpenGL ES 2.0 framebuffer plus glReadPixels path. */
    Es2,

    /** Optional OpenGL ES 3.x PBO accelerated readback path. */
    Es3Pbo,
}

/** Source that produced the current capture geometry. */
public enum class CaptureGeometrySource {
    /** Geometry came directly from the metrics provider. */
    MetricsProvider,

    /** Geometry came from metrics but is provisional until platform resize is authoritative. */
    MetricsProviderProvisional,

    /** Geometry came from MediaProjection captured-content resize callback. */
    CapturedContentResize,
}

/** Positive pixel size. */
public class Size public constructor(public val width: Int, public val height: Int) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
    }

    public override fun equals(other: Any?): Boolean = other is Size && width == other.width && height == other.height

    public override fun hashCode(): Int = 31 * width + height
}

/** Rectangle in image coordinates, using left/top inclusive and right/bottom exclusive edges. */
public class ImageRect public constructor(
    public val left: Int,
    public val top: Int,
    public val right: Int,
    public val bottom: Int,
) {
    /** Rectangle width in pixels. */
    public val width: Int
        get() = right - left

    /** Rectangle height in pixels. */
    public val height: Int
        get() = bottom - top

    init {
        require(left >= 0) { "left must be non-negative, was $left" }
        require(top >= 0) { "top must be non-negative, was $top" }
        require(right >= left) { "right must be greater than or equal to left" }
        require(bottom >= top) { "bottom must be greater than or equal to top" }
    }

    public override fun equals(other: Any?): Boolean =
        other is ImageRect && left == other.left && top == other.top && right == other.right && bottom == other.bottom

    public override fun hashCode(): Int = 31 * (31 * (31 * left + top) + right) + bottom
}

/**
 * Pixel crop insets applied to the selected [SourceRegion].
 *
 * Insets are interpreted in logical captured-content pixels and each value must be in `0..32768`.
 * If crop makes the selected rect empty, the plan is invalid: startup fails, a parameter update is rejected, or running output becomes suspended after a
 * geometry change.
 */
public class CropInsetsPx public constructor(
    public val left: Int = 0,
    public val top: Int = 0,
    public val right: Int = 0,
    public val bottom: Int = 0,
) {
    init {
        require(left in CROP_INSET_RANGE) { "left must be in $CROP_INSET_RANGE, was $left" }
        require(top in CROP_INSET_RANGE) { "top must be in $CROP_INSET_RANGE, was $top" }
        require(right in CROP_INSET_RANGE) { "right must be in $CROP_INSET_RANGE, was $right" }
        require(bottom in CROP_INSET_RANGE) { "bottom must be in $CROP_INSET_RANGE, was $bottom" }
    }

    public override fun equals(other: Any?): Boolean =
        other is CropInsetsPx && left == other.left && top == other.top && right == other.right && bottom == other.bottom

    public override fun hashCode(): Int = 31 * (31 * (31 * left + top) + right) + bottom

    public companion object {
        /** No crop. */
        public val Zero: CropInsetsPx = CropInsetsPx()
    }
}

/**
 * Requested final output sizing policy.
 *
 * Output sizing is resolved after source selection, crop, and rotation. Large requested outputs
 * may be rejected by configured caps or device/runtime limits; the engine does not enlarge the
 * projection capture target just to satisfy final-output upscale. Positive finite dimensions use
 * `floor(value + 0.5)`, then clamp to at least 1 px only after a non-empty source rect exists.
 */
public sealed interface OutputSize {
    /** Scales selected oriented content by [factor] in `0.10..2.00` using positive-size rounding. */
    public class ScaleFactor public constructor(public val factor: Double) : OutputSize {
        init {
            require(factor.isFinite() && factor in SCALE_FACTOR_RANGE) { "factor must be finite and in $SCALE_FACTOR_RANGE, was $factor" }
        }

        public override fun equals(other: Any?): Boolean = other is ScaleFactor && factor == other.factor

        public override fun hashCode(): Int = factor.hashCode()
    }

    /**
     * Resolves output within or exactly to explicit [width] x [height] dimensions. Dimensions must be in `1..32768`.
     *
     * [ContentMode.Stretch] produces exactly the target size. [ContentMode.AspectFit] treats
     * the target as max bounds, preserves aspect ratio, and adds no padding.
     */
    public class TargetSize public constructor(
        public val width: Int,
        public val height: Int,
        public val contentMode: ContentMode,
    ) : OutputSize {
        init {
            require(width in TARGET_SIZE_RANGE) { "width must be in $TARGET_SIZE_RANGE, was $width" }
            require(height in TARGET_SIZE_RANGE) { "height must be in $TARGET_SIZE_RANGE, was $height" }
        }

        public override fun equals(other: Any?): Boolean =
            other is TargetSize && width == other.width && height == other.height && contentMode == other.contentMode

        public override fun hashCode(): Int = 31 * (31 * width + height) + contentMode.hashCode()
    }
}

/**
 * Frame production policy requested by the owner.
 *
 * Frame rate controls production opportunities, not consumer queues. Slow consumers cause
 * delivery drops and do not make the engine build a backlog.
 */
public sealed interface FrameRate {
    /** Publishes at most [fps] frames per second from source frame availability; [fps] must be in `1..120`. */
    public class MaxFps public constructor(
        public val fps: Int,
    ) : FrameRate {
        init {
            require(fps in MAX_FPS_RANGE) { "fps must be in $MAX_FPS_RANGE, was $fps" }
        }

        public override fun equals(other: Any?): Boolean = other is MaxFps && fps == other.fps

        public override fun hashCode(): Int = fps
    }

    /**
     * Requests periodic refresh publication opportunities for static sources.
     *
     * [intervalMillis] must be in `1000..300000`; lifecycle state and backpressure may still skip a refresh opportunity.
     */
    public class PeriodicRefresh public constructor(public val intervalMillis: Long) : FrameRate {
        init {
            require(intervalMillis in PERIODIC_REFRESH_INTERVAL_RANGE) {
                "intervalMillis must be in $PERIODIC_REFRESH_INTERVAL_RANGE, was $intervalMillis"
            }
        }

        public override fun equals(other: Any?): Boolean = other is PeriodicRefresh && intervalMillis == other.intervalMillis

        public override fun hashCode(): Int = intervalMillis.hashCode()
    }

    /** Engine-defined bounded frame-rate policy; concrete pacing is resolved before production starts. */
    public data object Auto : FrameRate
}

/**
 * Metrics emitted by [CaptureMetricsProvider].
 *
 * Metrics are mandatory even when API 34+ captured-content resize becomes authoritative, because density and bootstrap geometry still need an owner-context
 * source.
 */
public class CaptureMetrics public constructor(
    /** Logical width in pixels. */
    public val widthPx: Int,

    /** Logical height in pixels. */
    public val heightPx: Int,

    /** Density used for virtual-display sizing. */
    public val densityDpi: Int,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive, was $widthPx" }
        require(heightPx > 0) { "heightPx must be positive, was $heightPx" }
        require(densityDpi > 0) { "densityDpi must be positive, was $densityDpi" }
    }

    public override fun equals(other: Any?): Boolean =
        other is CaptureMetrics && widthPx == other.widthPx && heightPx == other.heightPx && densityDpi == other.densityDpi

    public override fun hashCode(): Int = 31 * (31 * widthPx + heightPx) + densityDpi
}

/**
 * Current logical capture geometry used for planning.
 *
 * The [source] records whether geometry came from metrics, provisional metrics, or the authoritative MediaProjection captured-content resize callback.
 */
public class CaptureGeometry public constructor(
    public val widthPx: Int,
    public val heightPx: Int,
    public val densityDpi: Int,
    public val source: CaptureGeometrySource,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive, was $widthPx" }
        require(heightPx > 0) { "heightPx must be positive, was $heightPx" }
        require(densityDpi > 0) { "densityDpi must be positive, was $densityDpi" }
    }

    public override fun equals(other: Any?): Boolean =
        other is CaptureGeometry && widthPx == other.widthPx && heightPx == other.heightPx && densityDpi == other.densityDpi && source == other.source

    public override fun hashCode(): Int = 31 * (31 * (31 * widthPx + heightPx) + densityDpi) + source.hashCode()
}

/**
 * Actual projection target surface size selected by planning.
 *
 * This target represents the whole logical captured content, possibly downscaled before GL source selection and final output rendering. Integer target
 * dimensions may quantize the two axes differently; [scaleFromLogicalCapture] reports the effective minimum axis scale.
 */
public class CaptureTarget public constructor(
    /** Capture target width in pixels. */
    public val width: Int,

    /** Capture target height in pixels. */
    public val height: Int,

    /** Effective minimum scale from logical captured content to capture target. */
    public val scaleFromLogicalCapture: Double,

    /** True when the target is smaller than logical captured content for early downscale. */
    public val isEarlyDownscaled: Boolean,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(scaleFromLogicalCapture.isFinite() && scaleFromLogicalCapture > 0.0 && scaleFromLogicalCapture <= 1.0) {
            "scaleFromLogicalCapture must be finite and in (0.0, 1.0], was $scaleFromLogicalCapture"
        }
    }

    public override fun equals(other: Any?): Boolean =
        other is CaptureTarget && width == other.width && height == other.height && scaleFromLogicalCapture == other.scaleFromLogicalCapture &&
                isEarlyDownscaled == other.isEarlyDownscaled

    public override fun hashCode(): Int = 31 * (31 * (31 * width + height) + scaleFromLogicalCapture.hashCode()) + isEarlyDownscaled.hashCode()
}

private val CROP_INSET_RANGE: IntRange = 0..32768
private val SCALE_FACTOR_RANGE: ClosedFloatingPointRange<Double> = 0.10..2.00
private val TARGET_SIZE_RANGE: IntRange = 1..32768
private val MAX_FPS_RANGE: IntRange = 1..120
private val PERIODIC_REFRESH_INTERVAL_RANGE: LongRange = 1_000L..300_000L
