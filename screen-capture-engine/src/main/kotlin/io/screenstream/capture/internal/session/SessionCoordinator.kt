package io.screenstream.capture.internal.session

import android.media.projection.MediaProjection
import android.os.HandlerThread
import io.screenstream.capture.EncodedFrame
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.ScreenCaptureDiagnosticEvent
import io.screenstream.capture.ScreenCaptureException
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.ScreenCaptureState
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.capture.ScreenCaptureStopReason
import io.screenstream.capture.internal.capture.AndroidEglPlatform
import io.screenstream.capture.internal.capture.AndroidGlesPlatform
import io.screenstream.capture.internal.capture.AndroidProjectionPlatform
import io.screenstream.capture.internal.capture.AndroidTargetPlatform
import io.screenstream.capture.internal.capture.CaptureApplyResult
import io.screenstream.capture.internal.capture.CaptureFailureScope
import io.screenstream.capture.internal.capture.CaptureOpenResult
import io.screenstream.capture.internal.capture.CaptureProjectionIdentity
import io.screenstream.capture.internal.capture.CaptureReadResult
import io.screenstream.capture.internal.capture.CaptureSourceIdentity
import io.screenstream.capture.internal.capture.EglPlatform
import io.screenstream.capture.internal.capture.GlesPlatform
import io.screenstream.capture.internal.capture.ProjectionPlatform
import io.screenstream.capture.internal.capture.ProjectionStopCompletion
import io.screenstream.capture.internal.capture.TargetPlatform
import io.screenstream.capture.internal.delivery.DeliveryClosedStage
import io.screenstream.capture.internal.delivery.DeliveryFact
import io.screenstream.capture.internal.delivery.DeliveryOffer
import io.screenstream.capture.internal.encoding.EncodingInput
import io.screenstream.capture.internal.encoding.EncodingInputResult
import io.screenstream.capture.internal.encoding.EncodingInputSettlement
import io.screenstream.capture.internal.encoding.EncodingReconcileResult
import io.screenstream.capture.internal.encoding.EncodingReconcileSubmission
import io.screenstream.capture.internal.encoding.EncodingResult
import io.screenstream.capture.internal.encoding.NativeJpegFacade
import io.screenstream.capture.internal.encoding.NativeJpegProcess
import io.screenstream.capture.internal.metrics.MetricsAttachmentLifecycle
import io.screenstream.capture.internal.metrics.MetricsSnapshot
import io.screenstream.capture.internal.metrics.SessionMetricsOwner
import io.screenstream.capture.internal.metrics.SessionMetricsSourceSelection
import io.screenstream.capture.internal.runtime.DelayedEntryScheduler
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.runtime.HandlerTaskPoster
import io.screenstream.capture.internal.runtime.HandlerThreadPlatform
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.session.delivery.SessionDelivery
import io.screenstream.capture.internal.session.lifecycle.SessionLifecycle
import io.screenstream.capture.internal.session.production.SessionProduction
import io.screenstream.capture.internal.session.production.SessionProductionRecord
import io.screenstream.capture.internal.session.production.SessionReadBridge
import io.screenstream.capture.internal.session.topology.SessionTopology
import io.screenstream.capture.internal.storage.PublishedFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CancellationException

/**
 * Permanent transaction root for one screen-capture session.
 *
 * Returned leaf facts are first correlated through their typed links, including stale facts needed to settle exact
 * physical ownership. Identity and currentness are then revalidated before a fact may affect live session semantics.
 *
 * Cross-domain work is serialized in `publicationGate -> sessionGate` order. Platform, codec, callback, cleanup,
 * clock, dispatch, waiting, payload, and Flow-assignment work must run with both gates released. Methods suffixed `Locked`
 * require `sessionGate`; both Control turns and callbacks use them, and a callback may arrive before its submit call
 * returns.
 */
internal class SessionCoordinator(
    private val metricsSourceSelection: SessionMetricsSourceSelection,
    private val jpegBackendPolicy: JpegBackendPolicy,
    private val workerDispatcher: NonInlineDispatcher,
    private val handlerThreadPlatform: HandlerThreadPlatform,
    private val handlerTaskPoster: HandlerTaskPoster,
    private val delayedEntryScheduler: DelayedEntryScheduler,
    private val executionClock: ElapsedRealtimeClock,
    currentEpochMillis: () -> Long,
    private val platformSdkInt: Int,
    private val projectionPlatform: ProjectionPlatform = AndroidProjectionPlatform,
    private val eglPlatform: EglPlatform = AndroidEglPlatform,
    private val glesPlatform: GlesPlatform = AndroidGlesPlatform,
    private val targetPlatform: TargetPlatform = AndroidTargetPlatform,
    private val nativeJpeg: NativeJpegFacade = NativeJpegProcess,
) {
    private class ImmediateControlWake(coordinator: SessionCoordinator, val executor: SessionControlExecutor) {
        val task = Runnable { coordinator.enterImmediateWake(this) }
    }

    private val pacingWakeTask = Runnable { enterPacingWake() }

    private class TerminalPublicationCandidate(
        val lifecycle: SessionLifecycle.TerminalPreparation,
        val topology: SessionTopology.TerminalEvidence,
        val production: SessionProduction.TerminalSnapshot,
        val delivery: SessionDelivery.TerminalPreparation,
        val state: ScreenCaptureState,
        val diagnostic: SessionObservationPublisher.DiagnosticRequest?,
    )

    private class TerminalFacts(
        val callbackFailure: DeliveryFact.CallbackFailure?,
        val closed: DeliveryFact.Closed?,
    ) {
        fun isEmpty(): Boolean = (callbackFailure == null) && (closed == null)
    }

    private sealed interface OrdinaryPublication {
        class State(val value: ScreenCaptureState) : OrdinaryPublication

        class Active(
            val value: ScreenCaptureState.Active,
            val reservation: SessionLifecycle.ActiveReservation.Reserved,
            val commit: SessionTopology.ActivePublicationCommit,
        ) : OrdinaryPublication

        class Stats(val value: ScreenCaptureStats) : OrdinaryPublication
    }

    private class OrdinaryPublicationSettlement(
        val canContinueOrdinaryWork: Boolean,
        val startSettlement: SessionLifecycle.StartSettlement?,
    )

    private class TerminalProgress(val wake: ImmediateControlWake?, val finishBeforeControl: Boolean)

    private val publicationGate = Any()
    private val sessionGate = Any()
    private val observations = SessionObservationPublisher(currentEpochMillis)
    private val lifecycle = SessionLifecycle()
    private val topology = SessionTopology()
    private val production = SessionProduction(executionClock.nowNanos())
    private val delivery = SessionDelivery()
    private val bootstrapOwnership = BootstrapOwnership()
    private val terminalPublicationCompletion = CompletableDeferred<Unit>()
    private val projectionStopCompletion = ProjectionStopCompletion()

    private val bootstrap = SessionBootstrap(
        coordinator = this,
        ownership = bootstrapOwnership,
        workerDispatcher = workerDispatcher,
        handlerThreadPlatform = handlerThreadPlatform,
        handlerTaskPoster = handlerTaskPoster,
        metricsSourceSelection = metricsSourceSelection,
        executionClock = executionClock,
        platformSdkInt = platformSdkInt,
        projectionPlatform = projectionPlatform,
        eglPlatform = eglPlatform,
        glesPlatform = glesPlatform,
        targetPlatform = targetPlatform,
        nativeJpeg = nativeJpeg,
        projectionStopCompletion = projectionStopCompletion,
    )

    private var controlExecutor: SessionControlExecutor? = null
    private var controlWorkPending = false
    private var pendingControlWake: ImmediateControlWake? = null
    private var terminalPublicationClaimed = false
    private var ordinaryPublication: OrdinaryPublication? = null
    private var postedPacingWake: SessionProduction.PacingWake? = null
    private lateinit var captureLink: SessionCaptureLink
    private lateinit var metricsOwner: SessionMetricsOwner
    private lateinit var encodingLink: SessionEncodingLink
    private lateinit var deliveryLink: SessionDeliveryLink

    internal val state: StateFlow<ScreenCaptureState> = observations.state
    internal val stats: StateFlow<ScreenCaptureStats> = observations.stats
    internal val diagnosticEvents: SharedFlow<ScreenCaptureDiagnosticEvent> = observations.diagnosticEvents

    internal suspend fun stop() {
        requestStop()
        currentCoroutineContext().ensureActive()
        terminalPublicationCompletion.await()
        // Active can reserve its successful startup outcome before completing it outside the session gates.
        // A racing terminal publication then has no startup settlement of its own to complete.
        lifecycle.startWaiter.awaitCompletion()
        projectionStopCompletion.awaitCompletion()
    }

    internal fun adoptProjection(mediaProjection: MediaProjection) {
        synchronized(sessionGate) {
            check(!lifecycle.isTerminal) { "A terminal Session cannot adopt a projection" }
            bootstrapOwnership.adoptAcceptedProjection(mediaProjection)
        }
    }

    internal suspend fun start(initialParameters: ScreenCaptureParameters) {
        val callerContext = currentCoroutineContext()
        var cancellationBeforeAdmission: CancellationException? = null
        var cancellationProgress: TerminalProgress? = null
        serializePublication {
            synchronized(sessionGate) {
                try {
                    callerContext.ensureActive()
                } catch (cancellation: CancellationException) {
                    if (lifecycle.isFreshStartEligible) {
                        cancellationProgress = offerTerminalProgressLocked(SessionLifecycle.TerminalDecision.Requested)
                    }
                    cancellationBeforeAdmission = cancellation
                }
            }
        }
        if (cancellationBeforeAdmission != null) {
            continueTerminalProgress(cancellationProgress)
            throw cancellationBeforeAdmission
        }
        val acceptedNanos = checkedNowNanos()
        val deadlineNanos = try {
            Math.addExact(acceptedNanos, STARTUP_WINDOW_NANOS)
        } catch (failure: ArithmeticException) {
            throw ScreenCaptureException.create(ScreenCaptureProblem.InternalFailure, failure)
        }
        var startingPublication: OrdinaryPublication.State? = null
        val deadline = serializePublication {
            val acceptedDeadline = synchronized(sessionGate) {
                try {
                    callerContext.ensureActive()
                } catch (cancellation: CancellationException) {
                    if (lifecycle.isFreshStartEligible) {
                        cancellationProgress = offerTerminalProgressLocked(SessionLifecycle.TerminalDecision.Requested)
                    }
                    cancellationBeforeAdmission = cancellation
                    return@synchronized null
                }
                val candidate = lifecycle.acceptStart(acceptedNanos, deadlineNanos)
                topology.initialize(initialParameters)
                startingPublication = reserveStateLocked(ScreenCaptureState.Starting)
                candidate
            }
            acceptedDeadline
        }
        if (cancellationBeforeAdmission != null) {
            continueTerminalProgress(cancellationProgress)
            throw cancellationBeforeAdmission
        }
        try {
            if (publishOrdinary(checkNotNull(startingPublication)).canContinueOrdinaryWork) {
                armStartupDeadline(checkNotNull(deadline))
                try {
                    bootstrap.dispatch()
                } catch (failure: Exception) {
                    onBootstrapFailure(bootstrapOwnership, failure)
                }
            }
            awaitStart(lifecycle.startWaiter)
        } catch (cancellation: CancellationException) {
            try {
                callerContext.ensureActive()
            } catch (_: CancellationException) {
                requestStop()
            }
            throw cancellation
        }
    }

    internal fun updateParameters(parameters: ScreenCaptureParameters) {
        var wake: ImmediateControlWake? = null
        var progress: TerminalProgress? = null
        var selectedFailure: ScreenCaptureException? = null
        serializePublication {
            synchronized(sessionGate) {
                check((controlExecutor != null) && (lifecycle.canUpdateParameters)) {
                    "Parameters can be updated only while the Session is running"
                }
                val candidate = topology.prepareParameterUpdate(parameters) ?: return@synchronized
                try {
                    topology.commitParameterUpdate(candidate)
                    lifecycle.pauseProduction()
                } catch (failure: ArithmeticException) {
                    val cause = IllegalStateException("Session revision exhausted", failure)
                    progress = offerTerminalProgressLocked(SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, cause))
                    selectedFailure = ScreenCaptureException.create(ScreenCaptureProblem.InternalFailure, cause)
                }
                if (progress == null) {
                    controlWorkPending = true
                    wake = requestControlWakeLocked()
                }
            }
        }
        postControlWake(wake)
        continueTerminalProgress(progress)
        selectedFailure?.let { throw it }
    }

    internal fun registerFrameConsumer(consumer: (EncodedFrame) -> Unit): suspend () -> Unit {
        var wake: ImmediateControlWake? = null
        var progress: TerminalProgress? = null
        var registration: SessionDelivery.Registration? = null
        var selectedFailure: ScreenCaptureException? = null
        serializePublication {
            synchronized(sessionGate) {
                when (val result = delivery.register(consumer)) {
                    is SessionDelivery.RegistrationResult.Accepted -> {
                        controlWorkPending = true
                        wake = requestControlWakeLocked()
                        registration = result.registration
                        result.registration.installDetachAction { token -> detachDeliveryAfterProof(result.registration, token) }
                        result.registration.installBeginUnregisterAction { unregister(result.registration) }
                    }

                    SessionDelivery.RegistrationResult.Occupied -> error("Only one unresolved frame registration is allowed")
                    SessionDelivery.RegistrationResult.Terminal -> error("A terminal Session cannot accept a frame consumer")
                    SessionDelivery.RegistrationResult.IdExhausted -> {
                        val cause = IllegalStateException("Delivery registration id exhausted")
                        progress = offerTerminalProgressLocked(SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, cause))
                        selectedFailure = ScreenCaptureException.create(ScreenCaptureProblem.InternalFailure, cause)
                    }
                }
            }
        }
        postControlWake(wake)
        continueTerminalProgress(progress)
        selectedFailure?.let { throw it }
        val accepted = checkNotNull(registration)
        return { accepted.awaitUnregister() }
    }

    private fun detachDeliveryAfterProof(registration: SessionDelivery.Registration, token: io.screenstream.capture.internal.delivery.DeliveryHandoffToken) {
        synchronized(sessionGate) {
            delivery.detachAfterProof(registration, token)
        }
        registration.completeIfReady()
    }

    internal fun requestStop() = offerTerminal(SessionLifecycle.TerminalDecision.Requested)

    internal fun bootstrapCutoffWon(ownership: BootstrapOwnership): Boolean = synchronized(sessionGate) {
        check(ownership === bootstrapOwnership)
        if (lifecycle.isOrdinaryAdmissionOpen) return@synchronized false
        ownership.makeCutoffInert()
        true
    }

    internal fun claimControlThreadStart(ownership: BootstrapOwnership, thread: HandlerThread): BootstrapOwnership.LaneStartDecision =
        synchronized(sessionGate) {
            check(ownership === bootstrapOwnership)
            if (!lifecycle.isOrdinaryAdmissionOpen) ownership.makeCutoffInert()
            ownership.claimControlThreadStart(thread)
        }

    internal fun claimCaptureThreadStart(ownership: BootstrapOwnership, thread: HandlerThread): BootstrapOwnership.LaneStartDecision =
        synchronized(sessionGate) {
            check(ownership === bootstrapOwnership)
            if (!lifecycle.isOrdinaryAdmissionOpen) ownership.makeCutoffInert()
            ownership.claimCaptureThreadStart(thread)
        }

    internal fun enterFirstControlTask(
        ownership: BootstrapOwnership,
        executor: SessionControlExecutor,
        task: Runnable,
        prepared: SessionBootstrap.PreparedGraph,
    ): BootstrapOwnership.FirstControlEntry {
        val entryNanos = try {
            executionClock.nowNanos()
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            Long.MAX_VALUE
        }
        val entry = synchronized(sessionGate) {
            check(ownership === bootstrapOwnership && prepared.executor === executor)
            lifecycle.startupDeadlineCandidate?.let { deadline ->
                when (lifecycle.checkStartupDeadline(deadline, entryNanos)) {
                    SessionLifecycle.StartupCheck.ClockRegressed -> offerTerminalLocked(
                        SessionLifecycle.TerminalDecision.Failed(
                            problem = ScreenCaptureProblem.InternalFailure,
                            cause = IllegalStateException("Elapsed realtime regressed before Control entry"),
                        ),
                    )

                    SessionLifecycle.StartupCheck.DeadlineExpired -> offerTerminalLocked(
                        SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.CaptureUnavailable, null),
                    )

                    SessionLifecycle.StartupCheck.Eligible, SessionLifecycle.StartupCheck.Stale -> Unit
                }
            }
            if (!lifecycle.isOrdinaryAdmissionOpen) ownership.makeCutoffInert()
            when (val entry = ownership.enterFirstControlTask(executor, task)) {
                is BootstrapOwnership.FirstControlEntry.Entered -> {
                    check(controlExecutor == null)
                    captureLink = prepared.captureLink
                    captureLink.bindOwnerLocked(prepared.captureOwner, entry.acceptedProjection)
                    metricsOwner = prepared.metricsOwner
                    encodingLink = prepared.encodingLink
                    deliveryLink = prepared.deliveryLink
                    controlExecutor = executor
                    controlWorkPending = true
                    ownership.commitFirstControlTransfer()
                    entry
                }

                BootstrapOwnership.FirstControlEntry.CutoffInert -> entry
            }
        }
        if (entry === BootstrapOwnership.FirstControlEntry.CutoffInert) finishTerminal()
        return entry
    }

    internal fun onBootstrapFailure(ownership: BootstrapOwnership, cause: Exception) {
        if (ownership !== bootstrapOwnership) return
        offerFailure(ScreenCaptureProblem.InternalFailure, cause)
    }

    internal fun onBootstrapWorkerAccepted(ownership: BootstrapOwnership) = recordBootstrapFact(ownership, worker = true)

    internal fun onFirstControlPostAccepted(ownership: BootstrapOwnership) = recordBootstrapFact(ownership, worker = false)

    private fun recordBootstrapFact(ownership: BootstrapOwnership, worker: Boolean) {
        if (ownership !== bootstrapOwnership) return
        var wake: ImmediateControlWake? = null
        synchronized(sessionGate) {
            val result = if (worker) lifecycle.recordBootstrapWorkerAccepted() else lifecycle.recordFirstControlPostAccepted()
            if (result == SessionLifecycle.BootstrapFactResult.Ready) {
                controlWorkPending = true
                wake = requestControlWakeLocked()
            }
        }
        postControlWake(wake)
    }

    internal fun signalControl() {
        val wake = synchronized(sessionGate) {
            if (lifecycle.isTerminal) return
            controlWorkPending = true
            requestControlWakeLocked()
        }
        postControlWake(wake)
    }

    internal fun enterControlTurn(candidate: SessionControlExecutor) {
        val admitted = synchronized(sessionGate) {
            if (controlExecutor !== candidate || lifecycle.isTerminal) return@synchronized false
            controlWorkPending = false
            true
        }
        if (!admitted) return
        runControlTurn(candidate)
    }

    internal fun onControlTurnFailure(candidate: SessionControlExecutor, failure: Exception): Boolean {
        var wake: ImmediateControlWake? = null
        val handled = serializePublication {
            synchronized(sessionGate) {
                if (controlExecutor !== candidate || terminalPublicationClaimed) return@synchronized false
                offerTerminalLocked(SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, failure))
                controlWorkPending = true
                wake = requestControlWakeLocked()
                true
            }
        }
        postControlWake(wake)
        return handled
    }

    internal fun finishControlTurn(candidate: SessionControlExecutor) {
        val wake = synchronized(sessionGate) {
            if (controlExecutor !== candidate || !controlWorkPending || lifecycle.isTerminal) return
            requestControlWakeLocked()
        }
        postControlWake(wake)
    }

    private fun runControlTurn(executor: SessionControlExecutor) {
        check(synchronized(sessionGate) { controlExecutor === executor })
        checkStartupDeadlineOnControl()
        if (synchronized(sessionGate) { lifecycle.isTerminalPending }) {
            finishTerminal()
            return
        }
        ensureMetricsAttachment()
        if (finishTerminalIfPending()) return
        consumeDesiredIngress()
        if (finishTerminalIfPending()) return
        consumeMetrics()
        if (finishTerminalIfPending()) return
        consumeLinkFacts()
        if (finishTerminalIfPending()) return
        executePendingUnregisterAction()
        if (finishTerminalIfPending()) return
        consumeCaptureSignals()
        if (finishTerminalIfPending()) return
        processCompletedFreshOutput()
        consumePendingResizeRevision()
        if (finishTerminalIfPending()) return
        resolvePlanAndConverge()
        if (finishTerminalIfPending()) return
        publishActiveIfReady()
        publishActiveVisibilityIfNeeded()
        publishPausedVisibilityIfNeeded()
        offerCachedFirst()
        startFreshProduction()
        publishStatsIfDue()
        finishTerminalIfPending()
        syncPacingWakeTask()
    }

    private fun ensureMetricsAttachment() {
        val attach = synchronized(sessionGate) {
            lifecycle.isOrdinaryAdmissionOpen && ::metricsOwner.isInitialized && topology.claimMetricsAttachment()
        }
        if (!attach) return
        try {
            metricsOwner.attach()
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    private fun consumeDesiredIngress() {
        var paused: SessionTopology.PausedPublication?
        var publication: OrdinaryPublication.State? = null
        var shouldInvalidateCache = false
        serializePublication {
            synchronized(sessionGate) {
                if (!lifecycle.isOrdinaryAdmissionOpen) return@synchronized
                val candidate = topology.prepareDesiredIngress() ?: return@synchronized
                val frameRateChanged = candidate.previousParameters.frameRate != candidate.parameters.frameRate
                paused = topology.commitDesired(candidate)
                production.prepareStaleOutput(candidate.revision)?.let(production::discardStaleOutput)
                lifecycle.pauseProduction()
                if (frameRateChanged) production.resetForFrameRateChange() else production.suppressPacingWake()
                shouldInvalidateCache = !candidate.isCachedImageCompatible
                paused?.let { pausedCandidate ->
                    val state = pausedCandidate.problem?.let { problem ->
                        ScreenCaptureState.Suspended.create(
                            requestedParameters = pausedCandidate.requestedParameters,
                            problem = problem,
                            lastOutputInfo = pausedCandidate.historicalOutputInfo,
                            isCapturedContentVisible = pausedCandidate.isCapturedContentVisible,
                        )
                    } ?: ScreenCaptureState.Reconfiguring.create(
                        requestedParameters = pausedCandidate.requestedParameters,
                        lastOutputInfo = pausedCandidate.historicalOutputInfo,
                        isCapturedContentVisible = pausedCandidate.isCapturedContentVisible,
                    )
                    topology.commitPausedPublication(pausedCandidate)
                    publication = reserveStateLocked(state)
                }
            }
        }
        publication?.let {
            if (!publishOrdinary(it).canContinueOrdinaryWork) return
        }
        if (shouldInvalidateCache) invalidateCache()
    }

    private fun consumeMetrics() {
        var consumedSnapshot: MetricsSnapshot? = null
        var paused: SessionTopology.PausedPublication? = null
        var publication: OrdinaryPublication.State? = null
        var shouldSuspend = false
        var committed = false
        var metricsFailure: Throwable? = null
        var revisionFailure: Exception? = null
        serializePublication {
            synchronized(sessionGate) {
                if (!lifecycle.isOrdinaryAdmissionOpen) return@synchronized
                val snapshot = metricsOwner.readSnapshot()
                consumedSnapshot = snapshot
                when (val decision = topology.prepareMetrics(snapshot, platformSdkInt)) {
                    SessionTopology.MetricsDecision.Duplicate -> return@synchronized
                    SessionTopology.MetricsDecision.BlockedByPendingIngress -> {
                        controlWorkPending = true
                        return@synchronized
                    }

                    is SessionTopology.MetricsDecision.Failed -> {
                        metricsFailure = decision.cause
                        return@synchronized
                    }

                    is SessionTopology.MetricsDecision.Update -> {
                        val revision = try {
                            topology.commitMetrics(decision)
                        } catch (failure: ArithmeticException) {
                            revisionFailure = failure
                            return@synchronized
                        }
                        committed = true
                        if (revision != null || decision.closesActiveAdmission) {
                            revision?.let { production.prepareStaleOutput(it)?.let(production::discardStaleOutput) }
                            lifecycle.pauseProduction()
                            production.suppressPacingWake()
                            paused = if (decision.hasPublicEffectivePlan && decision.historicalOutputInfo != null) {
                                topology.prepareReconfiguration()
                            } else {
                                null
                            }
                            paused?.let { pausedCandidate ->
                                val state = ScreenCaptureState.Reconfiguring.create(
                                    requestedParameters = pausedCandidate.requestedParameters,
                                    lastOutputInfo = pausedCandidate.historicalOutputInfo,
                                    isCapturedContentVisible = pausedCandidate.isCapturedContentVisible,
                                )
                                topology.commitPausedPublication(pausedCandidate)
                                publication = reserveStateLocked(state)
                            }
                        }
                        shouldSuspend = snapshot.metrics == null && decision.historicalOutputInfo != null
                    }
                }
            }
        }
        metricsFailure?.let {
            offerFailure(ScreenCaptureProblem.InternalFailure, it)
            return
        }
        revisionFailure?.let {
            offerFailure(ScreenCaptureProblem.InternalFailure, it)
            return
        }
        if (!committed) return
        publication?.let {
            if (!publishOrdinary(it).canContinueOrdinaryWork) return
        }
        val snapshot = checkNotNull(consumedSnapshot)
        if (paused != null) invalidateCache()
        if (shouldSuspend) publishSuspended()
        if (snapshot.lifecycle == MetricsAttachmentLifecycle.Completed && snapshot.handleAdopted) {
            if (snapshot.metrics == null && synchronized(sessionGate) { lifecycle.isFirstActiveRequired }) {
                offerFailure(ScreenCaptureProblem.CaptureUnavailable, null)
            } else if (snapshot.isReady() && synchronized(sessionGate) { topology.claimMetricsCompletionDiagnostic() }
            ) {
                emitDiagnostic("MetricsSource", "CapabilityCheck", null) { "Metrics source completed after readiness" }
            }
        }
    }

    private fun consumeLinkFacts() {
        while (true) {
            if (synchronized(sessionGate) { lifecycle.isTerminalPending }) return
            val read = synchronized(sessionGate) { captureLink.takeReturnedReadLocked() }
            if (read != null) {
                consumeRead(read)
                continue
            }
            val open = synchronized(sessionGate) { captureLink.takeOpenFactLocked() }
            if (open != null) {
                consumeOpen(open)
                continue
            }
            val apply = synchronized(sessionGate) { captureLink.takeApplyFactLocked() }
            if (apply != null) {
                consumeApply(apply)
                continue
            }
            val reconcile = synchronized(sessionGate) { encodingLink.takeReconcileFactLocked() }
            if (reconcile != null) {
                consumeReconcile(reconcile)
                continue
            }
            val encoded = synchronized(sessionGate) { encodingLink.takeProductionFactLocked() }
            if (encoded != null) {
                consumeEncoding(encoded)
                continue
            }
            val callbackFailure = synchronized(sessionGate) { deliveryLink.takeCallbackFailureLocked() }
            if (callbackFailure != null) {
                recordCallbackFailure(callbackFailure)
                continue
            }
            val closed = synchronized(sessionGate) { deliveryLink.takeClosedLocked() }
            if (closed != null) {
                consumeDeliveryClosed(closed)
                continue
            }
            return
        }
    }

    private fun consumeCaptureSignals() {
        val resize = synchronized(sessionGate) { captureLink.takeResizeFactLocked() }
        if (resize != null) {
            synchronized(sessionGate) {
                if (lifecycle.isOrdinaryAdmissionOpen) {
                    topology.recordCapturedResize(resize.projectionIdentity, resize.widthPx, resize.heightPx)
                }
            }
        }
        val visibility = synchronized(sessionGate) { captureLink.takeVisibilityLocked() }
        if (visibility != null) {
            synchronized(sessionGate) {
                if (lifecycle.isOrdinaryAdmissionOpen) {
                    topology.recordCapturedContentVisibility(visibility)
                }
            }
        }
    }

    private fun consumePendingResizeRevision() {
        var paused: SessionTopology.PausedPublication? = null
        var publication: OrdinaryPublication.State? = null
        var failure: Exception? = null
        serializePublication {
            synchronized(sessionGate) {
                if (!lifecycle.isOrdinaryAdmissionOpen) return@synchronized
                when (topology.preparePendingResizeRevision()) {
                    SessionTopology.PendingResizeDecision.None -> return@synchronized
                    SessionTopology.PendingResizeDecision.BlockedByPendingIngress -> {
                        controlWorkPending = true
                        return@synchronized
                    }

                    SessionTopology.PendingResizeDecision.RevisionCandidate -> Unit
                }
                val revision = try {
                    topology.commitPendingResizeRevision()
                } catch (cause: ArithmeticException) {
                    failure = cause
                    return@synchronized
                }
                production.prepareStaleOutput(revision)?.let(production::discardStaleOutput)
                lifecycle.pauseProduction()
                production.suppressPacingWake()
                paused = topology.prepareReconfiguration()
                paused?.let { pausedCandidate ->
                    val state = ScreenCaptureState.Reconfiguring.create(
                        requestedParameters = pausedCandidate.requestedParameters,
                        lastOutputInfo = pausedCandidate.historicalOutputInfo,
                        isCapturedContentVisible = pausedCandidate.isCapturedContentVisible,
                    )
                    topology.commitPausedPublication(pausedCandidate)
                    publication = reserveStateLocked(state)
                }
            }
        }
        failure?.let { offerFailure(ScreenCaptureProblem.InternalFailure, it) }
        publication?.let {
            if (!publishOrdinary(it).canContinueOrdinaryWork) return
        }
        if (paused != null) invalidateCache()
    }

    private fun resolvePlanAndConverge() {
        when (val decision = synchronized(sessionGate) { topology.resolvePlan(platformSdkInt) }) {
            SessionTopology.PlanDecision.Suspended,
            SessionTopology.PlanDecision.WaitingForIngress,
            SessionTopology.PlanDecision.WaitingForMetrics,
            SessionTopology.PlanDecision.Current,
                -> Unit

            is SessionTopology.PlanDecision.Install -> synchronized(sessionGate) {
                if (lifecycle.isOrdinaryAdmissionOpen && decision.isCurrent(topology)) topology.commitPlan(decision)
            }

            is SessionTopology.PlanDecision.Rejected -> {
                var progress: TerminalProgress? = null
                var shouldInvalidateCache = false
                var publication: OrdinaryPublication.State? = null
                serializePublication {
                    synchronized(sessionGate) {
                        if (!lifecycle.isOrdinaryAdmissionOpen || !decision.isCurrent(topology)) return@synchronized
                        topology.commitRejectedPlan(decision)
                        if (lifecycle.isFirstActiveRequired) {
                            progress = offerTerminalProgressLocked(
                                SessionLifecycle.TerminalDecision.Failed(decision.problem, decision.cause),
                            )
                        } else {
                            lifecycle.pauseProduction()
                            val suspension = topology.prepareSuspension(decision.problem)
                            if (suspension != null && suspension.isCurrent(topology)) {
                                val state = ScreenCaptureState.Suspended.create(
                                    requestedParameters = suspension.requestedParameters,
                                    problem = checkNotNull(suspension.problem),
                                    lastOutputInfo = suspension.historicalOutputInfo,
                                    isCapturedContentVisible = suspension.isCapturedContentVisible,
                                )
                                topology.commitPausedPublication(suspension)
                                publication = reserveStateLocked(state)
                                production.suppressPacingWake()
                                shouldInvalidateCache = true
                            }
                        }
                    }
                }
                continueTerminalProgress(progress)
                publication?.let {
                    if (!publishOrdinary(it).canContinueOrdinaryWork) return
                }
                if (shouldInvalidateCache) invalidateCache()
                return
            }
        }
        when (val step = synchronized(sessionGate) { topology.nextConvergence(production.hasMaterializedProduction) }) {
            SessionTopology.ConvergenceStep.Waiting, SessionTopology.ConvergenceStep.Ready -> Unit
            is SessionTopology.ConvergenceStep.Open -> dispatchOpen(step)
            is SessionTopology.ConvergenceStep.Apply -> dispatchApply(step)
            is SessionTopology.ConvergenceStep.ReconcileEncoding -> dispatchReconcile(step)
        }
    }

    private fun dispatchOpen(step: SessionTopology.ConvergenceStep.Open) {
        val request = synchronized(sessionGate) {
            if (!lifecycle.isOrdinaryAdmissionOpen || !step.isCurrent(topology)) return
            val prepared = captureLink.prepareOpenLocked(step.plan)
            topology.commitCaptureOpenDispatch(step)
            prepared
        }
        val failure = try {
            if (captureLink.executeOpen(request)) return
            IllegalStateException("Capture Open dispatch was rejected")
        } catch (failure: Exception) {
            failure
        }
        val progress = serializePublication {
            synchronized(sessionGate) {
                val settled = captureLink.settleOpenRejectedLocked(request)
                if (settled) topology.settleCaptureOpenDispatchFailure(step)
                if (settled && lifecycle.isOrdinaryAdmissionOpen) {
                    offerTerminalProgressLocked(SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, failure))
                } else {
                    null
                }
            }
        }
        continueTerminalProgress(progress)
    }

    private fun dispatchApply(step: SessionTopology.ConvergenceStep.Apply) {
        val request = synchronized(sessionGate) {
            if (!lifecycle.isOrdinaryAdmissionOpen || !step.isCurrent(topology)) return
            captureLink.prepareApplyLocked(step.revision, step.plan).also {
                topology.commitCaptureApplyDispatch(step)
            }
        }
        val failure = try {
            if (captureLink.executeApply(request)) return
            IllegalStateException("Capture Apply dispatch was rejected")
        } catch (failure: Exception) {
            failure
        }
        val progress = serializePublication {
            synchronized(sessionGate) {
                val settled = captureLink.settleApplyRejectedLocked(request)
                val isCurrent = settled && lifecycle.isOrdinaryAdmissionOpen && topology.isCurrentCaptureApplyDispatch(step)
                if (settled) topology.rollbackCaptureApplyDispatch(step)
                if (isCurrent) {
                    offerTerminalProgressLocked(SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, failure))
                } else {
                    null
                }
            }
        }
        continueTerminalProgress(progress)
    }

    private fun dispatchReconcile(step: SessionTopology.ConvergenceStep.ReconcileEncoding) {
        val request = synchronized(sessionGate) {
            if (!lifecycle.isOrdinaryAdmissionOpen || !step.isCurrent(topology)) return
            encodingLink.prepareReconcileLocked(step.revision, step.plan).also {
                topology.commitEncodingDispatch(step)
            }
        }
        val submission = try {
            encodingLink.executeReconcile(request, jpegBackendPolicy)
        } catch (failure: Exception) {
            EncodingReconcileSubmission.Rejected(failure)
        }
        var progress: TerminalProgress? = null
        serializePublication {
            synchronized(sessionGate) {
                val recorded = encodingLink.recordReconcileSubmissionLocked(request, submission)
                if (!recorded) {
                    progress = offerTerminalProgressLocked(
                        SessionLifecycle.TerminalDecision.Failed(
                            problem = ScreenCaptureProblem.InternalFailure,
                            cause = IllegalStateException("Encoding reconcile identity mismatch"),
                        ),
                    )
                    return@synchronized
                }
                if (submission is EncodingReconcileSubmission.Rejected) {
                    val isCurrent = lifecycle.isOrdinaryAdmissionOpen && topology.isCurrentEncodingDispatch(step)
                    topology.rollbackEncodingDispatch(step)
                    if (isCurrent) {
                        progress = offerTerminalProgressLocked(SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, submission.cause))
                    }
                }
            }
        }
        continueTerminalProgress(progress)
    }

    private fun consumeOpen(fact: SessionCaptureLink.OpenFact) {
        var pendingResizeAccepted = false
        var progress: TerminalProgress? = null
        serializePublication {
            synchronized(sessionGate) {
                val candidate = topology.prepareCaptureOpenResultCandidate(fact.request.plan) ?: return@synchronized
                when (val result = fact.result) {
                    is CaptureOpenResult.Opened -> {
                        pendingResizeAccepted = topology.commitCaptureOpenResult(
                            candidate = candidate,
                            appliedPlan = fact.request.plan,
                            projectionIdentity = result.projectionIdentity,
                        )
                    }

                    is CaptureOpenResult.Failed -> {
                        topology.commitCaptureOpenFailure(candidate)
                        if (lifecycle.isOrdinaryAdmissionOpen) {
                            progress = offerTerminalProgressLocked(SessionLifecycle.TerminalDecision.Failed(result.problem, result.cause))
                        }
                    }

                    CaptureOpenResult.CutoffInert -> {
                        topology.commitCaptureOpenFailure(candidate)
                    }
                }
            }
        }
        continueTerminalProgress(progress)
        if (pendingResizeAccepted) signalControl()
    }

    private fun consumeApply(fact: SessionCaptureLink.ApplyFact) {
        var progress: TerminalProgress? = null
        var shouldInvalidateCache = false
        var publication: OrdinaryPublication.State? = null
        serializePublication {
            synchronized(sessionGate) {
                val candidate = topology.prepareCaptureApplyResultCandidate(fact.request.configRevision, fact.request.plan)
                    ?: return@synchronized
                when (val result = fact.result) {
                    is CaptureApplyResult.Applied -> topology.commitCaptureApplyResult(candidate, fact.request.plan)

                    is CaptureApplyResult.ResourceDenied -> {
                        val isCurrent = lifecycle.isOrdinaryAdmissionOpen && candidate.isCurrent(topology)
                        val firstActive = lifecycle.isFirstActiveRequired
                        topology.commitCaptureApplyResult(candidate, null)
                        if (isCurrent) {
                            if (firstActive) {
                                progress = offerTerminalProgressLocked(
                                    SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.ResourceExhausted, result.cause),
                                )
                            } else {
                                lifecycle.pauseProduction()
                                val suspension = topology.prepareSuspension(ScreenCaptureProblem.ResourceExhausted)
                                if (suspension != null && suspension.isCurrent(topology)) {
                                    val state = ScreenCaptureState.Suspended.create(
                                        requestedParameters = suspension.requestedParameters,
                                        problem = checkNotNull(suspension.problem),
                                        lastOutputInfo = suspension.historicalOutputInfo,
                                        isCapturedContentVisible = suspension.isCapturedContentVisible,
                                    )
                                    topology.commitPausedPublication(suspension)
                                    publication = reserveStateLocked(state)
                                    production.suppressPacingWake()
                                    shouldInvalidateCache = true
                                }
                            }
                        }
                    }

                    is CaptureApplyResult.Failed -> {
                        val isCurrent = lifecycle.isOrdinaryAdmissionOpen && candidate.isCurrent(topology)
                        topology.commitCaptureApplyResult(candidate, null)
                        if (isCurrent || (result.scope == CaptureFailureScope.OwnerInvalidated)) {
                            progress = offerTerminalProgressLocked(
                                SessionLifecycle.TerminalDecision.Failed(result.problem, result.cause),
                            )
                        }
                    }

                    CaptureApplyResult.CutoffInert -> topology.commitCaptureApplyResult(candidate, null)
                }
            }
        }
        continueTerminalProgress(progress)
        publication?.let {
            if (!publishOrdinary(it).canContinueOrdinaryWork) return
        }
        if (shouldInvalidateCache) invalidateCache()
    }

    private fun consumeReconcile(fact: SessionEncodingLink.ReconcileFact) {
        var progress: TerminalProgress? = null
        serializePublication {
            synchronized(sessionGate) {
                val candidate = topology.prepareEncodingReconcileResultCandidate(fact.request.configRevision, fact.request.plan)
                    ?: return@synchronized
                when (val result = fact.result) {
                    EncodingReconcileResult.Ready -> topology.commitEncodingResult(candidate, ready = true)

                    is EncodingReconcileResult.Failed -> {
                        val isCurrent = lifecycle.isOrdinaryAdmissionOpen && candidate.isCurrent(topology)
                        topology.commitEncodingResult(candidate, ready = false)
                        if (isCurrent) {
                            progress = offerTerminalProgressLocked(
                                SessionLifecycle.TerminalDecision.Failed(result.problem, result.cause),
                            )
                        }
                    }

                    EncodingReconcileResult.CutoffInert -> topology.commitEncodingResult(candidate, ready = false)
                }
            }
        }
        continueTerminalProgress(progress)
    }

    private fun publishActiveIfReady() {
        var isFirstActive = false
        var deadline: SessionLifecycle.StartupDeadline? = null
        val candidate = synchronized(sessionGate) {
            val snapshot = metricsOwner.readSnapshot()
            isFirstActive = lifecycle.isFirstActiveRequired
            val assessment = topology.assessActivePublication(platformSdkInt, snapshot) as?
                    SessionTopology.ActiveAssessment.Candidate ?: return@synchronized null
            if (!lifecycle.activePublicationMayProceed(isFirstActive)) return@synchronized null
            if (isFirstActive) {
                deadline = lifecycle.startupDeadlineCandidate ?: return@synchronized null
            }
            assessment
        } ?: return
        val activeState = ScreenCaptureState.Active.create(
            candidate.plan.outputInfo,
            candidate.isCapturedContentVisible,
        )
        var deadlineFailure: SessionLifecycle.ActiveReservation? = null
        val sampledNanos = if (isFirstActive) {
            try {
                executionClock.nowNanos()
            } catch (failure: Exception) {
                offerFailure(ScreenCaptureProblem.InternalFailure, failure)
                return
            }
        } else {
            null
        }
        var publication: OrdinaryPublication.Active? = null
        serializePublication {
            synchronized(sessionGate) {
                val snapshot = metricsOwner.readSnapshot()
                if (!candidate.acceptsSnapshot(topology, snapshot) ||
                    !lifecycle.activePublicationMayProceed(isFirstActive)
                ) return@synchronized
                when (val reservation = lifecycle.reserveActive(isFirstActive, deadline, sampledNanos)) {
                    is SessionLifecycle.ActiveReservation.Reserved -> {
                        val commit = topology.commitActivePublication(candidate, reservation.token.isFirst)
                        publication = reserveActiveLocked(activeState, reservation, commit)
                    }

                    SessionLifecycle.ActiveReservation.ClockRegressed, SessionLifecycle.ActiveReservation.DeadlineExpired -> deadlineFailure = reservation
                    SessionLifecycle.ActiveReservation.Stale -> Unit
                }
            }
        }
        if (deadlineFailure === SessionLifecycle.ActiveReservation.ClockRegressed) {
            offerFailure(
                problem = ScreenCaptureProblem.InternalFailure,
                cause = IllegalStateException("Elapsed realtime regressed before Active publication"),
            )
            return
        }
        if (deadlineFailure === SessionLifecycle.ActiveReservation.DeadlineExpired) {
            offerFailure(ScreenCaptureProblem.CaptureUnavailable, null)
            return
        }
        val exactPublication = publication ?: return
        val publicationSettlement = publishOrdinary(exactPublication)
        if (!publicationSettlement.canContinueOrdinaryWork) return
        when (val exact = exactPublication.commit.diagnostic) {
            SessionTopology.ActiveTargetDiagnostic.None -> Unit
            is SessionTopology.ActiveTargetDiagnostic.Selected -> emitDiagnostic("SurfaceTarget", "CapabilityCheck", null) {
                "Selected ${exact.targetMode.name} target"
            }

            is SessionTopology.ActiveTargetDiagnostic.Changed -> emitDiagnostic("SurfaceTarget", "RuntimeModeChanged", null) {
                "Surface target changed from ${exact.previous.name} to ${exact.current.name}"
            }
        }
        publicationSettlement.startSettlement?.complete()
    }

    private fun publishActiveVisibilityIfNeeded() {
        val candidate = synchronized(sessionGate) { topology.prepareActiveVisibility() } ?: return
        val state = ScreenCaptureState.Active.create(
            outputInfo = candidate.outputInfo,
            isCapturedContentVisible = candidate.isCapturedContentVisible,
        )
        var publication: OrdinaryPublication.State? = null
        serializePublication {
            synchronized(sessionGate) {
                if (!lifecycle.isOrdinaryAdmissionOpen || !candidate.isCurrent(topology)) return@synchronized
                topology.commitActiveVisibility(candidate)
                publication = reserveStateLocked(state)
            }
        }
        publication?.let(::publishOrdinary)
    }

    private fun publishSuspended() {
        val candidate = synchronized(sessionGate) {
            lifecycle.pauseProduction()
            topology.prepareSuspension(ScreenCaptureProblem.CaptureUnavailable)
        } ?: run {
            if (synchronized(sessionGate) { lifecycle.isFirstActiveRequired }) {
                offerFailure(ScreenCaptureProblem.CaptureUnavailable, null)
            }
            return
        }
        var publication: OrdinaryPublication.State? = null
        serializePublication {
            synchronized(sessionGate) {
                if (!lifecycle.isOrdinaryAdmissionOpen || !candidate.isCurrent(topology)) return@synchronized
                val state = ScreenCaptureState.Suspended.create(
                    requestedParameters = candidate.requestedParameters,
                    problem = checkNotNull(candidate.problem),
                    lastOutputInfo = candidate.historicalOutputInfo,
                    isCapturedContentVisible = candidate.isCapturedContentVisible,
                )
                topology.commitPausedPublication(candidate)
                production.suppressPacingWake()
                publication = reserveStateLocked(state)
            }
        }
        publication?.let {
            if (!publishOrdinary(it).canContinueOrdinaryWork) return
        }
        invalidateCache()
    }

    private fun publishPausedVisibilityIfNeeded() {
        val candidate = synchronized(sessionGate) { topology.preparePausedVisibility() } ?: return
        var publication: OrdinaryPublication.State? = null
        serializePublication {
            synchronized(sessionGate) {
                if (!lifecycle.isOrdinaryAdmissionOpen || !candidate.isCurrent(topology)) return@synchronized
                val state = candidate.problem?.let { problem ->
                    ScreenCaptureState.Suspended.create(
                        requestedParameters = candidate.requestedParameters,
                        problem = problem,
                        lastOutputInfo = candidate.historicalOutputInfo,
                        isCapturedContentVisible = candidate.isCapturedContentVisible,
                    )
                } ?: ScreenCaptureState.Reconfiguring.create(
                    requestedParameters = candidate.requestedParameters,
                    lastOutputInfo = candidate.historicalOutputInfo,
                    isCapturedContentVisible = candidate.isCapturedContentVisible,
                )
                topology.commitPausedPublication(candidate)
                publication = reserveStateLocked(state)
            }
        }
        publication?.let(::publishOrdinary)
    }

    private fun startFreshProduction() {
        val readiness = synchronized(sessionGate) { topology.prepareProductionReadiness() } ?: return
        val nowNanos = try {
            executionClock.nowNanos()
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        val request = synchronized(sessionGate) {
            val mayRun = lifecycle.canRunProduction(
                sessionReady = topology.isActiveFor(readiness.revision),
                revisionCurrent = topology.acceptsSettledRevision(readiness.revision),
            ) && readiness.isCurrent(topology) && captureLink.sourceOpportunityAvailableLocked() &&
                    !production.hasMaterializedProduction && encodingLink.isProductionSlotFree
            if (!mayRun) return@synchronized null
            val record = production.allocateRecord(readiness.revision, readiness.parameters.jpegQuality)
            encodingLink.prepareProductionLocked(record)
        }
        if (request == null) return
        val record = request.record
        val acquired = try {
            encodingLink.executeAcquire(request)
        } catch (failure: Exception) {
            EncodingInputResult.Failed(ScreenCaptureProblem.InternalFailure, failure)
        }
        val acquireRecorded = synchronized(sessionGate) { encodingLink.recordAcquireReturnedLocked(request, acquired) }
        if (!acquireRecorded) {
            offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Encoding input identity mismatch"))
            return
        }
        val input = when (acquired) {
            is EncodingInput -> acquired
            is EncodingInputResult.Failed -> {
                offerCurrentProductionFailure(readiness, acquired.problem, acquired.cause)
                return
            }
        }
        var constructionFailure: Exception? = null
        val read = synchronized(sessionGate) {
            val isCurrent = lifecycle.canRunProduction(
                sessionReady = topology.isActiveFor(readiness.revision),
                revisionCurrent = topology.acceptsSettledRevision(readiness.revision),
            ) && readiness.isCurrent(topology)
            if (!isCurrent) return@synchronized null
            try {
                production.beginReadConstruction(
                    record = record,
                    input = input,
                    expectedByteCount = readiness.plan.encoderPlan.byteCount,
                ) { bridge, result -> onCaptureReadReturned(captureLink, bridge, result) }
            } catch (failure: Exception) {
                constructionFailure = failure
                null
            }
        }
        if (read == null) {
            val settlement = discardInput(record, input)
            if (settlement !== EncodingInputSettlement.Settled) {
                val failure = (settlement as? EncodingInputSettlement.Failed)?.cause
                offerCurrentProductionFailure(
                    readiness = readiness,
                    problem = ScreenCaptureProblem.InternalFailure,
                    cause = failure ?: IllegalStateException("Stale encoding input discard failed"),
                )
            } else {
                constructionFailure?.let {
                    offerCurrentProductionFailure(readiness, ScreenCaptureProblem.InternalFailure, it)
                }
            }
            return
        }
        when (val grant = production.prepareFreshGrant(readiness.parameters.frameRate, nowNanos)) {
            SessionProduction.FreshGrantDecision.InvalidEvidence -> {
                discardReadConstruction(record, input, read)
                offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Invalid fresh cadence evidence"))
            }

            is SessionProduction.FreshGrantDecision.RetainUntil -> {
                if (discardReadConstruction(record, input, read)) {
                    armPacingWake(grant.targetNanos, readiness.revision)
                } else {
                    offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Input discard failed"))
                }
            }

            is SessionProduction.FreshGrantDecision.Grant -> installAndExecuteRead(readiness, read, grant)
        }
    }

    private fun installAndExecuteRead(
        readiness: SessionTopology.ProductionReadiness,
        read: SessionReadBridge,
        grant: SessionProduction.FreshGrantDecision.Grant,
    ) {
        val request = synchronized(sessionGate) {
            val mayRun = lifecycle.canRunProduction(
                sessionReady = topology.isActiveFor(readiness.revision),
                revisionCurrent = topology.acceptsSettledRevision(readiness.revision),
            )
            if (!mayRun || !readiness.isCurrent(topology) || !grant.isCurrent(production)) return@synchronized null
            val installed = captureLink.installReadLocked(read) ?: return@synchronized null
            production.commitFreshRead(read, grant)
            installed
        }
        if (request == null) {
            if (!discardReadConstruction(read.record, read.input, read)) {
                offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Input discard failed"))
            }
            return
        }
        val accepted = try {
            captureLink.executeRead(request)
        } catch (failure: Exception) {
            settleRejectedRead(read, failure)
            return
        }
        if (!accepted) settleRejectedRead(read, IllegalStateException("Capture Read dispatch was rejected"))
    }

    private fun settleRejectedRead(read: SessionReadBridge, failure: Exception) {
        val request = synchronized(sessionGate) { captureLink.settleReadRejectedLocked(read) }
        if (request == null) {
            val detached = synchronized(sessionGate) { captureLink.settleDetachedReadRejectedLocked(read) }
            if (detached) discardInput(read.record, read.input)
            return
        }
        val settlement = discardInput(read.record, read.input)
        var progress: TerminalProgress? = null
        serializePublication {
            synchronized(sessionGate) {
                val isCurrent = lifecycle.canRunProduction(
                    sessionReady = topology.isActiveFor(read.record.configRevision),
                    revisionCurrent = topology.acceptsSettledRevision(read.record.configRevision),
                ) && production.currentRecordMatches(read.record)
                check(production.clearProduction(read, read.record) === read.record)
                check(captureLink.settleRejectedReadLocked(request, restoreOpportunity = false))
                val settlementFailure = (settlement as? EncodingInputSettlement.Failed)?.cause
                if (isCurrent) {
                    progress = offerTerminalProgressLocked(
                        SessionLifecycle.TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, settlementFailure ?: failure),
                    )
                }
            }
        }
        continueTerminalProgress(progress)
    }

    private fun discardReadConstruction(record: SessionProductionRecord, input: EncodingInput, read: SessionReadBridge?): Boolean {
        val result = discardInput(record, input)
        if (read != null && result === EncodingInputSettlement.Settled) {
            production.clearReadConstruction(read)
        }
        return result === EncodingInputSettlement.Settled
    }

    private fun consumeRead(read: SessionReadBridge) {
        // A selected successful read is mechanical accounting even after stop closes ordinary admission; stop alone
        // does not make its production identity stale. Semantic currentness separately decides encoding and output.
        val result = read.requireClaimedResult()
        var semanticCurrent = false
        var ordinaryAdmissionOpen: Boolean
        var shouldEncode = false
        var beganSettlement = false
        var settlementBeginFailure: EncodingInputSettlement.Failed? = null
        synchronized(sessionGate) {
            val sessionReady = topology.isActiveFor(read.record.configRevision)
            val revisionCurrent = topology.acceptsSettledRevision(read.record.configRevision)
            val productionCurrent = production.currentRecordMatches(read.record) && production.currentReadMatches(read)
            semanticCurrent = sessionReady && revisionCurrent && productionCurrent
            ordinaryAdmissionOpen = lifecycle.canRunProduction(sessionReady = sessionReady, revisionCurrent = revisionCurrent) && productionCurrent
            when (result) {
                is CaptureReadResult.Filled -> {
                    production.recordReadback(result.readbackDurationNanos)
                    if (ordinaryAdmissionOpen) {
                        if (!production.clearReadKeepProduction(read, read.record)) {
                            settlementBeginFailure = EncodingInputSettlement.Failed(
                                problem = ScreenCaptureProblem.InternalFailure,
                                cause = IllegalStateException("Fresh read identity changed"),
                            )
                        } else {
                            beganSettlement = encodingLink.beginSettlementLocked(read.record, read.input, shouldEncode = true)
                            shouldEncode = beganSettlement
                            if (!beganSettlement) {
                                settlementBeginFailure = EncodingInputSettlement.Failed(
                                    problem = ScreenCaptureProblem.InternalFailure,
                                    cause = IllegalStateException("Encoding input settlement identity mismatch"),
                                )
                                production.clearProduction(null, read.record)
                            }
                        }
                    } else {
                        if (!semanticCurrent) production.recordStaleWork()
                        beganSettlement = encodingLink.beginSettlementLocked(read.record, read.input, shouldEncode = false)
                        if (!beganSettlement) {
                            settlementBeginFailure = EncodingInputSettlement.Failed(
                                problem = ScreenCaptureProblem.InternalFailure,
                                cause = IllegalStateException("Encoding input settlement identity mismatch"),
                            )
                        }
                        production.clearProduction(read, read.record)
                    }
                }

                is CaptureReadResult.Failed -> {
                    production.recordProductionFailure()
                    beganSettlement = encodingLink.beginSettlementLocked(read.record, read.input, shouldEncode = false)
                    if (!beganSettlement) {
                        settlementBeginFailure = EncodingInputSettlement.Failed(
                            ScreenCaptureProblem.InternalFailure,
                            IllegalStateException("Encoding input settlement identity mismatch"),
                        )
                    }
                    production.clearProduction(read, read.record)
                }

                is CaptureReadResult.CutoffInert -> {
                    beganSettlement = encodingLink.beginSettlementLocked(read.record, read.input, shouldEncode = false)
                    if (!beganSettlement) {
                        settlementBeginFailure = EncodingInputSettlement.Failed(
                            problem = ScreenCaptureProblem.InternalFailure,
                            cause = IllegalStateException("Encoding input settlement identity mismatch"),
                        )
                    }
                    production.clearProduction(read, read.record)
                }
            }
        }

        val settlement = if (beganSettlement) {
            settleBegunInput(read.record, read.input, shouldEncode)
        } else {
            checkNotNull(settlementBeginFailure)
        }
        var progress: TerminalProgress? = null
        serializePublication {
            synchronized(sessionGate) {
                val exactCurrent = semanticCurrent && lifecycle.canRunProduction(
                    sessionReady = topology.isActiveFor(read.record.configRevision),
                    revisionCurrent = topology.acceptsSettledRevision(read.record.configRevision),
                )
                val failure = settlement as? EncodingInputSettlement.Failed
                if (failure != null && result !is CaptureReadResult.Failed) production.recordProductionFailure()
                if (settlement !== EncodingInputSettlement.Accepted) production.clearProduction(null, read.record)
                val semanticFailure = when {
                    result is CaptureReadResult.Failed &&
                            (exactCurrent || (result.scope == CaptureFailureScope.OwnerInvalidated)) -> result.problem to result.cause

                    failure != null && exactCurrent -> failure.problem to failure.cause
                    else -> null
                }
                if (semanticFailure != null) {
                    progress = offerTerminalProgressLocked(
                        SessionLifecycle.TerminalDecision.Failed(semanticFailure.first, semanticFailure.second),
                    )
                }
            }
        }
        continueTerminalProgress(progress)
    }

    private fun settleBegunInput(record: SessionProductionRecord, input: EncodingInput, shouldEncode: Boolean): EncodingInputSettlement {
        val result = try {
            if (shouldEncode) input.encode(record.jpegQuality) else input.discard()
        } catch (failure: Exception) {
            EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, failure)
        }
        return recordInputSettlement(record, input, result)
    }

    private fun discardInput(record: SessionProductionRecord, input: EncodingInput): EncodingInputSettlement {
        val begun = synchronized(sessionGate) {
            encodingLink.beginSettlementLocked(record, input, shouldEncode = false)
        }
        if (!begun) {
            return EncodingInputSettlement.Failed(
                problem = ScreenCaptureProblem.InternalFailure,
                cause = IllegalStateException("Encoding input settlement identity mismatch"),
            )
        }
        val result = try {
            input.discard()
        } catch (failure: Exception) {
            EncodingInputSettlement.Failed(ScreenCaptureProblem.InternalFailure, failure)
        }
        return recordInputSettlement(record, input, result)
    }

    private fun recordInputSettlement(record: SessionProductionRecord, input: EncodingInput, result: EncodingInputSettlement): EncodingInputSettlement {
        val recorded = synchronized(sessionGate) {
            encodingLink.recordSettlementReturnedLocked(record, input, result)
        }
        return if (recorded) result else EncodingInputSettlement.Failed(
            problem = ScreenCaptureProblem.InternalFailure,
            cause = IllegalStateException("Encoding settlement return identity mismatch"),
        )
    }

    private fun consumeEncoding(fact: SessionEncodingLink.ProductionFact) {
        val record = fact.request.record
        when (val result = fact.result) {
            is EncodingResult.Encoded -> {
                production.recordEncodeSuccess(result.encodeDurationNanos, result.payload.byteCount)
                val output = production.completeEncoding(record, result.payload)
                if (output == null) {
                    production.recordStaleWork()
                    return
                }
                val (semanticCurrent, ordinaryAdmissionOpen) = synchronized(sessionGate) {
                    val sessionReady = topology.isActiveFor(record.configRevision)
                    val revisionCurrent = topology.acceptsSettledRevision(record.configRevision)
                    (sessionReady && revisionCurrent) to lifecycle.canRunProduction(sessionReady = sessionReady, revisionCurrent = revisionCurrent)
                }
                when {
                    !semanticCurrent -> discardStaleOutput(record, output)
                    !ordinaryAdmissionOpen -> discardUnadmittedOutput(record, output)
                }
            }

            EncodingResult.FrameFailed -> {
                production.recordProductionFailure()
                production.clearProduction(null, record)
            }

            EncodingResult.ReadinessChanged -> {
                var publication: OrdinaryPublication.State? = null
                serializePublication {
                    synchronized(sessionGate) {
                        production.recordProductionFailure()
                        production.clearProduction(null, record)
                        if (!lifecycle.isOrdinaryAdmissionOpen) return@synchronized
                        val invalidatesActivePlan = topology.invalidateEncodingReadiness()
                        production.prepareCache()?.let(production::invalidateCache)
                        if (invalidatesActivePlan) {
                            lifecycle.pauseProduction()
                            production.suppressPacingWake()
                            val paused = checkNotNull(topology.prepareReconfiguration())
                            val state = ScreenCaptureState.Reconfiguring.create(
                                requestedParameters = paused.requestedParameters,
                                lastOutputInfo = paused.historicalOutputInfo,
                                isCapturedContentVisible = paused.isCapturedContentVisible,
                            )
                            topology.commitPausedPublication(paused)
                            publication = reserveStateLocked(state)
                        }
                    }
                }
                publication?.let(::publishOrdinary)
            }

            is EncodingResult.Failed -> {
                var progress: TerminalProgress? = null
                serializePublication {
                    synchronized(sessionGate) {
                        val isCurrent = lifecycle.canRunProduction(
                            sessionReady = topology.isActiveFor(record.configRevision),
                            revisionCurrent = topology.acceptsSettledRevision(record.configRevision),
                        ) && production.currentRecordMatches(record)
                        production.recordProductionFailure()
                        production.clearProduction(null, record)
                        if (isCurrent) {
                            progress = offerTerminalProgressLocked(
                                SessionLifecycle.TerminalDecision.Failed(result.problem, result.cause),
                            )
                        }
                    }
                }
                continueTerminalProgress(progress)
            }

            EncodingResult.CutoffInert -> {
                production.recordStaleWork()
                production.clearProduction(null, record)
            }
        }
    }

    private fun discardStaleOutput(record: SessionProductionRecord, output: SessionProduction.UnpublishedOutput) {
        production.discardUnpublishedOutput(output)
        production.clearProduction(null, record)
        production.recordStaleWork()
    }

    private fun discardUnadmittedOutput(record: SessionProductionRecord, output: SessionProduction.UnpublishedOutput) {
        production.discardUnpublishedOutput(output)
        production.clearProduction(null, record)
    }

    private fun processCompletedFreshOutput() {
        val readiness = synchronized(sessionGate) { topology.prepareProductionReadiness() } ?: return
        val nowNanos = try {
            executionClock.nowNanos()
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        when (val decision = production.prepareFreshOutput(readiness.plan.outputInfo, nowNanos, readiness.parameters.frameRate)) {
            SessionProduction.FreshOutputDecision.Missing -> Unit
            SessionProduction.FreshOutputDecision.InvalidEvidence ->
                offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Invalid fresh output evidence"))

            SessionProduction.FreshOutputDecision.SequenceExhausted ->
                offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Output sequence exhausted"))

            is SessionProduction.FreshOutputDecision.Deferred ->
                armPacingWake(decision.targetNanos, readiness.revision)

            is SessionProduction.FreshOutputDecision.Candidate -> {
                var frame: PublishedFrame? = null
                serializePublication {
                    synchronized(sessionGate) {
                        val isCurrent = lifecycle.canRunProduction(
                            sessionReady = topology.isActiveFor(readiness.revision),
                            revisionCurrent = topology.acceptsSettledRevision(readiness.revision),
                        )
                        if (!isCurrent || !readiness.isCurrent(topology) || !decision.isCurrent(production)) {
                            return@synchronized
                        }
                        frame = production.commitFreshOutput(decision)
                    }
                }
                frame?.let { offerPublishedFrame(readiness, it) }
            }
        }
    }

    private fun offerPublishedFrame(readiness: SessionTopology.ProductionReadiness, frame: PublishedFrame) {
        val publishedOffer = synchronized(sessionGate) {
            val isCurrent = lifecycle.canRunProduction(
                sessionReady = topology.isActiveFor(readiness.revision),
                revisionCurrent = topology.acceptsSettledRevision(readiness.revision),
            ) && readiness.isCurrent(topology) && production.frameIsLatest(frame)
            if (!isCurrent) return@synchronized SessionDelivery.PublishedFrameOffer.NotAvailable
            val isPhysicalHandoffFree = deliveryLink.currentHandoffLocked() == null && !deliveryLink.hasPendingOfferLocked()
            delivery.preparePublishedFrameOffer(frame, isPhysicalHandoffFree)
        }
        when (publishedOffer) {
            SessionDelivery.PublishedFrameOffer.NotAvailable -> Unit
            SessionDelivery.PublishedFrameOffer.ConsumerBusy -> production.recordConsumerBusy()
            is SessionDelivery.PublishedFrameOffer.Prepared -> executeDeliveryOffer(publishedOffer.offer)
        }
    }

    private fun offerCachedFirst() {
        val readiness = synchronized(sessionGate) { topology.prepareProductionReadiness() } ?: return
        val cachedFirstCheck = synchronized(sessionGate) { delivery.beginCachedFirstCheck() } ?: return
        val cache = production.prepareCache()
        val offer = synchronized(sessionGate) {
            val isCurrent = lifecycle.canRunProduction(
                sessionReady = topology.isActiveFor(readiness.revision),
                revisionCurrent = topology.acceptsSettledRevision(readiness.revision),
            ) && readiness.isCurrent(topology)
            if (!isCurrent) return@synchronized null
            val frame = when {
                cache == null -> null
                cache.isCurrent(production) && readiness.isCachedImageCurrent(topology, cache.frame.outputInfo) -> cache.frame
                else -> return@synchronized null
            }
            val currentHandoff = deliveryLink.currentHandoffLocked()
            val isPhysicalHandoffFree = currentHandoff == null && !deliveryLink.hasPendingOfferLocked()
            if (!isPhysicalHandoffFree && currentHandoff?.registrationId != cachedFirstCheck.registration.id) {
                return@synchronized null
            }
            delivery.settleCachedFirstCheck(cachedFirstCheck, frame, isPhysicalHandoffFree)
        } ?: return
        when (offer) {
            SessionDelivery.CachedFirstOffer.Stale, SessionDelivery.CachedFirstOffer.Skipped -> Unit
            SessionDelivery.CachedFirstOffer.ConsumerBusy -> production.recordConsumerBusy()
            is SessionDelivery.CachedFirstOffer.Prepared -> executeDeliveryOffer(offer.offer)
        }
    }

    private fun executeDeliveryOffer(offer: SessionDelivery.Offer) {
        val request = synchronized(sessionGate) {
            deliveryLink.prepareOfferLocked(offer.handoff, offer.completion, offer.callback, offer.frame)
        }
        val result = try {
            deliveryLink.executeOffer(request)
        } catch (failure: Exception) {
            settleOfferThatDidNotStart(offer)
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        var terminalPublicationAlreadyClaimed = false
        val recorded = synchronized(sessionGate) {
            val current = deliveryLink.recordOfferReturnedLocked(request, result)
            terminalPublicationAlreadyClaimed = terminalPublicationClaimed
            current
        }
        if (!recorded) {
            if (terminalPublicationAlreadyClaimed) {
                when (result) {
                    is DeliveryOffer.Accepted -> when (synchronized(sessionGate) {
                        delivery.settleAcceptedOffer(offer, result.handoff)
                    }) {
                        SessionDelivery.AcceptedOfferSettlement.RequestCutoff -> executeCutoff(offer.registration, offer.handoff)
                        SessionDelivery.AcceptedOfferSettlement.Stale,
                        SessionDelivery.AcceptedOfferSettlement.Retained,
                            -> Unit
                    }

                    is DeliveryOffer.Rejected -> settleOfferThatDidNotStart(offer, result.handoff)
                    DeliveryOffer.Occupied,
                    DeliveryOffer.Cutoff -> settleOfferThatDidNotStart(offer)
                }
            }
            if (!terminalPublicationAlreadyClaimed) {
                offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Delivery offer identity mismatch"))
            }
            return
        }
        when (result) {
            is DeliveryOffer.Accepted -> when (synchronized(sessionGate) {
                delivery.settleAcceptedOffer(offer, result.handoff)
            }) {
                SessionDelivery.AcceptedOfferSettlement.Stale, SessionDelivery.AcceptedOfferSettlement.Retained -> Unit
                SessionDelivery.AcceptedOfferSettlement.RequestCutoff -> executeCutoff(offer.registration, offer.handoff)
            }

            is DeliveryOffer.Rejected -> {
                offerFailure(
                    problem = ScreenCaptureProblem.InternalFailure,
                    cause = result.cause ?: IllegalStateException("Delivery callback dispatch was rejected"),
                )
                settleOfferThatDidNotStart(offer, result.handoff)
            }

            DeliveryOffer.Occupied -> {
                production.recordConsumerBusy()
                settleOfferThatDidNotStart(offer)
            }

            DeliveryOffer.Cutoff -> settleOfferThatDidNotStart(offer)
        }
    }

    private fun settleOfferThatDidNotStart(
        offer: SessionDelivery.Offer,
        returnedToken: io.screenstream.capture.internal.delivery.DeliveryHandoffToken = offer.handoff,
    ) {
        val settlement = synchronized(sessionGate) { delivery.settleOfferThatDidNotStart(offer, returnedToken) }
        completeHandoffSettlement(settlement)
    }

    private fun consumeDeliveryClosed(fact: DeliveryFact.Closed) {
        var failure: Exception? = null
        var settlement: SessionDelivery.HandoffSettlement? = null
        synchronized(sessionGate) {
            if (!deliveryLink.clearHandoffLocked(fact.handoff)) {
                if (lifecycle.isOrdinaryAdmissionOpen) {
                    failure = IllegalStateException("Delivery closed handoff identity mismatch")
                }
                return@synchronized
            }
            failure = if (lifecycle.isOrdinaryAdmissionOpen) {
                when (val outcome = fact.outcome) {
                    DeliveryFact.Closed.Outcome.CallbackReturned, DeliveryFact.Closed.Outcome.CutoffBeforeEntry -> null
                    is DeliveryFact.Closed.Outcome.InternalFailure -> outcome.cause
                }
            } else {
                null
            }
            if (failure == null) {
                settlement = delivery.settleClosedHandoff(fact.handoff.registrationId)
            }
        }
        failure?.let {
            offerFailure(ScreenCaptureProblem.InternalFailure, it)
            settlement = synchronized(sessionGate) {
                delivery.settleClosedHandoff(fact.handoff.registrationId)
            }
        }
        settlement?.let(::completeHandoffSettlement)
    }

    private fun completeHandoffSettlement(settlement: SessionDelivery.HandoffSettlement) {
        (settlement as? SessionDelivery.HandoffSettlement.ReadyToCompleteUnregister)?.settlement?.complete()
    }

    private suspend fun unregister(registration: SessionDelivery.Registration) {
        val link = synchronized(sessionGate) {
            if (::deliveryLink.isInitialized) deliveryLink else null
        }
        check(link?.isEnteredCallbackThread(registration.id) != true) { "A frame consumer cannot unregister itself" }
        var wake: ImmediateControlWake? = null
        val action = serializePublication {
            synchronized(sessionGate) {
                val publicationInFlight = ordinaryPublication != null
                val selected = delivery.beginUnregister(registration, requestCutoffImmediately = !publicationInFlight)
                if (publicationInFlight) {
                    controlWorkPending = true
                    wake = requestControlWakeLocked()
                }
                selected
            }
        }
        postControlWake(wake)
        when (action) {
            is SessionDelivery.UnregisterAction.AwaitCompletion -> Unit
            is SessionDelivery.UnregisterAction.Complete -> action.settlement.complete()
            is SessionDelivery.UnregisterAction.RequestCutoff -> executeCutoff(action.registration, action.token)
        }
        action.waiter.awaitCompletion()
    }

    private fun executePendingUnregisterAction() {
        when (val pending = synchronized(sessionGate) { delivery.claimPendingUnregisterAction() } ?: return) {
            is SessionDelivery.UnregisterAction.AwaitCompletion -> Unit
            is SessionDelivery.UnregisterAction.Complete -> pending.settlement.complete()
            is SessionDelivery.UnregisterAction.RequestCutoff -> executeCutoff(pending.registration, pending.token)
        }
    }

    private fun executeCutoff(
        registration: SessionDelivery.Registration,
        token: io.screenstream.capture.internal.delivery.DeliveryHandoffToken,
    ) {
        val cutoff = try {
            deliveryLink.executeCutoff(token)
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        when (val settlement = synchronized(sessionGate) { delivery.recordCutoffResult(registration, token, cutoff) }) {
            is SessionDelivery.CutoffSettlement.Handoff -> completeHandoffSettlement(settlement.settlement)
            SessionDelivery.CutoffSettlement.RequestSuccessor -> executeCutoff(registration, token)
        }
    }

    private fun armPacingWake(targetNanos: Long, revision: Long) {
        synchronized(sessionGate) {
            if (!lifecycle.isOrdinaryAdmissionOpen || !topology.acceptsSettledRevision(revision)) return
            production.armPacingWake(targetNanos, revision)
        }
        syncPacingWakeTask()
    }

    private fun enterPacingWake() {
        val isCurrent = synchronized(sessionGate) {
            val wake = postedPacingWake
            postedPacingWake = null
            wake != null && production.clearPacingWake(wake) && lifecycle.isOrdinaryAdmissionOpen && topology.acceptsSettledRevision(wake.configRevision)
        }
        if (isCurrent) signalControl()
    }

    private fun syncPacingWakeTask() {
        var removeExisting = false
        var wakeToPost: SessionProduction.PacingWake? = null
        synchronized(sessionGate) {
            val logical = if (lifecycle.isOrdinaryAdmissionOpen) {
                production.currentPacingWake()
            } else {
                production.suppressPacingWake()
                null
            }
            val posted = postedPacingWake
            if (posted !== logical) {
                postedPacingWake = logical
                removeExisting = posted != null
                wakeToPost = logical
            }
        }
        val executor = controlExecutor
        if (removeExisting && executor != null) executor.removeCallbacks(pacingWakeTask)
        val wake = wakeToPost ?: return
        if (executor == null) {
            settlePacingSchedulingFailure(wake, IllegalStateException("Control executor unavailable for pacing wake"))
            return
        }
        val remainingNanos = try {
            maxOf(0L, Math.subtractExact(wake.targetNanos, executionClock.nowNanos()))
        } catch (failure: Exception) {
            settlePacingSchedulingFailure(wake, failure)
            return
        }
        val stillPending = synchronized(sessionGate) {
            postedPacingWake === wake && production.currentPacingWake() === wake
        }
        if (!stillPending) return
        val accepted = try {
            executor.postDelayed(pacingWakeTask, nanosToCeilingMillis(remainingNanos))
        } catch (failure: Exception) {
            settlePacingSchedulingFailure(wake, failure)
            return
        }
        if (!accepted) {
            settlePacingSchedulingFailure(wake, IllegalStateException("Delayed Control pacing wake was rejected"))
        }
    }

    private fun settlePacingSchedulingFailure(wake: SessionProduction.PacingWake, failure: Exception) {
        val shouldFail = synchronized(sessionGate) {
            if (postedPacingWake !== wake) return@synchronized false
            if (production.currentPacingWake() !== wake) return@synchronized false
            postedPacingWake = null
            production.clearPacingWake(wake)
        }
        if (shouldFail) offerFailure(ScreenCaptureProblem.InternalFailure, failure)
    }

    private fun publishStatsIfDue() {
        val nowNanos = try {
            executionClock.nowNanos()
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        val candidate = try {
            synchronized(sessionGate) {
                if (!lifecycle.canRunProduction(sessionReady = topology.isActive, revisionCurrent = true)) return
                production.prepareStats(nowNanos)
            }
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            return
        } ?: return
        var publication: OrdinaryPublication.Stats? = null
        serializePublication {
            synchronized(sessionGate) {
                if (!lifecycle.canRunProduction(sessionReady = topology.isActive, revisionCurrent = true) || !candidate.isCurrent(production)) {
                    return@synchronized
                }
                production.commitStatsPublication(candidate)
                publication = reserveStatsLocked(candidate.stats)
            }
        }
        publication?.let(::publishOrdinary)
    }

    private fun invalidateCache() {
        val candidate = production.prepareCache() ?: return
        serializePublication {
            synchronized(sessionGate) {
                if (candidate.isCurrent(production)) production.invalidateCache(candidate)
            }
        }
    }

    private fun finishTerminalIfPending(): Boolean {
        val pending = synchronized(sessionGate) { lifecycle.isTerminalPending }
        if (pending) finishTerminal()
        return pending
    }

    private fun finishTerminal() {
        while (true) {
            if (synchronized(sessionGate) { terminalPublicationClaimed || ordinaryPublication != null }) return
            if (::deliveryLink.isInitialized) deliveryLink.executeTerminalEntryFence()

            var returnedDuringClaim: SessionReadBridge? = null
            var factsDuringClaim: TerminalFacts? = null
            val terminalPublication = serializePublication {
                synchronized(sessionGate) {
                    if (terminalPublicationClaimed) return@synchronized null
                    if (ordinaryPublication != null) return@synchronized null
                    takeTerminalFactsLocked()?.let {
                        factsDuringClaim = it
                        return@synchronized null
                    }
                    if (::captureLink.isInitialized) {
                        captureLink.takeReturnedReadLocked()?.let {
                            returnedDuringClaim = it
                            return@synchronized null
                        }
                    }

                    val lifecyclePreparation = lifecycle.prepareTerminal() ?: return@synchronized null
                    val topologyEvidence = topology.prepareTerminalEvidence()
                    val deliveryPreparation = delivery.prepareTerminal() ?: return@synchronized null
                    val productionSnapshot = production.prepareTerminal()
                    val preparation = TerminalPublicationCandidate(
                        lifecycle = lifecyclePreparation,
                        topology = topologyEvidence,
                        production = productionSnapshot,
                        delivery = deliveryPreparation,
                        state = terminalState(lifecyclePreparation.decision, topologyEvidence),
                        diagnostic = terminalDiagnostic(lifecyclePreparation.decision),
                    )

                    if (!lifecycle.isTerminalPreparationCurrent(preparation.lifecycle) ||
                        !preparation.topology.isCurrent(topology) ||
                        !preparation.production.isCurrent(production) ||
                        !delivery.isTerminalPreparationCurrent(preparation.delivery)
                    ) return@synchronized null
                    val currentRead = preparation.production.currentRead
                    if (::captureLink.isInitialized || ::encodingLink.isInitialized) {
                        check(::captureLink.isInitialized && ::encodingLink.isInitialized)
                        check(captureLink.prevalidateTerminalFreezeLocked(currentRead))
                        check(encodingLink.canFreezeTerminalLocked(currentRead?.record, currentRead?.input))
                    }

                    // Returned reads and recorded delivery facts are consumed before freeze. An unresolved read loan is
                    // detached for exact late settlement; this does not claim every unconsumed encoder operation drained.
                    if (::deliveryLink.isInitialized) deliveryLink.freezeTerminalLocked()
                    if (::captureLink.isInitialized) {
                        captureLink.freezeTerminalLocked(currentRead)
                        encodingLink.freezeTerminalLocked(currentRead?.record, currentRead?.input)
                    }
                    lifecycle.commitTerminal(preparation.lifecycle)
                    delivery.commitTerminal(preparation.delivery)
                    production.commitTerminal(preparation.production)
                    topology.invalidateActiveTopology()
                    postedPacingWake = null
                    terminalPublicationClaimed = true
                    preparation
                }
            }
            if (terminalPublication == null) {
                val exactFactsDuringClaim = factsDuringClaim
                if (exactFactsDuringClaim != null) {
                    consumeTerminalFacts(exactFactsDuringClaim)
                    continue
                }
                val exactReturnedDuringClaim = returnedDuringClaim
                if (exactReturnedDuringClaim != null) {
                    consumeRead(exactReturnedDuringClaim)
                    continue
                }
                if (synchronized(sessionGate) { controlWorkPending && controlExecutor != null }) signalControl()
                return
            }

            observations.publishTerminal(
                finalStats = terminalPublication.production.finalStats,
                diagnosticRequest = terminalPublication.diagnostic,
                terminalState = terminalPublication.state,
            )
            terminalPublication.lifecycle.startSettlement?.complete()
            terminalPublicationCompletion.complete(Unit)
            terminalPublication.delivery.settlement?.complete()
            delivery.completeTerminalRegistration(terminalPublication.delivery)
            controlExecutor?.removeCallbacks(pacingWakeTask)
            bootstrap.requestPrefixRetirement()
            if (::metricsOwner.isInitialized) {
                metricsOwner.retire()
            }
            if (::captureLink.isInitialized) {
                captureLink.retire()
            }
            if (::encodingLink.isInitialized) {
                encodingLink.retire()
            }
            controlExecutor?.requestQuit()
            return
        }
    }

    private fun takeTerminalFactsLocked(): TerminalFacts? {
        check(Thread.holdsLock(sessionGate))
        if (!::deliveryLink.isInitialized) return null
        val facts = TerminalFacts(
            callbackFailure = deliveryLink.takeCallbackFailureLocked(),
            closed = deliveryLink.takeClosedLocked(),
        )
        return facts.takeUnless { it.isEmpty() }
    }

    private fun consumeTerminalFacts(facts: TerminalFacts) {
        facts.callbackFailure?.let(::recordCallbackFailure)
        facts.closed?.let(::consumeDeliveryClosed)
    }

    private fun recordCallbackFailure(failure: DeliveryFact.CallbackFailure) {
        production.recordCallbackFailure()
        emitDiagnostic("Delivery", "CallbackFailure", failure.exception) { "Frame callback failed" }
    }

    private fun terminalState(decision: SessionLifecycle.TerminalDecision, evidence: SessionTopology.TerminalEvidence): ScreenCaptureState = when (decision) {
        SessionLifecycle.TerminalDecision.Requested ->
            ScreenCaptureState.Stopped.create(ScreenCaptureStopReason.Requested, evidence.requestedParameters, evidence.lastOutputInfo)

        SessionLifecycle.TerminalDecision.ProjectionStopped ->
            ScreenCaptureState.Stopped.create(ScreenCaptureStopReason.ProjectionStopped, evidence.requestedParameters, evidence.lastOutputInfo)

        is SessionLifecycle.TerminalDecision.Failed ->
            ScreenCaptureState.Failed.create(decision.problem, evidence.requestedParameters, evidence.lastOutputInfo)
    }

    private fun terminalDiagnostic(decision: SessionLifecycle.TerminalDecision): SessionObservationPublisher.DiagnosticRequest? = when (decision) {
        SessionLifecycle.TerminalDecision.Requested, SessionLifecycle.TerminalDecision.ProjectionStopped -> null
        is SessionLifecycle.TerminalDecision.Failed -> SessionObservationPublisher.DiagnosticRequest(
            source = "SessionCoordinator",
            eventName = "TerminalFailure",
            message = "Session failed: ${decision.problem.name}",
            cause = decision.cause,
        )
    }

    private fun offerFailure(problem: ScreenCaptureProblem, cause: Throwable?) =
        offerTerminal(SessionLifecycle.TerminalDecision.Failed(problem, cause))

    private fun offerCurrentProductionFailure(readiness: SessionTopology.ProductionReadiness, problem: ScreenCaptureProblem, cause: Throwable?) {
        val progress = serializePublication {
            synchronized(sessionGate) {
                val isCurrent = lifecycle.canRunProduction(
                    sessionReady = topology.isActiveFor(readiness.revision),
                    revisionCurrent = topology.acceptsSettledRevision(readiness.revision),
                ) && readiness.isCurrent(topology)
                if (!isCurrent) return@synchronized null
                offerTerminalProgressLocked(SessionLifecycle.TerminalDecision.Failed(problem, cause))
            }
        }
        continueTerminalProgress(progress)
    }

    private fun offerTerminalLocked(decision: SessionLifecycle.TerminalDecision): Boolean {
        val offer = lifecycle.offerTerminal(decision)
        if (offer !is SessionLifecycle.TerminalOffer.Accepted) return false
        if (offer.closesOrdinaryAdmission) {
            delivery.closeAdmissionForTerminal()
            if (controlExecutor == null) bootstrapOwnership.makeCutoffInert()
        }
        return true
    }

    private fun offerTerminalProgressLocked(decision: SessionLifecycle.TerminalDecision): TerminalProgress? {
        check(Thread.holdsLock(publicationGate) && Thread.holdsLock(sessionGate))
        if (!offerTerminalLocked(decision)) return null
        if (controlExecutor == null) return TerminalProgress(wake = null, finishBeforeControl = true)
        controlWorkPending = true
        return TerminalProgress(wake = requestControlWakeLocked(), finishBeforeControl = false)
    }

    private fun continueTerminalProgress(progress: TerminalProgress?) {
        if (progress == null) return
        postControlWake(progress.wake)
        if (progress.finishBeforeControl) finishTerminal()
    }

    private fun checkStartupDeadlineOnControl() {
        val deadline = synchronized(sessionGate) { lifecycle.startupDeadlineCandidate } ?: return
        val nowNanos = try {
            executionClock.nowNanos()
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        when (synchronized(sessionGate) { lifecycle.checkStartupDeadline(deadline, nowNanos) }) {
            SessionLifecycle.StartupCheck.ClockRegressed ->
                offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Elapsed realtime regressed on Control"))

            SessionLifecycle.StartupCheck.DeadlineExpired -> offerFailure(ScreenCaptureProblem.CaptureUnavailable, null)
            SessionLifecycle.StartupCheck.Eligible, SessionLifecycle.StartupCheck.Stale -> Unit
        }
    }

    private fun armStartupDeadline(deadline: SessionLifecycle.StartupDeadline) {
        val nowNanos = try {
            executionClock.nowNanos()
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        if (nowNanos < deadline.acceptedStartNanos) {
            offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Elapsed realtime regressed while arming"))
            return
        }
        val task = Runnable { enterStartupDeadline(deadline) }
        val accepted = try {
            delayedEntryScheduler.trySchedule(task, maxOf(0L, deadline.deadlineNanos - nowNanos))
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        if (!accepted) {
            offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Startup deadline scheduling was rejected"))
        }
    }

    private fun enterStartupDeadline(deadline: SessionLifecycle.StartupDeadline) {
        val nowNanos = try {
            executionClock.nowNanos()
        } catch (failure: Exception) {
            offerFailure(ScreenCaptureProblem.InternalFailure, failure)
            return
        }
        when (synchronized(sessionGate) { lifecycle.checkStartupDeadline(deadline, nowNanos) }) {
            SessionLifecycle.StartupCheck.ClockRegressed ->
                offerFailure(ScreenCaptureProblem.InternalFailure, IllegalStateException("Elapsed realtime regressed at startup deadline"))

            SessionLifecycle.StartupCheck.DeadlineExpired -> offerFailure(ScreenCaptureProblem.CaptureUnavailable, null)
            SessionLifecycle.StartupCheck.Eligible -> armStartupDeadline(deadline)
            SessionLifecycle.StartupCheck.Stale -> Unit
        }
    }

    private fun requestControlWakeLocked(): ImmediateControlWake? {
        val executor = controlExecutor ?: return null
        if (lifecycle.isTerminal || ordinaryPublication != null || pendingControlWake != null) return null
        return ImmediateControlWake(this, executor).also { pendingControlWake = it }
    }

    private fun postControlWake(wake: ImmediateControlWake?) {
        if (wake == null) return
        val accepted = try {
            wake.executor.post(wake.task)
        } catch (failure: Exception) {
            settleRejectedWake(wake, failure)
            return
        }
        if (!accepted) {
            settleRejectedWake(wake, IllegalStateException("Control wake dispatch was rejected"))
        }
    }

    private fun enterImmediateWake(wake: ImmediateControlWake) {
        val entered = synchronized(sessionGate) {
            if (pendingControlWake !== wake) return@synchronized false
            pendingControlWake = null
            true
        }
        if (entered) wake.executor.enterTurn()
    }

    private fun settleRejectedWake(wake: ImmediateControlWake, failure: Exception) {
        val shouldFail = synchronized(sessionGate) {
            if (pendingControlWake !== wake) return@synchronized false
            pendingControlWake = null
            lifecycle.isOrdinaryAdmissionOpen
        }
        if (shouldFail) offerFailure(ScreenCaptureProblem.InternalFailure, failure)
    }

    private fun checkedNowNanos(): Long {
        val nowNanos = try {
            executionClock.nowNanos()
        } catch (failure: Exception) {
            throw ScreenCaptureException.create(ScreenCaptureProblem.InternalFailure, failure)
        }
        if (nowNanos < 0L) {
            throw ScreenCaptureException.create(
                ScreenCaptureProblem.InternalFailure,
                IllegalStateException("Elapsed realtime must be nonnegative"),
            )
        }
        return nowNanos
    }

    private fun nanosToCeilingMillis(nanos: Long): Long {
        check(nanos >= 0L)
        val whole = nanos / ElapsedRealtimeClock.NANOS_PER_MILLISECOND
        return if (nanos % ElapsedRealtimeClock.NANOS_PER_MILLISECOND == 0L) whole else whole + 1L
    }

    private fun emitDiagnostic(source: String, eventName: String, cause: Throwable?, message: () -> String) {
        try {
            observations.tryPublishDiagnostic(SessionObservationPublisher.DiagnosticRequest(source, eventName, message(), cause))
        } catch (_: Exception) {
        }
    }

    private suspend fun awaitStart(waiter: SessionLifecycle.StartWaiter) {
        when (val outcome = waiter.awaitCompletion()) {
            SessionLifecycle.StartOutcome.Succeeded -> Unit
            SessionLifecycle.StartOutcome.Cancelled -> throw CancellationException("Screen capture start was stopped")
            is SessionLifecycle.StartOutcome.Failed -> throw outcome.failure
        }
    }

    internal fun onCaptureOpenReturned(link: SessionCaptureLink, result: CaptureOpenResult) {
        if (link !== captureLink) return
        var failure: Exception? = null
        val recorded = synchronized(sessionGate) {
            val fact = link.recordOpenReturnedLocked(result)
            if (fact == null && lifecycle.isOrdinaryAdmissionOpen) {
                failure = IllegalStateException("Capture Open return correlation mismatch")
            }
            fact != null
        }
        if (recorded) signalControl()
        failure?.let { offerFailure(ScreenCaptureProblem.InternalFailure, it) }
    }

    internal fun onCaptureApplyReturned(link: SessionCaptureLink, result: CaptureApplyResult) {
        if (link !== captureLink) return
        var failure: Exception? = null
        val recorded = synchronized(sessionGate) {
            val fact = link.recordApplyReturnedLocked(result)
            if (fact == null && lifecycle.isOrdinaryAdmissionOpen) {
                failure = IllegalStateException("Capture Apply return correlation mismatch")
            }
            fact != null
        }
        if (recorded) signalControl()
        failure?.let { offerFailure(ScreenCaptureProblem.InternalFailure, it) }
    }

    internal fun onCaptureReadReturned(link: SessionCaptureLink, read: SessionReadBridge, result: CaptureReadResult) {
        if (link !== captureLink) return
        var detachedCleanup = false
        var failure: Exception? = null
        val admission = synchronized(sessionGate) {
            val recorded = link.recordReadReturnedLocked(read, result, restoreSourceOpportunity = lifecycle.isOrdinaryAdmissionOpen)
            if (recorded == SessionCaptureLink.ReadReturnAdmission.Detached) {
                detachedCleanup = read.claimDetachedSettlementLocked()
            } else if (recorded == SessionCaptureLink.ReadReturnAdmission.MismatchOrDuplicate && lifecycle.isOrdinaryAdmissionOpen) {
                failure = IllegalStateException("Capture Read return correlation mismatch")
            }
            recorded
        }
        if (detachedCleanup) {
            discardInput(read.record, read.input)
            return
        }
        if (admission == SessionCaptureLink.ReadReturnAdmission.Recorded) signalControl()
        failure?.let { offerFailure(ScreenCaptureProblem.InternalFailure, it) }
    }

    internal fun onCaptureSourceAvailable(link: SessionCaptureLink, identity: CaptureSourceIdentity) {
        if (link !== captureLink) return
        val accepted = synchronized(sessionGate) {
            lifecycle.isOrdinaryAdmissionOpen && link.recordSourceAvailableLocked(identity)
        }
        if (accepted) signalControl()
    }

    internal fun onCaptureProjectionStopped(link: SessionCaptureLink, identity: CaptureProjectionIdentity) {
        if (link !== captureLink) return
        val isCurrentProjection = synchronized(sessionGate) { link.correlateProjectionIdentityLocked(identity) }
        if (isCurrentProjection) offerTerminal(SessionLifecycle.TerminalDecision.ProjectionStopped)
    }

    internal fun onCapturedContentResize(link: SessionCaptureLink, identity: CaptureProjectionIdentity, widthPx: Int, heightPx: Int) {
        if (link !== captureLink) return
        val accepted = synchronized(sessionGate) {
            lifecycle.isOrdinaryAdmissionOpen && link.recordResizeFactLocked(identity, widthPx, heightPx) != null
        }
        if (accepted) signalControl()
    }

    internal fun onCapturedContentVisibilityChanged(link: SessionCaptureLink, identity: CaptureProjectionIdentity, isVisible: Boolean) {
        if (link !== captureLink) return
        val accepted = synchronized(sessionGate) {
            lifecycle.isOrdinaryAdmissionOpen && link.recordVisibilityLocked(identity, isVisible)
        }
        if (accepted) signalControl()
    }

    internal fun onCaptureFailure(link: SessionCaptureLink, failure: Exception) {
        if (link === captureLink) offerFailure(ScreenCaptureProblem.InternalFailure, failure)
    }

    internal fun onEncodingReconcileReturned(link: SessionEncodingLink, request: SessionEncodingLink.ReconcileRequest, result: EncodingReconcileResult) {
        if (link !== encodingLink) return
        var failure: Exception? = null
        val admission = synchronized(sessionGate) {
            val recorded = link.recordReconcileCallbackLocked(request, result)
            if (recorded == SessionEncodingLink.FactAdmission.MismatchOrDuplicate && lifecycle.isOrdinaryAdmissionOpen) {
                failure = IllegalStateException("Encoding reconcile return correlation mismatch")
            }
            recorded
        }
        if (admission == SessionEncodingLink.FactAdmission.Recorded) signalControl()
        failure?.let { offerFailure(ScreenCaptureProblem.InternalFailure, it) }
    }

    internal fun onEncodingProductionReturned(link: SessionEncodingLink, request: SessionEncodingLink.ProductionRequest, result: EncodingResult) {
        if (link !== encodingLink) return
        var failure: Exception? = null
        val admission = synchronized(sessionGate) {
            val recorded = link.recordProductionCallbackLocked(request, result)
            if (recorded == SessionEncodingLink.FactAdmission.MismatchOrDuplicate && lifecycle.isOrdinaryAdmissionOpen) {
                failure = IllegalStateException("Encoding production return correlation mismatch")
            }
            recorded
        }
        if (admission == SessionEncodingLink.FactAdmission.Recorded) signalControl()
        failure?.let { offerFailure(ScreenCaptureProblem.InternalFailure, it) }
    }

    internal fun onDeliveryFact(fact: DeliveryFact) {
        if (!::deliveryLink.isInitialized) return
        var failure: Exception? = null
        val admission = synchronized(sessionGate) {
            val recorded = deliveryLink.recordFactLocked(fact)
            if ((recorded == SessionDeliveryLink.FactAdmission.Duplicate ||
                        recorded == SessionDeliveryLink.FactAdmission.Mismatch) &&
                lifecycle.isOrdinaryAdmissionOpen
            ) {
                failure = IllegalStateException("Delivery fact correlation mismatch")
            }
            recorded
        }
        if (admission == SessionDeliveryLink.FactAdmission.Recorded) signalControl()
        failure?.let { offerFailure(ScreenCaptureProblem.InternalFailure, it) }
    }

    internal fun stageDeliveryClosed(fact: DeliveryFact.Closed): DeliveryClosedStage {
        if (!::deliveryLink.isInitialized) return DeliveryClosedStage { }
        val admission = synchronized(sessionGate) { deliveryLink.recordFactLocked(fact) }
        return when (admission) {
            SessionDeliveryLink.FactAdmission.Recorded -> DeliveryClosedStage {
                val ready = synchronized(sessionGate) { deliveryLink.markClosedReadyLocked(fact) }
                if (ready) signalControl()
            }

            SessionDeliveryLink.FactAdmission.Duplicate,
            SessionDeliveryLink.FactAdmission.Mismatch,
                -> throw IllegalStateException("Delivery Closed stage correlation mismatch")

            SessionDeliveryLink.FactAdmission.Stale -> DeliveryClosedStage { }
        }
    }

    private fun offerTerminal(decision: SessionLifecycle.TerminalDecision) {
        val progress = serializePublication {
            synchronized(sessionGate) {
                offerTerminalProgressLocked(decision)
            }
        }
        continueTerminalProgress(progress)
    }

    private fun reserveStateLocked(state: ScreenCaptureState): OrdinaryPublication.State {
        check(Thread.holdsLock(publicationGate) && Thread.holdsLock(sessionGate))
        check(ordinaryPublication == null)
        return OrdinaryPublication.State(state).also { ordinaryPublication = it }
    }

    private fun reserveStatsLocked(stats: ScreenCaptureStats): OrdinaryPublication.Stats {
        check(Thread.holdsLock(publicationGate) && Thread.holdsLock(sessionGate))
        check(ordinaryPublication == null)
        return OrdinaryPublication.Stats(stats).also { ordinaryPublication = it }
    }

    private fun reserveActiveLocked(
        state: ScreenCaptureState.Active,
        reservation: SessionLifecycle.ActiveReservation.Reserved,
        commit: SessionTopology.ActivePublicationCommit,
    ): OrdinaryPublication.Active {
        check(Thread.holdsLock(publicationGate) && Thread.holdsLock(sessionGate))
        check(ordinaryPublication == null)
        return OrdinaryPublication.Active(state, reservation, commit).also { ordinaryPublication = it }
    }

    private fun publishOrdinary(publication: OrdinaryPublication): OrdinaryPublicationSettlement {
        // This off-gate Flow assignment settles the same reserved operation afterward. An immediate collector cannot
        // consume it; the reservation defers competing wakes and terminal claim. Active then revalidates topology and
        // metrics before settlement. No frame is implied by assignment.
        when (publication) {
            is OrdinaryPublication.State -> observations.publishState(publication.value)
            is OrdinaryPublication.Active -> observations.publishState(publication.value)
            is OrdinaryPublication.Stats -> observations.publishStats(publication.value)
        }

        var wake: ImmediateControlWake? = null
        var finishTerminal = false
        var startSettlement: SessionLifecycle.StartSettlement? = null
        val canContinue = serializePublication {
            synchronized(sessionGate) {
                check(ordinaryPublication === publication)
                ordinaryPublication = null
                val ordinaryAdmissionOpen = lifecycle.isOrdinaryAdmissionOpen
                var mayContinueOrdinaryWork = ordinaryAdmissionOpen
                if (publication is OrdinaryPublication.Active) {
                    val snapshot = metricsOwner.readSnapshot()
                    val activeMayContinue = ordinaryAdmissionOpen && !controlWorkPending &&
                            publication.commit.isCurrent(topology, snapshot)
                    mayContinueOrdinaryWork = activeMayContinue
                    startSettlement = lifecycle.settleActive(publication.reservation.token, activeMayContinue)
                    topology.settleActivePublication(activeMayContinue)
                    controlWorkPending = true
                }
                finishTerminal = lifecycle.isTerminalPending
                if (!finishTerminal && controlWorkPending) wake = requestControlWakeLocked()
                mayContinueOrdinaryWork && !finishTerminal
            }
        }
        postControlWake(wake)
        if (finishTerminal) finishTerminal()
        return OrdinaryPublicationSettlement(canContinue, startSettlement)
    }

    private inline fun <T> serializePublication(block: () -> T): T = synchronized(publicationGate, block)

    private companion object {
        private const val STARTUP_WINDOW_NANOS: Long = 10L * ElapsedRealtimeClock.NANOS_PER_SECOND
    }
}
