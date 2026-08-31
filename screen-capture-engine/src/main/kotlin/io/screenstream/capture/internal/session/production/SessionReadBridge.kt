package io.screenstream.capture.internal.session.production

import io.screenstream.capture.internal.capture.CaptureReadResult
import io.screenstream.capture.internal.capture.CaptureReadReturnPort
import io.screenstream.capture.internal.encoding.EncodingInput
import java.nio.ByteBuffer

/**
 * Exact bridge between one Capture read return and one Encoding carrier loan.
 *
 * Before Capture request installation, Session may directly discard the exact unentered input. After installation,
 * only a matching real Capture return or definite pre-entry rejection can settle it. Terminal detachment preserves
 * this bridge only until such an exact late settlement; it never fabricates return, rejection, cancellation, or
 * carrier release. All decision methods are serialized by the Session coordinator's gate.
 */
internal class SessionReadBridge(
    internal val record: SessionProductionRecord,
    internal val input: EncodingInput,
    private val returnSink: (SessionReadBridge, CaptureReadResult) -> Unit,
) : CaptureReadReturnPort {
    private enum class Decision { Open, Claimed, Detached, }

    private var returnedSlot: CaptureReadResult? = null
    private var decision = Decision.Open
    private var detachedSettlementClaimed = false

    internal fun requireClaimedResult(): CaptureReadResult {
        check(decision == Decision.Claimed)
        return checkNotNull(returnedSlot)
    }

    internal val writableView: ByteBuffer
        get() = input.writableView

    override fun onReadReturned(result: CaptureReadResult) {
        returnSink(this, result)
    }

    internal fun recordReturnLocked(result: CaptureReadResult): Boolean {
        if ((decision == Decision.Claimed) || (returnedSlot != null)) return false
        returnedSlot = result
        return true
    }

    internal fun claimReturnedLocked(): Boolean {
        if (decision != Decision.Open) return false
        if (returnedSlot == null) return false
        decision = Decision.Claimed
        return true
    }

    internal fun claimRejectedBeforeEntryLocked(): Boolean {
        if (returnedSlot != null) return false
        return when (decision) {
            Decision.Open -> {
                decision = Decision.Claimed
                true
            }

            Decision.Detached -> {
                if (detachedSettlementClaimed) {
                    false
                } else {
                    detachedSettlementClaimed = true
                    true
                }
            }

            Decision.Claimed -> false
        }
    }

    internal fun detachLocked() {
        check((decision == Decision.Open) && (returnedSlot == null))
        decision = Decision.Detached
    }

    internal fun claimDetachedSettlementLocked(): Boolean {
        if ((decision != Decision.Detached) || detachedSettlementClaimed) return false
        if (returnedSlot == null) return false
        detachedSettlementClaimed = true
        return true
    }

    internal fun isDetachedLocked(): Boolean = decision == Decision.Detached

    internal fun canDetachLocked(): Boolean = (decision == Decision.Open) && (returnedSlot == null)
}
