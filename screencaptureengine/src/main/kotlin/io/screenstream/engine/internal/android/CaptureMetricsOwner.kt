package io.screenstream.engine.internal.android

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import io.screenstream.engine.BuiltInCaptureMetricsDefinition
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsObserver
import io.screenstream.engine.CaptureMetricsProvider
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.internal.MetricsEndpointShutdownAction
import io.screenstream.engine.CaptureMetricsSubscription
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.ControlWakeSubmissionDisposition
import io.screenstream.engine.internal.settlement.ControlWakeThrowableDisposition
import io.screenstream.engine.internal.settlement.DeadlineArmResult
import io.screenstream.engine.internal.settlement.DeadlineDisposition
import io.screenstream.engine.internal.settlement.DeadlineOccurrence
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorOperation
import io.screenstream.engine.internal.settlement.PrivateExecutorRuntime
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorSubmissionResult
import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val firstMetricsReadinessNanos: Long = 5_000_000_000L

internal fun interface BuiltInCaptureMetricsSink {
    fun publish(metrics: CaptureMetrics?, display: Display?, displayEpoch: Long)
}

internal class CaptureMetricsOwner(
    applicationContext: Context,
    configuredSource: CaptureMetricsSource?,
    private val sessionGate: ReentrantLock,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    attachmentIdentity: Long,
    readinessDeadlineIdentity: Long,
    readinessWakeIdentity: Long,
    readinessTimeoutCause: Throwable,
    closeOperationIdentity: Long,
) {
    internal val cleanupShutdownAction: MetricsEndpointShutdownAction = MetricsEndpointShutdownAction(this)
    init {
        require(attachmentIdentity > 0L)
        require(readinessDeadlineIdentity > 0L)
        require(readinessWakeIdentity > 0L)
        require(closeOperationIdentity > 0L)
    }

    private val source: CaptureMetricsSource =
        configuredSource ?: BuiltInCaptureMetricsDefinition(applicationContext)
    private val summary = CaptureMetricsIngressSummary(
        source = source,
        observationIdentity = attachmentIdentity,
        sequenceExhaustionCause = IllegalStateException("Capture metrics ingress sequence exhausted"),
    )
    private val readinessDeadlineGuardCause =
        IllegalStateException("Capture metrics readiness deadline could not be armed")
    private val observer: CaptureMetricsObserver = MetricsObserver()
    private val attachmentEvidence = CaptureMetricsAttachmentEvidence()
    private val closeEvidence = CaptureMetricsCloseEvidence()
    private val closeOwnerBag = CaptureMetricsCloseOwnerBag(attachmentEvidence.subscriptionOwner)
    private val builtInSink = BuiltInCaptureMetricsSink { metrics, display, displayEpoch ->
        publishMetricsIngress(metrics, display, displayEpoch)
    }
    private val builtInAttachment: BuiltInCaptureMetricsAttachment? =
        (source as? BuiltInCaptureMetricsDefinition)?.let { definition ->
            BuiltInCaptureMetricsAttachment(
                definition = definition,
                observer = observer,
                sink = builtInSink,
                settlementSignal = settlementSignal,
                subscriptionOwner = attachmentEvidence.subscriptionOwner,
            )
        }
    private val endpoint = PrivateExecutorRuntime(
        threadName = "ScreenCaptureEngine-Metrics",
        settlementSignal = settlementSignal,
    )
    private val attachmentOperation: OperationOccurrence<CaptureMetricsAttachmentEvidence>
    private val readinessDeadline: DeadlineOccurrence
    private val closeOperation: OperationOccurrence<CaptureMetricsCloseEvidence>
    private val attachmentEndpointOperation: PrivateExecutorOperation<CaptureMetricsAttachmentEvidence>

    private var readinessClaimed = false
    private var terminalClaimed = false
    private var attachmentEndpointOperationReleased = false
    private var refreshEndpointOperation: PrivateExecutorOperation<CaptureMetricsRefreshEvidence>? = null
    private var refreshEndpointOperationReleased = false
    private var closeEndpointOperation: PrivateExecutorOperation<CaptureMetricsCloseEvidence>? = null
    private var closeEndpointOperationReleased = false
    private var closeRequested = false
    private var closeSubmitted = false
    private var closeSettled = false
    private var readinessGuardFailed = false

    init {
        attachmentOperation = OperationOccurrence(
            identity = attachmentIdentity,
            clock = clock,
            returnCell = OperationReturnCell(attachmentEvidence),
            ownerBag = CaptureMetricsAttachmentOwnerBag(source, observer),
        )
        readinessDeadline = DeadlineOccurrence(
            identity = readinessDeadlineIdentity,
            boundOccurrenceIdentity = attachmentIdentity,
            durationNanos = firstMetricsReadinessNanos,
            initialWakeGeneration = readinessWakeIdentity,
            timeoutCause = readinessTimeoutCause,
            settlementGate = attachmentOperation.settlementGate,
            clock = clock,
            signal = settlementSignal,
        )
        closeOperation = OperationOccurrence(
            identity = closeOperationIdentity,
            clock = clock,
            returnCell = OperationReturnCell(closeEvidence),
            ownerBag = closeOwnerBag,
        )
        attachmentEndpointOperation = endpoint.operation(
            occurrence = attachmentOperation,
            enteredWork = Runnable { performAttachment() },
        )
    }

    internal val providerCause: Throwable?
        get() = sessionGate.withLock { summary.terminalCause }

    internal val observedFatal: Throwable?
        get() = endpoint.observedFatal

    internal val isEndpointPoisoned: Boolean
        get() = endpoint.isPoisoned

    internal val isEndpointTerminated: Boolean
        get() = endpointTerminationReceipt != null

    internal val endpointTerminationReceipt: PrivateExecutorTerminationReceipt?
        get() = endpoint.terminationReceipt

    internal val endpointStartupFailure: Throwable?
        get() = endpoint.observedStartupFailure

    internal fun prestartEndpoint(): PrivateExecutorStartupDisposition = endpoint.prestart()

    internal val readinessWakeLink: ControlWakeLink
        get() = readinessDeadline.controlWakeLink

    internal val refreshFailure: Throwable?
        get() {
            val occurrence = refreshEndpointOperation?.occurrence ?: return null
            return occurrence.settlementGate.withLock {
                occurrence.returnCell.throwable
            }
        }

    internal val closeFailure: Throwable?
        get() = closeOperation.settlementGate.withLock {
            closeOperation.returnCell.throwable
        }

    internal fun attach(): PrivateExecutorSubmissionResult = endpoint.submit(attachmentEndpointOperation)

    internal fun arbitrateReadiness(): CaptureMetricsReadinessArbitration {
        return sessionGate.withLock sessionLock@{
            attachmentOperation.settlementGate.withLock attachmentLock@{
                if (readinessClaimed) return@attachmentLock CaptureMetricsReadinessArbitration.None

                val result = when {
                    readinessGuardFailed ->
                        CaptureMetricsReadinessArbitration.DeadlineGuardFailed

                    readinessWakeLink.submissionDisposition == ControlWakeSubmissionDisposition.Rejected &&
                            readinessWakeLink.schedulingThrowableDisposition ==
                            ControlWakeThrowableDisposition.NonfatalException ->
                        CaptureMetricsReadinessArbitration.AttachmentFailed

                    attachmentOperation.returnCell.disposition == OperationReturnDisposition.Thrown ->
                        CaptureMetricsReadinessArbitration.AttachmentFailed

                    attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                            attachmentOperation.returnCell.evidence.handleDisposition == CaptureMetricsHandleDisposition.NullReturned ->
                        CaptureMetricsReadinessArbitration.AttachmentFailed

                    attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                            summary.terminalKind == CaptureMetricsTerminalKind.Failed ->
                        CaptureMetricsReadinessArbitration.FailedBeforeReadiness

                    attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                            summary.terminalKind == CaptureMetricsTerminalKind.Completed ->
                        CaptureMetricsReadinessArbitration.CompletedBeforeReadiness

                    attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                            summary.postValidLossBeforeActive ->
                        CaptureMetricsReadinessArbitration.AvailabilityLostBeforeActive

                    attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                            isJointReadinessPresentLocked() -> {
                        readinessDeadline.retireLocked()
                        summary.commitJointReadinessLocked()
                        CaptureMetricsReadinessArbitration.Timely
                    }

                    readinessDeadline.disposition == DeadlineDisposition.Armed &&
                            clock.nowNanos() >= readinessDeadline.deadlineNanos -> {
                        readinessDeadline.expireLocked()
                        CaptureMetricsReadinessArbitration.Expired
                    }

                    readinessDeadline.disposition == DeadlineDisposition.Expired ->
                        CaptureMetricsReadinessArbitration.Expired

                    attachmentOperation.returnCell.disposition == OperationReturnDisposition.Empty ->
                        CaptureMetricsReadinessArbitration.None

                    else -> CaptureMetricsReadinessArbitration.None
                }
                if (result != CaptureMetricsReadinessArbitration.None) {
                    if (result != CaptureMetricsReadinessArbitration.Expired) readinessDeadline.retireLocked()
                    readinessClaimed = true
                }
                result
            }
        }
    }

    internal fun commitFirstActiveLocked(): Boolean {
        check(sessionGate.isHeldByCurrentThread)
        return summary.commitFirstActiveLocked()
    }

    internal fun claimLatest(): CaptureMetricsClaimedValue? {
        var claimedSource: CaptureMetricsSource? = null
        var observationIdentity = 0L
        var sequence = 0L
        var metrics: CaptureMetrics? = null
        var display: Display? = null
        var displayEpoch = 0L
        var available = false
        val claimed = sessionGate.withLock {
            if (!summary.claimLatestLocked()) return@withLock false
            claimedSource = summary.latestSource
            observationIdentity = summary.latestObservationIdentity
            sequence = summary.latestSequence
            metrics = summary.latestMetrics
            display = summary.latestDisplay
            displayEpoch = summary.latestDisplayEpoch
            available = summary.latestValueAvailable
            true
        }
        if (!claimed) return null
        return CaptureMetricsClaimedValue(
            source = checkNotNull(claimedSource),
            observationIdentity = observationIdentity,
            sequence = sequence,
            metrics = metrics,
            display = display,
            displayEpoch = displayEpoch,
            isAvailable = available,
        )
    }

    internal fun claimTerminal(): CaptureMetricsTerminalArbitration = sessionGate.withLock {
        if (terminalClaimed) return@withLock CaptureMetricsTerminalArbitration.None
        val phase = summary.terminalPhase ?: return@withLock CaptureMetricsTerminalArbitration.None
        val kind = summary.terminalKind ?: return@withLock CaptureMetricsTerminalArbitration.None
        terminalClaimed = true
        when (kind) {
            CaptureMetricsTerminalKind.Completed -> when (phase) {
                CaptureMetricsTerminalPhase.BeforeJointReadiness ->
                    CaptureMetricsTerminalArbitration.CompletedBeforeReadiness

                CaptureMetricsTerminalPhase.AfterJointReadiness ->
                    CaptureMetricsTerminalArbitration.CompletedAfterReadiness
            }

            CaptureMetricsTerminalKind.Failed -> when (phase) {
                CaptureMetricsTerminalPhase.BeforeJointReadiness ->
                    CaptureMetricsTerminalArbitration.FailedBeforeReadiness

                CaptureMetricsTerminalPhase.AfterJointReadiness ->
                    CaptureMetricsTerminalArbitration.FailedAfterReadiness
            }
        }
    }

    internal fun submitPendingRefresh(operationIdentity: Long): PrivateExecutorSubmissionResult {
        require(operationIdentity > 0L)
        val attachment = builtInAttachment ?: return PrivateExecutorSubmissionResult.NotSubmitted
        releaseReturnedOperation()
        if (endpoint.hasUnsettledOperation || refreshEndpointOperation != null || !attachment.hasPendingRefresh || closeRequested) {
            return PrivateExecutorSubmissionResult.NotSubmitted
        }

        val operation = OperationOccurrence(
            identity = operationIdentity,
            clock = clock,
            returnCell = OperationReturnCell(CaptureMetricsRefreshEvidence()),
            ownerBag = CaptureMetricsRefreshOwnerBag(attachment),
        )
        val endpointOperation = endpoint.operation(
            occurrence = operation,
            enteredWork = Runnable {
                attachment.performPendingRefresh()
                operation.publishNormalReturn()
            },
        )
        refreshEndpointOperation = endpointOperation
        refreshEndpointOperationReleased = false
        return endpoint.submit(endpointOperation)
    }

    internal fun requestClose(): Boolean {
        val requested = sessionGate.withLock {
            if (closeRequested) return@withLock false
            closeRequested = true
            summary.closeIngressLocked()
            true
        }
        if (requested) {
            builtInAttachment?.fenceCallbacks()
            signalBestEffort()
        }
        return requested
    }

    internal fun submitPendingClose(): PrivateExecutorSubmissionResult {
        releaseReturnedOperation()
        if (!closeRequested || closeSubmitted || endpoint.hasUnsettledOperation) {
            return PrivateExecutorSubmissionResult.NotSubmitted
        }
        val subscriptionOwner = attachmentOperation.settlementGate.withLock {
            val evidence = attachmentOperation.returnCell.evidence
            when {
                evidence.handleDisposition == CaptureMetricsHandleDisposition.Adopted -> evidence.subscriptionOwner
                builtInAttachment?.hasCloseObligation == true && evidence.subscriptionOwner.isBound ->
                    evidence.subscriptionOwner

                else -> null
            }
        } ?: return PrivateExecutorSubmissionResult.NotSubmitted

        val endpointOperation = endpoint.operation(
            occurrence = closeOperation,
            enteredWork = Runnable {
                subscriptionOwner.subscription.close()
                closeOperation.publishNormalReturn()
            },
        )
        closeEndpointOperation = endpointOperation
        closeEndpointOperationReleased = false
        val result = endpoint.submit(endpointOperation)
        if (result != PrivateExecutorSubmissionResult.NotSubmitted) closeSubmitted = true
        return result
    }

    internal fun requestEndpointShutdown(): Boolean {
        if (!prepareEndpointShutdown()) return false
        return endpoint.requestShutdown()
    }

    internal fun prepareEndpointShutdown(): Boolean {
        requestClose()
        submitPendingClose()
        releaseReturnedOperation()
        if (endpoint.isPoisoned) return true
        if (endpoint.hasUnsettledOperation && !endpoint.isPoisoned) return false
        val adoptedHandle = attachmentOperation.settlementGate.withLock {
            val evidence = attachmentOperation.returnCell.evidence
            evidence.handleDisposition == CaptureMetricsHandleDisposition.Adopted ||
                    builtInAttachment?.hasCloseObligation == true
        }
        if (adoptedHandle && !closeSettled) return false
        return true
    }

    internal val isEndpointShutdownReady: Boolean
        get() {
            if (endpoint.isPoisoned) return true
            if (endpoint.hasUnsettledOperation) return false
            val adoptedHandle = attachmentOperation.settlementGate.withLock {
                val evidence = attachmentOperation.returnCell.evidence
                evidence.handleDisposition == CaptureMetricsHandleDisposition.Adopted ||
                        builtInAttachment?.hasCloseObligation == true
            }
            return !adoptedHandle || closeSettled
        }

    private fun performAttachment() {
        val armed = attachmentOperation.settlementGate.withLock {
            when (readinessDeadline.armLocked(clock.nowNanos())) {
                DeadlineArmResult.Armed -> true
                DeadlineArmResult.InvalidClockOrOverflow -> {
                    readinessGuardFailed = true
                    false
                }

                DeadlineArmResult.AlreadySettled -> false
            }
        }
        if (!armed) {
            attachmentOperation.publishThrownReturn(readinessDeadlineGuardCause)
            signalBestEffort()
            return
        }
        signalBestEffort()

        val returnedSubscription: CaptureMetricsSubscription? = when (val selectedSource = source) {
            is CaptureMetricsProvider -> selectedSource.subscribe(observer)
            is BuiltInCaptureMetricsDefinition -> checkNotNull(builtInAttachment).attachOnMetricsLane()
        }
        attachmentOperation.settlementGate.withLock {
            val handleSettlementNanos = clock.nowNanos()
            check(
                attachmentOperation.returnCell.evidence.recordReturnedHandleLocked(
                    subscription = returnedSubscription,
                    settlementNanos = handleSettlementNanos,
                ),
            )
            check(attachmentOperation.returnCell.publishNormalLocked(handleSettlementNanos))
        }
    }

    private fun isJointReadinessPresentLocked(): Boolean {
        val evidence = attachmentOperation.returnCell.evidence
        return evidence.handleDisposition == CaptureMetricsHandleDisposition.Adopted &&
                summary.earliestPositiveMetrics != null &&
                summary.earliestPositiveSampleNanos < readinessDeadline.deadlineNanos &&
                evidence.handleSettlementNanos < readinessDeadline.deadlineNanos
    }

    private fun releaseReturnedOperation() {
        if (!attachmentEndpointOperationReleased && endpoint.releaseSettledOperation(attachmentEndpointOperation)) {
            attachmentEndpointOperationReleased = true
        }
        val currentRefresh = refreshEndpointOperation
        if (currentRefresh != null && !refreshEndpointOperationReleased && endpoint.releaseSettledOperation(currentRefresh)) {
            refreshEndpointOperationReleased = true
            if (currentRefresh.occurrence.returnCell.disposition != OperationReturnDisposition.Thrown) {
                refreshEndpointOperation = null
            }
        }
        val currentClose = closeEndpointOperation
        if (currentClose != null && !closeEndpointOperationReleased && endpoint.releaseSettledOperation(currentClose)) {
            closeEndpointOperationReleased = true
            closeSettled = true
        }
    }

    private inner class MetricsObserver : CaptureMetricsObserver {
        override fun onMetricsChanged(metrics: CaptureMetrics?) {
            publishMetricsIngress(metrics, display = null, displayEpoch = 0L)
        }

        override fun onComplete() {
            val result = sessionGate.withLock {
                summary.publishTerminalLocked(CaptureMetricsTerminalKind.Completed, null)
            }
            signalIngressResult(result)
        }

        override fun onFailure(cause: Throwable) {
            val result = sessionGate.withLock {
                summary.publishTerminalLocked(CaptureMetricsTerminalKind.Failed, cause)
            }
            signalIngressResult(result)
        }
    }

    private fun publishMetricsIngress(metrics: CaptureMetrics?, display: Display?, displayEpoch: Long) {
        val result = sessionGate.withLock {
            summary.publishMetricsLocked(
                metrics = metrics,
                sampleNanos = clock.nowNanos(),
                display = display,
                displayEpoch = displayEpoch,
            )
        }
        signalIngressResult(result)
    }

    private fun signalIngressResult(result: CaptureMetricsIngressPublishResult) {
        if (result == CaptureMetricsIngressPublishResult.Published ||
            result == CaptureMetricsIngressPublishResult.SequenceExhausted
        ) {
            signalBestEffort()
        }
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
            // Durable state is consumed by the future Control owner.
        }
    }
}

internal class BuiltInCaptureMetricsAttachment(
    private val definition: BuiltInCaptureMetricsDefinition,
    private val observer: CaptureMetricsObserver,
    private val sink: BuiltInCaptureMetricsSink,
    private val settlementSignal: SettlementSignal,
    private val subscriptionOwner: CaptureMetricsSubscriptionOwner,
) {
    private val callbacksOpen = AtomicBoolean(true)
    private val epochInvalidated = AtomicBoolean(false)
    private val refreshDirty = AtomicBoolean(true)
    private val closeCalled = AtomicBoolean(false)
    private val registrationAttempted = AtomicBoolean(false)
    private val reusableRealSizePoint = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) Point() else null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val epochExhaustionCause = IllegalStateException("Capture metrics display epoch exhausted")

    private var currentEpochDisplay: Display? = null
    private var currentEpochIdentity = 0L
    private var lastEpochIdentity = 0L
    private var currentEpochWindowContext: Context? = null
    private var currentEpochWindowManager: WindowManager? = null

    private val listener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            signalBoundary(displayId)
        }

        override fun onDisplayRemoved(displayId: Int) {
            signalBoundary(displayId)
        }

        override fun onDisplayChanged(displayId: Int) {
            if (callbacksOpen.get() && displayId == definition.selectedDisplayId) requestRefresh()
        }
    }
    private val subscription = CaptureMetricsSubscription { closeOnMetricsLane() }

    init {
        check(subscriptionOwner.bind(subscription))
    }

    internal val hasPendingRefresh: Boolean
        get() = refreshDirty.get()

    internal val hasCloseObligation: Boolean
        get() = registrationAttempted.get()

    internal fun attachOnMetricsLane(): CaptureMetricsSubscription {
        registrationAttempted.set(true)
        definition.displayManager.registerDisplayListener(listener, mainHandler)
        performPendingRefresh()
        return subscription
    }

    internal fun performPendingRefresh() {
        if (!callbacksOpen.get() || !refreshDirty.getAndSet(false)) return
        if (epochInvalidated.getAndSet(false)) {
            val retiredDisplay = currentEpochDisplay
            val retiredEpoch = currentEpochIdentity
            retireCurrentEpoch()
            sink.publish(null, retiredDisplay, retiredEpoch)
            requestRefresh()
            return
        }

        val selectedDisplay = resolveSelectedDisplay()
        if (selectedDisplay == null || !selectedDisplay.isValid) {
            val retiredDisplay = currentEpochDisplay
            val retiredEpoch = currentEpochIdentity
            retireCurrentEpoch()
            sink.publish(null, retiredDisplay ?: selectedDisplay, retiredEpoch)
            return
        }
        if (currentEpochDisplay !== selectedDisplay) {
            retireCurrentEpoch()
            if (!installDisplayEpoch(selectedDisplay)) return
        }

        val epochDisplay = currentEpochDisplay ?: return
        val epochIdentity = currentEpochIdentity
        if (!selectionStillMatches(epochDisplay) || !epochDisplay.isValid) {
            retireCurrentEpoch()
            sink.publish(null, epochDisplay, epochIdentity)
            return
        }
        val candidate = readCompleteMetrics(epochDisplay)
        if (!selectionStillMatches(epochDisplay) || !epochDisplay.isValid || epochInvalidated.get()) {
            retireCurrentEpoch()
            sink.publish(null, epochDisplay, epochIdentity)
            requestRefresh()
            return
        }
        sink.publish(candidate, epochDisplay, epochIdentity)
    }

    internal fun fenceCallbacks(): Boolean = callbacksOpen.compareAndSet(true, false)

    private fun closeOnMetricsLane() {
        if (!closeCalled.compareAndSet(false, true)) return
        fenceCallbacks()
        refreshDirty.set(false)
        retireCurrentEpoch()
        if (registrationAttempted.get()) {
            definition.displayManager.unregisterDisplayListener(listener)
            registrationAttempted.set(false)
        }
    }

    private fun signalBoundary(displayId: Int) {
        if (!callbacksOpen.get() || displayId != definition.selectedDisplayId) return
        epochInvalidated.set(true)
        requestRefresh()
    }

    private fun requestRefresh() {
        if (!callbacksOpen.get() || !refreshDirty.compareAndSet(false, true)) return
        signalBestEffort()
    }

    private fun resolveSelectedDisplay(): Display? =
        definition.fixedDisplay ?: definition.displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    private fun selectionStillMatches(epochDisplay: Display): Boolean =
        if (definition.fixedDisplay != null) {
            epochDisplay === definition.fixedDisplay
        } else {
            definition.displayManager.getDisplay(Display.DEFAULT_DISPLAY) === epochDisplay
        }

    private fun installDisplayEpoch(epochDisplay: Display): Boolean {
        if (lastEpochIdentity == Long.MAX_VALUE) {
            fenceCallbacks()
            observer.onFailure(epochExhaustionCause)
            return false
        }
        val epochIdentity = lastEpochIdentity + 1L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                definition.applicationContext.createWindowContext(
                    epochDisplay,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    null,
                )
            } else {
                definition.applicationContext.createDisplayContext(epochDisplay)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
            }
            currentEpochWindowContext = windowContext
            currentEpochWindowManager = requireNotNull(windowContext.getSystemService(WindowManager::class.java)) {
                "WindowManager must be available for the selected display"
            }
        }
        currentEpochDisplay = epochDisplay
        currentEpochIdentity = epochIdentity
        lastEpochIdentity = epochIdentity
        return true
    }

    @Suppress("DEPRECATION")
    private fun readCompleteMetrics(epochDisplay: Display): CaptureMetrics? {
        val widthPx: Int
        val heightPx: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = checkNotNull(currentEpochWindowManager).maximumWindowMetrics.bounds
            widthPx = bounds.width()
            heightPx = bounds.height()
        } else {
            val point = checkNotNull(reusableRealSizePoint)
            epochDisplay.getRealSize(point)
            widthPx = point.x
            heightPx = point.y
        }
        val densityDpi = definition.applicationContext
            .createDisplayContext(epochDisplay)
            .resources.configuration.densityDpi
        return if (widthPx > 0 && heightPx > 0 && densityDpi > 0) {
            CaptureMetrics(widthPx, heightPx, densityDpi)
        } else {
            null
        }
    }

    private fun retireCurrentEpoch() {
        currentEpochDisplay = null
        currentEpochIdentity = 0L
        currentEpochWindowContext = null
        currentEpochWindowManager = null
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
            // Sticky refresh state survives a failed signal.
        }
    }
}
