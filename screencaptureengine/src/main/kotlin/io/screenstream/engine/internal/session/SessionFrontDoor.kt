package io.screenstream.engine.internal.session

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.EncodedImageFrame
import io.screenstream.engine.JpegBackendPolicy
import io.screenstream.engine.ScreenCaptureException
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.ScreenCaptureState
import io.screenstream.engine.ScreenCaptureStopReason
import io.screenstream.engine.internal.capture.CaptureCapsule
import io.screenstream.engine.internal.capture.CaptureCommand
import io.screenstream.engine.internal.capture.CaptureRetirement
import io.screenstream.engine.internal.delivery.DeliveryCapsule
import io.screenstream.engine.internal.delivery.DeliveryEntryState
import io.screenstream.engine.internal.delivery.DeliveryRetirement
import io.screenstream.engine.internal.jpeg.EncoderCapsule
import io.screenstream.engine.internal.jpeg.EncoderEntryState
import io.screenstream.engine.internal.jpeg.EncoderProductionTask
import io.screenstream.engine.internal.jpeg.EncoderRetirement
import io.screenstream.engine.internal.jpeg.EncoderSetupTask
import io.screenstream.engine.internal.metrics.MetricsCapsule
import io.screenstream.engine.internal.metrics.MetricsRetirement
import io.screenstream.engine.internal.runtime.SessionExecutionComposition
import io.screenstream.engine.internal.runtime.SessionSerialRoles
import io.screenstream.engine.requireLocallyValid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CancellationException

internal class SessionFrontDoor internal constructor(
    @Suppress("unused")
    private val applicationContext: Context,
    @Suppress("unused")
    internal val metricsSource: CaptureMetricsSource,
    @Suppress("unused")
    internal val jpegBackendPolicy: JpegBackendPolicy,
    private val execution: SessionExecutionComposition,
) {
    /** Execution serialization only. Lock order is publicationMutex -> sessionGate. */
    private val publicationMutex = Any()
    internal val sessionGate = Any()
    internal val observations = SessionObservations(execution.wallClock)
    internal val metricsCapsule = MetricsCapsule()
    internal val captureCapsule = CaptureCapsule()
    internal val encoderCapsule = EncoderCapsule()
    internal val deliveryCapsule = DeliveryCapsule()
    internal val serialRoles = SessionSerialRoles(execution.cpuDispatcher)
    internal val executionClock: io.screenstream.engine.internal.runtime.ElapsedRealtimeClock
        get() = execution.elapsedRealtimeClock
    internal val creationElapsedRealtimeNanos: Long = execution.elapsedRealtimeClock.nanos()
    internal val platformSdkInt: Int
        get() = execution.platformSdkInt

    private val bootstrapCapsule = BootstrapCapsule()
    private val startCompletion = CompletableDeferred<Unit>()
    private var phase: FrontDoorPhase = FrontDoorPhase.NotStarted
    private var terminalAdmissionClosed = false
    private var terminalDecision: TerminalDecision? = null
    private var terminalPublicationClaimed = false
    private var startingPublicationPending = false
    private var desiredParameters: ScreenCaptureParameters = ScreenCaptureParameters()
    private var configRevision = 0L
    private var productionPaused = false
    private var lastEffectiveParameters: io.screenstream.engine.ScreenCaptureEffectiveParameters? = null
    private var activePublication: ActivePublication? = null
    private var controlPublicationInFlight: ControlPublicationKind? = null
    private var lastActiveModes: ActiveRuntimeModes? = null
    private var registrationId = 0L
    private var registration: Registration? = null
    private var controlLoop: SessionControlLoop? = null
    private var captureCommandExceptionCommand: CaptureCommand? = null
    private var captureCommandExceptionCause: Exception? = null
    private var captureDirectFatalCommand: CaptureCommand? = null
    private var captureDirectFatalCause: Throwable? = null
    private var dirty = false
    private var wakeScheduled = false
    internal val state: StateFlow<ScreenCaptureState> = observations.state
    internal val stats: StateFlow<io.screenstream.engine.ScreenCaptureStats> = observations.stats
    internal val diagnosticEvents: SharedFlow<io.screenstream.engine.ScreenCaptureDiagnosticEvent> =
        observations.diagnosticEvents

    internal suspend fun start(
        mediaProjection: MediaProjection,
        initialParameters: ScreenCaptureParameters,
    ) {
        currentCoroutineContext().ensureActive()
        var terminalAfterStarting: ClaimedTerminal? = null
        serializePublication {
            synchronized(sessionGate) {
                check(phase == FrontDoorPhase.NotStarted && !terminalAdmissionClosed) {
                    "ScreenCaptureSession can be started only once"
                }
                phase = FrontDoorPhase.Starting
                desiredParameters = initialParameters
                configRevision = 1L
                startingPublicationPending = true
                bootstrapCapsule.adoptAcceptedProjection(mediaProjection, initialParameters, configRevision)
            }

            observations.publishStarting()
            terminalAfterStarting = synchronized(sessionGate) {
                startingPublicationPending = false
                claimPreControlTerminalLocked()
            }
        }
        if (terminalAfterStarting != null) {
            publishPreControlTerminal(checkNotNull(terminalAfterStarting))
        } else {
            try {
                SessionBootstrap(this, bootstrapCapsule, execution).dispatch()
            } catch (exception: Exception) {
                onBootstrapFailure(bootstrapCapsule, exception)
            } catch (fatal: Throwable) {
                onBootstrapFatal(bootstrapCapsule)
                throw fatal
            }
        }

        try {
            startCompletion.await()
        } catch (cancellation: CancellationException) {
            stop()
            throw cancellation
        }
    }

    internal fun updateParameters(parameters: ScreenCaptureParameters) {
        parameters.requireLocallyValid()
        var wake: SessionControlLoop? = null
        var failure: ScreenCaptureException? = null
        serializePublication {
            synchronized(sessionGate) {
                check(phase == FrontDoorPhase.Running && !terminalAdmissionClosed) {
                    "Parameters can be updated only while the Session is running"
                }
                if (parameters == desiredParameters) return@serializePublication
                if (configRevision == Long.MAX_VALUE) {
                    val cause = IllegalStateException("configRevision exhausted")
                    failure = ScreenCaptureException.create(ScreenCaptureProblem.InternalFailure, cause)
                    selectTerminalLocked(TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, cause))
                    dirty = true
                    wake = requestControlWakeLocked()
                } else {
                    configRevision += 1L
                    desiredParameters = parameters
                    productionPaused = true
                    dirty = true
                    wake = requestControlWakeLocked()
                }
            }
        }
        requestWakeOutsideGate(wake)
        failure?.let { throw it }
    }

    internal fun registerFrameCallback(callback: (EncodedImageFrame) -> Unit): SubscriptionHandle {
        var wake: SessionControlLoop? = null
        var failure: ScreenCaptureException? = null
        var subscription: SubscriptionHandle? = null
        serializePublication {
            synchronized(sessionGate) {
                check(!terminalAdmissionClosed) { "A terminal Session cannot accept a frame callback" }
                check(registration == null) { "Only one unresolved frame registration is allowed" }
                if (registrationId == Long.MAX_VALUE) {
                    val cause = IllegalStateException("registrationId exhausted")
                    selectTerminalLocked(TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, cause))
                    dirty = true
                    wake = requestControlWakeLocked()
                    failure = ScreenCaptureException.create(ScreenCaptureProblem.InternalFailure, cause)
                } else {
                    registrationId += 1L
                    val accepted = Registration(registrationId, callback)
                    registration = accepted
                    subscription = RegistrationSubscription(this, accepted)
                }
            }
        }
        requestWakeOutsideGate(wake)
        failure?.let { throw it }
        return checkNotNull(subscription)
    }

    internal fun stop() {
        var terminal: ClaimedTerminal? = null
        var wake: SessionControlLoop? = null
        serializePublication {
            synchronized(sessionGate) {
                if (!selectTerminalLocked(TerminalDecision.OwnerStop)) return@serializePublication
                if (phase == FrontDoorPhase.ControlStarting || phase == FrontDoorPhase.Running) {
                    dirty = true
                    wake = requestControlWakeLocked()
                } else {
                    bootstrapCapsule.makeCutoffInert()
                    terminal = claimPreControlTerminalLocked()
                }
            }
        }
        if (terminal != null) publishPreControlTerminal(checkNotNull(terminal))
        requestWakeOutsideGate(wake)
    }

    internal fun enterBootstrap(capsule: BootstrapCapsule): Boolean = synchronized(sessionGate) {
        if (capsule !== bootstrapCapsule || terminalAdmissionClosed) {
            capsule.makeCutoffInert()
            false
        } else {
            capsule.enter()
        }
    }

    internal fun bootstrapCutoffWon(capsule: BootstrapCapsule): Boolean = synchronized(sessionGate) {
        capsule !== bootstrapCapsule || terminalAdmissionClosed
    }

    internal fun claimBootstrapRetirement(capsule: BootstrapCapsule): BootstrapLaneRetirement? =
        synchronized(sessionGate) {
            if (capsule !== bootstrapCapsule || !terminalAdmissionClosed) null else capsule.claimRetirement()
        }

    internal fun recordBootstrapRetirement(
        capsule: BootstrapCapsule,
        retiredCaptureThread: HandlerThread?,
        retiredControlThread: HandlerThread?,
        captureCause: Exception?,
        controlCause: Exception?,
    ) {
        synchronized(sessionGate) {
            if (capsule === bootstrapCapsule) {
                capsule.recordRetirement(
                    retiredCaptureThread,
                    retiredControlThread,
                    captureCause,
                    controlCause,
                )
            }
        }
    }

    internal fun recordControlThread(capsule: BootstrapCapsule, thread: HandlerThread) =
        synchronized(sessionGate) { if (capsule === bootstrapCapsule) capsule.recordControlThread(thread) }

    internal fun recordControlThreadStarted(capsule: BootstrapCapsule, thread: HandlerThread) =
        synchronized(sessionGate) { if (capsule === bootstrapCapsule) capsule.recordControlThreadStarted(thread) }

    internal fun recordControlLooper(capsule: BootstrapCapsule, thread: HandlerThread, looper: Looper) =
        synchronized(sessionGate) { if (capsule === bootstrapCapsule) capsule.recordControlLooper(thread, looper) }

    internal fun recordControlHandler(capsule: BootstrapCapsule, looper: Looper, handler: Handler) =
        synchronized(sessionGate) { if (capsule === bootstrapCapsule) capsule.recordControlHandler(looper, handler) }

    internal fun recordCaptureThread(capsule: BootstrapCapsule, thread: HandlerThread) =
        synchronized(sessionGate) { if (capsule === bootstrapCapsule) capsule.recordCaptureThread(thread) }

    internal fun recordCaptureThreadStarted(capsule: BootstrapCapsule, thread: HandlerThread) =
        synchronized(sessionGate) { if (capsule === bootstrapCapsule) capsule.recordCaptureThreadStarted(thread) }

    internal fun recordCaptureLooper(capsule: BootstrapCapsule, thread: HandlerThread, looper: Looper) =
        synchronized(sessionGate) { if (capsule === bootstrapCapsule) capsule.recordCaptureLooper(thread, looper) }

    internal fun recordCaptureHandler(capsule: BootstrapCapsule, looper: Looper, handler: Handler) =
        synchronized(sessionGate) { if (capsule === bootstrapCapsule) capsule.recordCaptureHandler(looper, handler) }

    internal fun recordFirstControlPost(capsule: BootstrapCapsule, accepted: Boolean) {
        synchronized(sessionGate) {
            if (capsule !== bootstrapCapsule) return
            capsule.recordFirstControlPost(accepted)
        }
        if (!accepted) {
            onBootstrapFailure(capsule, IllegalStateException("First Control post was rejected"))
        }
    }

    internal fun transferToControl(
        capsule: BootstrapCapsule,
        newControlLoop: SessionControlLoop,
    ): BootstrapTransfer? = synchronized(sessionGate) {
        if (capsule !== bootstrapCapsule || terminalAdmissionClosed || phase != FrontDoorPhase.Starting) return null
        val transfer = capsule.transferAuthority()
        captureCapsule.adoptAcceptedProjection(transfer.acceptedProjection)
        controlLoop = newControlLoop
        phase = FrontDoorPhase.ControlStarting
        dirty = true
        transfer
    }

    internal fun onBootstrapFailure(capsule: BootstrapCapsule, cause: Exception) {
        var terminal: ClaimedTerminal? = null
        var wake: SessionControlLoop? = null
        serializePublication {
            synchronized(sessionGate) {
                if (capsule !== bootstrapCapsule || terminalAdmissionClosed) return@serializePublication
                selectTerminalLocked(TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, cause))
                if (phase == FrontDoorPhase.ControlStarting || phase == FrontDoorPhase.Running) {
                    dirty = true
                    wake = requestControlWakeLocked()
                } else {
                    terminal = claimPreControlTerminalLocked()
                }
            }
        }
        if (terminal != null) publishPreControlTerminal(checkNotNull(terminal))
        requestWakeOutsideGate(wake)
    }

    internal fun onBootstrapFatal(capsule: BootstrapCapsule) {
        serializePublication {
            synchronized(sessionGate) {
                if (capsule === bootstrapCapsule) closeOrdinaryAdmissionLocked()
            }
        }
    }

    internal fun beginControlTurn(owner: SessionControlLoop): ControlIngress? = synchronized(sessionGate) {
        if (controlLoop !== owner) return null
        wakeScheduled = false
        if (!dirty) return null
        dirty = false
        ControlIngress(
            desiredParameters = desiredParameters,
            configRevision = configRevision,
            terminalDecision = terminalDecision,
            registration = registration,
        )
    }

    internal fun enterActivePublicationLocked(
        owner: SessionControlLoop,
        expectedRevision: Long,
        effectiveParameters: io.screenstream.engine.ScreenCaptureEffectiveParameters,
        modes: ActiveRuntimeModes,
        first: Boolean,
    ): Boolean = synchronized(sessionGate) {
        check(Thread.holdsLock(publicationMutex))
        check(Thread.holdsLock(sessionGate))
        val expectedPhase = if (first) FrontDoorPhase.ControlStarting else FrontDoorPhase.Running
        if (controlLoop !== owner || terminalAdmissionClosed || terminalDecision != null ||
            configRevision != expectedRevision || phase != expectedPhase || activePublication != null ||
            controlPublicationInFlight != null || dirty
        ) {
            return false
        }
        controlPublicationInFlight = ControlPublicationKind.ActiveBundle
        activePublication = ActivePublication(expectedRevision, first)
        lastEffectiveParameters = effectiveParameters
        lastActiveModes = modes
        true
    }

    internal fun completeActivePublication(
        owner: SessionControlLoop,
        expectedRevision: Long,
    ): ActivePublicationCompletion {
        val first = synchronized(sessionGate) {
            check(Thread.holdsLock(publicationMutex))
            val publication = checkNotNull(activePublication)
            check(controlLoop === owner && publication.revision == expectedRevision)
            activePublication = null
            check(controlPublicationInFlight == ControlPublicationKind.ActiveBundle)
            controlPublicationInFlight = null
            if (publication.first) {
                check(phase == FrontDoorPhase.ControlStarting)
                phase = FrontDoorPhase.Running
            }
            if (!terminalAdmissionClosed && terminalDecision == null &&
                configRevision == expectedRevision && !dirty
            ) {
                productionPaused = false
            }
            publication.first
        }
        if (first) startCompletion.complete(Unit)
        val ordinaryWorkMayContinue = synchronized(sessionGate) {
            check(Thread.holdsLock(publicationMutex))
            productionRevisionOpenLocked(owner, expectedRevision) && !dirty
        }
        return ActivePublicationCompletion(first, ordinaryWorkMayContinue)
    }

    internal fun reserveControlPublicationLocked(
        owner: SessionControlLoop,
        kind: ControlPublicationKind,
    ): Boolean {
        check(Thread.holdsLock(publicationMutex))
        check(Thread.holdsLock(sessionGate))
        if (!ordinaryAdmissionOpenLocked(owner) || controlPublicationInFlight != null) return false
        controlPublicationInFlight = kind
        return true
    }

    internal fun completeControlPublication(owner: SessionControlLoop, kind: ControlPublicationKind) {
        check(Thread.holdsLock(publicationMutex))
        synchronized(sessionGate) {
            check(controlLoop === owner && controlPublicationInFlight == kind)
            controlPublicationInFlight = null
        }
    }

    internal fun completeDeliveryHandoff(expected: Registration) {
        val complete = synchronized(sessionGate) {
            if (!expected.handoffOutstanding) return
            val unsubscribed = expected.completeHandoffLocked()
            if (unsubscribed && registration === expected) registration = null
            unsubscribed
        }
        if (complete) expected.completion.complete(Unit)
    }

    internal fun currentRegistrationLocked(expectedId: Long): Registration? {
        check(Thread.holdsLock(sessionGate))
        return registration?.takeIf { it.id == expectedId }
    }

    /** Non-semantic execution serialization; callers may acquire [sessionGate] only inside [action]. */
    internal fun <T> serializePublication(action: () -> T): T {
        check(!Thread.holdsLock(sessionGate)) { "publicationMutex must be acquired before sessionGate" }
        return synchronized(publicationMutex) { action() }
    }

    internal fun openRegistrationLocked(): Registration? {
        check(Thread.holdsLock(sessionGate))
        return registration?.takeIf { it.state == RegistrationState.Open }
    }

    internal fun isCurrentRevisionLocked(owner: SessionControlLoop, expectedRevision: Long): Boolean {
        check(Thread.holdsLock(sessionGate))
        return ordinaryAdmissionOpenLocked(owner) && configRevision == expectedRevision
    }

    /** The sole historical output committed by the latest successful Active publication. */
    internal fun lastEffectiveParametersLocked(
        owner: SessionControlLoop,
    ): io.screenstream.engine.ScreenCaptureEffectiveParameters? {
        check(Thread.holdsLock(sessionGate))
        return lastEffectiveParameters.takeIf { controlLoop === owner }
    }

    /** Allocates the next topology revision without moving desired-parameter ownership out of the Front Door. */
    internal fun advanceTopologyRevisionLocked(owner: SessionControlLoop, expectedRevision: Long): Long? {
        check(Thread.holdsLock(sessionGate))
        if (!isCurrentRevisionLocked(owner, expectedRevision)) return null
        check(configRevision != Long.MAX_VALUE) { "configRevision exhausted" }
        configRevision += 1L
        productionPaused = true
        dirty = true
        return configRevision
    }

    internal fun productionRevisionOpenLocked(owner: SessionControlLoop, expectedRevision: Long): Boolean {
        check(Thread.holdsLock(sessionGate))
        return isCurrentRevisionLocked(owner, expectedRevision) && !productionPaused
    }

    internal fun pauseProductionForSuspensionLocked(
        owner: SessionControlLoop,
        expectedRevision: Long,
    ): Boolean {
        check(Thread.holdsLock(sessionGate))
        if (!isCurrentRevisionLocked(owner, expectedRevision)) return false
        productionPaused = true
        return true
    }

    /** Current desired-revision quiescence while Control replaces a disabled Native JPEG backend. */
    internal fun pauseProductionForEncoderFallbackLocked(
        owner: SessionControlLoop,
        expectedRevision: Long,
    ): Boolean {
        check(Thread.holdsLock(sessionGate))
        if (!isCurrentRevisionLocked(owner, expectedRevision)) return false
        productionPaused = true
        return true
    }

    internal fun selectCaptureEnded() {
        var wake: SessionControlLoop? = null
        serializePublication {
            synchronized(sessionGate) {
                if (!selectTerminalLocked(TerminalDecision.CaptureEnded)) return@serializePublication
                dirty = true
                wake = requestControlWakeLocked()
            }
        }
        requestWakeOutsideGate(wake)
    }

    internal fun selectControlFailure(problem: ScreenCaptureProblem, cause: Throwable?): Boolean {
        var wake: SessionControlLoop? = null
        val selected = serializePublication {
            synchronized(sessionGate) {
                if (!selectTerminalLocked(TerminalDecision.Failed(problem, cause))) return@synchronized false
                dirty = true
                wake = requestControlWakeLocked()
                true
            }
        }
        if (!selected) return false
        requestWakeOutsideGate(wake)
        return true
    }

    internal fun selectProductionFailure(
        owner: SessionControlLoop,
        expectedRevision: Long,
        problem: ScreenCaptureProblem,
        cause: Throwable?,
    ): Boolean {
        var wake: SessionControlLoop? = null
        val selected = serializePublication {
            synchronized(sessionGate) {
                if (!productionRevisionOpenLocked(owner, expectedRevision) ||
                    !selectTerminalLocked(TerminalDecision.Failed(problem, cause))
                ) {
                    return@synchronized false
                }
                dirty = true
                wake = requestControlWakeLocked()
                true
            }
        }
        if (selected) requestWakeOutsideGate(wake)
        return selected
    }

    /** Fatal ingress for a role whose serial permit will not run a release continuation. */
    internal fun selectControlDirectFatal(
        owner: SessionControlLoop,
        problem: ScreenCaptureProblem,
        cause: Throwable,
        isCurrentLocked: () -> Boolean = { true },
    ) {
        serializePublication {
            synchronized(sessionGate) {
                if (controlLoop !== owner || phase == FrontDoorPhase.Terminal || !isCurrentLocked()) {
                    return@synchronized
                }
                selectTerminalLocked(TerminalDecision.Failed(problem, cause))
                dirty = true
            }
        }
    }

    internal fun selectCaptureCommandException(
        owner: SessionControlLoop,
        command: CaptureCommand,
        failure: Exception,
    ) {
        val rooted = synchronized(sessionGate) {
            if (controlLoop !== owner || !owner.captureCommandRootedLocked(command)) return
            val existingCause = captureCommandExceptionCause
            if (existingCause != null &&
                (captureCommandExceptionCommand !== command || existingCause !== failure)
            ) return
            captureCommandExceptionCommand = command
            captureCommandExceptionCause = failure
            closeOrdinaryAdmissionFenceLocked()
            true
        }
        if (!rooted) return
        try {
            var wake: SessionControlLoop? = null
            val selected = serializePublication {
                synchronized(sessionGate) {
                    if (controlLoop !== owner || captureCommandExceptionCommand !== command ||
                        captureCommandExceptionCause !== failure ||
                        !selectTerminalLocked(TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, failure))
                    ) return@synchronized false
                    dirty = true
                    wake = requestControlWakeLocked()
                    true
                }
            }
            if (selected) requestWakeOutsideGate(wake)
        } catch (_: Exception) {
            // Exact original failure remains rooted and admission remains closed.
        }
    }

    /** Capture's allocation-free outer fatal fence; it intentionally selects no public terminal outcome. */
    internal fun fenceCaptureDirectFatal(
        owner: SessionControlLoop,
        command: CaptureCommand,
        fatal: Throwable,
    ) {
        synchronized(sessionGate) {
            if (controlLoop !== owner || captureDirectFatalCause != null ||
                !owner.captureCommandRootedLocked(command)
            ) return
            captureDirectFatalCommand = command
            captureDirectFatalCause = fatal
            closeOrdinaryAdmissionFenceLocked()
        }
    }

    internal fun signalControl() {
        val wake = synchronized(sessionGate) {
            if (phase == FrontDoorPhase.Terminal) return
            dirty = true
            requestControlWakeLocked()
        }
        requestWakeOutsideGate(wake)
    }

    /** The single gate-owned one-way fence used by every ordinary Control/role admission. */
    internal fun ordinaryAdmissionOpenLocked(owner: SessionControlLoop): Boolean {
        check(Thread.holdsLock(sessionGate))
        return controlLoop === owner && !terminalAdmissionClosed
    }

    internal fun prepareTerminalFromControl(owner: SessionControlLoop): TerminalFold? = synchronized(sessionGate) {
        if (controlLoop !== owner || terminalPublicationClaimed || phase == FrontDoorPhase.Terminal) return null
        terminalDecision ?: return null
        phase = FrontDoorPhase.Terminal
        owner.prepareTerminalLocked()
    }

    internal fun claimPreparedTerminalFromControl(
        owner: SessionControlLoop,
        finalStats: io.screenstream.engine.ScreenCaptureStats,
    ): ClaimedTerminal? = synchronized(sessionGate) {
        if (controlLoop !== owner || phase != FrontDoorPhase.Terminal) return null
        claimTerminalPublicationLocked(checkNotNull(terminalDecision), finalStats)
    }

    internal fun completeTerminalPublication(terminal: ClaimedTerminal) {
        terminal.registration?.completeTerminal(terminal.decision)
        terminal.completeStart(startCompletion)
    }

    private fun selectTerminalLocked(decision: TerminalDecision): Boolean {
        check(Thread.holdsLock(publicationMutex))
        check(Thread.holdsLock(sessionGate))
        if (terminalPublicationClaimed) return false
        if (terminalDecision != null) return false
        terminalDecision = decision
        closeOrdinaryAdmissionLocked()
        registration?.markTerminal(decision)
        return true
    }

    private fun closeOrdinaryAdmissionLocked() {
        check(Thread.holdsLock(publicationMutex))
        check(Thread.holdsLock(sessionGate))
        closeOrdinaryAdmissionFenceLocked()
    }

    private fun closeOrdinaryAdmissionFenceLocked() {
        check(Thread.holdsLock(sessionGate))
        if (terminalAdmissionClosed) return
        terminalAdmissionClosed = true
        if (metricsCapsule.entryState == io.screenstream.engine.internal.metrics.MetricsEntryState.Queued &&
            metricsCapsule.entryKind == io.screenstream.engine.internal.metrics.MetricsEntryKind.Attachment
        ) {
            metricsCapsule.markCutoffInert()
        }
        if (captureCapsule.entryState == io.screenstream.engine.internal.capture.CaptureEntryState.Queued) {
            captureCapsule.markCutoffInert()
        }
        val encoderTask = encoderCapsule.task
        if (encoderCapsule.entryState == EncoderEntryState.Queued &&
            (encoderTask is EncoderSetupTask || encoderTask is EncoderProductionTask)
        ) {
            encoderCapsule.markCutoffInert(encoderTask)
        }
        if (deliveryCapsule.entryState == DeliveryEntryState.Queued) {
            deliveryCapsule.markCutoffInert(checkNotNull(deliveryCapsule.handoff))
        }
    }

    private fun claimPreControlTerminalLocked(): ClaimedTerminal? {
        val decision = terminalDecision ?: return null
        if (startingPublicationPending || phase == FrontDoorPhase.ControlStarting || phase == FrontDoorPhase.Running) {
            return null
        }
        phase = FrontDoorPhase.Terminal
        preparePreControlRetirementsLocked()
        return claimTerminalPublicationLocked(decision)
    }

    private fun claimTerminalPublicationLocked(
        decision: TerminalDecision,
        finalStats: io.screenstream.engine.ScreenCaptureStats = zeroStats(),
    ): ClaimedTerminal? {
        if (terminalPublicationClaimed) return null
        terminalPublicationClaimed = true
        return ClaimedTerminal(
            decision = decision,
            requestedParameters = desiredParameters,
            lastEffectiveParameters = lastEffectiveParameters,
            finalStats = finalStats,
            registration = registration,
            lastActiveModes = lastActiveModes,
        )
    }

    private fun publishPreControlTerminal(terminal: ClaimedTerminal) {
        serializePublication { observations.publishTerminal(terminal.publication) }

        observations.terminalCleanupSink.activateMetrics()
        observations.terminalCleanupSink.activateCapture()
        observations.terminalCleanupSink.activateEncoder()
        observations.terminalCleanupSink.activateDelivery()

        completeTerminalPublication(terminal)
    }

    private fun preparePreControlRetirementsLocked() {
        bootstrapCapsule.takeProjectionForCaptureRetirement()?.let(captureCapsule::adoptAcceptedProjection)
        bootstrapCapsule.closeCutoffInertWithoutLanes()
        metricsCapsule.requestClose()
        metricsCapsule.markCutoffInert()
        metricsCapsule.queueCloseIfReady()
        val metricsRetirement = if (metricsCapsule.hasUnresolvedOwnership) {
            MetricsRetirement.ReturnExpected(metricsCapsule)
        } else {
            MetricsRetirement.Closed
        }
        val captureRetirement = if (!captureCapsule.hasUnresolvedOwnership) {
            CaptureRetirement.Closed
        } else {
            captureCapsule.markCutoffInert()
            CaptureRetirement.ReturnExpected(captureCapsule)
        }
        val encoderRetirement = when {
            !encoderCapsule.hasUnresolvedOwnership -> EncoderRetirement.Closed
            encoderCapsule.hasOnlyProcessLifetimeResidue ->
                EncoderRetirement.ProcessLifetimeResidue(encoderCapsule)

            else -> {
                if (encoderCapsule.entryState == EncoderEntryState.Queued) {
                    encoderCapsule.markCutoffInert(checkNotNull(encoderCapsule.task))
                }
                EncoderRetirement.ReturnExpected(encoderCapsule)
            }
        }
        val deliveryRetirement = if (!deliveryCapsule.hasUnresolvedOwnership) {
            DeliveryRetirement.Closed
        } else {
            if (deliveryCapsule.entryState == DeliveryEntryState.Queued) {
                deliveryCapsule.markCutoffInert(checkNotNull(deliveryCapsule.handoff))
            }
            DeliveryRetirement.ReturnExpected(deliveryCapsule)
        }
        observations.terminalCleanupSink.initializeMetrics(metricsRetirement)
        observations.terminalCleanupSink.initializeCapture(captureRetirement)
        observations.terminalCleanupSink.initializeEncoder(encoderRetirement)
        observations.terminalCleanupSink.initializeDelivery(deliveryRetirement)
    }

    private fun requestControlWakeLocked(): SessionControlLoop? {
        val owner = controlLoop ?: return null
        if (wakeScheduled) return null
        wakeScheduled = true
        return owner
    }

    internal fun requestWakeOutsideGate(owner: SessionControlLoop?) {
        if (owner == null) return
        val failure = try {
            if (owner.requestWake()) null
            else IllegalStateException("Control wake dispatch was rejected")
        } catch (exception: Exception) {
            exception
        }
        if (failure != null) failClosedRejectedControlWake(owner, failure)
    }

    private fun failClosedRejectedControlWake(owner: SessionControlLoop, cause: Exception) {
        val rejected = serializePublication {
            synchronized(sessionGate) {
                if (controlLoop !== owner || phase == FrontDoorPhase.Terminal) return@synchronized null
                wakeScheduled = false
                if (terminalDecision == null) {
                    selectTerminalLocked(TerminalDecision.Failed(ScreenCaptureProblem.InternalFailure, cause))
                }
                dirty = true
                RejectedControlWake(checkNotNull(terminalDecision), registration)
            }
        } ?: return
        rejected.registration?.completeTerminal(rejected.decision)
        completeStartForDecision(rejected.decision)
    }

    private fun completeStartForDecision(decision: TerminalDecision) {
        when (decision) {
            TerminalDecision.OwnerStop,
            TerminalDecision.CaptureEnded,
                -> startCompletion.completeExceptionally(
                ScreenCaptureException.create(ScreenCaptureProblem.CaptureUnavailable),
            )

            is TerminalDecision.Failed -> startCompletion.completeExceptionally(
                ScreenCaptureException.create(decision.problem, decision.cause),
            )
        }
    }

    internal suspend fun unsubscribe(expected: Registration) {
        var completeNow = false
        val resolution = serializePublication {
            synchronized(sessionGate) {
                check(controlLoop?.isSelfUnsubscribeLocked(expected.id) != true) {
                    "A frame callback cannot unsubscribe itself"
                }
                when (expected.state) {
                    RegistrationState.Open -> {
                        expected.state = RegistrationState.Closing
                        if (!expected.handoffOutstanding) {
                            expected.state = RegistrationState.Success
                            if (registration === expected) registration = null
                            completeNow = true
                        }
                        UnsubscribeResolution.Pending
                    }

                    RegistrationState.Closing -> UnsubscribeResolution.Pending
                    RegistrationState.Success -> UnsubscribeResolution.Success
                    RegistrationState.TerminalStopped -> UnsubscribeResolution.TerminalStopped
                    RegistrationState.TerminalFailed -> UnsubscribeResolution.TerminalFailed(
                        expected.terminalDecision as TerminalDecision.Failed,
                    )
                }
            }
        }
        when (resolution) {
            UnsubscribeResolution.Pending -> if (completeNow) expected.completion.complete(Unit)
            UnsubscribeResolution.Success -> expected.completion.complete(Unit)
            UnsubscribeResolution.TerminalStopped ->
                expected.completion.completeExceptionally(CancellationException("Session stopped"))

            is UnsubscribeResolution.TerminalFailed -> expected.completion.completeExceptionally(
                ScreenCaptureException.create(resolution.decision.problem, resolution.decision.cause),
            )
        }
        expected.completion.await()
    }
}

private sealed interface UnsubscribeResolution {
    data object Pending : UnsubscribeResolution
    data object Success : UnsubscribeResolution
    data object TerminalStopped : UnsubscribeResolution
    class TerminalFailed(val decision: TerminalDecision.Failed) : UnsubscribeResolution
}

private class RejectedControlWake(
    val decision: TerminalDecision,
    val registration: Registration?,
)

internal interface SubscriptionHandle {
    suspend fun unsubscribe()
}

private class RegistrationSubscription(
    private val frontDoor: SessionFrontDoor,
    private val registration: Registration,
) : SubscriptionHandle {
    override suspend fun unsubscribe() {
        frontDoor.unsubscribe(registration)
    }
}

internal class Registration internal constructor(
    internal val id: Long,
    internal val callback: (EncodedImageFrame) -> Unit,
) {
    internal val completion = CompletableDeferred<Unit>()
    internal var state: RegistrationState = RegistrationState.Open
    internal var handoffOutstanding: Boolean = false
    internal var terminalDecision: TerminalDecision? = null

    internal fun markTerminal(decision: TerminalDecision) {
        if (state == RegistrationState.Success) return
        terminalDecision = decision
        state = if (decision is TerminalDecision.Failed) {
            RegistrationState.TerminalFailed
        } else {
            RegistrationState.TerminalStopped
        }
    }

    internal fun completeTerminal(decision: TerminalDecision) {
        when (state) {
            RegistrationState.TerminalFailed -> {
                val failed = decision as TerminalDecision.Failed
                completion.completeExceptionally(ScreenCaptureException.create(failed.problem, failed.cause))
            }

            RegistrationState.TerminalStopped ->
                completion.completeExceptionally(CancellationException("Session stopped"))

            RegistrationState.Open,
            RegistrationState.Closing,
            RegistrationState.Success,
                -> Unit
        }
    }

    internal fun admitHandoff() {
        check(state == RegistrationState.Open && !handoffOutstanding)
        handoffOutstanding = true
    }

    internal fun completeHandoffLocked(): Boolean {
        check(handoffOutstanding)
        handoffOutstanding = false
        if (state != RegistrationState.Closing) return false
        state = RegistrationState.Success
        return true
    }
}

internal enum class RegistrationState {
    Open,
    Closing,
    Success,
    TerminalStopped,
    TerminalFailed,
}

internal enum class FrontDoorPhase {
    NotStarted,
    Starting,
    ControlStarting,
    Running,
    Terminal,
}

internal sealed interface TerminalDecision {
    data object OwnerStop : TerminalDecision

    data object CaptureEnded : TerminalDecision

    class Failed internal constructor(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable?,
    ) : TerminalDecision
}

internal enum class ActiveTargetMode {
    Full,
    Downscaled,
}

internal enum class ActiveCarrierMode {
    NativeMalloc,
    ManagedDirect,
}

internal enum class ActiveJpegMode {
    Native,
    Framework,
}

internal class ActiveRuntimeModes internal constructor(
    internal val target: ActiveTargetMode,
    internal val carrier: ActiveCarrierMode,
    internal val jpeg: ActiveJpegMode,
) {
    internal fun describe(): String = "target=${target.name}, carrier=${carrier.name}, jpeg=${jpeg.name}"
}

private class ActivePublication(
    val revision: Long,
    val first: Boolean,
)

internal class ActivePublicationCompletion internal constructor(
    internal val first: Boolean,
    internal val ordinaryWorkMayContinue: Boolean,
)

internal enum class ControlPublicationKind {
    ActiveBundle,
    State,
    Stats,
    Diagnostic,
    Output,
    Cache,
}

internal class ClaimedTerminal internal constructor(
    internal val decision: TerminalDecision,
    internal val requestedParameters: ScreenCaptureParameters,
    internal val lastEffectiveParameters: io.screenstream.engine.ScreenCaptureEffectiveParameters?,
    internal val finalStats: io.screenstream.engine.ScreenCaptureStats,
    internal val registration: Registration?,
    internal val lastActiveModes: ActiveRuntimeModes?,
) {
    internal val publication: TerminalPublication
        get() {
            val state = when (decision) {
                TerminalDecision.OwnerStop -> ScreenCaptureState.Stopped.create(
                    reason = ScreenCaptureStopReason.OwnerStop,
                    requestedParameters = requestedParameters,
                    lastEffectiveParameters = lastEffectiveParameters,
                )

                TerminalDecision.CaptureEnded -> ScreenCaptureState.Stopped.create(
                    reason = ScreenCaptureStopReason.CaptureEnded,
                    requestedParameters = requestedParameters,
                    lastEffectiveParameters = lastEffectiveParameters,
                )

                is TerminalDecision.Failed -> ScreenCaptureState.Failed.create(
                    problem = decision.problem,
                    requestedParameters = requestedParameters,
                    lastEffectiveParameters = lastEffectiveParameters,
                )
            }
            val outcome = when (decision) {
                TerminalDecision.OwnerStop -> "Session stopped by owner"
                TerminalDecision.CaptureEnded -> "Capture authority ended"
                is TerminalDecision.Failed -> "Session failed: ${decision.problem.name}"
            }
            val message = "$outcome; last active modes: ${lastActiveModes?.describe() ?: "none"}"
            return TerminalPublication(
                finalStats = finalStats,
                diagnosticMessage = message,
                cause = (decision as? TerminalDecision.Failed)?.cause,
                terminalState = state,
            )
        }

    internal fun completeStart(completion: CompletableDeferred<Unit>) {
        when (decision) {
            TerminalDecision.OwnerStop,
            TerminalDecision.CaptureEnded,
                -> completion.completeExceptionally(
                ScreenCaptureException.create(ScreenCaptureProblem.CaptureUnavailable),
            )

            is TerminalDecision.Failed -> completion.completeExceptionally(
                ScreenCaptureException.create(decision.problem, decision.cause),
            )
        }
    }
}

internal class ControlIngress internal constructor(
    internal val desiredParameters: ScreenCaptureParameters,
    internal val configRevision: Long,
    internal val terminalDecision: TerminalDecision?,
    internal val registration: Registration?,
)
