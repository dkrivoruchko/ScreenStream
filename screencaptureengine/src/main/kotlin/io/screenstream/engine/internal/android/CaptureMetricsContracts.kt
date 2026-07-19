package io.screenstream.engine.internal.android

import android.view.Display
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsObserver
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.CaptureMetricsSubscription
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnedOwner

internal enum class CaptureMetricsReadinessArbitration {
    None,
    Timely,
    Expired,
    CompletedBeforeReadiness,
    FailedBeforeReadiness,
    AvailabilityLostBeforeActive,
    AttachmentFailed,
    DeadlineGuardFailed,
}

internal enum class CaptureMetricsIngressPublishResult {
    Published,
    Duplicate,
    Closed,
    SequenceExhausted,
}

internal class CaptureMetricsClaimedValue(
    internal val source: CaptureMetricsSource,
    internal val observationIdentity: Long,
    internal val sequence: Long,
    internal val metrics: CaptureMetrics?,
    internal val display: Display?,
    internal val displayEpoch: Long,
    internal val isAvailable: Boolean,
)

internal enum class CaptureMetricsTerminalKind {
    Completed,
    Failed,
}

internal enum class CaptureMetricsTerminalPhase {
    BeforeJointReadiness,
    AfterJointReadiness,
}

internal enum class CaptureMetricsTerminalArbitration {
    None,
    CompletedBeforeReadiness,
    CompletedAfterReadiness,
    FailedBeforeReadiness,
    FailedAfterReadiness,
}

internal enum class CaptureMetricsHandleDisposition {
    Pending,
    Adopted,
    NullReturned,
}

internal object CaptureMetricsAttachmentReceipt : OperationReceipt

internal class CaptureMetricsSubscriptionOwner : OperationReturnedOwner {
    private var fixedSubscription: CaptureMetricsSubscription? = null

    internal val subscription: CaptureMetricsSubscription
        get() = checkNotNull(fixedSubscription)

    internal val isBound: Boolean
        get() = fixedSubscription != null

    internal fun bind(subscription: CaptureMetricsSubscription): Boolean {
        val existing = fixedSubscription
        if (existing != null) return existing === subscription
        fixedSubscription = subscription
        return true
    }
}

internal class CaptureMetricsAttachmentEvidence : OperationEvidence {
    internal val subscriptionOwner = CaptureMetricsSubscriptionOwner()

    internal var handleDisposition: CaptureMetricsHandleDisposition = CaptureMetricsHandleDisposition.Pending
        private set

    internal var handleSettlementNanos: Long = Long.MIN_VALUE
        private set

    override val receipt: OperationReceipt?
        get() = if (handleDisposition == CaptureMetricsHandleDisposition.Adopted) {
            CaptureMetricsAttachmentReceipt
        } else {
            null
        }

    override val returnedOwner: OperationReturnedOwner?
        get() = if (handleDisposition == CaptureMetricsHandleDisposition.Adopted) subscriptionOwner else null

    internal fun recordReturnedHandleLocked(
        subscription: CaptureMetricsSubscription?,
        settlementNanos: Long,
    ): Boolean {
        if (handleDisposition != CaptureMetricsHandleDisposition.Pending) return false
        handleSettlementNanos = settlementNanos
        if (subscription == null) {
            handleDisposition = CaptureMetricsHandleDisposition.NullReturned
        } else {
            check(subscriptionOwner.bind(subscription))
            handleDisposition = CaptureMetricsHandleDisposition.Adopted
        }
        return true
    }
}

internal class CaptureMetricsAttachmentOwnerBag(
    internal val source: CaptureMetricsSource,
    internal val observer: CaptureMetricsObserver,
) : OperationOwnerBag

internal object CaptureMetricsRefreshReceipt : OperationReceipt

internal class CaptureMetricsRefreshEvidence : OperationEvidence {
    override val receipt: OperationReceipt = CaptureMetricsRefreshReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class CaptureMetricsRefreshOwnerBag(
    internal val attachment: BuiltInCaptureMetricsAttachment,
) : OperationOwnerBag

internal object CaptureMetricsCloseReceipt : OperationReceipt

internal class CaptureMetricsCloseEvidence : OperationEvidence {
    override val receipt: OperationReceipt = CaptureMetricsCloseReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class CaptureMetricsCloseOwnerBag(
    internal val subscriptionOwner: CaptureMetricsSubscriptionOwner,
) : OperationOwnerBag

internal class CaptureMetricsIngressSummary(
    internal val source: CaptureMetricsSource,
    internal val observationIdentity: Long,
    private val sequenceExhaustionCause: Throwable,
) {
    internal var ingressOpen: Boolean = true
        private set

    internal var nextSequence: Long = 1L
        private set

    internal var earliestPositiveSequence: Long = 0L
        private set

    internal var earliestPositiveSampleNanos: Long = Long.MIN_VALUE
        private set

    internal var earliestPositiveMetrics: CaptureMetrics? = null
        private set

    internal var latestSequence: Long = 0L
        private set

    internal var latestSource: CaptureMetricsSource? = null
        private set

    internal var latestObservationIdentity: Long = 0L
        private set

    internal var latestAvailable: Boolean = false
        private set

    internal var latestMetrics: CaptureMetrics? = null
        private set

    internal var latestDisplay: Display? = null
        private set

    internal var latestDisplayEpoch: Long = 0L
        private set

    internal var latestValueAvailable: Boolean = false
        private set

    internal var latestClaimedSequence: Long = 0L
        private set

    internal var postValidLossBeforeActive: Boolean = false
        private set

    internal var jointReadinessCommitted: Boolean = false
        private set

    internal var firstActiveCommitted: Boolean = false
        private set

    internal var terminalKind: CaptureMetricsTerminalKind? = null
        private set

    internal var terminalCause: Throwable? = null
        private set

    internal var terminalSequence: Long = 0L
        private set

    internal var terminalPhase: CaptureMetricsTerminalPhase? = null
        private set

    internal fun publishMetricsLocked(
        metrics: CaptureMetrics?,
        sampleNanos: Long,
        display: Display?,
        displayEpoch: Long,
    ): CaptureMetricsIngressPublishResult {
        if (!ingressOpen) return CaptureMetricsIngressPublishResult.Closed
        val sequence = reserveSequenceLocked()
        if (sequence == 0L) return CaptureMetricsIngressPublishResult.SequenceExhausted
        val available = metrics != null
        if (latestAvailable &&
            latestSource === source &&
            latestObservationIdentity == observationIdentity &&
            latestMetrics == metrics &&
            latestDisplay === display &&
            latestDisplayEpoch == displayEpoch &&
            latestValueAvailable == available
        ) {
            return CaptureMetricsIngressPublishResult.Duplicate
        }
        if (metrics != null && earliestPositiveMetrics == null) {
            earliestPositiveSequence = sequence
            earliestPositiveSampleNanos = sampleNanos
            earliestPositiveMetrics = metrics
        } else if (metrics == null && earliestPositiveMetrics != null && !firstActiveCommitted) {
            postValidLossBeforeActive = true
        }
        latestSequence = sequence
        latestSource = source
        latestObservationIdentity = observationIdentity
        latestMetrics = metrics
        latestDisplay = display
        latestDisplayEpoch = displayEpoch
        latestValueAvailable = available
        latestAvailable = true
        return CaptureMetricsIngressPublishResult.Published
    }

    internal fun publishTerminalLocked(
        kind: CaptureMetricsTerminalKind,
        cause: Throwable?,
    ): CaptureMetricsIngressPublishResult {
        if (!ingressOpen || terminalKind != null) return CaptureMetricsIngressPublishResult.Closed
        val sequence = reserveSequenceLocked()
        if (sequence == 0L) return CaptureMetricsIngressPublishResult.SequenceExhausted
        terminalKind = kind
        terminalCause = cause
        terminalSequence = sequence
        terminalPhase = if (jointReadinessCommitted) {
            CaptureMetricsTerminalPhase.AfterJointReadiness
        } else {
            CaptureMetricsTerminalPhase.BeforeJointReadiness
        }
        ingressOpen = false
        return CaptureMetricsIngressPublishResult.Published
    }

    internal fun closeIngressLocked(): Boolean {
        if (!ingressOpen) return false
        ingressOpen = false
        return true
    }

    internal fun commitJointReadinessLocked(): Boolean {
        if (jointReadinessCommitted) return false
        jointReadinessCommitted = true
        return true
    }

    internal fun commitFirstActiveLocked(): Boolean {
        if (firstActiveCommitted || !jointReadinessCommitted) return false
        firstActiveCommitted = true
        return true
    }

    internal fun claimLatestLocked(): Boolean {
        if (!latestAvailable || latestClaimedSequence == latestSequence) return false
        latestClaimedSequence = latestSequence
        return true
    }

    private fun reserveSequenceLocked(): Long {
        if (nextSequence <= 0L || nextSequence == Long.MAX_VALUE) {
            if (terminalKind == null) {
                terminalKind = CaptureMetricsTerminalKind.Failed
                terminalCause = sequenceExhaustionCause
                terminalSequence = Long.MAX_VALUE
                terminalPhase = if (jointReadinessCommitted) {
                    CaptureMetricsTerminalPhase.AfterJointReadiness
                } else {
                    CaptureMetricsTerminalPhase.BeforeJointReadiness
                }
            }
            ingressOpen = false
            return 0L
        }
        val reserved = nextSequence
        nextSequence += 1L
        return reserved
    }
}
