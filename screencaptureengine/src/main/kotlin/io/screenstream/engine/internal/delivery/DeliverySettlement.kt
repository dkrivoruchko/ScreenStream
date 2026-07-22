package io.screenstream.engine.internal.delivery

import io.screenstream.engine.internal.EncodedStorageOwner
import java.util.concurrent.atomic.AtomicReference

internal class DeliverySubmissionCell internal constructor() {
    internal var disposition: DeliverySubmissionDisposition = DeliverySubmissionDisposition.Empty
        private set
    internal var throwable: Throwable? = null
        private set

    internal fun beginLocked(): Boolean {
        if (disposition != DeliverySubmissionDisposition.Empty) return false
        disposition = DeliverySubmissionDisposition.InCall
        return true
    }

    internal fun publishReturnedLocked(): Boolean {
        if (disposition != DeliverySubmissionDisposition.InCall) return false
        disposition = DeliverySubmissionDisposition.Returned
        return true
    }

    internal fun publishThrownLocked(raw: Throwable): Boolean {
        if (disposition != DeliverySubmissionDisposition.InCall) return false
        throwable = raw
        disposition = if (raw is Exception) DeliverySubmissionDisposition.ThrownException else DeliverySubmissionDisposition.ThrownFatal
        return true
    }
}

internal class DeliveryEntryCell internal constructor() {
    internal var disposition: DeliveryEntryDisposition = DeliveryEntryDisposition.Empty
        private set
    internal var callbackThread: Thread? = null
        private set

    internal fun publishLocked(result: DeliveryEntryDisposition): Boolean {
        require(result != DeliveryEntryDisposition.Empty)
        if (disposition != DeliveryEntryDisposition.Empty) return false
        disposition = result
        return true
    }

    internal fun publishCallbackThreadLocked(thread: Thread): Boolean {
        if (disposition != DeliveryEntryDisposition.Entered || callbackThread != null) return false
        callbackThread = thread
        return true
    }

    internal fun clearCallbackThreadLocked() {
        callbackThread = null
    }
}

internal class DeliveryCallbackCell internal constructor() {
    internal var disposition: DeliveryCallbackDisposition = DeliveryCallbackDisposition.Empty
        private set
    internal var throwable: Throwable? = null
        private set

    internal fun publishLocked(raw: Throwable?): Boolean {
        if (disposition != DeliveryCallbackDisposition.Empty) return false
        throwable = raw
        disposition = when {
            raw == null -> DeliveryCallbackDisposition.Returned
            raw is Exception -> DeliveryCallbackDisposition.ThrownException
            else -> DeliveryCallbackDisposition.ThrownFatal
        }
        return true
    }
}

internal class DeliveryRunnableCell internal constructor() {
    internal var disposition: DeliveryRunnableDisposition = DeliveryRunnableDisposition.Empty
        private set
    internal var throwable: Throwable? = null
        private set
    internal var handledException: Exception? = null
        private set

    internal fun publishReturnedLocked(handledFailure: Exception? = null): Boolean {
        if (disposition != DeliveryRunnableDisposition.Empty) return false
        handledException = handledFailure
        disposition = DeliveryRunnableDisposition.Returned
        return true
    }

    internal fun publishFatalLocked(raw: Throwable): Boolean {
        require(raw !is Exception)
        if (disposition != DeliveryRunnableDisposition.Empty) return false
        throwable = raw
        disposition = DeliveryRunnableDisposition.ThrownFatal
        return true
    }
}

internal class DeliveryNoCallbackCell internal constructor() {
    internal var disposition: DeliveryNoCallbackDisposition = DeliveryNoCallbackDisposition.Empty
        private set
    internal var throwable: Throwable? = null
        private set

    internal fun publishLocked(afterEntry: Boolean, raw: Throwable): Boolean {
        if (disposition != DeliveryNoCallbackDisposition.Empty) return false
        throwable = raw
        disposition = if (afterEntry) DeliveryNoCallbackDisposition.FailedAfterEntry else DeliveryNoCallbackDisposition.FailedBeforeEntry
        return true
    }
}

internal class DeliveryShutdownCell internal constructor() {
    private val atomicDisposition = AtomicReference(DeliveryShutdownDisposition.Empty)
    internal var throwable: Throwable? = null
        private set
    internal val disposition: DeliveryShutdownDisposition
        get() = atomicDisposition.get()

    internal fun begin(): Boolean = atomicDisposition.compareAndSet(
        DeliveryShutdownDisposition.Empty,
        DeliveryShutdownDisposition.InCall,
    )

    internal fun publishReturned() {
        check(atomicDisposition.compareAndSet(DeliveryShutdownDisposition.InCall, DeliveryShutdownDisposition.Returned))
    }

    internal fun publishThrown(raw: Throwable) {
        throwable = raw
        val result = if (raw is Exception) DeliveryShutdownDisposition.ThrownException else DeliveryShutdownDisposition.ThrownFatal
        check(atomicDisposition.compareAndSet(DeliveryShutdownDisposition.InCall, result))
    }
}

internal class DeliveryLeaseSlot internal constructor(
    lease: EncodedStorageOwner.EncodedPayloadLease,
) {
    internal var lease: EncodedStorageOwner.EncodedPayloadLease? = lease
        private set
    internal var disposition: DeliveryLeaseDisposition = DeliveryLeaseDisposition.Owned
        private set

    internal fun claimReleaseLocked(): EncodedStorageOwner.EncodedPayloadLease? {
        if (disposition != DeliveryLeaseDisposition.Owned) return null
        val current = lease ?: return null
        disposition = DeliveryLeaseDisposition.Releasing
        return current
    }

    internal fun publishReleaseLocked(released: Boolean): Boolean {
        if (disposition != DeliveryLeaseDisposition.Releasing) return false
        disposition = if (released) DeliveryLeaseDisposition.Released else DeliveryLeaseDisposition.ReleaseConflict
        return true
    }

    internal fun clearConsumedLocked(expectedLease: EncodedStorageOwner.EncodedPayloadLease): Boolean {
        if ((disposition != DeliveryLeaseDisposition.Released && disposition != DeliveryLeaseDisposition.ReleaseConflict) ||
            lease !== expectedLease
        ) {
            return false
        }
        lease = null
        return true
    }
}
