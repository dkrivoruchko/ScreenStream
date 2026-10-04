package io.screenstream.mjpeg

import android.content.Context
import android.media.projection.MediaProjection
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.FrameConsumerRegistration
import io.screenstream.capture.JpegBackendPolicy
import io.screenstream.capture.OutputSize
import io.screenstream.capture.ScreenCaptureConfig
import io.screenstream.capture.ScreenCaptureEngine
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.ScreenCaptureSession
import io.screenstream.capture.ScreenCaptureState
import io.screenstream.capture.ScreenCaptureStopReason
import io.screenstream.mjpeg.MjpegCaptureSession.State.Failure
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.MjpegCaptureSession.State.SuspensionProblem
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One inert capture attempt; call [runCapture] once before [stop] or [updateImageSettings].
 * Admit a replacement only after successful adapter cleanup. Cancellation requests shutdown;
 * entered cleanup continues in NonCancellable.
 *
 * [onJpeg] may ignore or synchronously copy borrowed bytes into caller-owned storage at offset zero.
 * The copier is valid only during that callback on its thread; never retain it. Callback failure ends capture.
 * [enterForeground] precedes projection creation after consent. [leaveForeground] follows media cleanup
 * and must tolerate rollback before promotion. Both signal failure by throwing.
 */
internal class MjpegCaptureSession(
    val id: StreamingModule.CaptureAttemptId,
    private val context: Context,
    private val controllerScope: CoroutineScope,
    private val captureAccess: ScreenCaptureAccess,
    private val enterForeground: suspend () -> Unit,
    private val leaveForeground: () -> Unit,
    private val onJpeg: (byteCount: Int, copyTo: (ByteArray) -> Unit) -> Unit,
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

        /** Recoverable reason shown only while [status] is [Status.Suspended]. */
        val suspensionProblem: SuspensionProblem?

        /** First acquisition or capture failure. */
        val captureFailure: Failure?

        /** First resource-release failure. */
        val cleanupFailure: Failure?

        /**
         * Outcome of this adapter's cleanup operations, null until they finish; separate from capture success.
         * Engine stop confirms logical shutdown and projection stop, not all internal Engine resource cleanup.
         */
        val cleanupSucceeded: Boolean?
    }

    private data class StateSnapshot(
        val engine: ScreenCaptureState? = null,
        val startSucceeded: Boolean = false,
        val stopReason: StopReason? = null,
        override val captureFailure: Failure? = null,
        override val cleanupFailure: Failure? = null,
        override val cleanupSucceeded: Boolean? = null,
    ) : State {
        override val status: Status
            get() = when {
                captureFailure != null || cleanupFailure != null || cleanupSucceeded == false || engine is ScreenCaptureState.Failed -> Status.Failed
                cleanupSucceeded != null -> Status.Stopped
                stopReason != null -> Status.Stopping
                else -> when (engine) {
                    null -> Status.WaitingForPermission
                    ScreenCaptureState.NotStarted, ScreenCaptureState.Starting -> Status.Starting
                    is ScreenCaptureState.Active -> Status.Active
                    is ScreenCaptureState.Reconfiguring -> Status.Reconfiguring
                    is ScreenCaptureState.Suspended -> Status.Suspended
                    is ScreenCaptureState.Stopped -> Status.Stopping
                }
            }

        override val isStreaming: Boolean
            get() = startSucceeded && stopReason == null && captureFailure == null && cleanupFailure == null && cleanupSucceeded == null &&
                    engine !is ScreenCaptureState.Stopped && engine !is ScreenCaptureState.Failed

        override val suspensionProblem: SuspensionProblem?
            get() = when (val current = engine) {
                is ScreenCaptureState.Suspended if (status == Status.Suspended) -> when (current.problem) {
                    ScreenCaptureProblem.InvalidRequest -> SuspensionProblem.InvalidRequest
                    ScreenCaptureProblem.CaptureUnavailable -> SuspensionProblem.CaptureUnavailable
                    ScreenCaptureProblem.ResourceExhausted -> SuspensionProblem.ResourceExhausted
                    ScreenCaptureProblem.InternalFailure, ScreenCaptureProblem.UnsupportedColorSpace ->
                        error("Unexpected suspension problem: ${current.problem}")
                }

                else -> null
            }
    }

    private lateinit var desiredParameters: MutableStateFlow<ScreenCaptureParameters>
    private lateinit var captureJob: Job

    /**
     * Latest read-only snapshot; collection does not start or stop work. Intermediate values may be skipped,
     * while capture failure, cleanup failure and the cleanup outcome remain available.
     */
    val state: StateFlow<State>
        field = MutableStateFlow(StateSnapshot())

    /** Launch capture once, fixing the JPEG backend; return does not confirm consent, startup or a JPEG. */
    fun runCapture(initialImageSettings: ImageSettings) {
        check(!::captureJob.isInitialized) { "Capture has already been started" }
        desiredParameters = MutableStateFlow(initialImageSettings.captureParameters())
        captureJob = controllerScope.launch { capture(initialImageSettings.jpegBackendPolicy) }
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
    fun stop(reason: StopReason) {
        state.update { it.copy(stopReason = it.stopReason ?: reason) }
        captureJob.cancel()
    }

    /** Retain the latest dynamic settings through consent/startup for Engine application; ignore them after Stop. */
    fun updateImageSettings(imageSettings: ImageSettings) {
        if (state.value.stopReason == null) desiredParameters.value = imageSettings.captureParameters()
    }

    /**
     * Wait without starting or stopping capture. True confirms successful adapter cleanup, including exact JPEG
     * consumer completion, even if capture failed. Cancelling the waiter does not cancel capture or its cleanup.
     */
    suspend fun awaitCleanup(): Boolean = checkNotNull(state.first { it.cleanupSucceeded != null }.cleanupSucceeded)

    private suspend fun capture(jpegBackendPolicy: JpegBackendPolicy) {
        var consent: ScreenCaptureAccess.Grant? = null
        var unownedProjection: MediaProjection? = null
        var engineSession: ScreenCaptureSession? = null
        var frameRegistration: FrameConsumerRegistration? = null
        var failureStage = "Startup.consent"
        try {
            consent = captureAccess.requestConsent(id)
            if (consent == null) {
                state.update { it.copy(stopReason = it.stopReason ?: StopReason.User) }
                return
            }

            failureStage = "Startup.foreground"
            enterForeground()

            failureStage = "Startup.projection"
            unownedProjection = captureAccess.createProjection(consent)
            failureStage = "Engine.creation"
            engineSession = ScreenCaptureEngine.createSession(
                context = context,
                mediaProjection = unownedProjection,
                config = ScreenCaptureConfig(jpegBackendPolicy = jpegBackendPolicy),
            )
            // The Engine now owns the projection; cleanup must stop the session instead.
            unownedProjection = null
            failureStage = "Startup.projectionHandoff"
            captureAccess.finishProjectionCreation(id)

            failureStage = "Engine.registration"
            val captureContext = currentCoroutineContext()
            frameRegistration = engineSession.registerFrameConsumer { frame ->
                runCatching {
                    val byteCount = frame.byteCount
                    onJpeg(byteCount) { bytes -> check(frame.copyTo(bytes) == byteCount) { "Incomplete JPEG copy" } }
                }.onFailure { cause ->
                    state.update { snapshot ->
                        snapshot.copy(
                            captureFailure = snapshot.captureFailure ?: Failure("Output.frame", cause),
                            stopReason = snapshot.stopReason ?: StopReason.Error,
                        )
                    }
                    captureContext.cancel()
                }
            }

            failureStage = "Engine.start"
            runEngineSession(engineSession)
        } catch (_: CancellationException) {
            val failed = engineSession?.state?.value as? ScreenCaptureState.Failed
            if (failed != null) {
                state.update { snapshot ->
                    snapshot.copy(
                        captureFailure = snapshot.captureFailure ?: Failure(
                            stage = "Engine.state",
                            cause = IllegalStateException("Engine capture failed: ${failed.problem}"),
                        ),
                        stopReason = snapshot.stopReason ?: StopReason.Error,
                    )
                }
            }
        } catch (cause: Throwable) {
            state.update { snapshot ->
                snapshot.copy(
                    captureFailure = snapshot.captureFailure ?: Failure(failureStage, cause),
                    stopReason = snapshot.stopReason ?: StopReason.Error,
                )
            }
        } finally {
            state.update { it.copy(stopReason = it.stopReason ?: StopReason.System) }
            releaseResources(consent, unownedProjection, engineSession, frameRegistration)
        }
    }

    private suspend fun runEngineSession(engineSession: ScreenCaptureSession): Nothing = coroutineScope {
        val initialParameters = desiredParameters.value
        engineSession.state.onEach { engineState ->
            this@MjpegCaptureSession.logV("Engine.state", "state=${engineState.javaClass.simpleName}")
            state.update { snapshot ->
                when (engineState) {
                    is ScreenCaptureState.Failed -> snapshot.copy(
                        engine = engineState,
                        captureFailure = snapshot.captureFailure ?: Failure(
                            stage = "Engine.state",
                            cause = IllegalStateException("Engine capture failed: ${engineState.problem}"),
                        ),
                        stopReason = snapshot.stopReason ?: StopReason.Error,
                    )

                    is ScreenCaptureState.Stopped -> snapshot.copy(
                        engine = engineState,
                        stopReason = snapshot.stopReason ?: StopReason.System,
                    )

                    else -> snapshot.copy(engine = engineState)
                }
            }
            if (engineState is ScreenCaptureState.Failed || engineState is ScreenCaptureState.Stopped) {
                this@coroutineScope.cancel()
            }
        }.catch { cause ->
            state.update { snapshot ->
                snapshot.copy(
                    captureFailure = snapshot.captureFailure ?: Failure("Engine.stateObservation", cause),
                    stopReason = snapshot.stopReason ?: StopReason.Error,
                )
            }
            this@coroutineScope.cancel()
        }.launchIn(this)

        var submittedParameters = initialParameters
        combine(desiredParameters, engineSession.state) { parameters, engineState -> parameters to engineState }.onEach { (parameters, engineState) ->
            if (engineState is ScreenCaptureState.Running && state.value.stopReason == null && parameters != submittedParameters) {
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
        unownedProjection: MediaProjection?,
        engineSession: ScreenCaptureSession?,
        frameRegistration: FrameConsumerRegistration?,
    ): Unit = withContext(NonCancellable) {
        var cleanupSucceeded = false
        try {
            coroutineScope {
                val consumerCleanup = async {
                    runCatching { frameRegistration?.unregister() }
                        .onFailure { cause ->
                            state.update { snapshot -> snapshot.copy(cleanupFailure = snapshot.cleanupFailure ?: Failure("Cleanup.consumer", cause)) }
                        }
                        .isSuccess
                }
                val mediaReleased = runCatching { if (engineSession != null) engineSession.stop() else unownedProjection?.stop() }
                    .onFailure { cause ->
                        state.update { snapshot -> snapshot.copy(cleanupFailure = snapshot.cleanupFailure ?: Failure("Cleanup.media", cause)) }
                    }.isSuccess
                cleanupSucceeded = mediaReleased
                engineSession?.state?.value?.let { engineState ->
                    state.update { snapshot ->
                        if (engineState is ScreenCaptureState.Failed) {
                            snapshot.copy(
                                engine = engineState,
                                captureFailure = snapshot.captureFailure ?: Failure(
                                    stage = "Engine.state",
                                    cause = IllegalStateException("Engine capture failed: ${engineState.problem}"),
                                ),
                                stopReason = snapshot.stopReason ?: StopReason.Error,
                            )
                        } else {
                            snapshot.copy(engine = engineState)
                        }
                    }
                }

                if (engineSession == null || mediaReleased) {
                    cleanupSucceeded = runCatching(leaveForeground)
                        .onFailure { cause ->
                            state.update { snapshot -> snapshot.copy(cleanupFailure = snapshot.cleanupFailure ?: Failure("Cleanup.foreground", cause)) }
                        }
                        .isSuccess && cleanupSucceeded
                }
                cleanupSucceeded = runCatching { captureAccess.finishProjectionCreation(id) }
                    .onFailure { cause ->
                        state.update { snapshot -> snapshot.copy(cleanupFailure = snapshot.cleanupFailure ?: Failure("Cleanup.projectionCreation", cause)) }
                    }
                    .isSuccess && cleanupSucceeded
                cleanupSucceeded = consumerCleanup.await() && cleanupSucceeded

                val snapshot = state.value
                val reuseConsent = cleanupSucceeded && snapshot.startSucceeded && snapshot.captureFailure == null &&
                        (snapshot.stopReason == StopReason.User || snapshot.stopReason == StopReason.Shutdown) &&
                        (snapshot.engine as? ScreenCaptureState.Stopped)?.reason == ScreenCaptureStopReason.Requested
                if (reuseConsent && consent != null) {
                    cleanupSucceeded = runCatching { captureAccess.saveConsentForReuse(consent) }
                        .onFailure { cause ->
                            state.update { snapshot -> snapshot.copy(cleanupFailure = snapshot.cleanupFailure ?: Failure("Cleanup.consentReuse", cause)) }
                        }
                        .isSuccess
                }
            }
        } catch (cause: Throwable) {
            cleanupSucceeded = false
            state.update { snapshot -> snapshot.copy(cleanupFailure = snapshot.cleanupFailure ?: Failure("Cleanup.capture", cause)) }
        } finally {
            state.update { it.copy(cleanupSucceeded = cleanupSucceeded) }
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
