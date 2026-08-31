package io.screenstream.capture.internal.metrics

import io.screenstream.capture.CaptureMetrics
import io.screenstream.capture.CaptureMetricsSource
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.runtime.SerialTaskSlot
import java.lang.AutoCloseable

internal sealed interface SessionMetricsSourceSelection {
    class Explicit(internal val source: CaptureMetricsSource) : SessionMetricsSourceSelection

    class AbsentConfigDefault(internal val source: BuiltInCaptureMetricsSource) : SessionMetricsSourceSelection
}

internal enum class MetricsAttachmentLifecycle { Attaching, Live, Completed, Failed, Retired, }

internal class MetricsSnapshot(
    internal val metrics: CaptureMetrics?,
    internal val lifecycle: MetricsAttachmentLifecycle,
    internal val handleAdopted: Boolean,
    internal val completionCloseSettled: Boolean,
    internal val failure: Throwable?,
) {
    internal fun isReady(requireCompletionCloseSettlement: Boolean): Boolean =
        (metrics != null) && (handleAdopted) &&
                (lifecycle != MetricsAttachmentLifecycle.Failed) && (lifecycle != MetricsAttachmentLifecycle.Retired) &&
                ((lifecycle != MetricsAttachmentLifecycle.Completed) || (!requireCompletionCloseSettlement) || (completionCloseSettled))
}

/**
 * Per-session owner of metrics-source attachment, callback ingress, the current immutable snapshot, and exact
 * subscription-handle retirement.
 *
 * Source callbacks may arrive inline, reentrantly, concurrently, and from arbitrary threads. They are serialized by
 * the owner gate and coalesced onto one queue-less worker slot. The exact returned handle is adopted before later
 * fallible work and closed at most once. Session may read [MetricsSnapshot] while holding its session gate, so this
 * owner must never acquire a Session lock or call Session while its own gate is held.
 */
internal class SessionMetricsOwner(
    workerDispatcher: NonInlineDispatcher,
    private val sourceSelection: SessionMetricsSourceSelection,
    private val requestControlTurn: () -> Unit,
    private val submitTurn: (task: () -> Unit, afterTaskReleased: () -> Unit) -> SerialTaskSlot.Submission = SerialTaskSlot(workerDispatcher)::trySubmit,
) : BuiltInCaptureMetricsSink {
    private enum class CloseState { Open, Requested, Entered, Settled, Failed, }

    private sealed interface Action {
        data object Attach : Action
        class Refresh(val task: Runnable) : Action
        data object Close : Action
        data object NotifyControl : Action
    }

    private val ownerGate = Any()
    private val builtInDispatcher = BuiltInMetricsOwnerDispatcher(::requestRefresh)
    private var submissionInFlight = false
    private var releaseObservedDuringSubmission = false
    private var attachRequested = false
    private var attachEntered = false
    private var retired = false
    private var pendingRefresh: Runnable? = null
    private var enteredRefresh: Runnable? = null
    private var controlNotificationPending = false
    private var exactHandle: AutoCloseable? = null
    private var builtInObservation: BuiltInCaptureMetricsObservation? = null
    private var closeState = CloseState.Open
    private var availability: CaptureMetrics? = null
    private var lifecycle = MetricsAttachmentLifecycle.Attaching
    private var handleAdopted = false
    private var failure: Throwable? = null
    private var snapshot = MetricsSnapshot(
        metrics = null,
        lifecycle = lifecycle,
        handleAdopted = handleAdopted,
        completionCloseSettled = false,
        failure = failure,
    )

    private val observer = object : CaptureMetricsSource.Observer {
        override fun onMetricsChanged(metrics: CaptureMetrics?) {
            stageMetrics(metrics)
        }

        override fun onComplete() {
            stageCompletion()
        }

        override fun onFailure(cause: Throwable) {
            stageFailure(cause)
        }
    }

    internal fun attach() {
        synchronized(ownerGate) {
            check((lifecycle == MetricsAttachmentLifecycle.Attaching) && !attachRequested && !attachEntered && !handleAdopted && !retired) {
                "Metrics source attachment is start-once"
            }
            attachRequested = true
        }
        requestOwnerTurn()
    }

    internal fun readSnapshot(): MetricsSnapshot = synchronized(ownerGate) { snapshot }

    internal fun retire() {
        val schedule = synchronized(ownerGate) {
            if (retired) return
            retired = true
            attachRequested = false
            pendingRefresh = null
            controlNotificationPending = false
            failure = null
            lifecycle = MetricsAttachmentLifecycle.Retired
            installSnapshotLocked()
            if ((exactHandle != null) && (closeState == CloseState.Open)) closeState = CloseState.Requested
            hasPendingActionLocked()
        }
        if (schedule) requestOwnerTurn()
    }

    override fun onMetricsChanged(metrics: CaptureMetrics?) {
        stageMetrics(metrics)
    }

    override fun onObservationFailure(cause: Exception) {
        stageFailure(cause)
    }

    private fun stageMetrics(metrics: CaptureMetrics?) {
        val changed = synchronized(ownerGate) {
            if (retired || !acceptsIngressLocked()) return
            if (availability == metrics) return
            availability = metrics
            installSnapshotLocked()
            controlNotificationPending = true
            true
        }
        if (changed) requestOwnerTurn()
    }

    private fun stageCompletion() {
        val changed = synchronized(ownerGate) {
            if (retired || !acceptsIngressLocked()) return
            lifecycle = MetricsAttachmentLifecycle.Completed
            if ((exactHandle != null) && (closeState == CloseState.Open)) closeState = CloseState.Requested
            installSnapshotLocked()
            controlNotificationPending = true
            true
        }
        if (changed) requestOwnerTurn()
    }

    private fun stageFailure(cause: Throwable) {
        val changed = synchronized(ownerGate) {
            if (retired || !acceptsIngressLocked()) return
            recordFailureLocked(cause)
            true
        }
        if (changed) requestOwnerTurn()
    }

    private fun requestRefresh(task: Runnable) {
        val changed = synchronized(ownerGate) {
            if (retired || !acceptsIngressLocked()) return
            val known = pendingRefresh ?: enteredRefresh
            if ((known != null) && (known !== task)) {
                recordFailureLocked(IllegalStateException("Built-in Metrics refresh identity changed"))
            } else {
                pendingRefresh = task
            }
            true
        }
        if (changed) requestOwnerTurn()
    }

    private fun requestOwnerTurn() {
        synchronized(ownerGate) {
            if (!hasPendingActionLocked() || submissionInFlight) return
            submissionInFlight = true
            releaseObservedDuringSubmission = false
        }

        val submission = try {
            submitTurn(::runTurn, ::onOwnerTurnReleased)
        } catch (failure: Exception) {
            settleDispatchFailure(failure)
            throw failure
        }
        when (submission) {
            SerialTaskSlot.Submission.Accepted,
            SerialTaskSlot.Submission.Occupied -> if (finishSubmission()) requestOwnerTurn()

            is SerialTaskSlot.Submission.Rejected ->
                settleDispatchFailure(submission.cause ?: IllegalStateException("Metrics worker dispatch was rejected"))
        }
    }

    private fun finishSubmission(): Boolean = synchronized(ownerGate) {
        check(submissionInFlight)
        submissionInFlight = false
        releaseObservedDuringSubmission.also { releaseObservedDuringSubmission = false }
    }

    private fun onOwnerTurnReleased() {
        val requestNow = synchronized(ownerGate) {
            if (submissionInFlight) {
                releaseObservedDuringSubmission = true
                false
            } else {
                true
            }
        }
        if (requestNow) requestOwnerTurn()
    }

    private fun settleDispatchFailure(cause: Exception) {
        val notify = synchronized(ownerGate) {
            check(submissionInFlight)
            submissionInFlight = false
            releaseObservedDuringSubmission = false
            attachRequested = false
            pendingRefresh = null
            if (!retired) {
                recordFailureLocked(cause)
                controlNotificationPending = false
                true
            } else {
                false
            }
        }
        if (notify) try {
            requestControlTurn()
        } catch (_: Exception) {
        }
    }

    private fun runTurn() {
        try {
            when (val action = takeAction()) {
                Action.Attach -> runAttachment()
                is Action.Refresh -> runRefresh(action.task)
                Action.Close -> runClose()
                Action.NotifyControl -> runNotification()
                null -> Unit
            }
        } catch (failure: Exception) {
            synchronized(ownerGate) {
                if (!retired) recordFailureLocked(failure)
            }
        }
    }

    private fun takeAction(): Action? = synchronized(ownerGate) {
        when {
            controlNotificationPending && !retired -> {
                controlNotificationPending = false
                Action.NotifyControl
            }

            (closeState == CloseState.Requested) && (exactHandle != null) -> {
                closeState = CloseState.Entered
                Action.Close
            }

            attachRequested && !retired -> {
                attachRequested = false
                attachEntered = true
                Action.Attach
            }

            (pendingRefresh != null) && !retired -> {
                val task = checkNotNull(pendingRefresh)
                pendingRefresh = null
                enteredRefresh = task
                Action.Refresh(task)
            }

            else -> null
        }
    }

    private fun runAttachment() {
        var createdObservation: BuiltInCaptureMetricsObservation? = null
        val handle: AutoCloseable? = try {
            when (val selection = sourceSelection) {
                is SessionMetricsSourceSelection.Explicit -> selection.source.subscribe(observer)
                is SessionMetricsSourceSelection.AbsentConfigDefault -> {
                    BuiltInCaptureMetricsObservation(
                        source = selection.source,
                        sink = this,
                        ownerDispatcher = builtInDispatcher,
                    ).also { observation ->
                        createdObservation = observation
                        synchronized(ownerGate) { builtInObservation = observation }
                        observation.startOnMetricsOwner()
                    }
                }
            }
        } catch (failure: Exception) {
            closeUnadoptedBuiltIn(createdObservation)
            synchronized(ownerGate) { if (!retired) recordFailureLocked(failure) }
            return
        }

        @Suppress("SENSELESS_COMPARISON")
        if (handle == null) {
            synchronized(ownerGate) {
                if (!retired) recordFailureLocked(IllegalStateException("Metrics source returned no close handle"))
            }
            return
        }

        synchronized(ownerGate) {
            check(exactHandle == null)
            exactHandle = handle
            handleAdopted = true
            if (!retired && (lifecycle == MetricsAttachmentLifecycle.Attaching)) {
                lifecycle = MetricsAttachmentLifecycle.Live
            }
            if (retired || (lifecycle == MetricsAttachmentLifecycle.Completed) || (lifecycle == MetricsAttachmentLifecycle.Failed)) {
                closeState = CloseState.Requested
            }
            installSnapshotLocked()
            if (!retired) controlNotificationPending = true
        }
    }

    private fun closeUnadoptedBuiltIn(observation: BuiltInCaptureMetricsObservation?) {
        if (observation == null) return
        try {
            observation.closeOnMetricsOwner()
        } catch (_: Exception) {
        }
        synchronized(ownerGate) {
            if (builtInObservation === observation) builtInObservation = null
        }
    }

    private fun runRefresh(task: Runnable) {
        try {
            task.run()
        } catch (failure: Exception) {
            synchronized(ownerGate) {
                if (enteredRefresh === task) enteredRefresh = null
            }
            throw failure
        }
        synchronized(ownerGate) {
            if (enteredRefresh === task) enteredRefresh = null
        }
    }

    private fun runClose() {
        lateinit var handle: AutoCloseable
        var ownerCreatedBuiltIn: BuiltInCaptureMetricsObservation? = null
        synchronized(ownerGate) {
            check(closeState == CloseState.Entered)
            handle = checkNotNull(exactHandle)
            ownerCreatedBuiltIn = builtInObservation?.takeIf { it === handle }
        }
        val observation = ownerCreatedBuiltIn
        var failure: Exception? = null
        try {
            if (observation != null) {
                failure = observation.closeOnMetricsOwner()
            } else {
                handle.close()
            }
        } catch (cause: Exception) {
            failure = cause
        }

        val closeFailure = failure
        synchronized(ownerGate) {
            check((exactHandle === handle) && (closeState == CloseState.Entered))
            if (closeFailure == null) {
                closeState = CloseState.Settled
                exactHandle = null
                if (builtInObservation === handle) builtInObservation = null
                installSnapshotLocked()
                if (!retired) controlNotificationPending = true
            } else {
                closeState = CloseState.Failed
                if (!retired) recordFailureLocked(closeFailure)
            }
        }
    }

    private fun runNotification() {
        try {
            requestControlTurn()
        } catch (failure: Exception) {
            synchronized(ownerGate) {
                if (!retired) {
                    recordFailureLocked(failure)
                    controlNotificationPending = false
                }
            }
        }
    }

    private fun recordFailureLocked(cause: Throwable) {
        check(Thread.holdsLock(ownerGate))
        if (failure == null) failure = cause
        pendingRefresh = null
        lifecycle = MetricsAttachmentLifecycle.Failed
        if ((exactHandle != null) && (closeState == CloseState.Open)) closeState = CloseState.Requested
        installSnapshotLocked()
        controlNotificationPending = true
    }

    private fun acceptsIngressLocked(): Boolean {
        check(Thread.holdsLock(ownerGate))
        return when (lifecycle) {
            MetricsAttachmentLifecycle.Attaching,
            MetricsAttachmentLifecycle.Live -> true

            MetricsAttachmentLifecycle.Completed,
            MetricsAttachmentLifecycle.Failed,
            MetricsAttachmentLifecycle.Retired -> false
        }
    }

    private fun installSnapshotLocked() {
        check(Thread.holdsLock(ownerGate))
        val current = snapshot
        val settled = (lifecycle == MetricsAttachmentLifecycle.Completed) && (closeState == CloseState.Settled)
        val nextMetrics = availability
        if ((current.metrics == nextMetrics) && (current.lifecycle == lifecycle) && (current.handleAdopted == handleAdopted) &&
            (current.completionCloseSettled == settled) && (current.failure === failure)
        ) {
            return
        }
        snapshot = MetricsSnapshot(
            metrics = nextMetrics,
            lifecycle = lifecycle,
            handleAdopted = handleAdopted,
            completionCloseSettled = settled,
            failure = failure,
        )
    }

    private fun hasPendingActionLocked(): Boolean {
        check(Thread.holdsLock(ownerGate))
        return ((closeState == CloseState.Requested) && (exactHandle != null)) ||
                (attachRequested && !retired) ||
                ((pendingRefresh != null) && !retired) ||
                (controlNotificationPending && !retired)
    }
}
