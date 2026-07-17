package io.screenstream.engine.internal.android

import io.screenstream.engine.CaptureMetrics
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean

internal enum class CaptureMetricsReadinessArbitration {
    None,
    Timely,
    Expired,
    SchedulerRejected,
    DeadlineGuardFailed,
}

internal enum class CaptureMetricsLatestArbitration {
    None,
    Claimed,
}

internal enum class CaptureMetricsProviderArbitration {
    None,
    Completed,
    ObserveFailed,
    UnusableFlow,
    CollectionFailed,
}

internal class CaptureMetricsEntryCell {
    internal var entered: Boolean = false
        private set

    internal fun enterLocked(): Boolean {
        if (entered) return false
        entered = true
        return true
    }
}

internal class CaptureMetricsFlowReturnCell {
    internal var returned: Boolean = false
        private set

    internal var flow: Flow<CaptureMetrics?>? = null
        private set

    internal fun publishLocked(returnedFlow: Flow<CaptureMetrics?>?): Boolean {
        if (returned) return false
        flow = returnedFlow
        returned = true
        return true
    }
}

internal class CaptureMetricsLatestCell {
    internal var metrics: CaptureMetrics? = null
        private set

    internal var available: Boolean = false
        private set

    internal var claimed: Boolean = false
        private set

    internal var claimedMetrics: CaptureMetrics? = null
        private set

    internal fun publishLocked(publishedMetrics: CaptureMetrics?) {
        metrics = publishedMetrics
        available = true
        claimed = false
    }

    internal fun claimLocked(): Boolean {
        if (!available || claimed) return false
        claimedMetrics = metrics
        claimed = true
        return true
    }
}

internal class CaptureMetricsProviderOutcomeCell {
    internal var outcome: CaptureMetricsProviderArbitration = CaptureMetricsProviderArbitration.None
        private set

    internal var cause: Throwable? = null
        private set

    internal var claimed: Boolean = false
        private set

    internal fun publishLocked(
        providerOutcome: CaptureMetricsProviderArbitration,
        providerCause: Throwable?,
    ): Boolean {
        if (outcome != CaptureMetricsProviderArbitration.None) return false
        outcome = providerOutcome
        cause = providerCause
        return true
    }

    internal fun claimLocked(): CaptureMetricsProviderArbitration {
        if (claimed) {
            return CaptureMetricsProviderArbitration.None
        }
        if (outcome == CaptureMetricsProviderArbitration.None) {
            return CaptureMetricsProviderArbitration.None
        }
        claimed = true
        return outcome
    }
}

internal class CaptureMetricsParentCompletionCell {
    private val completed = AtomicBoolean(false)

    internal val isComplete: Boolean
        get() = completed.get()

    internal fun publish(): Boolean = completed.compareAndSet(false, true)
}
