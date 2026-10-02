package io.screenstream.mjpeg.capture

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import info.dvkr.screenstream.common.notification.NotificationHelper
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.EncodedFrame
import io.screenstream.capture.OutputSize
import io.screenstream.capture.ScreenCaptureConfig
import io.screenstream.capture.ScreenCaptureEngine
import io.screenstream.capture.ScreenCaptureException
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.ScreenCaptureSession
import io.screenstream.capture.ScreenCaptureState
import io.screenstream.capture.ScreenCaptureStopReason
import io.screenstream.mjpeg.MjpegStreamingModuleService
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.streaming.capture.ScreenCaptureAccess
import io.screenstream.streaming.foreground.createForegroundNotification
import io.screenstream.streaming.logE
import io.screenstream.streaming.logV
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one consent/foreground/projection/Engine attempt and its independent cleanup.
 * Startup records resources before the next fallible step. A paused capture keeps its session and
 * can resume when conditions or settings change. Stop closes admission immediately and ends the
 * attempt; cleanup joins startup and observers before independently stopping media and unregistering the borrowed consumer.
 */
internal class MjpegCaptureAttempt(
    val id: StreamingModule.CaptureAttemptId,
    private val service: MjpegStreamingModuleService,
    private val captureAccess: ScreenCaptureAccess,
    private val notifications: NotificationHelper,
    private val imageOutput: ImageOutput,
    image: ImageSettings,
    private val isInstanceCurrent: () -> Boolean,
    private val changed: () -> Unit,
) {
    enum class StopReason { User, System, Error, Shutdown }

    /** Terminal capture failure; suspension itself is a nonterminal Engine state. */
    enum class Failure { CaptureFailed }

    /** Consent and Engine facts; terminal Engine state alone does not confirm cleanup. */
    enum class Phase { Stopped, WaitingForPermission, Starting, Active, Reconfiguring, Suspended, Stopping, Failed }

    /** Borrowed-frame delivery and inert unavailable-event reservation; effects run outside the attempt gate. */
    interface ImageOutput {
        /** Consume the borrowed frame synchronously on its callback thread. */
        fun onFrame(id: StreamingModule.CaptureAttemptId, frame: EncodedFrame)

        /**
         * Reserve image unavailability at acceptance under the attempt gate. This must only change
         * image-owner state, never enter HTTP or the controller gate. Invoke the returned effect
         * outside the attempt gate; it retains neither a frame nor a capture resource.
         */
        fun reserveUnavailable(id: StreamingModule.CaptureAttemptId, reason: UnavailableReason): (() -> Unit)?

        /** Reserve a locally confirmed Active resumption with the same inert-effect contract. */
        fun reserveResumed(id: StreamingModule.CaptureAttemptId): (() -> Unit)?
    }

    /** Capture facts; the controller chooses whether to clear, retain or replace the image. */
    sealed interface UnavailableReason {
        data object Suspended : UnavailableReason
        data class Stopped(val reason: StopReason) : UnavailableReason
    }

    /**
     * Current attempt facts; cleanup remains unconfirmed until the independent task completes.
     * [suspensionProblem] is the current Engine problem only while the effective phase is Suspended.
     */
    data class Snapshot(
        val phase: Phase,
        val streaming: Boolean,
        val failure: Failure?,
        val cleanupSucceeded: Boolean?,
        val stopRequested: Boolean,
        val projectionAccepted: Boolean,
        val suspensionProblem: ScreenCaptureProblem?,
    )

    private val gate = Any()
    private val workJob = SupervisorJob()
    private val workScope = CoroutineScope(workJob + Dispatchers.Default)
    private val initialParameters = image.captureParameters()
    private val config = ScreenCaptureConfig(jpegBackendPolicy = image.jpegBackendPolicy)

    // Resource ownership survives cancellation and ends only when the independent cleanup completes.
    private var grant: ScreenCaptureAccess.Grant? = null
    private var projection: MediaProjection? = null
    private var session: ScreenCaptureSession? = null
    private var unregister: (suspend () -> Unit)? = null
    private var projectionAccepted = false
    private var startSucceeded = false

    // Stop admission and accepted Engine parameters share one gate.
    private var sentParameters = initialParameters
    private var stopReason: StopReason? = null
    private var failure: Failure? = null
    private var cleanupResult: Boolean? = null
    private var startupPhase = Phase.Starting

    /** Inert until the controller has stored this attempt. */
    private val producer = workScope.launch(start = CoroutineStart.LAZY) { produce() }

    /** Outlives work cancellation and every caller awaiting cleanup. */
    private val cleanupTask: Deferred<Boolean> = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
        finishCleanup()
    }.also { it.invokeOnCompletion { changed() } }

    // Controller commands and facts.

    fun start() {
        producer.start()
    }

    fun snapshot(): Snapshot = synchronized(gate) {
        val latest = session?.state?.value ?: ScreenCaptureState.NotStarted
        val currentFailure = failure ?: Failure.CaptureFailed.takeIf { latest is ScreenCaptureState.Failed }
        val cleanupSucceeded = cleanupResult.takeIf { cleanupTask.isCompleted }
        val phase = when {
            currentFailure != null || cleanupSucceeded == false -> Phase.Failed
            stopReason != null || latest is ScreenCaptureState.Stopped -> if (cleanupSucceeded == true) Phase.Stopped else Phase.Stopping
            latest is ScreenCaptureState.Active -> Phase.Active
            latest is ScreenCaptureState.Reconfiguring -> Phase.Reconfiguring
            latest is ScreenCaptureState.Suspended -> Phase.Suspended
            latest is ScreenCaptureState.Starting -> Phase.Starting
            else -> startupPhase
        }
        Snapshot(
            phase = phase,
            streaming = stopReason == null && startSucceeded && latest !is ScreenCaptureState.Stopped && latest !is ScreenCaptureState.Failed,
            failure = currentFailure,
            cleanupSucceeded = cleanupSucceeded,
            stopRequested = stopReason != null,
            projectionAccepted = projectionAccepted,
            suspensionProblem = if (phase == Phase.Suspended && latest is ScreenCaptureState.Suspended) latest.problem else null,
        )
    }

    /** Submit unequal image requests; only accepted Engine admission updates the remembered parameters. */
    fun updateImage(image: ImageSettings) {
        val parameters = image.captureParameters()
        var stopped: StopWork? = null
        var rejectedUpdate: Throwable? = null
        synchronized(gate) {
            if (stopReason != null || !isInstanceCurrent()) return
            val ownedSession = session ?: return
            if (parameters == sentParameters || (!startSucceeded && ownedSession.state.value !is ScreenCaptureState.Running)) return
            try {
                // Engine posts to its Handler without an inline image callback.
                ownedSession.updateParameters(parameters)
                sentParameters = parameters
            } catch (cause: Throwable) {
                stopped = acceptUpdateFailure(ownedSession, cause)
                if (stopped == null) rejectedUpdate = cause
            }
        }
        rejectedUpdate?.let { cause ->
            this@MjpegCaptureAttempt.logE("Engine.imageUpdate", "Parameter update rejected, attempt=$id", cause)
        }
        stopped?.let(::finishStop)
        changed()
    }

    /** Close exact-attempt admission before output effects; late startup results remain owned by cleanup. */
    fun requestStop(reason: StopReason) {
        val stopped = synchronized(gate) { acceptStop(reason) }
        finishStop(stopped)
    }

    /** Cancelling a caller cancels only its wait; false means an applicable cleanup API failed. */
    suspend fun awaitCleanup(): Boolean = cleanupTask.await()

    private fun isCurrent(): Boolean = isInstanceCurrent() && synchronized(gate) { stopReason == null }

    // Startup: notification → consent → foreground → projection → Engine → consumer/observation → start.

    // ServiceCompat ignores the compile-time service-type constant below API 29.
    @SuppressLint("InlinedApi")
    private suspend fun produce() {
        var stage = "Startup.notification"
        try {
            val notification = withContext(Dispatchers.Main.immediate) {
                notifications.createForegroundNotification(service, id)
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            stage = "Startup.consent"
            synchronized(gate) { startupPhase = Phase.WaitingForPermission }
            changed()
            val ownedGrant = captureAccess.requestConsent(id, ::isCurrent)
            synchronized(gate) { grant = ownedGrant }
            if (ownedGrant == null) {
                requestStop(StopReason.User)
                return
            }
            synchronized(gate) { startupPhase = Phase.Starting }
            changed()
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            stage = "Startup.foreground"
            when (val promotion = service.promoteForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, notification)) {
                StreamingModuleService.PromotionResult.ApiCompleted -> Unit
                is StreamingModuleService.PromotionResult.Failed -> {
                    fail(stage, promotion.cause)
                    return
                }

                StreamingModuleService.PromotionResult.Rejected -> {
                    if (isCurrent()) fail(stage, context = "promotion=Rejected")
                    return
                }
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            stage = "Startup.projection"
            val rawProjection = try {
                captureAccess.createProjection(ownedGrant)
            } catch (cause: Throwable) {
                fail(stage, cause)
                return
            }
            synchronized(gate) { projection = rawProjection; projectionAccepted = true }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            stage = "Engine.creation"
            val ownedSession = ScreenCaptureEngine.createSession(service, rawProjection, config)
            synchronized(gate) { session = ownedSession; projection = null }
            stage = "Startup.projectionHandoff"
            captureAccess.finishProjectionCreation(id)
            stage = "Engine.registration"
            // Registration may enter a callback before returning; the session is already owned.
            val registration = ownedSession.registerFrameConsumer { frame ->
                try {
                    var publishResumed: (() -> Unit)? = null
                    val admitted = synchronized(gate) {
                        stopReason == null && session === ownedSession && when (ownedSession.state.value) {
                            is ScreenCaptureState.Active -> {
                                publishResumed = imageOutput.reserveResumed(id)
                                true
                            }

                            is ScreenCaptureState.Suspended, is ScreenCaptureState.Stopped, is ScreenCaptureState.Failed -> false
                            else -> true
                        }
                    }
                    publishResumed?.invoke()
                    if (admitted) imageOutput.onFrame(id, frame)
                } catch (cause: Throwable) {
                    fail("Output.frame", cause)
                }
            }
            synchronized(gate) { unregister = registration::unregister }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            stage = "Engine.observation"
            observeEngine(ownedSession)
            stage = "Engine.start"
            ownedSession.start(initialParameters)
            synchronized(gate) { startSucceeded = true }
            changed()
        } catch (_: CancellationException) {
            if (isCurrent()) requestStop(StopReason.System)
        } catch (cause: Throwable) {
            fail(stage, cause)
        } finally {
            if (!isCurrent()) requestStop(synchronized(gate) { stopReason } ?: StopReason.Shutdown)
        }
    }

    // Observation and synchronous borrowed-image admission.

    private fun observeEngine(ownedSession: ScreenCaptureSession) {
        workScope.launch { observeState(ownedSession) }
        workScope.launch {
            // Changed statistics are published at approximately one-second intervals, not per frame.
            ownedSession.stats.collect { value ->
                this@MjpegCaptureAttempt.logV("Engine.stats", "attempt=$id, $value")
            }
        }
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            ownedSession.diagnosticEvents.collect { event ->
                this@MjpegCaptureAttempt.logV(
                    "Engine.diagnostic",
                    "attempt=$id, sequence=${event.sequence}, source=${event.source}, event=${event.eventName}, message=${event.message}",
                    event.cause,
                )
            }
        }
    }

    private suspend fun observeState(ownedSession: ScreenCaptureSession) {
        try {
            ownedSession.state.collect { value ->
                this@MjpegCaptureAttempt.logV("Engine.state", "attempt=$id, $value")
                val publishImage = synchronized(gate) {
                    if (stopReason != null) return@collect
                    val current = ownedSession.state.value
                    when {
                        session !== ownedSession || !isInstanceCurrent() -> null
                        value is ScreenCaptureState.Active && current is ScreenCaptureState.Active -> imageOutput.reserveResumed(id)
                        value is ScreenCaptureState.Suspended && current is ScreenCaptureState.Suspended -> imageOutput.reserveUnavailable(
                            id,
                            UnavailableReason.Suspended
                        )

                        else -> null
                    }
                }
                if (publishImage != null) {
                    try {
                        publishImage.invoke()
                    } catch (cause: Throwable) {
                        if (cause !is CancellationException || isCurrent()) fail("Output.state", cause)
                        return@collect
                    }
                }
                changed()
                when (value) {
                    is ScreenCaptureState.Failed -> fail("Engine.state", problem = value.problem, context = value.toString())
                    is ScreenCaptureState.Stopped -> requestStop(StopReason.System)
                    else -> Unit
                }
            }
        } catch (cause: CancellationException) {
            if (isCurrent()) fail("Engine.stateObservation", cause)
        } catch (cause: Throwable) {
            fail("Engine.stateObservation", cause)
        }
    }

    /** Called with the gate held; terminal admission can close before its state is published. */
    private fun acceptUpdateFailure(ownedSession: ScreenCaptureSession, cause: Throwable): StopWork? {
        val stage = "Engine.imageUpdate"
        return when (val current = ownedSession.state.value) {
            is ScreenCaptureState.Stopped -> acceptStop(StopReason.System).copy(diagnostic = FailureDiagnostic(stage, null, cause, current.toString()))
            is ScreenCaptureState.Failed -> acceptFailure(stage, cause, current.problem)
            else -> if (cause is IllegalStateException) null else acceptFailure(stage, cause)
        }
    }

    // Stopping and cleanup: admission under the gate, all output/media/core effects outside it.

    private fun fail(stage: String, cause: Throwable? = null, problem: ScreenCaptureProblem? = null, context: String? = null) {
        val stopped = synchronized(gate) {
            if (stopReason != null) return
            acceptFailure(stage, cause, problem, context = context)
        }
        finishStop(stopped)
    }

    /** Record product failure and its original diagnostic before admitting Stop. Caller holds the gate. */
    private fun acceptFailure(
        stage: String,
        cause: Throwable? = null,
        problem: ScreenCaptureProblem? = null,
        context: String? = null,
    ): StopWork {
        if (failure == null) failure = Failure.CaptureFailed
        val diagnostic = FailureDiagnostic(stage, problem ?: (cause as? ScreenCaptureException)?.problem, cause, context)
        return acceptStop(StopReason.Error).copy(diagnostic = diagnostic)
    }

    /** Record Stop and reserve its image transition under the gate; perform no external effects here. */
    private fun acceptStop(reason: StopReason): StopWork {
        val first = stopReason == null
        if (first) stopReason = reason
        val publishUnavailable = if (first) imageOutput.reserveUnavailable(id, UnavailableReason.Stopped(checkNotNull(stopReason))) else null
        return StopWork(session, publishUnavailable)
    }

    /** Output failure must never skip cancellation, session stop or the independent cleanup task. */
    private fun finishStop(stopped: StopWork) {
        try {
            stopped.diagnostic?.let { diagnostic ->
                this@MjpegCaptureAttempt.logE(
                    diagnostic.stage,
                    "attempt=$id, engineProblem=${diagnostic.problem}, context=${diagnostic.context}",
                    diagnostic.cause ?: IllegalStateException("Capture failed at ${diagnostic.stage}: ${diagnostic.problem ?: diagnostic.context}"),
                )
            }
            stopped.publishUnavailable?.invoke()
        } catch (cause: Throwable) {
            synchronized(gate) { if (failure == null) failure = Failure.CaptureFailed }
            logE("Output.stopped", "Stopped-image publication failed, attempt=$id", cause)
        } finally {
            workScope.cancel()
            try {
                stopped.session?.requestStop()
            } catch (cause: Throwable) {
                logE("Cleanup.requestStop", "Capture stop request failed, attempt=$id", cause)
            } finally {
                cleanupTask.start()
                changed()
            }
        }
    }

    private suspend fun finishCleanup(): Boolean = coroutineScope {
        workJob.join()
        val ownedSession = synchronized(gate) { session }
        // Borrowed callback completion and media stop are independent obligations of this owner.
        val consumerCleanup = async {
            try {
                synchronized(gate) { unregister }?.invoke()
                true
            } catch (cause: Throwable) {
                this@MjpegCaptureAttempt.logE("Cleanup.consumer", "Consumer unregister failed, attempt=$id", cause)
                false
            }
        }
        var succeeded = try {
            if (ownedSession != null) ownedSession.stop() else synchronized(gate) { projection }?.stop()
            true
        } catch (cause: Throwable) {
            this@MjpegCaptureAttempt.logE("Cleanup.media", "Media stop failed, attempt=$id", cause)
            false
        }
        if (ownedSession != null) {
            this@MjpegCaptureAttempt.logV(
                "Engine.cleanup",
                "attempt=$id, mediaStopSucceeded=$succeeded, state=${ownedSession.state.value}, stats=${ownedSession.stats.value}"
            )
        }
        // Failed Engine stop retains foreground; pre-Engine rollback still attempts REMOVE after raw stop failure.
        if (ownedSession == null || succeeded) {
            try {
                when (val release = service.removeForeground()) {
                    is StreamingModuleService.ReleaseResult.Failed -> {
                        this@MjpegCaptureAttempt.logE("Cleanup.foreground", "Foreground release failed, attempt=$id", release.cause)
                        succeeded = false
                    }

                    StreamingModuleService.ReleaseResult.Unconfirmed -> {
                        this@MjpegCaptureAttempt.logE(
                            "Cleanup.foreground",
                            "Foreground release unconfirmed, attempt=$id",
                            IllegalStateException("Foreground release was not confirmed for attempt=$id"),
                        )
                        succeeded = false
                    }

                    else -> Unit
                }
            } catch (cause: Throwable) {
                this@MjpegCaptureAttempt.logE("Cleanup.foreground", "Foreground release failed, attempt=$id", cause)
                succeeded = false
            }
        }
        // Safe after startup joins and rollback, even if projection creation was never entered.
        try {
            captureAccess.finishProjectionCreation(id)
        } catch (cause: Throwable) {
            this@MjpegCaptureAttempt.logE("Cleanup.projectionCreation", "Projection creation release failed, attempt=$id", cause)
            succeeded = false
        }
        succeeded = consumerCleanup.await() && succeeded
        val reusableGrant = synchronized(gate) {
            val normalStop = (ownedSession?.state?.value as? ScreenCaptureState.Stopped)?.reason == ScreenCaptureStopReason.Requested
            grant.takeIf {
                succeeded && startSucceeded && (stopReason == StopReason.User || stopReason == StopReason.Shutdown) && failure == null && normalStop
            }
        }
        // Core can call admission back; never enter it while holding the attempt gate.
        if (reusableGrant != null) {
            try {
                captureAccess.saveConsentForReuse(reusableGrant)
            } catch (cause: Throwable) {
                this@MjpegCaptureAttempt.logE("Cleanup.consentReuse", "Consent reuse failed, attempt=$id", cause)
                succeeded = false
            }
        }
        synchronized(gate) {
            grant = null
            projection = null
            session = null
            unregister = null
            cleanupResult = succeeded
        }
        succeeded
    }

    /** Translate the complete image request at the capture boundary. */
    private fun ImageSettings.captureParameters(): ScreenCaptureParameters = ScreenCaptureParameters(
        sourceRegion = sourceRegion,
        crop = if (cropEnabled) cropInsets else CropInsetsPx.ZERO,
        outputSize = when (outputSelection) {
            ImageSettings.OutputSelection.Scale -> OutputSize.ScaleFactor(scalePercent / 100.0)
            ImageSettings.OutputSelection.TargetSize -> checkNotNull(targetSize)
        },
        rotation = rotation,
        mirror = mirror,
        colorMode = colorMode,
        frameRate = frameRate,
        jpegQuality = jpegQuality,
    )

    private data class FailureDiagnostic(val stage: String, val problem: ScreenCaptureProblem?, val cause: Throwable?, val context: String?)
    private data class StopWork(
        val session: ScreenCaptureSession?,
        val publishUnavailable: (() -> Unit)?,
        val diagnostic: FailureDiagnostic? = null,
    )
}
