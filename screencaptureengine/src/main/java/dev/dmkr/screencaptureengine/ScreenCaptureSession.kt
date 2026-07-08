package dev.dmkr.screencaptureengine

import android.media.projection.MediaProjection
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single non-restartable capture session.
 *
 * Sessions expose lifecycle state, statistics, diagnostic events, parameter updates, and in-process latest-only encoded frame delivery. A stopped or failed
 * session cannot be restarted; fresh user consent/projection creates a new session.
 */
public interface ScreenCaptureSession {
    /**
     * Requests new capture parameters for this session.
     *
     * For non-terminal sessions returned by [ScreenCaptureEngines.create], runtime parameter
     * updates are unavailable: the call returns [ScreenCaptureParameterUpdateResult.Rejected] with
     * [ScreenCaptureProblemKind.ParameterUpdateUnavailable], keeps the current plan unchanged, and
     * does not prepare or publish a partial plan.
     *
     * Calls are serialized, thread-safe, and main-safe. Calls from engine-owned execution contexts
     * fail fast with [IllegalStateException].
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
