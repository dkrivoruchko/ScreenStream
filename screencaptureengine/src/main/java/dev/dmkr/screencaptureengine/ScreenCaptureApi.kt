package dev.dmkr.screencaptureengine

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjection
import android.view.Display
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Entry point for creating screen-capture sessions from a user-approved [MediaProjection].
 *
 * The engine owns capture, rendering, encoding, and latest-frame publication for one session.
 * Consent UI and foreground-service compliance remain the responsibility of the integrating app.
 * Once a fresh projection is passed to [startSession], the engine owns it for that session.
 * A projection is single-use for the engine; a stopped or already-used projection cannot start
 * another session.
 */
public interface ScreenCaptureEngine {
    /**
     * Starts a new capture session using a fresh, active [mediaProjection].
     *
     * The call is suspending and main-safe. It returns only after required startup resources are
     * initialized and initial `Running(Active)` state is published; on API 34+ startup waits for
     * the first valid captured-content resize.
     */
    public suspend fun startSession(
        config: ScreenCaptureConfig,
        mediaProjection: MediaProjection,
        initialParameters: ScreenCaptureParameters = ScreenCaptureParameters.defaults(),
    ): ScreenCaptureSession
}

/**
 * Session-wide limits and integration hooks.
 *
 * These values bound retained encoded snapshots, published output size, encoded frame size,
 * and default callback dispatch. They are public construction limits, not allocation
 * guarantees. Per-frame capture/render/encode choices live in [ScreenCaptureParameters].
 */
public class ScreenCaptureConfig public constructor(
    /** Source of display/captured-content bootstrap metrics and density. */
    public val metricsProvider: CaptureMetricsProvider,

    /** Number of immutable encoded snapshot slots retained for public frame delivery. */
    public val publishedSnapshotSlotCount: Int = DEFAULT_PUBLISHED_SNAPSHOT_SLOT_COUNT,

    /** Diagnostic threshold for busy or failing frame subscriptions. */
    public val slowConsumerThreshold: Int = DEFAULT_SLOW_CONSUMER_THRESHOLD,

    /** Maximum final published image pixels before device/runtime limits are considered. */
    public val maxOutputPixels: Int = DEFAULT_MAX_OUTPUT_PIXELS,

    /** Hard cap for one encoded frame and one retained encoded snapshot slot. */
    public val maxEncodedBytes: Int = DEFAULT_MAX_ENCODED_BYTES,

    /** Default dispatcher used by [ScreenCaptureSession.onFrame] when no dispatcher is passed. */
    public val defaultFrameDeliveryDispatcher: CoroutineDispatcher? = null,
) {
    init {
        require(publishedSnapshotSlotCount in PUBLISHED_SNAPSHOT_SLOT_COUNT_RANGE) {
            "publishedSnapshotSlotCount must be in $PUBLISHED_SNAPSHOT_SLOT_COUNT_RANGE, was $publishedSnapshotSlotCount"
        }
        require(slowConsumerThreshold in SLOW_CONSUMER_THRESHOLD_RANGE) {
            "slowConsumerThreshold must be in $SLOW_CONSUMER_THRESHOLD_RANGE, was $slowConsumerThreshold"
        }
        require(maxOutputPixels in MAX_OUTPUT_PIXELS_RANGE) {
            "maxOutputPixels must be in $MAX_OUTPUT_PIXELS_RANGE, was $maxOutputPixels"
        }
        require(maxEncodedBytes in MAX_ENCODED_BYTES_RANGE) {
            "maxEncodedBytes must be in $MAX_ENCODED_BYTES_RANGE, was $maxEncodedBytes"
        }
    }
}

/**
 * Supplies capture metrics as state.
 *
 * Width and height are logical captured-content or bootstrap display pixels depending on
 * platform stage; density is used for virtual-display sizing. Invalid startup metrics fail
 * startup when possible; invalid running metrics are ignored by the engine.
 */
public interface CaptureMetricsProvider {
    /** Latest known valid metrics. Invalid runtime emissions are ignored by the engine. */
    public val metrics: StateFlow<CaptureMetrics>
}

/**
 * Built-in metric-provider factories for common Android integration points.
 *
 * Runtime bodies are intentionally not implemented yet; live update semantics and lifecycle
 * ownership must be resolved before these factories become usable.
 */
public object CaptureMetricsProviders {
    /** Currently throws; intended provider for capture started from an activity/window owner. */
    public fun fromActivity(activity: Activity): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is not implemented yet")
    }

    /** Currently throws; intended provider for UI-bound contexts without direct [Activity] access. */
    public fun fromUiContext(context: Context): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is not implemented yet")
    }

    /** Currently throws; intended provider for integrations that explicitly know the target [Display]. */
    public fun fromDisplay(baseContext: Context, display: Display): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is not implemented yet")
    }

    /** Currently throws; intended fallback for service/application contexts without better ownership. */
    public fun bestEffort(context: Context): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is not implemented yet")
    }
}

/**
 * A single non-restartable capture session.
 *
 * Sessions expose lifecycle state, statistics, diagnostic events, parameter updates, and
 * in-process latest-only encoded frame delivery. A stopped or failed session cannot be
 * restarted; fresh user consent/projection creates a new session.
 */
public interface ScreenCaptureSession {
    /** Atomically applies new capture parameters, or rejects them while keeping the old plan. */
    public suspend fun setParameters(parameters: ScreenCaptureParameters): ScreenCaptureParameterUpdateResult

    /** Asks the session to release or shrink optional buffers for the given Android trim level. */
    public fun trimMemory(level: Int)

    /**
     * Stops the session from the owner side.
     *
     * This is an immediate publication/frame-delivery boundary, not a synchronous
     * heavy-resource destruction barrier. Idempotent and fast-bounded.
     */
    public fun stop()

    /** Equivalent terminal boundary to [stop] for integrations using Closeable style. */
    public fun close()

    /** Authoritative lifecycle and current output state. */
    public val state: StateFlow<ScreenCaptureSessionState>

    /** Authoritative counters, rates, byte sizes, and consumer-pressure stats. */
    public val stats: StateFlow<ScreenCaptureStats>

    /** Best-effort diagnostics for logging and debug UI; not required for control flow. */
    public val events: SharedFlow<ScreenCaptureEvent>

    /**
     * Registers a latest-only encoded frame callback for this session.
     *
     * The callback receives a borrowed [EncodedImageFrame] that is valid only during the
     * synchronous callback body. Each subscription has at most one scheduled or running
     * delivery; if a newer frame arrives while it is busy, that delivery is skipped for this
     * subscription instead of queuing.
     */
    public fun onFrame(
        dispatcher: CoroutineDispatcher? = null,
        callback: (EncodedImageFrame) -> Unit,
    ): FrameSubscription
}

/** Handle for a frame callback registration. */
public interface FrameSubscription {
    /**
     * Cancels future deliveries.
     *
     * Thread-safe and idempotent; does not interrupt a callback that has already started.
     */
    public fun cancel()
}

/**
 * Startup failure before a [ScreenCaptureSession] was created.
 *
 * [requiresFreshProjection] tells the owner whether retry needs fresh user consent and a new
 * [MediaProjection] session from the engine's perspective. `false` only means the engine has
 * not performed a projection-consuming operation known to require a fresh session.
 */
public class ScreenCaptureStartException public constructor(
    public val requiresFreshProjection: Boolean,
    public val problem: ScreenCaptureProblem,
) : Exception(problem.message, problem.cause)

private const val DEFAULT_PUBLISHED_SNAPSHOT_SLOT_COUNT: Int = 4
private const val DEFAULT_SLOW_CONSUMER_THRESHOLD: Int = 2
private const val DEFAULT_MAX_OUTPUT_PIXELS: Int = 2_073_600
private const val DEFAULT_MAX_ENCODED_BYTES: Int = 8 * 1024 * 1024
private val PUBLISHED_SNAPSHOT_SLOT_COUNT_RANGE: IntRange = 1..16
private val SLOW_CONSUMER_THRESHOLD_RANGE: IntRange = 1..1024
private val MAX_OUTPUT_PIXELS_RANGE: IntRange = 1..268_435_456
private val MAX_ENCODED_BYTES_RANGE: IntRange = 1_024..268_435_456
