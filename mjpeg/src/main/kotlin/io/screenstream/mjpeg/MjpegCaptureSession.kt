package io.screenstream.mjpeg

import android.annotation.SuppressLint
import android.content.Context
import android.media.projection.MediaProjection
import android.os.PowerManager
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.OutputSize
import io.screenstream.capture.ScreenCaptureConfig
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.ScreenCaptureStopReason
import io.screenstream.mjpeg.MjpegCaptureSession.State.Failure
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.MjpegEngineState.Phase
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.streaming.capture.ScreenCaptureAccess
import io.screenstream.streaming.logD
import io.screenstream.streaming.logE
import io.screenstream.streaming.logV
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One inert capture attempt; call [start] once before [requestStop] or [updateImageSettings].
 * Admit a replacement only after successful required capture cleanup. Cancellation requests shutdown;
 * entered cleanup continues in NonCancellable.
 *
 * This session owns capture phase and generation; [http] applies them without entering this session.
 * Register the fresh [initialCaptureGeneration] with HTTP before [start]. JPEG offers carry the generation
 * captured under the state gate, then synchronously copy borrowed bytes outside the gates. The copier is
 * valid only during that callback on its thread; never retain it. Callback failure ends capture.
 * [enterForeground] precedes projection creation after consent. [leaveForeground] follows media cleanup
 * and must tolerate rollback before promotion. Both signal failure by throwing.
 */
internal class MjpegCaptureSession(
    val id: StreamingModule.CaptureAttemptId,
    initialImageSettings: ImageSettings,
    initialCaptureGeneration: Any,
    private val context: Context,
    private val controllerScope: CoroutineScope,
    private val captureAccess: ScreenCaptureAccess,
    private val http: HttpDelivery,
    private val enterForeground: suspend () -> Unit,
    private val leaveForeground: suspend () -> Unit,
    private val engineFactory: MjpegEngineFactory = DefaultMjpegEngineFactory,
) {
    /** First accepted Stop cause; later failures do not replace it. */
    enum class StopReason {
        /** The user requested Stop, or consent acquisition returned no grant. */
        User,

        System,

        Error,

        Shutdown,
    }

    data class State(
        /** Identity of the current capture generation; null while JPEG publication is revoked. */
        internal val captureGeneration: Any?,
        internal val engineState: MjpegEngineState? = null,
        internal val startSucceeded: Boolean = false,
        internal val stopReason: StopReason? = null,
        /** First acquisition or capture failure. */
        val captureFailure: Failure? = null,
        /** First resource-release failure. */
        val cleanupFailure: Failure? = null,
        /**
         * Outcome of this adapter's cleanup operations, null until they finish; separate from capture success.
         * Engine stop confirms logical shutdown and projection stop, not all internal Engine resource cleanup.
         * Optional screen-wake-lock errors only log and do not affect this outcome.
         */
        val cleanupSucceeded: Boolean? = null,
    ) {
        /** Capture availability for controls and the viewer page; [State.isStreaming] reports the capture lifetime. */
        enum class Status {
            Stopped,

            /** Preparing screen capture access; a permission prompt may be shown. */
            WaitingForPermission,

            /** Preparing capture; startup does not wait for the first JPEG. */
            Starting,

            /** Ready to produce JPEGs; [State.isStreaming] reports confirmed startup. */
            Active,

            Reconfiguring,

            Suspended,

            /** Stop was accepted; adapter cleanup is pending. */
            Stopping,

            /** Capture or cleanup failed; cleanup may still be pending. */
            Failed,
        }

        /** Recoverable problems that pause JPEG production. */
        enum class SuspensionProblem {
            /** Current geometry and parameters cannot produce the requested result. */
            InvalidRequest,

            /** The capture source or its required metrics are unavailable. */
            CaptureUnavailable,

            /** Required capacity or resource creation denied the request. */
            ResourceExhausted,
        }

        /**
         * Diagnostic capture/cleanup cause; retained separately so one failure cannot hide the other.
         */
        data class Failure(val stage: String, val cause: Throwable)

        /** Retained failure outranks Engine state and successful cleanup, keeping Failed until a new attempt. */
        val status: Status
            get() {
                val engineStatus = when (engineState?.phase) {
                    null -> Status.WaitingForPermission
                    Phase.NotStarted, Phase.Starting -> Status.Starting
                    Phase.Active -> Status.Active
                    Phase.Reconfiguring -> Status.Reconfiguring
                    Phase.Suspended -> Status.Suspended
                    Phase.Stopped -> Status.Stopping
                    Phase.Failed -> Status.Failed
                }
                return when {
                    captureFailure != null || cleanupFailure != null || cleanupSucceeded == false || engineStatus == Status.Failed -> Status.Failed
                    cleanupSucceeded != null -> Status.Stopped
                    stopReason != null -> Status.Stopping
                    else -> engineStatus
                }
            }

        /** Whether capture started successfully and has not accepted Stop or failed. Suspended capture still counts. */
        val isStreaming: Boolean
            get() = startSucceeded && !isStopRequested

        /**
         * A Stop cause has been accepted, including failure or natural/system termination. Remains true
         * after cleanup; this is not the availability of the UI Stop action.
         */
        val isStopRequested: Boolean
            get() = stopReason != null

        /** Recoverable reason shown only while [status] is [Status.Suspended]. */
        val suspensionProblem: SuspensionProblem?
            get() = when {
                engineState?.phase == Phase.Suspended && status == Status.Suspended -> when (engineState.problem) {
                    ScreenCaptureProblem.InvalidRequest -> SuspensionProblem.InvalidRequest
                    ScreenCaptureProblem.CaptureUnavailable -> SuspensionProblem.CaptureUnavailable
                    ScreenCaptureProblem.ResourceExhausted -> SuspensionProblem.ResourceExhausted
                    ScreenCaptureProblem.InternalFailure, ScreenCaptureProblem.UnsupportedColorSpace, null ->
                        error("Unexpected suspension problem: ${engineState.problem}")
                }

                else -> null
            }
    }

    private val desiredParameters = MutableStateFlow(initialImageSettings.captureParameters())
    private val jpegBackendPolicy: JpegBackendPolicy = initialImageSettings.jpegBackendPolicy
    private lateinit var captureJob: Job

    // Lock order: session state gate → HTTP control monitor → JPEG store monitor. Copy outside all three.
    private val stateGate = Any()
    private var screenWakeLock: PowerManager.WakeLock? = null

    /**
     * Latest read-only snapshot; collection does not start or stop work. Intermediate values may be skipped,
     * while capture failure, cleanup failure and the cleanup outcome remain available.
     */
    val state: StateFlow<State>
        field = MutableStateFlow(State(captureGeneration = initialCaptureGeneration))

    /** Launch capture once with the initial JPEG backend; return does not confirm consent, startup or a JPEG. */
    fun start() {
        check(!::captureJob.isInitialized) { "Capture has already been started" }
        captureJob = controllerScope.launch { capture() }
        captureJob.invokeOnCompletion { cause ->
            if (state.value.cleanupSucceeded != null) return@invokeOnCompletion
            // Entered capture publishes its own cleanup; cancellation before entry acquired no resources.
            val failure = if (cause is CancellationException) {
                null
            } else {
                Failure(
                    stage = "Cleanup.capture",
                    cause = cause ?: IllegalStateException("Capture completed without a cleanup outcome"),
                )
            }
            synchronized(stateGate) {
                val snapshot = state.value
                if (snapshot.cleanupSucceeded == null) {
                    commitCaptureStateLocked(
                        snapshot.copy(
                            stopReason = snapshot.stopReason ?: if (failure == null) StopReason.System else StopReason.Error,
                            cleanupFailure = snapshot.cleanupFailure ?: failure,
                            cleanupSucceeded = failure == null,
                        )
                    )
                }
            }
        }
    }

    /** Retain the first Stop cause and request cancellation without waiting for JPEG callbacks or cleanup. */
    fun requestStop(reason: StopReason) {
        acceptStop(reason)
        captureJob.cancel()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout") // The capture lifetime owns this lock; terminal paths release it directly.
    fun updateScreenAwake(enabled: Boolean) {
        synchronized(stateGate) {
            if (enabled && state.value.isStreaming) {
                try {
                    val wakeLock = screenWakeLock ?: checkNotNull(context.getSystemService(PowerManager::class.java))
                        .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "ScreenStream:MJPEG:KeepScreenAwake")
                        .also {
                            screenWakeLock = it
                            it.setReferenceCounted(false)
                        }
                    if (!wakeLock.isHeld) wakeLock.acquire()
                } catch (failure: Exception) {
                    logE("ScreenAwake.acquire", "Could not keep the screen awake", failure)
                }
            } else {
                releaseScreenAwakeLocked()
            }
        }
    }

    private fun releaseScreenAwakeLocked() {
        try {
            screenWakeLock?.let { if (it.isHeld) it.release() }
        } catch (failure: Exception) {
            logE("ScreenAwake.release", "Could not release the screen wake lock", failure)
        }
    }

    private fun acceptStop(reason: StopReason) {
        synchronized(stateGate) {
            val snapshot = state.value
            commitCaptureStateLocked(snapshot.copy(stopReason = snapshot.stopReason ?: reason))
        }
    }

    private fun failCapture(stage: String, cause: Throwable) {
        synchronized(stateGate) {
            val snapshot = state.value
            commitCaptureStateLocked(
                snapshot.copy(
                    captureFailure = snapshot.captureFailure ?: Failure(stage, cause),
                    stopReason = snapshot.stopReason ?: StopReason.Error,
                )
            )
        }
    }

    private fun failCleanup(stage: String, cause: Throwable) {
        synchronized(stateGate) {
            val snapshot = state.value
            commitCaptureStateLocked(
                snapshot.copy(
                    cleanupFailure = snapshot.cleanupFailure ?: Failure(stage, cause),
                    stopReason = snapshot.stopReason ?: StopReason.Error,
                )
            )
        }
    }

    private fun completeCleanup(succeeded: Boolean) {
        synchronized(stateGate) {
            val snapshot = state.value
            commitCaptureStateLocked(
                snapshot.copy(
                    cleanupSucceeded = succeeded,
                    stopReason = snapshot.stopReason ?: if (succeeded) StopReason.System else StopReason.Error,
                )
            )
        }
    }

    /** Read fresh facts under the gate; a delayed Flow payload cannot overwrite a newer frame observation. */
    private fun refreshEngineStateLocked(engineSession: MjpegEngineSession): MjpegEngineState {
        val engineState = engineSession.currentState
        val snapshot = state.value
        val stopReason = when (engineState.phase) {
            Phase.Failed -> StopReason.Error
            Phase.Stopped -> StopReason.System
            else -> null
        }
        commitCaptureStateLocked(
            snapshot.copy(
                engineState = engineState,
                captureFailure = snapshot.captureFailure ?: if (engineState.phase == Phase.Failed) engineFailure(engineState) else null,
                stopReason = snapshot.stopReason ?: stopReason,
            )
        )
        return engineState
    }

    private fun engineFailure(engineState: MjpegEngineState): Failure = Failure(
        stage = "Engine.state",
        cause = IllegalStateException("Engine capture failed: ${engineState.problem}"),
    )

    /** Commit the effective phase and generation together; HTTP only applies this session's decision. */
    private fun commitCaptureStateLocked(candidate: State) {
        val previous = state.value
        val captureGeneration = when (candidate.status) {
            Status.Active -> previous.captureGeneration ?: Any()
            Status.WaitingForPermission, Status.Starting, Status.Reconfiguring -> previous.captureGeneration
            Status.Suspended, Status.Failed, Status.Stopping, Status.Stopped -> null
        }
        val next = candidate.copy(captureGeneration = captureGeneration)
        state.value = next
        if (next.isStopRequested) releaseScreenAwakeLocked()
        if (previous.status != next.status || previous.captureGeneration !== captureGeneration) {
            http.updateCaptureStatus(id, next.status, captureGeneration)
        }
    }

    /** Retain the latest dynamic settings through consent/startup for Engine application; ignore them after Stop. */
    fun updateImageSettings(imageSettings: ImageSettings) {
        if (!state.value.isStopRequested) desiredParameters.value = imageSettings.captureParameters()
    }

    /**
     * Wait without starting or stopping capture. True confirms successful required capture cleanup, including exact
     * JPEG consumer completion, even if capture failed. Cancelling the waiter does not cancel capture or its cleanup.
     */
    suspend fun awaitCleanup(): Boolean = checkNotNull(state.first { it.cleanupSucceeded != null }.cleanupSucceeded)

    private suspend fun capture() {
        var consent: ScreenCaptureAccess.Grant? = null
        var projectionBeforeHandoff: MediaProjection? = null
        var engineSession: MjpegEngineSession? = null
        var frameRegistration: MjpegFrameRegistration? = null
        var failureStage = "Startup.consent"
        try {
            synchronized(stateGate) {
                val snapshot = state.value
                if (!snapshot.isStopRequested) {
                    http.updateCaptureStatus(id, snapshot.status, snapshot.captureGeneration)
                }
            }
            consent = captureAccess.requestConsent(id)
            if (consent == null) {
                acceptStop(StopReason.User)
                return
            }

            failureStage = "Startup.foreground"
            enterForeground()

            failureStage = "Startup.projection"
            projectionBeforeHandoff = captureAccess.createProjection(consent)
            failureStage = "Engine.creation"
            val createdEngineSession = engineFactory.create(
                context = context,
                mediaProjection = projectionBeforeHandoff,
                config = ScreenCaptureConfig(jpegBackendPolicy = jpegBackendPolicy),
            )
            engineSession = createdEngineSession
            // The Engine now owns the projection; cleanup must stop the session instead.
            projectionBeforeHandoff = null
            failureStage = "Startup.projectionHandoff"
            captureAccess.finishProjectionCreation(id)

            failureStage = "Engine.registration"
            val captureContext = currentCoroutineContext()
            frameRegistration = createdEngineSession.registerFrameConsumer { frame ->
                runCatching {
                    val captureGeneration = synchronized(stateGate) {
                        refreshEngineStateLocked(createdEngineSession)
                        state.value.captureGeneration
                    }
                    if (captureGeneration != null) {
                        val byteCount = frame.byteCount
                        http.offerJpeg(captureGeneration, byteCount) { bytes ->
                            check(frame.copyTo(bytes) == byteCount) { "Incomplete JPEG copy" }
                        }
                    }
                }.onFailure { cause ->
                    failCapture("Output.frame", cause)
                }
                if (state.value.isStopRequested) captureContext.cancel()
            }

            failureStage = "Engine.start"
            runEngineSession(createdEngineSession)
        } catch (_: CancellationException) {
            synchronized(stateGate) {
                val engineStateAtCancellation = engineSession?.currentState
                if (engineStateAtCancellation?.phase == Phase.Failed) {
                    val snapshot = state.value
                    commitCaptureStateLocked(
                        snapshot.copy(
                            captureFailure = snapshot.captureFailure ?: engineFailure(engineStateAtCancellation),
                            stopReason = snapshot.stopReason ?: StopReason.Error,
                        )
                    )
                }
            }
        } catch (cause: Throwable) {
            failCapture(failureStage, cause)
        } finally {
            acceptStop(StopReason.System)
            releaseResources(consent, projectionBeforeHandoff, engineSession, frameRegistration)
        }
    }

    private suspend fun runEngineSession(engineSession: MjpegEngineSession): Nothing = coroutineScope {
        val initialParameters = desiredParameters.value
        // Parameter submission must not backpressure terminal-state handling.
        engineSession.states.onEach {
            val engineState = synchronized(stateGate) { refreshEngineStateLocked(engineSession) }
            this@MjpegCaptureSession.logV("Engine.state", "state=${engineState.phase.name}")
            val isTerminal = engineState.phase == Phase.Failed || engineState.phase == Phase.Stopped
            if (isTerminal) {
                this@coroutineScope.cancel()
            }
        }.catch { cause ->
            failCapture("Engine.stateObservation", cause)
            this@coroutineScope.cancel()
        }.launchIn(this)

        var submittedParameters = initialParameters
        combine(desiredParameters, state) { parameters, captureState ->
            val canSubmitParameters = when (captureState.status) {
                Status.Active, Status.Reconfiguring, Status.Suspended -> true
                else -> false
            }
            if (!canSubmitParameters || state.value.isStopRequested || parameters == submittedParameters) return@combine
            try {
                engineSession.updateParameters(parameters)
                submittedParameters = parameters
            } catch (_: IllegalStateException) {
                this@MjpegCaptureSession.logD("Engine.imageUpdate", "Parameter update admission closed")
            }
        }.launchIn(this)

        engineSession.stats.onEach { stats ->
            this@MjpegCaptureSession.logV("Engine.stats", "frames=${stats.producedFrameCount}, fps=${stats.averageProducedFps}")
        }.catch { cause ->
            this@MjpegCaptureSession.logE("Engine.statsObservation", "Statistics observation failed", cause)
        }.launchIn(this)

        engineSession.start(initialParameters)
        synchronized(stateGate) { commitCaptureStateLocked(state.value.copy(startSucceeded = true)) }
        awaitCancellation()
    }

    /** Stop media and unregister the JPEG consumer independently; foreground release follows the media result. */
    private suspend fun releaseResources(
        consent: ScreenCaptureAccess.Grant?,
        projectionBeforeHandoff: MediaProjection?,
        engineSession: MjpegEngineSession?,
        frameRegistration: MjpegFrameRegistration?,
    ): Unit = withContext(NonCancellable) {
        var cleanupSucceeded = false
        try {
            coroutineScope {
                val consumerCleanup = async<Unit> {
                    runCatching { frameRegistration?.unregister() }
                        .onFailure { cause ->
                            failCleanup("Cleanup.consumer", cause)
                        }
                }
                val mediaReleased = runCatching { if (engineSession != null) engineSession.stop() else projectionBeforeHandoff?.stop() }
                    .onFailure { cause ->
                        failCleanup("Cleanup.media", cause)
                    }.isSuccess
                if (engineSession != null) {
                    synchronized(stateGate) { refreshEngineStateLocked(engineSession) }
                }

                runCatching { captureAccess.finishProjectionCreation(id) }
                    .onFailure { cause ->
                        failCleanup("Cleanup.projectionCreation", cause)
                    }
                consumerCleanup.await()

                val snapshot = state.value
                val reuseConsent = snapshot.cleanupFailure == null && snapshot.startSucceeded && snapshot.captureFailure == null &&
                        (snapshot.stopReason == StopReason.User || snapshot.stopReason == StopReason.Shutdown) &&
                        snapshot.engineState?.phase == Phase.Stopped && snapshot.engineState.stopReason == ScreenCaptureStopReason.Requested
                if (reuseConsent && consent != null) {
                    runCatching { captureAccess.saveConsentForReuse(consent) }
                        .onFailure { cause ->
                            failCleanup("Cleanup.consentReuse", cause)
                        }
                }
                if (mediaReleased && state.value.cleanupFailure == null) {
                    runCatching { leaveForeground() }
                        .onFailure { cause ->
                            failCleanup("Cleanup.foreground", cause)
                        }
                }
            }
            cleanupSucceeded = state.value.cleanupFailure == null
        } catch (cause: Throwable) {
            failCleanup("Cleanup.capture", cause)
        } finally {
            completeCleanup(cleanupSucceeded)
        }
    }

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
}
