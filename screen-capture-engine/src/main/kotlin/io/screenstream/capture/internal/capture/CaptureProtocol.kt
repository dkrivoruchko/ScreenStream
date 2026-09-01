package io.screenstream.capture.internal.capture

import io.screenstream.capture.ColorMode
import io.screenstream.capture.ImageRect
import io.screenstream.capture.Mirror
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout

internal class CapturePhysicalException(message: String) : Exception(message)

internal class CaptureBoundaryFailure(internal val problem: ScreenCaptureProblem, internal val physicalCause: Throwable) : Exception(physicalCause)

internal enum class CaptureTargetMode { Full, Downscaled, }

internal class CapturePlan(
    internal val appliedSourceRect: ImageRect,
    internal val rotation: Rotation,
    internal val mirror: Mirror,
    internal val colorMode: ColorMode,
    internal val sourceWidthPx: Int,
    internal val sourceHeightPx: Int,
    internal val densityDpi: Int,
    internal val targetMode: CaptureTargetMode,
    internal val targetWidthPx: Int,
    internal val targetHeightPx: Int,
    internal val rgbaLayout: Rgba8888Layout,
) {
    internal val outputWidthPx: Int
        get() = rgbaLayout.widthPx

    internal val outputHeightPx: Int
        get() = rgbaLayout.heightPx

    internal val rgbaCarrierByteCount: Int
        get() = rgbaLayout.byteCount

    internal fun hasSameCaptureConfigurationAs(other: CapturePlan): Boolean =
        (appliedSourceRect == other.appliedSourceRect) &&
                (rotation == other.rotation) && (mirror == other.mirror) && (colorMode == other.colorMode) &&
                (sourceWidthPx == other.sourceWidthPx) && (sourceHeightPx == other.sourceHeightPx) &&
                (densityDpi == other.densityDpi) && (targetMode == other.targetMode) &&
                (targetWidthPx == other.targetWidthPx) && (targetHeightPx == other.targetHeightPx) &&
                rgbaLayout.hasSameDimensionsAs(other.rgbaLayout)

    init {
        require(sourceWidthPx > 0)
        require(sourceHeightPx > 0)
        require(densityDpi > 0)
        require(targetWidthPx > 0)
        require(targetHeightPx > 0)
    }
}

internal class CaptureSourceIdentity(private val owner: SessionCaptureOwner, private val source: SourceCandidate) {
    internal fun names(expectedOwner: SessionCaptureOwner, expectedSource: SourceCandidate): Boolean =
        (owner === expectedOwner) && (source === expectedSource)
}

internal class CaptureProjectionIdentity(private val owner: SessionCaptureOwner, private val projection: ProjectionOwner.Token) {
    internal fun names(expectedOwner: SessionCaptureOwner, expectedProjection: ProjectionOwner.Token): Boolean =
        (owner === expectedOwner) && (projection === expectedProjection)
}

internal sealed interface CaptureOpenResult {
    data object CutoffInert : CaptureOpenResult
    class Opened(
        internal val plan: CapturePlan,
        internal val sourceIdentity: CaptureSourceIdentity,
        internal val projectionIdentity: CaptureProjectionIdentity,
    ) : CaptureOpenResult

    class Failed(internal val problem: ScreenCaptureProblem, internal val cause: Throwable) : CaptureOpenResult
}

internal enum class CaptureFailureScope { OperationLocal, OwnerInvalidated, }

internal sealed interface CaptureApplyResult {
    data object CutoffInert : CaptureApplyResult
    class Applied(internal val plan: CapturePlan, internal val sourceIdentity: CaptureSourceIdentity) : CaptureApplyResult
    class ResourceDenied(internal val cause: Throwable) : CaptureApplyResult
    class Failed(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable,
        internal val scope: CaptureFailureScope,
    ) : CaptureApplyResult
}

internal sealed interface CaptureReadResult {
    class Filled(internal val readbackDurationNanos: Long) : CaptureReadResult {
        init {
            require(readbackDurationNanos >= 0L)
        }
    }

    data object CutoffInert : CaptureReadResult

    class Failed(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable,
        internal val sourceConsumed: Boolean,
        internal val scope: CaptureFailureScope,
    ) : CaptureReadResult
}

internal fun interface CaptureReadReturnPort {
    fun onReadReturned(result: CaptureReadResult)
}

internal interface SessionCaptureFactPort {
    fun onOpenReturned(result: CaptureOpenResult)

    fun onApplyReturned(result: CaptureApplyResult)

    fun onSourceAvailable(sourceIdentity: CaptureSourceIdentity)

    fun onProjectionStopped(projectionIdentity: CaptureProjectionIdentity)

    fun onCapturedContentResize(projectionIdentity: CaptureProjectionIdentity, widthPx: Int, heightPx: Int)

    fun onCapturedContentVisibilityChanged(projectionIdentity: CaptureProjectionIdentity, isVisible: Boolean)

    fun onCaptureFailure(failure: Exception)
}

internal class SourceCandidate {
    internal class Token

    private enum class State { Unavailable, Available, Reserved, }

    internal val token: Token = Token()
    private var state: State = State.Unavailable

    internal fun markAvailable(): Boolean {
        if (state != State.Unavailable) return false
        state = State.Available
        return true
    }

    internal fun reserve(): Boolean {
        if (state != State.Available) return false
        state = State.Reserved
        return true
    }

    internal fun settle(sourceConsumed: Boolean) {
        check(state == State.Reserved)
        state = if (sourceConsumed) State.Unavailable else State.Available
    }
}
