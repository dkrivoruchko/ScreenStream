package io.screenstream.engine.internal.delivery

import io.screenstream.engine.EncodedImageFrame
import io.screenstream.engine.EncodedImageFrameAccess
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.internal.storage.EncodedPayloadLease
import io.screenstream.engine.internal.storage.EncodedPayloadLeaseRelease

/** Sole callback-scoped access authority behind one public borrowed frame. */
internal class BorrowedFrameAuthority internal constructor(
    lease: EncodedPayloadLease,
) : EncodedImageFrameAccess {
    private val accessGate = Any()
    private var retainedLease: EncodedPayloadLease? = lease
    private var callbackThread: Thread? = null
    private var open = false

    internal val frame: EncodedImageFrame = EncodedImageFrame.create(this)

    internal fun openOn(thread: Thread) {
        synchronized(accessGate) {
            check(!open && callbackThread == null && retainedLease != null)
            callbackThread = thread
            open = true
        }
    }

    /** Revokes public access before producing the exact one-shot storage release fact. */
    internal fun closeAndRelease(): EncodedPayloadLeaseRelease? {
        val lease = synchronized(accessGate) {
            check(open)
            open = false
            callbackThread = null
            retainedLease.also { retainedLease = null }
        }
        return lease?.release()
    }

    /** Used for cutoff/rejection before callback entry. */
    internal fun releaseWithoutOpening(): EncodedPayloadLeaseRelease? {
        val lease = synchronized(accessGate) {
            check(!open && callbackThread == null)
            retainedLease.also { retainedLease = null }
        }
        return lease?.release()
    }

    override fun byteCount(): Int = checkedLease().byteCount

    override fun effectiveParameters(): ScreenCaptureEffectiveParameters = checkedLease().effectiveParameters

    override fun sequence(): Long = checkedLease().sequence

    override fun timestampElapsedRealtimeNanos(): Long = checkedLease().timestampElapsedRealtimeNanos

    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int =
        checkedLease().copyTo(destination, destinationOffset)

    override fun copyBytes(): ByteArray = checkedLease().copyBytes()

    private fun checkedLease(): EncodedPayloadLease = synchronized(accessGate) {
        if (!open || retainedLease == null) {
            throw IllegalStateException("EncodedImageFrame is valid only during its callback body")
        }
        if (Thread.currentThread() !== callbackThread) {
            throw IllegalStateException("EncodedImageFrame is valid only on its callback thread")
        }
        checkNotNull(retainedLease)
    }
}
