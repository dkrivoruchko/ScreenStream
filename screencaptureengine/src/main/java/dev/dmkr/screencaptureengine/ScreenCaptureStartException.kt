package dev.dmkr.screencaptureengine

import android.media.projection.MediaProjection

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
