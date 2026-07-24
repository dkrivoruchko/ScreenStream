package io.screenstream.engine.internal.delivery

import io.screenstream.engine.EncodedImageFrame
import io.screenstream.engine.internal.storage.EncodedPayloadLease
import io.screenstream.engine.internal.storage.EncodedPayloadLeaseRelease

internal enum class DeliveryEntryState {
    Queued,
    Entered,
    CutoffInert,
    Closed,
}

internal enum class DeliveryOutputKind {
    Fresh,
    Repeat,
    CachedFirst,
}

/** Immutable one-opportunity handoff. Its exact FrameStore lease is never replaced. */
internal class DeliveryHandoff internal constructor(
    internal val registrationId: Long,
    internal val outputKind: DeliveryOutputKind,
    internal val callback: (EncodedImageFrame) -> Unit,
    internal val lease: EncodedPayloadLease,
) {
    init {
        require(registrationId > 0L)
        require(lease.registrationId == registrationId)
    }

    internal val borrowedFrame: BorrowedFrameAuthority = BorrowedFrameAuthority(lease)
}

/** Permanent Delivery ownership root. All mutation occurs while the owning Session gate is held. */
internal class DeliveryCapsule internal constructor() {
    internal var entryState: DeliveryEntryState = DeliveryEntryState.Closed
        private set
    internal var handoff: DeliveryHandoff? = null
        private set
    internal var callbackThread: Thread? = null
        private set
    internal var leaseRelease: EncodedPayloadLeaseRelease? = null
        private set
    internal var closedResult: DeliveryClosedResult? = null
        private set

    internal val hasUnresolvedOwnership: Boolean
        get() = handoff != null || entryState != DeliveryEntryState.Closed

    internal fun queue(record: DeliveryHandoff) {
        check(entryState == DeliveryEntryState.Closed && handoff == null)
        check(callbackThread == null && leaseRelease == null && closedResult == null)
        handoff = record
        entryState = DeliveryEntryState.Queued
    }

    internal fun markEntered(expected: DeliveryHandoff, thread: Thread): Boolean {
        if (entryState != DeliveryEntryState.Queued || handoff !== expected) return false
        callbackThread = thread
        entryState = DeliveryEntryState.Entered
        return true
    }

    internal fun markCutoffInert(expected: DeliveryHandoff): Boolean {
        if (entryState != DeliveryEntryState.Queued || handoff !== expected) return false
        entryState = DeliveryEntryState.CutoffInert
        return true
    }

    internal fun recordRealReturn(
        expected: DeliveryHandoff,
        result: DeliveryClosedResult,
    ) {
        val release = result.leaseRelease
        check(handoff === expected)
        check(result.handoff === expected)
        check(release.names(expected.lease))
        check(entryState == DeliveryEntryState.Entered || entryState == DeliveryEntryState.CutoffInert)
        check(leaseRelease == null && closedResult == null)
        callbackThread = null
        leaseRelease = release
        closedResult = result
    }

    /** Completion installation, not lease release alone, makes the one handoff slot reusable. */
    internal fun closeInstalled(expected: DeliveryHandoff, expectedResult: DeliveryClosedResult) {
        check(handoff === expected && closedResult === expectedResult)
        callbackThread = null
        leaseRelease = null
        closedResult = null
        handoff = null
        entryState = DeliveryEntryState.Closed
    }

    internal fun isEnteredCallbackThread(registrationId: Long, thread: Thread): Boolean =
        entryState == DeliveryEntryState.Entered &&
                handoff?.registrationId == registrationId &&
                callbackThread === thread
}

internal sealed interface DeliveryRetirement {
    data object Closed : DeliveryRetirement

    class ReturnExpected internal constructor(
        internal val capsule: DeliveryCapsule,
    ) : DeliveryRetirement

    class ProcessLifetimeResidue internal constructor(
        internal val capsule: DeliveryCapsule,
    ) : DeliveryRetirement
}
