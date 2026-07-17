package io.screenstream.engine.internal.android

import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetPorts

internal const val androidEnteredOperationSafetyNanos: Long = 5_000_000_000L

internal sealed class AndroidCaptureFact(
    internal val sessionEpoch: Long,
    internal val callbackIdentity: Long,
    internal val sampleNanos: Long,
) {
    init {
        require(sessionEpoch > 0L)
        require(callbackIdentity > 0L)
    }

    internal class CapturedContentResized(
        sessionEpoch: Long,
        callbackIdentity: Long,
        sampleNanos: Long,
        internal val widthPx: Int,
        internal val heightPx: Int,
    ) : AndroidCaptureFact(sessionEpoch, callbackIdentity, sampleNanos)

    internal class CapturedContentVisibilityChanged(
        sessionEpoch: Long,
        callbackIdentity: Long,
        sampleNanos: Long,
        internal val isVisible: Boolean,
    ) : AndroidCaptureFact(sessionEpoch, callbackIdentity, sampleNanos)

    internal class CaptureEnded(
        sessionEpoch: Long,
        callbackIdentity: Long,
        sampleNanos: Long,
    ) : AndroidCaptureFact(sessionEpoch, callbackIdentity, sampleNanos)
}

internal fun interface AndroidCaptureFactSink {
    fun publish(fact: AndroidCaptureFact)
}


internal class AndroidFiniteOperationIdentity(
    internal val operationIdentity: Long,
    internal val deadlineIdentity: Long,
    internal val deadlineWakeGeneration: Long,
    internal val timeoutCause: Throwable,
) {
    init {
        require(operationIdentity > 0L)
        require(deadlineIdentity > 0L)
        require(deadlineWakeGeneration > 0L)
    }
}

internal class AndroidInitialResizeDeadlineIdentity(
    internal val deadlineIdentity: Long,
    internal val deadlineWakeGeneration: Long,
    internal val timeoutCause: Throwable,
) {
    init {
        require(deadlineIdentity > 0L)
        require(deadlineWakeGeneration > 0L)
    }
}

internal enum class AndroidVirtualDisplayInstallResult {
    Installed,
    NotReady,
    Collision,
}


internal object AndroidTargetListenerInstallationReceipt : OperationReceipt

internal class AndroidTargetListenerInstallationEvidence : OperationEvidence {
    override val receipt: AndroidTargetListenerInstallationReceipt = AndroidTargetListenerInstallationReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidTargetListenerInstallationOwnerBag(
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidListenerInstallationPort,
) : OperationOwnerBag


internal object AndroidTargetListenerRemovalReceipt : OperationReceipt

internal enum class AndroidListenerSentinelSubmissionDisposition {
    None,
    Accepted,
    Rejected,
    AmbiguousError,
}

internal class AndroidTargetListenerRemovalEvidence : OperationEvidence {
    override val receipt: AndroidTargetListenerRemovalReceipt = AndroidTargetListenerRemovalReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var listenerRemovalReturned: Boolean = false
        private set

    internal var sentinelSubmissionDisposition: AndroidListenerSentinelSubmissionDisposition =
        AndroidListenerSentinelSubmissionDisposition.None
        private set

    internal var sentinelSubmissionCause: Throwable? = null
        private set

    internal fun recordListenerRemovalReturnLocked(): Boolean {
        if (listenerRemovalReturned) return false
        listenerRemovalReturned = true
        return true
    }

    internal fun recordSentinelSubmissionLocked(
        disposition: AndroidListenerSentinelSubmissionDisposition,
        cause: Throwable?,
    ): Boolean {
        if (sentinelSubmissionDisposition != AndroidListenerSentinelSubmissionDisposition.None ||
            disposition == AndroidListenerSentinelSubmissionDisposition.None ||
            disposition == AndroidListenerSentinelSubmissionDisposition.Accepted && cause != null ||
            disposition != AndroidListenerSentinelSubmissionDisposition.Accepted && cause == null
        ) {
            return false
        }
        sentinelSubmissionDisposition = disposition
        sentinelSubmissionCause = cause
        return true
    }
}

internal class AndroidTargetListenerRemovalOwnerBag(
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidListenerRemovalPort,
) : OperationOwnerBag
