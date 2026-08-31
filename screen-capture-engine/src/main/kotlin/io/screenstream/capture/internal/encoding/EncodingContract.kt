package io.screenstream.capture.internal.encoding

import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.isExactWritableRgbaCarrier
import io.screenstream.capture.internal.storage.ImmutableEncodedPayload
import java.nio.ByteBuffer

internal fun interface EncodingReconcileReturnPort {
    fun onReturned(result: EncodingReconcileResult)
}

internal fun interface EncodingProductionReturnPort {
    fun onReturned(result: EncodingResult)
}

internal sealed interface EncodingReconcileSubmission {
    data object Accepted : EncodingReconcileSubmission

    class Rejected(internal val cause: Throwable?) : EncodingReconcileSubmission
}

internal sealed interface EncodingReconcileResult {
    data object Ready : EncodingReconcileResult

    class Failed(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable?,
    ) : EncodingReconcileResult

    data object CutoffInert : EncodingReconcileResult
}

internal sealed interface EncodingInputResult {
    class Failed(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable?,
    ) : EncodingInputResult
}

internal class EncodingInput(
    private val owner: EncodingOwner,
    internal val returnPort: EncodingProductionReturnPort,
    internal val carrier: RgbaCarrier,
    internal val writableView: ByteBuffer,
    internal val byteCount: Int,
) : EncodingInputResult {
    init {
        require(byteCount == carrier.layout.byteCount)
        check(writableView.isExactWritableRgbaCarrier(byteCount))
    }

    internal fun discard(): EncodingInputSettlement = owner.discardInput(this)

    internal fun encode(jpegQuality: Int): EncodingInputSettlement = owner.encodeInput(this, jpegQuality)
}

internal sealed interface EncodingInputSettlement {
    data object Accepted : EncodingInputSettlement
    data object Settled : EncodingInputSettlement
    class Failed(internal val problem: ScreenCaptureProblem, internal val cause: Throwable?) : EncodingInputSettlement
}

internal sealed interface EncodingResult {
    class Encoded(internal val payload: ImmutableEncodedPayload, internal val encodeDurationNanos: Long) : EncodingResult {
        init {
            require(encodeDurationNanos >= 0L)
        }
    }

    data object FrameFailed : EncodingResult
    data object ReadinessChanged : EncodingResult
    class Failed(internal val problem: ScreenCaptureProblem, internal val cause: Throwable?) : EncodingResult
    data object CutoffInert : EncodingResult
}
