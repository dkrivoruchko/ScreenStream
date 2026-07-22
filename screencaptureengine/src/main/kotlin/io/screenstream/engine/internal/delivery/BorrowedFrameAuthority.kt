package io.screenstream.engine.internal.delivery

import io.screenstream.engine.EncodedImageFrame
import io.screenstream.engine.internal.EncodedStorageOwner
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Sole callback-scoped authority behind one public frame facade.
 *
 * All six public operations enter [checkedLease]. Closing clears both the callback thread and the retained lease
 * before the physical lease release is attempted, so no callback facade can keep storage authority alive.
 */
internal class BorrowedFrameAuthority internal constructor(
    lease: EncodedStorageOwner.EncodedPayloadLease,
    private val settlementGate: ReentrantLock,
) {
    private var retainedLease: EncodedStorageOwner.EncodedPayloadLease? = lease
    private var callbackThread: Thread? = null
    private var open: Boolean = false

    internal val frame: EncodedImageFrame = EncodedImageFrame.create(
        readByteCount = { checkedLease().byteCount },
        readEffectiveParameters = { checkedLease().effectiveParameters },
        readSequence = { checkedLease().sequence },
        readTimestampElapsedRealtimeNanos = { checkedLease().timestampElapsedRealtimeNanos },
        copyTo = { destination, destinationOffset ->
            checkedLease().copyTo(destination, destinationOffset)
        },
        copyBytes = { checkedLease().copyBytes() },
    )

    internal fun openLocked(thread: Thread): Boolean {
        check(settlementGate.isHeldByCurrentThread)
        if (open || retainedLease == null) return false
        callbackThread = thread
        open = true
        return true
    }

    /** Returns the retained lease after revoking every public frame operation. */
    internal fun closeAndDetachLocked(): EncodedStorageOwner.EncodedPayloadLease? {
        check(settlementGate.isHeldByCurrentThread)
        open = false
        callbackThread = null
        return retainedLease.also { retainedLease = null }
    }

    private fun checkedLease(): EncodedStorageOwner.EncodedPayloadLease = settlementGate.withLock {
        if (!open || retainedLease == null) throw CLOSED_AUTHORITY
        if (Thread.currentThread() !== callbackThread) throw WRONG_THREAD
        checkNotNull(retainedLease)
    }

    private companion object {
        private val WRONG_THREAD: IllegalStateException =
            IllegalStateException("EncodedImageFrame is valid only on its callback thread")
        private val CLOSED_AUTHORITY: IllegalStateException =
            IllegalStateException("EncodedImageFrame is valid only during its callback body")
    }
}
