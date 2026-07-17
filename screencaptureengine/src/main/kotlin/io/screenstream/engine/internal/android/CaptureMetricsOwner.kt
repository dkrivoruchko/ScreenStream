package io.screenstream.engine.internal.android

import android.content.Context
import io.screenstream.engine.BuiltInCaptureMetricsDefinition
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsProvider
import io.screenstream.engine.internal.settlement.DeadlineArmResult
import io.screenstream.engine.internal.settlement.DeadlineDisposition
import io.screenstream.engine.internal.settlement.DeadlineOccurrence
import io.screenstream.engine.internal.settlement.DeadlineWakeSubmissionDisposition
import io.screenstream.engine.internal.settlement.DeadlineWakeSuccessorResult
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.SettlementSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val firstMetricsReadinessNanos: Long = 5_000_000_000L

internal class CaptureMetricsReadinessOccurrence(
    internal val identity: Long,
    deadlineIdentity: Long,
    initialWakeGeneration: Long,
    private val clock: EngineClock,
    signal: SettlementSignal,
    timeoutCause: Throwable,
    private val observeEntryCell: CaptureMetricsEntryCell,
    private val collectionEntryCell: CaptureMetricsEntryCell,
    private val flowReturnCell: CaptureMetricsFlowReturnCell,
    private val latestCell: CaptureMetricsLatestCell,
    private val providerOutcomeCell: CaptureMetricsProviderOutcomeCell,
) {
    internal val settlementGate: ReentrantLock = ReentrantLock(false)

    internal val deadlineOccurrence: DeadlineOccurrence = DeadlineOccurrence(
        identity = deadlineIdentity,
        boundOccurrenceIdentity = identity,
        durationNanos = firstMetricsReadinessNanos,
        initialWakeGeneration = initialWakeGeneration,
        timeoutCause = timeoutCause,
        settlementGate = settlementGate,
        clock = clock,
        signal = signal,
    )

    private val settlementSignal: SettlementSignal = signal

    private var attached = false
    private var cleanupDomain = false
    private var readinessOutcome = CaptureMetricsReadinessArbitration.None
    private var readinessClaimed = false

    internal val observeEntered: Boolean
        get() = settlementGate.withLock { observeEntryCell.entered }

    internal val collectionEntered: Boolean
        get() = settlementGate.withLock { collectionEntryCell.entered }

    internal val flowReturned: Boolean
        get() = settlementGate.withLock { flowReturnCell.returned }

    internal val returnedFlow: Flow<CaptureMetrics?>?
        get() = settlementGate.withLock { flowReturnCell.flow }

    internal val latestMetrics: CaptureMetrics?
        get() = settlementGate.withLock { latestCell.claimedMetrics }

    internal val providerCause: Throwable?
        get() = settlementGate.withLock { providerOutcomeCell.cause }

    internal val readinessCause: Throwable?
        get() = settlementGate.withLock {
            when (readinessOutcome) {
                CaptureMetricsReadinessArbitration.Expired -> deadlineOccurrence.wakeLink.timeoutCause
                CaptureMetricsReadinessArbitration.SchedulerRejected -> deadlineOccurrence.wakeLink.schedulingRejection
                CaptureMetricsReadinessArbitration.None,
                CaptureMetricsReadinessArbitration.Timely,
                CaptureMetricsReadinessArbitration.DeadlineGuardFailed,
                    -> null
            }
        }

    internal fun attachLocked(): Boolean {
        if (attached || cleanupDomain) return false
        attached = true

        when (deadlineOccurrence.armLocked(clock.nowNanos())) {
            DeadlineArmResult.Armed -> Unit
            DeadlineArmResult.InvalidClockOrOverflow -> readinessOutcome = CaptureMetricsReadinessArbitration.DeadlineGuardFailed
            DeadlineArmResult.AlreadySettled -> return false
        }
        return true
    }

    internal fun requestDeadlineWake(): Boolean = deadlineOccurrence.wakeLink.requestSubmission()

    internal fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean =
        deadlineOccurrence.wakeLink.submitRequested(scheduler)

    internal fun performRequestedDeadlineCancellation(): Boolean =
        deadlineOccurrence.wakeLink.performRequestedCancellation()

    internal fun prepareEarlyDeadlineWakeSuccessor(): DeadlineWakeSuccessorResult =
        deadlineOccurrence.wakeLink.prepareEarlyWakeSuccessor()

    internal fun recordObserveEntry(): Boolean = settlementGate.withLock {
        if (!attached || cleanupDomain) return@withLock false
        observeEntryCell.enterLocked()
    }

    internal fun adoptFlow(returnedFlow: Flow<CaptureMetrics?>?): Boolean {
        var flowAdopted = false
        var collectionAllowed = false
        settlementGate.withLock {
            flowAdopted = flowReturnCell.publishLocked(returnedFlow)
            if (!flowAdopted) return@withLock

            if (!cleanupDomain && returnedFlow == null) {
                providerOutcomeCell.publishLocked(
                    providerOutcome = CaptureMetricsProviderArbitration.UnusableFlow,
                    providerCause = null,
                )
            }
            collectionAllowed = !cleanupDomain && returnedFlow != null
        }
        if (flowAdopted) settlementSignal.signal()
        return collectionAllowed
    }

    internal fun recordCollectionEntry(): Boolean = settlementGate.withLock {
        if (!attached || cleanupDomain || providerOutcomeCell.outcome != CaptureMetricsProviderArbitration.None) {
            return@withLock false
        }
        collectionEntryCell.enterLocked()
    }

    internal fun publishMetrics(metrics: CaptureMetrics?) {
        var published = false
        settlementGate.withLock {
            if (cleanupDomain) return@withLock

            if (readinessOutcome == CaptureMetricsReadinessArbitration.None && metrics != null) {
                val settlementNanos = clock.nowNanos()
                readinessOutcome = if (deadlineOccurrence.disposition == DeadlineDisposition.Armed && settlementNanos < deadlineOccurrence.deadlineNanos) {
                    deadlineOccurrence.retireLocked()
                    CaptureMetricsReadinessArbitration.Timely
                } else {
                    deadlineOccurrence.expireLocked()
                    CaptureMetricsReadinessArbitration.Expired
                }
                published = true
            }

            if (readinessOutcome == CaptureMetricsReadinessArbitration.Timely) {
                latestCell.publishLocked(metrics)
                published = true
            }
        }
        if (published) settlementSignal.signal()
    }

    internal fun publishProviderOutcome(outcome: CaptureMetricsProviderArbitration, cause: Throwable?) {
        val published = settlementGate.withLock {
            if (cleanupDomain) return@withLock false
            providerOutcomeCell.publishLocked(outcome, cause)
        }
        if (published) settlementSignal.signal()
    }

    internal fun arbitrateReadiness(): CaptureMetricsReadinessArbitration = settlementGate.withLock {
        if (readinessClaimed) return@withLock CaptureMetricsReadinessArbitration.None

        if (readinessOutcome == CaptureMetricsReadinessArbitration.None &&
            deadlineOccurrence.wakeLink.submissionDisposition == DeadlineWakeSubmissionDisposition.Rejected
        ) {
            deadlineOccurrence.retireLocked()
            readinessOutcome = CaptureMetricsReadinessArbitration.SchedulerRejected
        }
        if (readinessOutcome == CaptureMetricsReadinessArbitration.None &&
            deadlineOccurrence.disposition == DeadlineDisposition.Armed &&
            clock.nowNanos() >= deadlineOccurrence.deadlineNanos
        ) {
            deadlineOccurrence.expireLocked()
            readinessOutcome = CaptureMetricsReadinessArbitration.Expired
        }
        if (readinessOutcome == CaptureMetricsReadinessArbitration.None) {
            return@withLock CaptureMetricsReadinessArbitration.None
        }

        readinessClaimed = true
        readinessOutcome
    }

    internal fun claimLatest(): CaptureMetricsLatestArbitration = settlementGate.withLock {
        if (latestCell.claimLocked()) {
            CaptureMetricsLatestArbitration.Claimed
        } else {
            CaptureMetricsLatestArbitration.None
        }
    }

    internal fun claimProviderOutcome(): CaptureMetricsProviderArbitration = settlementGate.withLock {
        providerOutcomeCell.claimLocked()
    }

    internal fun transferToCleanupLocked(): Boolean {
        if (cleanupDomain) return false
        cleanupDomain = true
        deadlineOccurrence.retireLocked()
        return true
    }
}

internal class CaptureMetricsLifecycleOwners(
    internal val provider: CaptureMetricsProvider,
    internal val readinessOccurrence: CaptureMetricsReadinessOccurrence,
    internal val parentJob: CompletableJob,
    internal val metricsScope: CoroutineScope,
    internal val collectorChild: Job,
    internal val observeEntryCell: CaptureMetricsEntryCell,
    internal val collectionEntryCell: CaptureMetricsEntryCell,
    internal val flowReturnCell: CaptureMetricsFlowReturnCell,
    internal val latestCell: CaptureMetricsLatestCell,
    internal val providerOutcomeCell: CaptureMetricsProviderOutcomeCell,
    internal val parentCompletionCell: CaptureMetricsParentCompletionCell,
) {
    private var parentCompletionClaimed = false

    internal fun performTerminalCancellation() {
        readinessOccurrence.performRequestedDeadlineCancellation()
        parentJob.cancel()
    }

    internal fun claimParentCompletionReceipt(): Boolean = readinessOccurrence.settlementGate.withLock {
        if (!parentCompletionCell.isComplete || parentCompletionClaimed) return@withLock false
        parentCompletionClaimed = true
        true
    }
}

internal class CaptureMetricsOwner(
    applicationContext: Context,
    configuredProvider: CaptureMetricsProvider?,
    metricsIoView: CoroutineDispatcher,
    clock: EngineClock,
    signal: SettlementSignal,
    readinessOccurrenceIdentity: Long,
    readinessDeadlineIdentity: Long,
    readinessWakeIdentity: Long,
    readinessTimeoutCause: Throwable,
) {
    private var lifecycleOwners: CaptureMetricsLifecycleOwners?

    init {
        require(readinessOccurrenceIdentity > 0L) { "readinessOccurrenceIdentity must be positive" }
        require(readinessDeadlineIdentity > 0L) { "readinessDeadlineIdentity must be positive" }
        require(readinessWakeIdentity > 0L) { "readinessWakeIdentity must be positive" }

        val effectiveProvider = configuredProvider ?: BuiltInCaptureMetricsDefinition(applicationContext)
        val observeEntryCell = CaptureMetricsEntryCell()
        val collectionEntryCell = CaptureMetricsEntryCell()
        val flowReturnCell = CaptureMetricsFlowReturnCell()
        val latestCell = CaptureMetricsLatestCell()
        val providerOutcomeCell = CaptureMetricsProviderOutcomeCell()
        val parentCompletionCell = CaptureMetricsParentCompletionCell()
        val readinessOccurrence = CaptureMetricsReadinessOccurrence(
            identity = readinessOccurrenceIdentity,
            deadlineIdentity = readinessDeadlineIdentity,
            initialWakeGeneration = readinessWakeIdentity,
            clock = clock,
            signal = signal,
            timeoutCause = readinessTimeoutCause,
            observeEntryCell = observeEntryCell,
            collectionEntryCell = collectionEntryCell,
            flowReturnCell = flowReturnCell,
            latestCell = latestCell,
            providerOutcomeCell = providerOutcomeCell,
        )
        val parentJob = Job()
        val metricsScope = CoroutineScope(parentJob + metricsIoView)
        val collectorChild = metricsScope.launch(start = CoroutineStart.LAZY) {
            collectCaptureMetrics(effectiveProvider, readinessOccurrence)
        }

        parentJob.invokeOnCompletion {
            if (parentCompletionCell.publish()) signal.signal()
        }
        collectorChild.invokeOnCompletion {
            parentJob.complete()
        }

        lifecycleOwners = CaptureMetricsLifecycleOwners(
            provider = effectiveProvider,
            readinessOccurrence = readinessOccurrence,
            parentJob = parentJob,
            metricsScope = metricsScope,
            collectorChild = collectorChild,
            observeEntryCell = observeEntryCell,
            collectionEntryCell = collectionEntryCell,
            flowReturnCell = flowReturnCell,
            latestCell = latestCell,
            providerOutcomeCell = providerOutcomeCell,
            parentCompletionCell = parentCompletionCell,
        )
    }

    internal val observeEntered: Boolean
        get() = lifecycleOwners?.readinessOccurrence?.observeEntered == true

    internal val collectionEntered: Boolean
        get() = lifecycleOwners?.readinessOccurrence?.collectionEntered == true

    internal val flowReturned: Boolean
        get() = lifecycleOwners?.readinessOccurrence?.flowReturned == true

    internal val returnedFlow: Flow<CaptureMetrics?>?
        get() = lifecycleOwners?.readinessOccurrence?.returnedFlow

    internal val latestMetrics: CaptureMetrics?
        get() = lifecycleOwners?.readinessOccurrence?.latestMetrics

    internal val providerCause: Throwable?
        get() = lifecycleOwners?.readinessOccurrence?.providerCause

    internal val readinessCause: Throwable?
        get() = lifecycleOwners?.readinessOccurrence?.readinessCause

    internal fun attach(sessionGate: ReentrantLock, scheduler: ScheduledExecutorService): Boolean {
        val owners = lifecycleOwners ?: return false
        val attached = sessionGate.withLock {
            owners.readinessOccurrence.settlementGate.withLock {
                owners.readinessOccurrence.attachLocked()
            }
        }
        if (!attached) return false

        owners.readinessOccurrence.requestDeadlineWake()
        owners.readinessOccurrence.submitRequestedDeadlineWake(scheduler)
        owners.collectorChild.start()
        return true
    }

    internal fun arbitrateReadiness(): CaptureMetricsReadinessArbitration =
        lifecycleOwners?.readinessOccurrence?.arbitrateReadiness()
            ?: CaptureMetricsReadinessArbitration.None

    internal fun prepareEarlyDeadlineWakeSuccessor(): DeadlineWakeSuccessorResult =
        lifecycleOwners?.readinessOccurrence?.prepareEarlyDeadlineWakeSuccessor()
            ?: DeadlineWakeSuccessorResult.NotEligible

    internal fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean =
        lifecycleOwners?.readinessOccurrence?.submitRequestedDeadlineWake(scheduler) == true

    internal fun performRequestedDeadlineCancellation(): Boolean =
        lifecycleOwners?.readinessOccurrence?.performRequestedDeadlineCancellation() == true

    internal fun claimLatest(): CaptureMetricsLatestArbitration =
        lifecycleOwners?.readinessOccurrence?.claimLatest() ?: CaptureMetricsLatestArbitration.None

    internal fun claimProviderOutcome(): CaptureMetricsProviderArbitration =
        lifecycleOwners?.readinessOccurrence?.claimProviderOutcome()
            ?: CaptureMetricsProviderArbitration.None

    internal fun claimParentCompletionReceipt(): Boolean =
        lifecycleOwners?.claimParentCompletionReceipt() == true

    internal fun transferToCleanup(sessionGate: ReentrantLock): CaptureMetricsLifecycleOwners? {
        var transferredOwners: CaptureMetricsLifecycleOwners? = null
        sessionGate.withLock {
            val owners = lifecycleOwners ?: return@withLock
            val transferred = owners.readinessOccurrence.settlementGate.withLock {
                owners.readinessOccurrence.transferToCleanupLocked()
            }
            if (transferred) {
                lifecycleOwners = null
                transferredOwners = owners
            }
        }

        val owners = transferredOwners ?: return null
        owners.performTerminalCancellation()
        return owners
    }
}

private suspend fun collectCaptureMetrics(provider: CaptureMetricsProvider, readinessOccurrence: CaptureMetricsReadinessOccurrence) {
    if (!readinessOccurrence.recordObserveEntry()) return

    val observedFlow: Flow<CaptureMetrics?>? = try {
        provider.observe()
    } catch (failure: Throwable) {
        publishCaptureMetricsProviderFailure(
            readinessOccurrence = readinessOccurrence,
            outcome = CaptureMetricsProviderArbitration.ObserveFailed,
            failure = failure,
        )
        return
    }

    if (!readinessOccurrence.adoptFlow(observedFlow)) return
    val adoptedFlow = observedFlow ?: return
    if (!readinessOccurrence.recordCollectionEntry()) return

    try {
        adoptedFlow.collect { metrics -> readinessOccurrence.publishMetrics(metrics) }
    } catch (failure: Throwable) {
        publishCaptureMetricsProviderFailure(
            readinessOccurrence = readinessOccurrence,
            outcome = CaptureMetricsProviderArbitration.CollectionFailed,
            failure = failure,
        )
        return
    }

    readinessOccurrence.publishProviderOutcome(
        outcome = CaptureMetricsProviderArbitration.Completed,
        cause = null,
    )
}

private suspend fun publishCaptureMetricsProviderFailure(
    readinessOccurrence: CaptureMetricsReadinessOccurrence,
    outcome: CaptureMetricsProviderArbitration,
    failure: Throwable,
) {
    if (failure is CancellationException) {
        currentCoroutineContext().ensureActive()
    }
    readinessOccurrence.publishProviderOutcome(outcome, failure)
}
