package io.screenstream.capture

/**
 * Construction options for a [ScreenCaptureSession].
 *
 * Configuration objects retain identity semantics and expose read-only property references. A configured metrics
 * source may be stateful and is retained by exact identity for the session; it is not copied or wrapped as a
 * caller-visible source.
 *
 * @property captureMetricsSource source of capture dimensions and density. `null` follows the current default
 * display through the application [android.hardware.display.DisplayManager]. A custom source owns any
 * Activity, window, display, and lifecycle policy needed to keep its metrics consistent with the projection consent.
 * @property jpegBackendPolicy policy for selecting the JPEG encoder. Defaults to [JpegBackendPolicy.Auto].
 */
public class ScreenCaptureConfig(
    public val captureMetricsSource: CaptureMetricsSource? = null,
    public val jpegBackendPolicy: JpegBackendPolicy = JpegBackendPolicy.Auto,
)

/** Controls which JPEG implementations a session may use. */
public enum class JpegBackendPolicy {
    /**
     * Uses the optional native backend when it is available and supported. Expected setup unavailability or lack of
     * platform support selects the framework backend. Unexpected native failures retain their ordinary failure
     * semantics.
     *
     * A safely classified native compression rejection disables native encoding for later frames in that session;
     * it does not retry the same frame with the framework backend.
     */
    Auto,

    /** Uses the framework JPEG backend and makes no calls to the optional native backend. */
    FrameworkOnly,
}
