package dev.dmkr.screencaptureengine

/**
 * Logical source region selected before crop, rotation, and output scaling.
 *
 * Coordinates are expressed in logical captured-content pixels, before any internal capture
 * target downscale. This is a view-selection control, not a privacy/redaction boundary.
 */
public sealed interface SourceRegion {
    /** Full logical captured content. */
    public object Full : SourceRegion

    /** Left half split at width / 2. */
    public object LeftHalf : SourceRegion

    /** Right half split at width / 2; receives the extra pixel for odd widths. */
    public object RightHalf : SourceRegion
}

/** How a target output rectangle is resolved from selected content. */
public sealed interface ContentMode {
    /** Fill the requested target dimensions without preserving aspect ratio. */
    public object Stretch : ContentMode

    /** Fit within the requested target bounds while preserving aspect ratio and adding no padding. */
    public object AspectFit : ContentMode
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
public class Size public constructor(
    public val width: Int,
    public val height: Int,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
    }
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
}

/**
 * Pixel crop insets applied to the selected [SourceRegion].
 *
 * Insets are interpreted in logical captured-content pixels. If crop makes the selected rect
 * empty, the plan is invalid: startup fails, a parameter update is rejected, or running output
 * becomes suspended after a geometry change.
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
    /** Scales selected oriented content by [factor] using positive-size rounding. */
    public class ScaleFactor public constructor(
        public val factor: Double,
    ) : OutputSize {
        init {
            require(factor.isFinite() && factor in SCALE_FACTOR_RANGE) {
                "factor must be finite and in $SCALE_FACTOR_RANGE, was $factor"
            }
        }
    }

    /**
     * Resolves output within or exactly to explicit [width] x [height] dimensions.
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
    }
}

/**
 * Frame production policy requested by the owner.
 *
 * Frame rate controls production opportunities, not consumer queues. Slow consumers cause
 * delivery drops and do not make the engine build a backlog.
 */
public sealed interface FrameRate {
    /** Publishes at most [fps] frames per second from source frame availability. */
    public class MaxFps public constructor(
        public val fps: Int,
    ) : FrameRate {
        init {
            require(fps in MAX_FPS_RANGE) { "fps must be in $MAX_FPS_RANGE, was $fps" }
        }
    }

    /** Requests periodic refresh publication opportunities for static sources, subject to lifecycle and backpressure. */
    public class PeriodicRefresh public constructor(
        public val intervalMillis: Long,
    ) : FrameRate {
        init {
            require(intervalMillis in PERIODIC_REFRESH_INTERVAL_RANGE) {
                "intervalMillis must be in $PERIODIC_REFRESH_INTERVAL_RANGE, was $intervalMillis"
            }
        }
    }

    /** Engine-defined bounded frame-rate policy. */
    public object Auto : FrameRate
}

/**
 * Metrics emitted by [CaptureMetricsProvider].
 *
 * Metrics are mandatory even when API 34+ captured-content resize becomes authoritative,
 * because density and bootstrap geometry still need an owner-context source.
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
}

/**
 * Current logical capture geometry used for planning.
 *
 * The [source] records whether geometry came from metrics, provisional metrics, or the
 * authoritative MediaProjection captured-content resize callback.
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
}

/**
 * Actual projection target surface size selected by planning.
 *
 * This target represents the whole logical captured content, possibly uniformly downscaled
 * before GL source selection and final output rendering.
 */
public class CaptureTarget public constructor(
    /** Capture target width in pixels. */
    public val width: Int,

    /** Capture target height in pixels. */
    public val height: Int,

    /** Uniform scale from logical captured content to capture target. */
    public val scaleFromLogicalCapture: Double,

    /** True when the target is smaller than logical captured content for early downscale. */
    public val isEarlyDownscaled: Boolean,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(scaleFromLogicalCapture.isFinite() && scaleFromLogicalCapture > 0.0) {
            "scaleFromLogicalCapture must be finite and positive, was $scaleFromLogicalCapture"
        }
    }
}

/**
 * User-requested capture/render/encode parameters.
 *
 * These values describe desired behavior. [ScreenCaptureEffectiveParameters] reports what was
 * actually planned for the current geometry and device/runtime capabilities. The values are
 * applied atomically by [ScreenCaptureSession.setParameters].
 */
public class ScreenCaptureParameters public constructor(
    /** Logical source region selected before crop. */
    public val sourceRegion: SourceRegion = SourceRegion.Full,

    /** Logical pixel crop applied inside [sourceRegion]. */
    public val crop: CropInsetsPx = CropInsetsPx.Zero,

    /** Final encoded image sizing policy. */
    public val outputSize: OutputSize = OutputSize.ScaleFactor(1.0),

    /** Rotation applied after source selection and crop. */
    public val rotation: Rotation = Rotation.Degrees0,

    /** Mirror transform applied during final rendering. */
    public val mirror: Mirror = Mirror.None,

    /** Final color transform before encoding. */
    public val colorMode: ColorMode = ColorMode.Original,

    /** Production pacing policy. */
    public val frameRate: FrameRate = FrameRate.Auto,

    /** Synchronous encoded-image provider. */
    public val encoderProvider: ImageEncoderProvider = JpegImageEncoderProvider(),
) {
    init {
        require(encoderProvider.id.isNotBlank()) { "encoderProvider.id must not be blank" }
        require(encoderProvider.outputFormat.name.isNotBlank()) { "encoderProvider.outputFormat.name must not be blank" }
        require(encoderProvider.outputFormat.mimeType.isNotBlank()) {
            "encoderProvider.outputFormat.mimeType must not be blank"
        }
    }

    public companion object {
        /** Default parameter set. */
        public fun defaults(): ScreenCaptureParameters = ScreenCaptureParameters()
    }
}

/**
 * Effective parameters after validation, geometry resolution, and backend selection.
 *
 * These are the authoritative public coordinates and dimensions for active output. In
 * particular, [appliedSourceRect] is always reported in logical captured-content coordinates
 * before capture-target sampling, even when [captureTarget] is downscaled.
 */
public class ScreenCaptureEffectiveParameters public constructor(
    /** Geometry used to derive this plan. */
    public val captureGeometry: CaptureGeometry,

    /** Projection target selected for this plan. */
    public val captureTarget: CaptureTarget,

    /** Source region requested by the owner. */
    public val sourceRegion: SourceRegion,

    /** Crop requested by the owner. */
    public val crop: CropInsetsPx,

    /** Final selected source rect in logical captured-content coordinates. */
    public val appliedSourceRect: ImageRect,

    /** Source size after crop and rotation, before final output sizing. */
    public val orientedContentSize: Size,

    /** Output sizing policy requested by the owner. */
    public val outputSize: OutputSize,

    /** Final encoded image dimensions. */
    public val finalImageSize: Size,

    /** Rotation applied by this plan. */
    public val rotation: Rotation,

    /** Mirror transform applied by this plan. */
    public val mirror: Mirror,

    /** Color transform applied by this plan. */
    public val colorMode: ColorMode,

    /** Readback backend selected by this plan. */
    public val readbackMode: ReadbackMode,

    /** Encoder provider/backend selected by this plan. */
    public val encoderInfo: ImageEncoderInfo,

    /** Frame-rate policy applied by this plan. */
    public val frameRate: FrameRate,
)

/** Result of an atomic parameter update request. */
public sealed interface ScreenCaptureParameterUpdateResult {
    /** New parameters were fully applied. */
    public object Applied : ScreenCaptureParameterUpdateResult

    /** New parameters were rejected; previous active parameters remain in use. */
    public class Rejected public constructor(
        public val problem: ScreenCaptureProblem,
    ) : ScreenCaptureParameterUpdateResult
}

/**
 * Public lifecycle state of a session.
 *
 * [Running] means the projection/session is alive. Its output can still be [ScreenCaptureOutputState.Suspended],
 * in which case no new frames are published until parameters or geometry become valid again.
 */
public sealed interface ScreenCaptureSessionState {
    /** Session is alive; output is either active or suspended. */
    public class Running public constructor(
        public val output: ScreenCaptureOutputState,

        /** Visibility of captured content when the platform reports it, otherwise null. */
        public val capturedContentVisible: Boolean?,
    ) : ScreenCaptureSessionState

    /** Session ended normally from owner/system capture stop. Terminal and non-restartable. */
    public class Stopped public constructor(
        public val reason: ScreenCaptureStopReason,
        public val problem: ScreenCaptureProblem?,
    ) : ScreenCaptureSessionState

    /** Session ended due to a fatal engine/platform problem. Terminal and non-restartable. */
    public class Failed public constructor(
        public val problem: ScreenCaptureProblem,
    ) : ScreenCaptureSessionState
}

/** Current output status while the session is running. */
public sealed interface ScreenCaptureOutputState {
    /** Output plan is active and may publish frames. */
    public class Active public constructor(
        public val effectiveParameters: ScreenCaptureEffectiveParameters,
    ) : ScreenCaptureOutputState

    /**
     * Output plan is unavailable; no new frames are published until resumed.
     *
     * The projection remains alive. Existing latest output is retired for new deliveries, and
     * [previousEffectiveParameters] describes the last active plan.
     */
    public class Suspended public constructor(
        public val problem: ScreenCaptureProblem,
        public val previousEffectiveParameters: ScreenCaptureEffectiveParameters,
        public val currentCaptureGeometry: CaptureGeometry,
    ) : ScreenCaptureOutputState
}

/**
 * Structured problem used in exceptions, state, update results, and diagnostics.
 *
 * [kind] is stable, but severity is contextual: the same kind can appear in startup failure,
 * rejected parameter update, running suspension, terminal failure, or diagnostics.
 */
public class ScreenCaptureProblem public constructor(
    /** Monotonic problem sequence for ordering diagnostics. */
    public val sequence: Long,

    /** Stable technical classification. */
    public val kind: ScreenCaptureProblemKind,

    /** Optional diagnostic text, not a parsing contract. */
    public val message: String?,

    /** Optional underlying cause. */
    public val cause: Throwable?,
) {
    init {
        require(sequence >= 0L) { "sequence must be non-negative, was $sequence" }
    }
}

/**
 * Stable technical classification for public problems.
 *
 * This enum does not encode severity. Owners should interpret it together with the surface
 * carrying the problem: startup exception, rejected update, suspended output, failed state, or
 * diagnostic event.
 */
public enum class ScreenCaptureProblemKind {
    ProjectionInvalidOrStopped,
    ProjectionPermissionDenied,
    ProjectionSessionReused,
    VirtualDisplayCreateFailed,
    StartupGeometryUnavailable,

    MetricsUnavailableOrInvalid,
    OutputPlanInvalid,
    OutputLimitsExceeded,

    SurfaceCreateOrResizeFailed,
    GlInitializationFailed,
    GlResourceFailure,
    GlInvariantViolation,

    ReadbackUnavailable,
    ReadbackRepeatedFailure,
    PboValidationOrInvariantFailure,

    EncoderUnavailable,
    EncoderValidationFailed,
    EncodeRepeatedFailure,
    EncodedSizeLimitExceeded,

    AllocationFailed,
    MemoryPressure,

    FrameDeliveryFailed,
    FrameCallbackThrew,

    InternalInvariantViolation,
}

/** Terminal stop reason for non-failed session completion. */
public enum class ScreenCaptureStopReason {
    /** Owner called stop or close. */
    OwnerStop,

    /** Platform/user ended the projection. */
    CaptureEnded,
}

/**
 * Session counters, rates, byte sizes, and delivery pressure.
 *
 * Production drops and delivery drops are separate: one encoded frame can be published
 * successfully even if one or more subscriptions skip delivery.
 */
public class ScreenCaptureStats public constructor(
    /** Successful encoder outputs before stale-generation and encoded-size publication checks. */
    public val framesEncoded: Long = 0L,

    /** Successful replacements of the engine's internal latest encoded frame. */
    public val framesPublished: Long = 0L,

    /** Production opportunities/results that did not become the internal latest frame. */
    public val droppedFrames: ScreenCaptureFrameDropStats = ScreenCaptureFrameDropStats(),

    /** Per-subscription delivery skips; never increments [droppedFrames]. */
    public val droppedDeliveries: ScreenCaptureDeliveryDropStats = ScreenCaptureDeliveryDropStats(),

    /** Published frames per second over session lifetime using monotonic elapsed time. */
    public val publishedFps: Double = 0.0,

    /** Average synchronous encoder duration in milliseconds. */
    public val averageEncodeMs: Double = 0.0,

    /** Average readback duration in milliseconds. */
    public val averageReadbackMs: Double = 0.0,

    /** Encoded byte count of the last successfully encoded frame. */
    public val lastEncodedByteCount: Int = 0,

    /** Average encoded byte count for successfully encoded frames. */
    public val averageEncodedByteCount: Int = 0,

    /** Currently registered frame subscriptions. */
    public val activeFrameSubscriptions: Int = 0,

    /** Subscriptions currently considered slow or failing by engine diagnostics. */
    public val slowConsumers: Int = 0,
) {
    init {
        require(framesEncoded >= 0L) { "framesEncoded must be non-negative, was $framesEncoded" }
        require(framesPublished >= 0L) { "framesPublished must be non-negative, was $framesPublished" }
        require(publishedFps.isFinite() && publishedFps >= 0.0) {
            "publishedFps must be finite and non-negative, was $publishedFps"
        }
        require(averageEncodeMs.isFinite() && averageEncodeMs >= 0.0) {
            "averageEncodeMs must be finite and non-negative, was $averageEncodeMs"
        }
        require(averageReadbackMs.isFinite() && averageReadbackMs >= 0.0) {
            "averageReadbackMs must be finite and non-negative, was $averageReadbackMs"
        }
        require(lastEncodedByteCount >= 0) {
            "lastEncodedByteCount must be non-negative, was $lastEncodedByteCount"
        }
        require(averageEncodedByteCount >= 0) {
            "averageEncodedByteCount must be non-negative, was $averageEncodedByteCount"
        }
        require(activeFrameSubscriptions >= 0) {
            "activeFrameSubscriptions must be non-negative, was $activeFrameSubscriptions"
        }
        require(slowConsumers >= 0) { "slowConsumers must be non-negative, was $slowConsumers" }
    }
}

/**
 * Production-frame drop counters.
 *
 * [total] must equal the sum of the listed categories. Each dropped production opportunity or
 * completed encoded result is counted in exactly one category.
 */
public class ScreenCaptureFrameDropStats public constructor(
    /** Sum of all production drop categories. */
    public val total: Long = 0L,

    /** Frame skipped by max-FPS or periodic-refresh pacing. */
    public val byFrameRatePolicy: Long = 0L,

    /** Frame skipped because readback resources were busy. */
    public val byReadbackBusy: Long = 0L,

    /** Frame skipped because encoder resources were busy. */
    public val byEncoderBusy: Long = 0L,

    /** Frame skipped because current output is suspended. */
    public val byOutputSuspended: Long = 0L,

    /** Completed or signaled work discarded because it belonged to an old generation. */
    public val byStaleGeneration: Long = 0L,

    /** Encoded result discarded because it exceeded the encoded-size cap. */
    public val byEncodedSizeLimit: Long = 0L,

    /** Single recoverable readback or encode failure. */
    public val byTransientFailure: Long = 0L,
) {
    init {
        require(total >= 0L) { "total must be non-negative, was $total" }
        require(byFrameRatePolicy >= 0L) { "byFrameRatePolicy must be non-negative, was $byFrameRatePolicy" }
        require(byReadbackBusy >= 0L) { "byReadbackBusy must be non-negative, was $byReadbackBusy" }
        require(byEncoderBusy >= 0L) { "byEncoderBusy must be non-negative, was $byEncoderBusy" }
        require(byOutputSuspended >= 0L) { "byOutputSuspended must be non-negative, was $byOutputSuspended" }
        require(byStaleGeneration >= 0L) { "byStaleGeneration must be non-negative, was $byStaleGeneration" }
        require(byEncodedSizeLimit >= 0L) { "byEncodedSizeLimit must be non-negative, was $byEncodedSizeLimit" }
        require(byTransientFailure >= 0L) { "byTransientFailure must be non-negative, was $byTransientFailure" }
        val categoryTotal = try {
            Math.addExact(
                Math.addExact(
                    Math.addExact(byFrameRatePolicy, byReadbackBusy),
                    Math.addExact(byEncoderBusy, byOutputSuspended),
                ),
                Math.addExact(
                    Math.addExact(byStaleGeneration, byEncodedSizeLimit),
                    byTransientFailure,
                ),
            )
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("sum of frame drop categories must not overflow Long", exception)
        }
        require(total == categoryTotal) {
            "total must equal the sum of frame drop categories"
        }
    }
}

/**
 * Per-subscription delivery drop counters.
 *
 * [total] must equal the sum of the listed categories. Delivery drops are consumer-side skips
 * and do not mean production failed.
 */
public class ScreenCaptureDeliveryDropStats public constructor(
    /** Sum of all delivery drop categories. */
    public val total: Long = 0L,

    /** Subscription already had a scheduled or running callback for another frame. */
    public val bySubscriptionBusy: Long = 0L,

    /** Dispatch to the selected callback dispatcher failed or saturated. */
    public val byDispatchFailed: Long = 0L,

    /** No immutable snapshot slot was available for this delivery. */
    public val bySnapshotSlotsExhausted: Long = 0L,

    /** Delivery skipped because the session/output became stale or terminal. */
    public val byStaleSession: Long = 0L,
) {
    init {
        require(total >= 0L) { "total must be non-negative, was $total" }
        require(bySubscriptionBusy >= 0L) { "bySubscriptionBusy must be non-negative, was $bySubscriptionBusy" }
        require(byDispatchFailed >= 0L) { "byDispatchFailed must be non-negative, was $byDispatchFailed" }
        require(bySnapshotSlotsExhausted >= 0L) {
            "bySnapshotSlotsExhausted must be non-negative, was $bySnapshotSlotsExhausted"
        }
        require(byStaleSession >= 0L) { "byStaleSession must be non-negative, was $byStaleSession" }
        val categoryTotal = try {
            Math.addExact(
                Math.addExact(bySubscriptionBusy, byDispatchFailed),
                Math.addExact(bySnapshotSlotsExhausted, byStaleSession),
            )
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("sum of delivery drop categories must not overflow Long", exception)
        }
        require(total == categoryTotal) {
            "total must equal the sum of delivery drop categories"
        }
    }
}

/**
 * Best-effort diagnostic event for logging, telemetry, or debug UI.
 *
 * Events are not a control-flow contract. State, stats, update return values, and frame
 * callbacks remain authoritative for their respective surfaces.
 */
public class ScreenCaptureEvent public constructor(
    /** Monotonic event sequence. */
    public val sequence: Long,

    /** Event timestamp from elapsed realtime, in nanoseconds. */
    public val timestampElapsedRealtimeNanos: Long,

    /** Stable event category. */
    public val type: ScreenCaptureEventType,

    /** Optional associated problem. */
    public val problem: ScreenCaptureProblem?,

    /** Optional diagnostic text, not a parsing contract. */
    public val message: String?,
) {
    init {
        require(sequence >= 0L) { "sequence must be non-negative, was $sequence" }
        require(timestampElapsedRealtimeNanos >= 0L) {
            "timestampElapsedRealtimeNanos must be non-negative, was $timestampElapsedRealtimeNanos"
        }
    }
}

/** Stable diagnostic event category. */
public enum class ScreenCaptureEventType {
    SessionStarted,
    SessionStopped,
    CaptureGeometryChanged,
    CaptureTargetChanged,
    InvalidMetricsIgnored,
    OutputPlanApplied,
    OutputPlanRejected,
    OutputPlanSuspended,
    OutputPlanResumed,
    ReadbackModeChanged,
    EncoderChanged,
    EncodedFrameDropped,
    SlowConsumerPressure,
    FrameDeliveryFailure,
    MemoryTrimmed,
}

private val CROP_INSET_RANGE: IntRange = 0..32768
private val SCALE_FACTOR_RANGE: ClosedFloatingPointRange<Double> = 0.10..2.00
private val TARGET_SIZE_RANGE: IntRange = 1..32768
private val MAX_FPS_RANGE: IntRange = 1..120
private val PERIODIC_REFRESH_INTERVAL_RANGE: LongRange = 1_000L..300_000L
