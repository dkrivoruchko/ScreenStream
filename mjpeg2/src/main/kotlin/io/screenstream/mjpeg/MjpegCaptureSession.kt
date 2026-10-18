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
import io.screenstream.mjpeg.MjpegCaptureSession.State.SuspensionProblem
import io.screenstream.mjpeg.MjpegEngineState.Phase
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One inert capture attempt; call [start] once before [requestStop] or [updateImageSettings].
 * Admit a replacement only after successful required capture cleanup. Cancellation requests shutdown;
 * entered cleanup continues in NonCancellable.
 *
 * [onJpeg] may ignore or synchronously copy borrowed bytes into caller-owned storage at offset zero.
 * The copier is valid only during that callback on its thread; never retain it. Callback failure ends capture.
 * [enterForeground] precedes projection creation after consent. [leaveForeground] follows media cleanup
 * and must tolerate rollback before promotion. Both signal failure by throwing.
 */
internal class MjpegCaptureSession(
    val id: StreamingModule.CaptureAttemptId,
    initialImageSettings: ImageSettings,
    private val context: Context,
    private val controllerScope: CoroutineScope,
    private val captureAccess: ScreenCaptureAccess,
    private val enterForeground: suspend () -> Unit,
    private val leaveForeground: () -> Unit,
    private val onJpeg: (byteCount: Int, copyTo: (ByteArray) -> Unit) -> Unit,
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

    interface State {
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

        /** Whether capture started successfully and has not accepted Stop or failed. Suspended capture still counts. */
        val isStreaming: Boolean

        /**
         * A Stop cause has been accepted, including failure or natural/system termination. Remains true
         * after cleanup; this is not the availability of the UI Stop action.
         */
        val isStopRequested: Boolean

        /** Recoverable reason shown only while [status] is [Status.Suspended]. */
        val suspensionProblem: SuspensionProblem?

        /** First acquisition or capture failure. */
        val captureFailure: Failure?

        /** First resource-release failure. */
        val cleanupFailure: Failure?

        /**
         * Outcome of this adapter's cleanup operations, null until they finish; separate from capture success.
         * Engine stop confirms logical shutdown and projection stop, not all internal Engine resource cleanup.
         * Optional screen-wake-lock errors only log and do not affect this outcome.
         */
        val cleanupSucceeded: Boolean?
    }

    private data class StateSnapshot(
        val engineState: MjpegEngineState? = null,
        val startSucceeded: Boolean = false,
        val stopReason: StopReason? = null,
        override val captureFailure: Failure? = null,
        override val cleanupFailure: Failure? = null,
        override val cleanupSucceeded: Boolean? = null,
    ) : State {
        override val status: Status
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

        override val isStreaming: Boolean
            get() = startSucceeded && !isStopRequested

        override val isStopRequested: Boolean
            get() = stopReason != null

        override val suspensionProblem: SuspensionProblem?
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

        fun withStop(reason: StopReason): StateSnapshot = copy(stopReason = stopReason ?: reason)

        fun withCaptureFailure(stage: String, cause: Throwable): StateSnapshot = copy(
            captureFailure = captureFailure ?: Failure(stage, cause),
            stopReason = stopReason ?: StopReason.Error,
        )

        fun withCleanupFailure(stage: String, cause: Throwable): StateSnapshot = copy(
            cleanupFailure = cleanupFailure ?: Failure(stage, cause),
            stopReason = stopReason ?: StopReason.Error,
        )

        fun withEngineFailure(engineState: MjpegEngineState): StateSnapshot = copy(
            captureFailure = captureFailure ?: Failure(
                stage = "Engine.state",
                cause = IllegalStateException("Engine capture failed: ${engineState.problem}"),
            ),
            stopReason = stopReason ?: StopReason.Error,
        )

        fun withEngineState(engineState: MjpegEngineState): StateSnapshot {
            val snapshot = when (engineState.phase) {
                Phase.Failed -> withEngineFailure(engineState)
                Phase.Stopped -> withStop(StopReason.System)
                else -> this
            }
            return snapshot.copy(engineState = engineState)
        }

        fun withCleanupOutcome(succeeded: Boolean): StateSnapshot = copy(
            cleanupSucceeded = succeeded,
            stopReason = stopReason ?: if (succeeded) StopReason.System else StopReason.Error,
        )
    }

    private val desiredParameters = MutableStateFlow(initialImageSettings.captureParameters())
    private val jpegBackendPolicy: JpegBackendPolicy = initialImageSettings.jpegBackendPolicy
    private lateinit var captureJob: Job
    private val screenAwakeGate = Any()
    private var screenWakeLock: PowerManager.WakeLock? = null

    /**
     * Latest read-only snapshot; collection does not start or stop work. Intermediate values may be skipped,
     * while capture failure, cleanup failure and the cleanup outcome remain available.
     */
    val state: StateFlow<State>
        field = MutableStateFlow(StateSnapshot())

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
            state.update { snapshot ->
                if (snapshot.cleanupSucceeded != null) snapshot else snapshot.copy(
                    stopReason = snapshot.stopReason ?: if (failure == null) StopReason.System else StopReason.Error,
                    cleanupFailure = snapshot.cleanupFailure ?: failure,
                    cleanupSucceeded = failure == null,
                )
            }
        }
    }

    /** Retain the first Stop cause and request cancellation without waiting for JPEG callbacks or cleanup. */
    fun requestStop(reason: StopReason) {
        synchronized(screenAwakeGate) {
            state.update { it.withStop(reason) }
            releaseScreenAwakeLocked()
        }
        captureJob.cancel()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout") // The capture lifetime owns this lock; terminal paths release it directly.
    fun updateScreenAwake(enabled: Boolean) {
        synchronized(screenAwakeGate) {
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
            consent = captureAccess.requestConsent(id)
            if (consent == null) {
                state.update { it.withStop(StopReason.User) }
                return
            }

            failureStage = "Startup.foreground"
            enterForeground()

            failureStage = "Startup.projection"
            projectionBeforeHandoff = captureAccess.createProjection(consent)
            failureStage = "Engine.creation"
            engineSession = engineFactory.create(
                context = context,
                mediaProjection = projectionBeforeHandoff,
                config = ScreenCaptureConfig(jpegBackendPolicy = jpegBackendPolicy),
            )
            // The Engine now owns the projection; cleanup must stop the session instead.
            projectionBeforeHandoff = null
            failureStage = "Startup.projectionHandoff"
            captureAccess.finishProjectionCreation(id)

            failureStage = "Engine.registration"
            val captureContext = currentCoroutineContext()
            frameRegistration = engineSession.registerFrameConsumer { frame ->
                runCatching {
                    val byteCount = frame.byteCount
                    onJpeg(byteCount) { bytes -> check(frame.copyTo(bytes) == byteCount) { "Incomplete JPEG copy" } }
                }.onFailure { cause ->
                    synchronized(screenAwakeGate) {
                        state.update { it.withCaptureFailure("Output.frame", cause) }
                        releaseScreenAwakeLocked()
                    }
                    captureContext.cancel()
                }
            }

            failureStage = "Engine.start"
            runEngineSession(engineSession)
        } catch (_: CancellationException) {
            val engineStateAtCancellation = engineSession?.currentState
            if (engineStateAtCancellation?.phase == Phase.Failed) {
                state.update { it.withEngineFailure(engineStateAtCancellation) }
            }
        } catch (cause: Throwable) {
            state.update { it.withCaptureFailure(failureStage, cause) }
        } finally {
            synchronized(screenAwakeGate) {
                state.update { it.withStop(StopReason.System) }
                releaseScreenAwakeLocked()
            }
            releaseResources(consent, projectionBeforeHandoff, engineSession, frameRegistration)
        }
    }

    private suspend fun runEngineSession(engineSession: MjpegEngineSession): Nothing = coroutineScope {
        val initialParameters = desiredParameters.value
        // Parameter submission must not backpressure terminal-state handling.
        val observedStates = engineSession.states.onEach { engineState ->
            this@MjpegCaptureSession.logV("Engine.state", "state=${engineState.phase.name}")
            val isTerminal = engineState.phase == Phase.Failed || engineState.phase == Phase.Stopped
            synchronized(screenAwakeGate) {
                state.update { it.withEngineState(engineState) }
                if (isTerminal) {
                    releaseScreenAwakeLocked()
                }
            }
            if (isTerminal) {
                this@coroutineScope.cancel()
            }
        }.catch { cause ->
            synchronized(screenAwakeGate) {
                state.update { it.withCaptureFailure("Engine.stateObservation", cause) }
                releaseScreenAwakeLocked()
            }
            this@coroutineScope.cancel()
        }.conflate()

        var submittedParameters = initialParameters
        combine(desiredParameters, observedStates) { parameters, engineState -> parameters to engineState }.onEach { (parameters, engineState) ->
            val isRunning = engineState.phase == Phase.Active || engineState.phase == Phase.Reconfiguring || engineState.phase == Phase.Suspended
            if (isRunning && !state.value.isStopRequested && parameters != submittedParameters) {
                try {
                    engineSession.updateParameters(parameters)
                    submittedParameters = parameters
                } catch (_: IllegalStateException) {
                    this@MjpegCaptureSession.logD("Engine.imageUpdate", "Parameter update admission closed")
                }
            }
        }.launchIn(this)

        engineSession.stats.onEach { stats ->
            this@MjpegCaptureSession.logV("Engine.stats", "frames=${stats.producedFrameCount}, fps=${stats.averageProducedFps}")
        }.catch { cause ->
            this@MjpegCaptureSession.logE("Engine.statsObservation", "Statistics observation failed", cause)
        }.launchIn(this)

        engineSession.start(initialParameters)
        state.update { it.copy(startSucceeded = true) }
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
                val consumerCleanup = async {
                    runCatching { frameRegistration?.unregister() }
                        .onFailure { cause ->
                            state.update { it.withCleanupFailure("Cleanup.consumer", cause) }
                        }
                        .isSuccess
                }
                val mediaReleased = runCatching { if (engineSession != null) engineSession.stop() else projectionBeforeHandoff?.stop() }
                    .onFailure { cause ->
                        state.update { it.withCleanupFailure("Cleanup.media", cause) }
                    }.isSuccess
                engineSession?.currentState?.let { engineState ->
                    state.update { it.withEngineState(engineState) }
                }

                val foregroundReleased = (engineSession == null || mediaReleased) && runCatching(leaveForeground)
                    .onFailure { cause ->
                        state.update { it.withCleanupFailure("Cleanup.foreground", cause) }
                    }
                    .isSuccess
                val projectionGuardReleased = runCatching { captureAccess.finishProjectionCreation(id) }
                    .onFailure { cause ->
                        state.update { it.withCleanupFailure("Cleanup.projectionCreation", cause) }
                    }
                    .isSuccess
                val consumerReleased = consumerCleanup.await()
                cleanupSucceeded = mediaReleased && foregroundReleased && projectionGuardReleased && consumerReleased

                val snapshot = state.value
                val reuseConsent = cleanupSucceeded && snapshot.startSucceeded && snapshot.captureFailure == null &&
                        (snapshot.stopReason == StopReason.User || snapshot.stopReason == StopReason.Shutdown) &&
                        snapshot.engineState?.phase == Phase.Stopped && snapshot.engineState.stopReason == ScreenCaptureStopReason.Requested
                if (reuseConsent && consent != null) {
                    cleanupSucceeded = runCatching { captureAccess.saveConsentForReuse(consent) }
                        .onFailure { cause ->
                            state.update { it.withCleanupFailure("Cleanup.consentReuse", cause) }
                        }
                        .isSuccess
                }
            }
        } catch (cause: Throwable) {
            cleanupSucceeded = false
            state.update { it.withCleanupFailure("Cleanup.capture", cause) }
        } finally {
            state.update { it.withCleanupOutcome(cleanupSucceeded) }
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
