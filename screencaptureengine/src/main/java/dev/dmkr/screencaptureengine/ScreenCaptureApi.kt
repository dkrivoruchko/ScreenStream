package dev.dmkr.screencaptureengine

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjection
import android.view.Display
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Entry point for creating screen-capture sessions from a user-approved [MediaProjection].
 *
 * The engine owns capture, rendering, encoding, and latest-frame publication for one session.
 * Consent UI and foreground-service compliance remain the responsibility of the integrating app.
 * Passing a projection to [startSession] lets the engine attach startup callbacks and, if startup
 * reaches virtual-display creation, consume that projection. A returned active session owns the
 * projection lifecycle for this engine. If startup fails before a session is returned, retry
 * ownership is reported by [ScreenCaptureStartException.requiresFreshProjection].
 */
public interface ScreenCaptureEngine {
    /**
     * Starts a new capture session using a fresh, active [mediaProjection].
     *
     * Fresh means active, not stopped, not already used for a virtual display, and not already
     * observed stopped by [MediaProjection.Callback.onStop]. Implementations must be suspending
     * and main-safe. The call returns only after required startup resources are initialized and
     * initial `Running(Active)` state is published. On API 34+ startup waits up to `3_000 ms`
     * after non-null virtual-display creation for the first valid captured-content resize; timeout
     * fails startup with [ScreenCaptureProblemKind.StartupGeometryUnavailable], and projection stop
     * wins if observed first or in the same serialized control turn. Startup failures are reported
     * with [ScreenCaptureStartException]; [ScreenCaptureStartException.requiresFreshProjection] is
     * the retry signal. Calls from engine-owned callbacks or internal execution contexts fail fast
     * with [IllegalStateException] to avoid deadlocks.
     *
     * A returned active session owns the projection lifecycle. Owner [ScreenCaptureSession.stop] or
     * [ScreenCaptureSession.close] requests [MediaProjection.stop] best-effort for that session.
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
 * These values bound retained encoded snapshots, published output size, encoded frame size, and default callback dispatch. They are public construction
 * limits, not allocation guarantees. Per-frame capture/render/encode choices live in [ScreenCaptureParameters].
 */
public class ScreenCaptureConfig public constructor(
    /** Source of display/captured-content bootstrap metrics and density. */
    public val metricsProvider: CaptureMetricsProvider,

    /** Number of immutable public delivery snapshot slots in `1..16`, excluding the internal latest frame. */
    public val publishedSnapshotSlotCount: Int = DEFAULT_PUBLISHED_SNAPSHOT_SLOT_COUNT,

    /** Consecutive direct per-subscription delivery-problem threshold in `1..1024` for slow classification. */
    public val slowConsumerThreshold: Int = DEFAULT_SLOW_CONSUMER_THRESHOLD,

    /** Maximum final published image pixels in `1..268435456` before device/runtime limits are considered. */
    public val maxOutputPixels: Int = DEFAULT_MAX_OUTPUT_PIXELS,

    /** Hard cap in `1024..268435456` bytes for the internal latest frame and each public delivery snapshot. */
    public val maxEncodedBytes: Int = DEFAULT_MAX_ENCODED_BYTES,

    /** Session-level dispatcher for public frame callback bodies. Null uses the engine-owned bounded callback dispatcher. */
    public val frameCallbackDispatcher: CoroutineDispatcher? = null,
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

    public override fun equals(other: Any?): Boolean =
        other is ScreenCaptureConfig && metricsProvider == other.metricsProvider &&
                publishedSnapshotSlotCount == other.publishedSnapshotSlotCount && slowConsumerThreshold == other.slowConsumerThreshold &&
                maxOutputPixels == other.maxOutputPixels && maxEncodedBytes == other.maxEncodedBytes &&
                frameCallbackDispatcher == other.frameCallbackDispatcher

    public override fun hashCode(): Int =
        31 * (31 * (31 * (31 * (31 * metricsProvider.hashCode() + publishedSnapshotSlotCount) + slowConsumerThreshold) + maxOutputPixels) + maxEncodedBytes) +
                (frameCallbackDispatcher?.hashCode() ?: 0)
}

/**
 * Supplies capture metrics as state.
 *
 * Width and height describe the logical capture area used for output planning; density is used for virtual-display sizing. Invalid startup metrics fail startup
 * when possible; invalid running metrics are ignored by the engine. Provider objects are owned by the caller and may outlive a session; a session only owns its
 * own observation of a provider.
 */
public interface CaptureMetricsProvider {
    /** Latest known valid metrics. Invalid runtime emissions are ignored by the engine. */
    public val metrics: StateFlow<CaptureMetrics>
}

/**
 * Internal session attachment hook for built-in metrics providers.
 *
 * Factory providers do not register long-lived platform listeners at construction time. Runtime sessions attach an observation and dispose only that
 * attachment on stop, failure, or startup failure; the provider object remains caller-owned and reusable.
 */
@Suppress("unused")
internal interface EngineAttachableCaptureMetricsProvider : CaptureMetricsProvider {
    fun attachSessionAttachment(onMetricsChanged: () -> Unit): DisposableHandle
}

/**
 * Metric-provider factory API for common Android integration points.
 *
 * These public factory slots are reserved for built-in Android metrics providers. The current
 * production implementation does not provide those built-in providers, so each factory throws
 * [UnsupportedOperationException]. Caller-supplied [CaptureMetricsProvider] implementations remain
 * supported and are observed per session.
 */
@Suppress("UNUSED_PARAMETER")
public object CaptureMetricsProviders {
    /** Placeholder for an activity/window-owned provider; currently throws [UnsupportedOperationException]. */
    public fun fromActivity(activity: Activity): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }

    /** Placeholder for a UI-context provider; currently throws [UnsupportedOperationException]. */
    public fun fromUiContext(context: Context): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }

    /** Placeholder for a display-specific provider; currently throws [UnsupportedOperationException]. */
    public fun fromDisplay(baseContext: Context, display: Display): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }

    /** Placeholder for a best-effort provider; currently throws [UnsupportedOperationException]. */
    public fun bestEffort(context: Context): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }
}

/**
 * A single non-restartable capture session.
 *
 * Sessions expose lifecycle state, statistics, diagnostic events, parameter updates, and in-process latest-only encoded frame delivery. A stopped or failed
 * session cannot be restarted; fresh user consent/projection creates a new session.
 */
public interface ScreenCaptureSession {
    /**
     * Atomically applies new capture parameters, or rejects them while keeping the old plan.
     *
     * Calls are serialized, thread-safe, and main-safe. A successful result is returned only after
     * validation, resource preparation, and public state publication for the new plan complete.
     * Caller cancellation does not expose a partially applied public plan. Calls from engine-owned
     * execution contexts fail fast with [IllegalStateException].
     */
    public suspend fun setParameters(parameters: ScreenCaptureParameters): ScreenCaptureParameterUpdateResult

    /** Asks the session to release or shrink optional buffers for the given Android trim level. */
    public fun trimMemory(level: Int)

    /**
     * Stops the session from the owner side.
     *
     * This is an immediate publication/frame-delivery boundary, not a synchronous
     * heavy-resource destruction barrier. For a returned active session, this requests
     * [MediaProjection.stop] best-effort. Idempotent and fast-bounded.
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
     * The callback receives a borrowed [EncodedImageFrame] that is valid only during the admitted synchronous callback invocation. Each subscription has at
     * most one scheduled, handed-off, or admitted delivery; if a newer frame arrives while it is busy, that delivery is skipped for this subscription instead
     * of queuing. Callback invocations use [ScreenCaptureConfig.frameCallbackDispatcher] when non-null, otherwise an engine-owned bounded callback dispatcher.
     * All subscriptions in a session share the same final callback execution dispatcher. Engine-owned delivery coordination still owns snapshot leasing,
     * in-flight state, callback-entry gating, and accounting. Callbacks for different subscriptions may run concurrently when the configured dispatcher allows
     * it, and a callback that throws is contained and counted as a delivery failure without failing the session. Terminal session state or subscription
     * cancellation prevents deliveries that have not passed the final callback-entry gate from invoking the callback, but does not interrupt a callback delivery
     * that already passed that gate. A session accepts at most 16 active subscriptions and throws [IllegalStateException] when that limit is exceeded. Direct
     * engine subscriptions are intended for a small number of in-process consumers; transport fan-out should be implemented outside the engine.
     */
    public fun onFrame(callback: (EncodedImageFrame) -> Unit): FrameSubscription
}

/** Handle for a frame callback registration. */
public interface FrameSubscription {
    /**
     * Cancels future deliveries.
     *
     * Thread-safe and idempotent; does not interrupt a callback invocation that has already been admitted.
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
public class ScreenCaptureStartException : Exception {
    public val requiresFreshProjection: Boolean
    public val problem: ScreenCaptureProblem

    public constructor(requiresFreshProjection: Boolean, problem: ScreenCaptureProblem) : super(problem.message, problem.cause) {
        this.requiresFreshProjection = requiresFreshProjection
        this.problem = problem
    }
}

private const val DEFAULT_PUBLISHED_SNAPSHOT_SLOT_COUNT: Int = 4
private const val DEFAULT_SLOW_CONSUMER_THRESHOLD: Int = 2
private const val DEFAULT_MAX_OUTPUT_PIXELS: Int = 2_073_600
private const val DEFAULT_MAX_ENCODED_BYTES: Int = 8 * 1024 * 1024
private val PUBLISHED_SNAPSHOT_SLOT_COUNT_RANGE: IntRange = 1..16
private val SLOW_CONSUMER_THRESHOLD_RANGE: IntRange = 1..1024
private val MAX_OUTPUT_PIXELS_RANGE: IntRange = 1..268_435_456
private val MAX_ENCODED_BYTES_RANGE: IntRange = 1_024..268_435_456

@Suppress("unused")
internal const val API34_FIRST_CAPTURED_CONTENT_RESIZE_TIMEOUT_MILLIS: Long = 3_000L
