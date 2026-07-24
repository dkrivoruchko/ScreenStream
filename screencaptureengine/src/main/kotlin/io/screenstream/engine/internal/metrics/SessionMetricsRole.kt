package io.screenstream.engine.internal.metrics

import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsObserver
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.CaptureMetricsSubscription
import io.screenstream.engine.internal.runtime.AsyncSerialView
import io.screenstream.engine.internal.runtime.ElapsedRealtimeClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The one Session Metrics attachment and its bounded observation summary. Control supplies the sole
 * [sessionGate]; this role supplies facts and exact ownership only.
 */
internal class SessionMetricsRole internal constructor(
    private val sessionGate: Any,
    private val serialView: AsyncSerialView,
    private val elapsedRealtimeClock: ElapsedRealtimeClock,
    private val source: CaptureMetricsSource,
    internal val capsule: MetricsCapsule,
    private val signalControl: () -> Unit,
    private val lateCutoffSink: MetricsLateCutoffSink,
    /** Must be called only while [sessionGate] is held. */
    private val ordinaryAdmissionOpenLocked: () -> Boolean,
) : BuiltInCaptureMetricsSink {
    private enum class TerminalKind { Completed, Failed }

    private val sourceIdentity = MetricsSourceIdentity(
        source = source,
        kind = when (source) {
            is BuiltInCaptureMetricsSource -> if (source.fixedDisplay == null) {
                MetricsSourceKind.SessionDefaultDisplay
            } else {
                MetricsSourceKind.FixedDisplay
            }

            else -> MetricsSourceKind.Custom
        },
    )
    private val sequenceExhaustedCause = IllegalStateException("Metrics observation sequence exhausted")
    private val firstPositiveExpiredCause = IllegalStateException("First positive capture metrics expired")
    private val invalidElapsedRealtimeCause = IllegalStateException("Elapsed realtime must be nonnegative")
    private val elapsedRealtimeRegressionCause = IllegalStateException("Elapsed realtime regressed")
    private val refreshDispatchRejectedCause = IllegalStateException("Metrics refresh dispatch rejected")
    private val refreshDispatchConflictCause = IllegalStateException("Metrics refresh dispatch conflicted")
    private val roleTaskActive = AtomicBoolean(false)
    private val deferredBuiltInTask = AtomicReference<Runnable?>(null)
    private val deferredClose = AtomicReference<MetricsCloseDispatch?>(null)
    private val serialSubmissionAmbiguity = AtomicReference<Throwable?>(null)

    /** All fields below are confined by sessionGate. */
    private var ingressOpen = true
    private var cutoff = false
    private var firstActiveSeen = false
    private var sequence = 0L
    private var lastObservationNanos = Long.MIN_VALUE
    private var boundary: MetricsFirstPositiveBoundary? = null
    private var attachmentOutcome: MetricsAttachmentOutcome = MetricsAttachmentOutcome.NotQueued
    private var ambiguousAttachmentOutcome: MetricsAttachmentOutcome.DispatchFailed? = null
    private var attachmentSubmissionFatal: MetricsAttachmentOutcome.DirectFatal? = null
    private var closeOutcome: MetricsCloseOutcome = MetricsCloseOutcome.NotRequested
    private var ambiguousCloseOutcome: MetricsCloseOutcome.DispatchFailed? = null
    private var closeSubmissionFatal: MetricsCloseOutcome.DirectFatal? = null
    private var jointReady = false
    private var handleReturnedAtNanos = Long.MIN_VALUE
    private var expiry: MetricsExpiryFact? = null
    private var ingressFailure: MetricsIngressFailureFact? = null

    private var hasEarliestPositive = false
    private var earliestSequence = 0L
    private var earliestAtNanos = Long.MIN_VALUE
    private var earliestMetrics: CaptureMetrics? = null
    private var earliestDisplay: android.view.Display? = null
    private var earliestEpoch: BuiltInCaptureMetricsEpoch? = null
    private var earliestTimely = false

    private var hasPreActiveLoss = false
    private var lossSequence = 0L
    private var lossAtNanos = Long.MIN_VALUE
    private var lossDisplay: android.view.Display? = null
    private var lossEpoch: BuiltInCaptureMetricsEpoch? = null

    private var hasLatest = false
    private var latestSequence = 0L
    private var latestAtNanos = Long.MIN_VALUE
    private var latestMetrics: CaptureMetrics? = null
    private var latestDisplay: android.view.Display? = null
    private var latestEpoch: BuiltInCaptureMetricsEpoch? = null

    private var terminalKind: TerminalKind? = null
    private var terminalSequence = 0L
    private var terminalAtNanos = Long.MIN_VALUE
    private var terminalPhase = MetricsTerminalPhase.BeforeJointReadiness
    private var terminalCause: Throwable? = null

    private val observer = object : CaptureMetricsObserver {
        override fun onMetricsChanged(metrics: CaptureMetrics?) {
            acceptSample(metrics, null, null)
        }

        override fun onComplete() {
            acceptTerminal(TerminalKind.Completed, null)
        }

        override fun onFailure(cause: Throwable) {
            acceptTerminal(TerminalKind.Failed, cause)
        }
    }

    private val builtInDispatcher = BuiltInMetricsDispatcher { task -> dispatchBuiltIn(task) }

    internal fun queueAttachmentLocked(): MetricsAttachmentDispatch {
        requireGate()
        check(attachmentOutcome === MetricsAttachmentOutcome.NotQueued)
        check(!semanticCutoffLocked())
        capsule.queueAttachment(source, observer)
        attachmentOutcome = MetricsAttachmentOutcome.Queued
        return MetricsAttachmentDispatch(capsule)
    }

    internal fun submitAttachment(dispatch: MetricsAttachmentDispatch): MetricsSubmissionOutcome {
        check(dispatch.capsule === capsule)
        val permitReleased = AtomicBoolean(false)
        val accepted = try {
            submitSerialTask(permitReleased) { runAttachment() }
        } catch (failure: Exception) {
            settleAttachmentSubmissionAmbiguity(failure, permitMayRemainOccupied = !permitReleased.get())
            return MetricsSubmissionOutcome.Failed(failure)
        } catch (failure: Throwable) {
            if (!permitReleased.get()) serialSubmissionAmbiguity.compareAndSet(null, failure)
            synchronized(sessionGate) {
                ingressOpen = false
                capsule.requestClose()
                if (!semanticCutoffLocked()) {
                    attachmentSubmissionFatal = MetricsAttachmentOutcome.DirectFatal(failure)
                }
            }
            throw failure
        }
        return if (accepted) {
            MetricsSubmissionOutcome.Accepted
        } else {
            settleAttachmentDispatchRejection()
            MetricsSubmissionOutcome.Rejected
        }
    }

    internal fun summaryLocked(): SessionMetricsSummary {
        requireGate()
        val positive = positiveFactLocked()
        val loss = if (hasPreActiveLoss) {
            MetricsLossBeforeFirstActiveFact(
                lossSequence,
                lossAtNanos,
                sourceKey(lossDisplay, lossEpoch),
            )
        } else null
        val latest = if (!hasLatest) null else latestMetrics?.let {
            MetricsAvailabilityFact.Available(
                latestSequence,
                latestAtNanos,
                sourceKey(latestDisplay, latestEpoch),
                it,
            )
        } ?: MetricsAvailabilityFact.Unavailable(
            latestSequence,
            latestAtNanos,
            sourceKey(latestDisplay, latestEpoch),
        )
        val terminal = when (terminalKind) {
            TerminalKind.Completed -> MetricsTerminalFact.Completed(
                terminalSequence,
                terminalAtNanos,
                terminalPhase,
            )

            TerminalKind.Failed -> MetricsTerminalFact.Failed(
                terminalSequence,
                terminalAtNanos,
                terminalPhase,
                checkNotNull(terminalCause),
            )

            null -> null
        }
        return SessionMetricsSummary(
            sourceIdentity = sourceIdentity,
            observationSequence = sequence,
            boundary = boundary,
            earliestPositive = positive,
            lossBeforeFirstActive = loss,
            latest = latest,
            firstTerminal = terminal,
            attachmentOutcome = attachmentSubmissionFatal ?: ambiguousAttachmentOutcome ?: attachmentOutcome,
            jointReadiness = if (jointReady) {
                MetricsJointReadinessFact(checkNotNull(positive), handleReturnedAtNanos)
            } else null,
            expiry = expiry,
            closeOutcome = closeSubmissionFatal ?: ambiguousCloseOutcome ?: closeOutcome,
            ingressFailure = ingressFailure,
            cutoff = cutoff,
        )
    }

    /** Control calls this only after it has committed the Session's first Active under the same gate. */
    internal fun markFirstActiveLocked() {
        requireGate()
        firstActiveSeen = true
    }

    internal fun expireFirstPositiveLocked(nowNanos: Long): MetricsExpiryOutcome {
        requireGate()
        val exactBoundary = boundary ?: return MetricsExpiryOutcome.NotExpired
        if (jointReady || expiry != null || semanticCutoffLocked()) return MetricsExpiryOutcome.AlreadyResolved
        if (nowNanos < exactBoundary.deadlineNanos) return MetricsExpiryOutcome.NotExpired
        val fact = MetricsExpiryFact(nowNanos, exactBoundary)
        expiry = fact
        ingressOpen = false
        capsule.requestClose()
        closeOutcome = MetricsCloseOutcome.AwaitingAttachmentReturn
        return MetricsExpiryOutcome.Expired(fact, queueCloseLocked())
    }

    internal fun requestCloseLocked(): MetricsCloseDispatch? {
        requireGate()
        ingressOpen = false
        capsule.requestClose()
        if (closeOutcome === MetricsCloseOutcome.NotRequested) {
            closeOutcome = MetricsCloseOutcome.AwaitingAttachmentReturn
        }
        return queueCloseLocked()
    }

    internal fun submitClose(dispatch: MetricsCloseDispatch): MetricsSubmissionOutcome {
        check(dispatch.capsule === capsule)
        val permitReleased = AtomicBoolean(false)
        val accepted = try {
            submitSerialTask(permitReleased) { runClose() }
        } catch (failure: Exception) {
            settleCloseSubmissionAmbiguity(failure, permitMayRemainOccupied = !permitReleased.get())
            return MetricsSubmissionOutcome.Failed(failure)
        } catch (failure: Throwable) {
            if (!permitReleased.get()) serialSubmissionAmbiguity.compareAndSet(null, failure)
            synchronized(sessionGate) {
                closeSubmissionFatal = MetricsCloseOutcome.DirectFatal(failure)
                ingressOpen = false
                capsule.requestClose()
            }
            throw failure
        }
        val ambiguity = serialSubmissionAmbiguity.get()
        return when {
            accepted -> MetricsSubmissionOutcome.Accepted
            roleTaskActive.get() -> {
                check(deferredClose.compareAndSet(null, dispatch) || deferredClose.get() === dispatch)
                MetricsSubmissionOutcome.Accepted
            }

            ambiguity is Exception -> MetricsSubmissionOutcome.Failed(ambiguity)
            ambiguity != null -> throw ambiguity
            else -> {
                settleCloseDispatchRejection()
                MetricsSubmissionOutcome.Rejected
            }
        }
    }

    internal fun cutoffLocked(): MetricsCutoff {
        requireGate()
        if (!cutoff) {
            cutoff = true
            ingressOpen = false
            capsule.requestClose()
            if (closeOutcome === MetricsCloseOutcome.NotRequested) {
                closeOutcome = MetricsCloseOutcome.AwaitingAttachmentReturn
            }
            if (capsule.entryKind == MetricsEntryKind.Attachment) capsule.markCutoffInert()
        }
        val closeDispatch = queueCloseLocked()
        val retirement = if (capsule.hasUnresolvedOwnership) {
            MetricsRetirement.ReturnExpected(capsule)
        } else {
            MetricsRetirement.Closed
        }
        return MetricsCutoff(retirement, closeDispatch)
    }

    override fun onMetricsChanged(
        metrics: CaptureMetrics?,
        display: android.view.Display?,
        epoch: BuiltInCaptureMetricsEpoch?,
    ) {
        acceptSample(metrics, display, epoch)
    }

    override fun onObservationFailure(cause: Exception) {
        acceptTerminal(TerminalKind.Failed, cause)
    }

    override fun onCloseCompleted() = Unit

    override fun onCloseFailure(cause: Exception) = Unit

    private fun acceptSample(
        metrics: CaptureMetrics?,
        display: android.view.Display?,
        epoch: BuiltInCaptureMetricsEpoch?,
    ) {
        var changed = false
        var closeDispatch: MetricsCloseDispatch? = null
        var directFatal: Throwable? = null
        synchronized(sessionGate) {
            if (!ingressOpen || semanticCutoffLocked()) return@synchronized
            val observedAt = try {
                elapsedRealtimeClock.nanos()
            } catch (failure: Exception) {
                failIngressLocked(failure)
                closeDispatch = queueCloseLocked()
                changed = true
                return@synchronized
            } catch (failure: Throwable) {
                failIngressLocked(failure)
                closeDispatch = queueCloseLocked()
                directFatal = failure
                changed = true
                return@synchronized
            }
            if (observedAt < 0L) {
                failIngressLocked(invalidElapsedRealtimeCause)
                closeDispatch = queueCloseLocked()
                changed = true
                return@synchronized
            }
            val startNanos = boundary?.startNanos ?: Long.MAX_VALUE
            if (observedAt < startNanos || sequence > 0L && observedAt < lastObservationNanos) {
                failIngressLocked(elapsedRealtimeRegressionCause)
                closeDispatch = queueCloseLocked()
                changed = true
                return@synchronized
            }
            val next = nextSequenceLocked()
            if (next == null) {
                closeDispatch = queueCloseLocked()
                changed = true
                return@synchronized
            }
            hasLatest = true
            latestSequence = next
            latestAtNanos = observedAt
            latestMetrics = metrics
            latestDisplay = display
            latestEpoch = epoch
            lastObservationNanos = observedAt
            if (metrics != null && !hasEarliestPositive) {
                hasEarliestPositive = true
                earliestSequence = next
                earliestAtNanos = observedAt
                earliestMetrics = metrics
                earliestDisplay = display
                earliestEpoch = epoch
                earliestTimely = boundary?.let { observedAt < it.deadlineNanos } == true
                if (attachmentOutcome is MetricsAttachmentOutcome.ReturnedWithHandle && earliestTimely &&
                    !hasPreActiveLoss && terminalKind == null && expiry == null
                ) {
                    jointReady = true
                }
            } else if (metrics == null && hasEarliestPositive && !firstActiveSeen && !hasPreActiveLoss) {
                hasPreActiveLoss = true
                lossSequence = next
                lossAtNanos = observedAt
                lossDisplay = display
                lossEpoch = epoch
            }
            val exactBoundary = boundary
            if (!jointReady && expiry == null && exactBoundary != null &&
                observedAt >= exactBoundary.deadlineNanos
            ) {
                expiry = MetricsExpiryFact(observedAt, exactBoundary)
                ingressOpen = false
                capsule.requestClose()
                closeOutcome = MetricsCloseOutcome.AwaitingAttachmentReturn
                closeDispatch = queueCloseLocked()
            }
            changed = true
        }
        if (changed) signalControl()
        closeDispatch?.let(::submitClose)
        directFatal?.let { throw it }
    }

    private fun acceptTerminal(kind: TerminalKind, cause: Throwable?) {
        var changed = false
        var closeDispatch: MetricsCloseDispatch? = null
        var directFatal: Throwable? = null
        synchronized(sessionGate) {
            if (!ingressOpen || semanticCutoffLocked()) return@synchronized
            val observedAt = try {
                elapsedRealtimeClock.nanos()
            } catch (failure: Exception) {
                failIngressLocked(failure)
                closeDispatch = queueCloseLocked()
                changed = true
                return@synchronized
            } catch (failure: Throwable) {
                failIngressLocked(failure)
                closeDispatch = queueCloseLocked()
                directFatal = failure
                changed = true
                return@synchronized
            }
            if (observedAt < 0L) {
                failIngressLocked(invalidElapsedRealtimeCause)
                closeDispatch = queueCloseLocked()
                changed = true
                return@synchronized
            }
            val startNanos = boundary?.startNanos ?: Long.MAX_VALUE
            if (observedAt < startNanos || sequence > 0L && observedAt < lastObservationNanos) {
                failIngressLocked(elapsedRealtimeRegressionCause)
                closeDispatch = queueCloseLocked()
                changed = true
                return@synchronized
            }
            val next = nextSequenceLocked()
            if (next == null) {
                closeDispatch = queueCloseLocked()
                changed = true
                return@synchronized
            }
            terminalKind = kind
            terminalSequence = next
            terminalAtNanos = observedAt
            terminalPhase = if (jointReady) {
                MetricsTerminalPhase.AfterJointReadiness
            } else {
                MetricsTerminalPhase.BeforeJointReadiness
            }
            terminalCause = cause
            lastObservationNanos = observedAt
            ingressOpen = false
            capsule.requestClose()
            closeOutcome = MetricsCloseOutcome.AwaitingAttachmentReturn
            closeDispatch = queueCloseLocked()
            changed = true
        }
        if (changed) signalControl()
        closeDispatch?.let(::submitClose)
        directFatal?.let { throw it }
    }

    private fun runAttachment() {
        var builtIn: BuiltInCaptureMetricsObservation? = null
        var cutoffBeforeEntry = false
        var boundaryFailed = false
        synchronized(sessionGate) {
            if (capsule.entryState == MetricsEntryState.CutoffInert || semanticCutoffLocked()) {
                if (capsule.entryState == MetricsEntryState.Queued) capsule.markCutoffInert()
                capsule.closeCutoffInertEntry()
                cutoffBeforeEntry = true
            } else {
                check(capsule.markAttachmentEntered(source))
                attachmentOutcome = MetricsAttachmentOutcome.Entered
                val start = try {
                    elapsedRealtimeClock.nanos()
                } catch (failure: Exception) {
                    settleBoundaryFailureLocked(failure)
                    boundaryFailed = true
                    Long.MIN_VALUE
                } catch (failure: Throwable) {
                    settleAttachmentBeforeCallFatalLocked(failure)
                    throw failure
                }
                if (!boundaryFailed) {
                    try {
                        check(start >= 0L) { "elapsed realtime must be nonnegative" }
                        val deadline = Math.addExact(start, FIRST_METRICS_POSITIVE_SAFETY_NANOS)
                        boundary = MetricsFirstPositiveBoundary(
                            start,
                            deadline,
                            firstPositiveExpiredCause,
                        )
                    } catch (failure: Exception) {
                        settleBoundaryFailureLocked(failure)
                        boundaryFailed = true
                    }
                }
            }
        }
        signalControl()
        if (cutoffBeforeEntry) {
            lateCutoffSink.onLateMetricsFact(
                MetricsLateCutoffFact.AttachmentSettled(capsule, false, null),
            )
            return
        }
        if (boundaryFailed) return

        if (source is BuiltInCaptureMetricsSource) {
            val constructed = try {
                source.newObservation(this, builtInDispatcher)
            } catch (failure: Exception) {
                val late = synchronized(sessionGate) {
                    if (semanticCutoffLocked()) {
                        capsule.recordAttachmentNotCalled(source)
                        capsule.requestClose()
                        MetricsLateCutoffFact.AttachmentSettled(capsule, false, failure)
                    } else {
                        settleBoundaryFailureLocked(failure)
                        null
                    }
                }
                signalControl()
                late?.let(lateCutoffSink::onLateMetricsFact)
                return
            } catch (failure: Throwable) {
                val late = synchronized(sessionGate) {
                    if (semanticCutoffLocked()) {
                        capsule.recordAttachmentNotCalled(source)
                        capsule.requestClose()
                        MetricsLateCutoffFact.AttachmentSettled(capsule, false, failure)
                    } else {
                        settleAttachmentBeforeCallFatalLocked(failure)
                        null
                    }
                }
                signalControl()
                late?.let(lateCutoffSink::onLateMetricsFact)
                throw failure
            }
            var closeWithoutStart: CaptureMetricsSubscription? = null
            var lateConstruction: MetricsLateCutoffFact? = null
            synchronized(sessionGate) {
                capsule.adoptBuiltInObservation(source, constructed)
                builtIn = constructed
                if (semanticCutoffLocked()) {
                    capsule.recordAttachmentReturn(source, constructed)
                    capsule.requestClose()
                    closeWithoutStart = enterInlineCloseLocked()
                    lateConstruction = MetricsLateCutoffFact.AttachmentSettled(
                        capsule,
                        true,
                        null,
                    )
                }
            }
            if (closeWithoutStart != null) {
                signalControl()
                lateConstruction?.let(lateCutoffSink::onLateMetricsFact)
                closeEnteredHandle(checkNotNull(closeWithoutStart))
                return
            }
        }

        var returnedHandle: CaptureMetricsSubscription? = null
        var returnedFailure: Exception? = null
        try {
            returnedHandle = if (builtIn != null) {
                builtIn.startOnMetricsRole()
                builtIn
            } else {
                nullableInteropHandle(source.subscribe(observer))
            }
        } catch (failure: Exception) {
            returnedFailure = failure
        } catch (failure: Throwable) {
            val late = synchronized(sessionGate) {
                if (semanticCutoffLocked()) {
                    capsule.recordAttachmentFailure(source)
                    capsule.requestClose()
                    MetricsLateCutoffFact.AttachmentSettled(capsule, false, failure)
                } else {
                    settleAttachmentReturnedFatalLocked(failure)
                    null
                }
            }
            signalControl()
            late?.let(lateCutoffSink::onLateMetricsFact)
            throw failure
        }

        var closeInline: CaptureMetricsSubscription? = null
        var lateFact: MetricsLateCutoffFact? = null
        var postReturnFatal: Throwable? = null
        synchronized(sessionGate) {
            if (semanticCutoffLocked()) {
                if (returnedHandle != null) capsule.recordAttachmentReturn(source, returnedHandle)
                else capsule.recordAttachmentFailure(source)
                ingressOpen = false
                capsule.requestClose()
                closeInline = enterInlineCloseLocked()
                lateFact = MetricsLateCutoffFact.AttachmentSettled(
                    capsule,
                    capsule.hasUnresolvedOwnership,
                    returnedFailure,
                )
                return@synchronized
            }
            val returnedAt = try {
                elapsedRealtimeClock.nanos()
            } catch (failure: Exception) {
                if (returnedHandle != null) capsule.recordAttachmentReturn(source, returnedHandle)
                else capsule.recordAttachmentFailure(source)
                attachmentOutcome = MetricsAttachmentOutcome.BoundaryFailed(failure)
                ingressOpen = false
                capsule.requestClose()
                closeInline = enterInlineCloseLocked()
                if (semanticCutoffLocked()) {
                    lateFact = MetricsLateCutoffFact.AttachmentSettled(
                        capsule,
                        capsule.hasUnresolvedOwnership,
                        failure,
                    )
                }
                return@synchronized
            } catch (failure: Throwable) {
                if (returnedHandle != null) capsule.recordAttachmentReturn(source, returnedHandle)
                else capsule.recordAttachmentFailure(source)
                settleAttachmentFatalAfterReturnLocked(failure)
                postReturnFatal = failure
                if (semanticCutoffLocked()) {
                    lateFact = MetricsLateCutoffFact.AttachmentSettled(
                        capsule,
                        capsule.hasUnresolvedOwnership,
                        failure,
                    )
                }
                return@synchronized
            }
            val exactBoundary = checkNotNull(boundary)
            if (returnedAt < exactBoundary.startNanos || sequence > 0L && returnedAt < lastObservationNanos) {
                if (returnedHandle != null) capsule.recordAttachmentReturn(source, returnedHandle)
                else capsule.recordAttachmentFailure(source)
                attachmentOutcome = MetricsAttachmentOutcome.BoundaryFailed(elapsedRealtimeRegressionCause)
                ingressOpen = false
                capsule.requestClose()
                closeInline = enterInlineCloseLocked()
                if (semanticCutoffLocked()) {
                    lateFact = MetricsLateCutoffFact.AttachmentSettled(
                        capsule,
                        capsule.hasUnresolvedOwnership,
                        elapsedRealtimeRegressionCause,
                    )
                }
                return@synchronized
            }
            val timelyReturn = returnedAt < exactBoundary.deadlineNanos && expiry == null
            if (returnedFailure != null) {
                capsule.recordAttachmentFailure(source)
                attachmentOutcome = MetricsAttachmentOutcome.Thrown(returnedAt, returnedFailure, timelyReturn)
                ingressOpen = false
            } else {
                capsule.recordAttachmentReturn(source, returnedHandle)
                attachmentOutcome = if (returnedHandle == null) {
                    MetricsAttachmentOutcome.InvalidHandle(returnedAt, timelyReturn)
                } else {
                    handleReturnedAtNanos = returnedAt
                    MetricsAttachmentOutcome.ReturnedWithHandle(returnedAt)
                }
                if (returnedHandle == null) ingressOpen = false
            }
            if (!timelyReturn && expiry == null && !jointReady) {
                expiry = MetricsExpiryFact(returnedAt, exactBoundary)
                ingressOpen = false
            }
            if (returnedFailure == null && returnedHandle != null && timelyReturn &&
                hasEarliestPositive && earliestTimely && !hasPreActiveLoss && terminalKind == null &&
                !semanticCutoffLocked()
            ) {
                jointReady = true
            }
            if (returnedFailure != null || returnedHandle == null || semanticCutoffLocked() || !ingressOpen) {
                capsule.requestClose()
                if (closeOutcome === MetricsCloseOutcome.NotRequested) {
                    closeOutcome = MetricsCloseOutcome.AwaitingAttachmentReturn
                }
            }
            closeInline = enterInlineCloseLocked()
            if (semanticCutoffLocked()) {
                lateFact = MetricsLateCutoffFact.AttachmentSettled(
                    capsule,
                    capsule.hasUnresolvedOwnership,
                    returnedFailure,
                )
            }
        }
        signalControl()
        lateFact?.let(lateCutoffSink::onLateMetricsFact)
        postReturnFatal?.let { throw it }
        closeInline?.let(::closeEnteredHandle)
    }

    private fun runClose() {
        val handle = synchronized(sessionGate) {
            val queued = capsule.handleOwnership as? MetricsHandleOwnership.CloseQueued ?: return
            if (!capsule.markCloseEntered(queued.handle)) return
            closeOutcome = MetricsCloseOutcome.Entered
            queued.handle
        }
        closeEnteredHandle(handle)
    }

    private fun closeEnteredHandle(handle: CaptureMetricsSubscription) {
        var failure: Exception? = null
        try {
            failure = if (handle is BuiltInCaptureMetricsObservation) {
                handle.closeOnMetricsRole()
            } else {
                handle.close()
                null
            }
        } catch (cause: Exception) {
            failure = cause
        } catch (cause: Throwable) {
            val late = synchronized(sessionGate) {
                capsule.recordCloseFailure(handle)
                closeOutcome = MetricsCloseOutcome.DirectFatal(cause)
                if (semanticCutoffLocked()) MetricsLateCutoffFact.CloseSettled(capsule, true, cause) else null
            }
            signalControl()
            late?.let(lateCutoffSink::onLateMetricsFact)
            throw cause
        }
        val late = synchronized(sessionGate) {
            if (failure == null) {
                capsule.recordCloseReturn(handle)
                closeOutcome = MetricsCloseOutcome.Closed
            } else {
                capsule.recordCloseFailure(handle)
                closeOutcome = MetricsCloseOutcome.Failed(checkNotNull(failure))
            }
            if (semanticCutoffLocked()) {
                MetricsLateCutoffFact.CloseSettled(capsule, failure != null, failure)
            } else null
        }
        signalControl()
        late?.let(lateCutoffSink::onLateMetricsFact)
    }

    private fun enterInlineCloseLocked(): CaptureMetricsSubscription? {
        val handle = capsule.queueCloseIfReady() ?: return null
        check(capsule.markCloseEntered(handle))
        closeOutcome = MetricsCloseOutcome.Entered
        return handle
    }

    private fun queueCloseLocked(): MetricsCloseDispatch? {
        val handle = capsule.queueCloseIfReady() ?: return null
        closeOutcome = MetricsCloseOutcome.Queued
        check((capsule.handleOwnership as MetricsHandleOwnership.CloseQueued).handle === handle)
        return MetricsCloseDispatch(capsule)
    }

    private fun settleAttachmentDispatchRejection() {
        val late = synchronized(sessionGate) {
            if (capsule.entryState == MetricsEntryState.CutoffInert || semanticCutoffLocked()) {
                if (capsule.entryState == MetricsEntryState.Queued) capsule.markCutoffInert()
                capsule.closeCutoffInertEntry()
                MetricsLateCutoffFact.AttachmentSettled(capsule, false, null)
            } else {
                capsule.recordAttachmentDispatchFailure(source)
                attachmentOutcome = MetricsAttachmentOutcome.DispatchFailed(null)
                ingressOpen = false
                null
            }
        }
        signalControl()
        late?.let(lateCutoffSink::onLateMetricsFact)
    }

    private fun settleAttachmentSubmissionAmbiguity(
        failure: Exception,
        permitMayRemainOccupied: Boolean,
    ) {
        if (permitMayRemainOccupied) serialSubmissionAmbiguity.compareAndSet(null, failure)
        synchronized(sessionGate) {
            ingressOpen = false
            capsule.requestClose()
            if (!semanticCutoffLocked()) {
                ambiguousAttachmentOutcome = MetricsAttachmentOutcome.DispatchFailed(failure)
            } else if (capsule.entryState == MetricsEntryState.Queued) {
                capsule.markCutoffInert()
            }
        }
        signalControl()
    }

    private fun settleCloseDispatchRejection() {
        synchronized(sessionGate) {
            val queued = capsule.handleOwnership as? MetricsHandleOwnership.CloseQueued ?: return
            capsule.recordCloseDispatchFailure(queued.handle)
            closeOutcome = MetricsCloseOutcome.DispatchFailed(null)
        }
        signalControl()
    }

    private fun settleCloseSubmissionAmbiguity(
        failure: Exception,
        permitMayRemainOccupied: Boolean,
    ) {
        if (permitMayRemainOccupied) serialSubmissionAmbiguity.compareAndSet(null, failure)
        synchronized(sessionGate) {
            ambiguousCloseOutcome = MetricsCloseOutcome.DispatchFailed(failure)
            ingressOpen = false
            capsule.requestClose()
        }
        signalControl()
    }

    private fun settleBoundaryFailureLocked(failure: Exception) {
        capsule.recordAttachmentNotCalled(source)
        attachmentOutcome = MetricsAttachmentOutcome.BoundaryFailed(failure)
        ingressOpen = false
        capsule.requestClose()
    }

    private fun settleAttachmentBeforeCallFatalLocked(failure: Throwable) {
        capsule.recordAttachmentNotCalled(source)
        attachmentOutcome = MetricsAttachmentOutcome.DirectFatal(failure)
        ingressOpen = false
        capsule.requestClose()
    }

    private fun settleAttachmentReturnedFatalLocked(failure: Throwable) {
        capsule.recordAttachmentFailure(source)
        attachmentOutcome = MetricsAttachmentOutcome.DirectFatal(failure)
        ingressOpen = false
        capsule.requestClose()
    }

    private fun settleAttachmentFatalAfterReturnLocked(failure: Throwable) {
        attachmentOutcome = MetricsAttachmentOutcome.DirectFatal(failure)
        ingressOpen = false
        capsule.requestClose()
    }

    private fun failIngressLocked(failure: Throwable) {
        if (ingressFailure == null) ingressFailure = MetricsIngressFailureFact(failure)
        ingressOpen = false
        capsule.requestClose()
        closeOutcome = MetricsCloseOutcome.AwaitingAttachmentReturn
    }

    private fun nextSequenceLocked(): Long? {
        if (sequence == Long.MAX_VALUE) {
            failIngressLocked(sequenceExhaustedCause)
            return null
        }
        sequence += 1L
        return sequence
    }

    private fun positiveFactLocked(): MetricsEarliestPositiveFact? {
        if (!hasEarliestPositive) return null
        return MetricsEarliestPositiveFact(
            earliestSequence,
            earliestAtNanos,
            checkNotNull(earliestMetrics),
            sourceKey(earliestDisplay, earliestEpoch),
            earliestTimely,
        )
    }

    private fun sourceKey(
        display: android.view.Display?,
        epoch: BuiltInCaptureMetricsEpoch?,
    ): MetricsSourceKey = MetricsSourceKey(sourceIdentity, display, epoch)

    /** Keeps a Java implementation's structurally invalid null return observable as data. */
    private fun nullableInteropHandle(handle: CaptureMetricsSubscription?): CaptureMetricsSubscription? = handle

    private fun dispatchBuiltIn(task: Runnable) {
        val admitted = synchronized(sessionGate) { !semanticCutoffLocked() && ingressOpen }
        if (!admitted) return
        val permitReleased = AtomicBoolean(false)
        val outcome = try {
            submitSerialTask(permitReleased) {
                val enter = synchronized(sessionGate) { !semanticCutoffLocked() && ingressOpen }
                if (enter) task.run()
            }
        } catch (failure: Exception) {
            if (!permitReleased.get()) serialSubmissionAmbiguity.compareAndSet(null, failure)
            acceptTerminal(TerminalKind.Failed, failure)
            return
        } catch (failure: Throwable) {
            if (!permitReleased.get()) serialSubmissionAmbiguity.compareAndSet(null, failure)
            synchronized(sessionGate) {
                if (!semanticCutoffLocked()) {
                    failIngressLocked(failure)
                    closeOutcome = MetricsCloseOutcome.DirectFatal(failure)
                }
            }
            throw failure
        }
        if (outcome) return
        if (roleTaskActive.get()) {
            if (!deferredBuiltInTask.compareAndSet(null, task) && deferredBuiltInTask.get() !== task) {
                acceptTerminal(TerminalKind.Failed, refreshDispatchConflictCause)
            }
        } else {
            acceptTerminal(TerminalKind.Failed, refreshDispatchRejectedCause)
        }
    }

    private fun submitDeferredWork() {
        val close = deferredClose.getAndSet(null)
        if (close != null) {
            submitClose(close)
            return
        }
        val task = deferredBuiltInTask.getAndSet(null) ?: return
        dispatchBuiltIn(task)
    }

    private fun submitSerialTask(
        permitReleased: AtomicBoolean,
        task: () -> Unit,
    ): Boolean = serialView.submit(
        task = {
            check(roleTaskActive.compareAndSet(false, true))
            task()
        },
        afterPermitReleased = {
            permitReleased.set(true)
            check(roleTaskActive.compareAndSet(true, false))
            serialSubmissionAmbiguity.set(null)
            submitDeferredWork()
        },
    )

    private fun requireGate() {
        check(Thread.holdsLock(sessionGate)) { "sessionGate must be held" }
    }

    private fun semanticCutoffLocked(): Boolean {
        requireGate()
        return cutoff || !ordinaryAdmissionOpenLocked()
    }
}
