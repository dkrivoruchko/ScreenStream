package dev.dmkr.screencaptureengine

import android.media.projection.MediaProjection

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
     * The default engine returned by [ScreenCaptureEngines.create] has one startup/session slot. A
     * second call while that engine owns a non-terminal session fails before projection attachment
     * or consumption with [ScreenCaptureStartException] where
     * [ScreenCaptureStartException.requiresFreshProjection] is false and
     * the exception [ScreenCaptureStartException.problem] has kind
     * [ScreenCaptureProblemKind.EngineSessionAlreadyActive].
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
