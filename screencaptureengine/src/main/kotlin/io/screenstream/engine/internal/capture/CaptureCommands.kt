package io.screenstream.engine.internal.capture

import android.media.projection.MediaProjection
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.ScreenCaptureProblem
import java.nio.ByteBuffer

internal sealed interface CaptureCommand

internal enum class CaptureTargetMode {
    Full,
    Downscaled,
}

internal class CapturePlan internal constructor(
    internal val parameters: ScreenCaptureParameters,
    internal val sourceWidthPx: Int,
    internal val sourceHeightPx: Int,
    internal val densityDpi: Int,
    internal val targetMode: CaptureTargetMode,
    internal val targetWidthPx: Int,
    internal val targetHeightPx: Int,
    internal val outputWidthPx: Int,
    internal val outputHeightPx: Int,
) {
    internal val rgbaCarrierByteCount: Int

    init {
        require(sourceWidthPx > 0)
        require(sourceHeightPx > 0)
        require(densityDpi > 0)
        require(targetWidthPx > 0)
        require(targetHeightPx > 0)
        require(outputWidthPx > 0)
        require(outputHeightPx > 0)
        val pixels = Math.multiplyExact(outputWidthPx.toLong(), outputHeightPx.toLong())
        val byteCount = Math.multiplyExact(pixels, RGBA_BYTES_PER_PIXEL.toLong())
        require(byteCount <= Int.MAX_VALUE.toLong()) { "RGBA carrier exceeds the supported addressable range" }
        rgbaCarrierByteCount = byteCount.toInt()
    }

    private companion object {
        private const val RGBA_BYTES_PER_PIXEL = 4
    }
}

internal class OpenCapture internal constructor(
    internal val configRevision: Long,
    internal val mediaProjection: MediaProjection,
    internal val plan: CapturePlan,
) : CaptureCommand {
    init {
        require(configRevision > 0L)
    }
}

internal class ApplyPlan internal constructor(
    internal val configRevision: Long,
    internal val plan: CapturePlan,
) : CaptureCommand {
    init {
        require(configRevision > 0L)
    }
}

internal class ReadFrame internal constructor(
    internal val configRevision: Long,
    internal val productionId: Long,
    internal val plan: CapturePlan,
    internal val writableCarrier: ByteBuffer,
    internal val byteCount: Int,
) : CaptureCommand {
    init {
        require(configRevision > 0L)
        require(productionId > 0L)
        require(byteCount > 0)
        require(byteCount == plan.rgbaCarrierByteCount)
        require(writableCarrier.isDirect)
        require(writableCarrier.position() == 0)
        require(writableCarrier.limit() == byteCount)
        require(writableCarrier.capacity() >= byteCount)
        require(!writableCarrier.isReadOnly)
    }
}

internal class CloseCapture internal constructor() : CaptureCommand

internal sealed interface OpenCaptureResult {
    val command: OpenCapture
}

internal class Opened internal constructor(
    override val command: OpenCapture,
    internal val owner: CaptureOwner,
    internal val firstResizeDeadlineNanos: Long?,
) : OpenCaptureResult

internal class OpenFailed internal constructor(
    override val command: OpenCapture,
    internal val problem: ScreenCaptureProblem,
    internal val cause: Throwable?,
    internal val retirement: OpenFailureRetirement,
) : OpenCaptureResult

internal sealed interface OpenFailureRetirement {
    /** Every physical owner, including the accepted projection, returned normally from its required cleanup. */
    data object FullyRetired : OpenFailureRetirement

    /** Exact lane owner remains the only valid route for a later terminal CloseCapture. */
    class RetainedLocally internal constructor(
        internal val owner: CaptureOwner,
        internal val cleanupResidue: Throwable,
    ) : OpenFailureRetirement
}

internal sealed interface ApplyPlanResult {
    val command: ApplyPlan
}

internal class Applied internal constructor(
    override val command: ApplyPlan,
    internal val source: CaptureSourceToken,
) : ApplyPlanResult

internal class ApplySkippedBeforeEntry internal constructor(
    override val command: ApplyPlan,
) : ApplyPlanResult

internal class ApplyFailed internal constructor(
    override val command: ApplyPlan,
    internal val disposition: ApplyFailureDisposition,
) : ApplyPlanResult

internal sealed interface ApplyFailureDisposition

/** Clean deterministic capacity denial proven before any Apply mechanic can mutate. */
internal class ApplyPreflightResourceDenied internal constructor(
    internal val cause: Throwable,
) : ApplyFailureDisposition

/** Any unsafe, ambiguous, or post-mutation Apply failure. */
internal class ApplyUnsafeFailure internal constructor(
    internal val problem: ScreenCaptureProblem,
    internal val cause: Throwable?,
) : ApplyFailureDisposition

internal sealed interface ReadFrameResult {
    val command: ReadFrame
}

internal class FrameReady internal constructor(
    override val command: ReadFrame,
    internal val readbackDurationNanos: Long,
) : ReadFrameResult {
    init {
        require(readbackDurationNanos >= 0L)
    }
}

internal class NoCurrentSource internal constructor(
    override val command: ReadFrame,
) : ReadFrameResult

internal class ReadSkippedBeforeEntry internal constructor(
    override val command: ReadFrame,
) : ReadFrameResult

internal class ReadFailed internal constructor(
    override val command: ReadFrame,
    internal val problem: ScreenCaptureProblem,
    internal val cause: Throwable?,
) : ReadFrameResult

internal sealed interface CloseCaptureResult {
    val command: CloseCapture
}

internal class CaptureClosed internal constructor(
    override val command: CloseCapture,
    internal val cleanupFailure: Throwable? = null,
) : CloseCaptureResult

internal class CaptureRetainedLocally internal constructor(
    override val command: CloseCapture,
    internal val cause: Throwable?,
) : CloseCaptureResult

/** Resource-local identity; it is not a Session generation. */
internal class CaptureSourceToken private constructor() {
    internal companion object {
        internal fun create(): CaptureSourceToken = CaptureSourceToken()
    }
}

/** Exact physical owner returned by a successful OpenCapture. */
internal interface CaptureOwner {
    val mediaProjection: MediaProjection
    val source: CaptureSourceToken
}

internal class SourceAvailable internal constructor(
    internal val source: CaptureSourceToken,
)

internal class SourceCandidate internal constructor(
    internal val source: CaptureSourceToken,
) {
    internal var pending: Boolean = false
        private set

    internal fun markAvailable() {
        pending = true
    }

    internal fun consume(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
}
