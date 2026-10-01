package io.screenstream.mjpeg.capture

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.notification.NotificationHelper
import io.screenstream.capture.CropInsetsPx
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one exact capture attempt independently of the controller loop and cleanup waiters.
 * Its producer stores every resource before suspension; cleanup joins that producer before stopping media and foreground.
 * Consent is data only; projection creation is finished after Engine handoff or joined raw-projection rollback.
 * No frame consumer is needed for this capture-only stage.
 */
internal class MjpegCaptureAttempt(
    val id: StreamingModule.CaptureAttemptId,
    private val service: MjpegStreamingModuleService,
    private val captureAccess: ScreenCaptureAccess,
    private val notifications: NotificationHelper,
    image: ImageSettings,
    private val isInstanceCurrent: () -> Boolean,
    private val changed: () -> Unit,
) {
    enum class StopReason { User, System, Error, Shutdown }

    /** Cleanup result remains null until the independent task fully completes. */
    data class Snapshot(
        val streaming: Boolean,
        val issue: CaptureIssue?,
        val cleanupResult: Boolean?,
    )

    private class Recovery(val beganAt: Long, val parameters: ScreenCaptureParameters) {
        var retried = false
    }

    private val gate = Any()
    private val workJob = SupervisorJob()
    private val workScope = CoroutineScope(workJob + Dispatchers.Default)
    private val initialParameters = image.parameters()
    private val config = ScreenCaptureConfig(jpegBackendPolicy = image.jpegBackendPolicy)
    private var sentParameters = initialParameters
    private var projection: MediaProjection? = null
    private var session: ScreenCaptureSession? = null
    private var startSucceeded = false
    private var stopReason: StopReason? = null
    private var issue: CaptureIssue? = null
    private var cleanupResult: Boolean? = null
    private var grant: ScreenCaptureAccess.Grant? = null
    private var recovery: Recovery? = null

    /** Installed inertly; the controller starts this only after storing the attempt. */
    private val producer = workScope.launch(start = CoroutineStart.LAZY) { produce() }

    /** Outlives producer cancellation, the Service, the loop and every caller awaiting cleanup. */
    private val cleanupTask: Deferred<Boolean> = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
        finishCleanup()
    }.also { it.invokeOnCompletion { changed() } }

    fun start() {
        producer.start()
    }

    fun snapshot(): Snapshot = synchronized(gate) {
        val latest = session?.state?.value ?: ScreenCaptureState.NotStarted
        Snapshot(
            stopReason == null && startSucceeded && latest !is ScreenCaptureState.Stopped && latest !is ScreenCaptureState.Failed,
            issue, cleanupResult.takeIf { cleanupTask.isCompleted })
    }

    /** Close exact-attempt admission immediately; late producer results remain owned by cleanup. */
    fun requestStop(reason: StopReason) {
        val ownedSession = synchronized(gate) {
            if (stopReason == null) stopReason = reason
            recovery = null
            session
        }
        workScope.cancel()
        try {
            ownedSession?.requestStop()
        } catch (cause: Throwable) {
            Log.e(TAG, "Capture requestStop failed", cause)
        } finally {
            cleanupTask.start()
            changed()
        }
    }

    /** Cancelling a caller cancels only its wait; false means an applicable cleanup API failed. */
    suspend fun awaitCleanup(): Boolean = cleanupTask.await()

    private fun isCurrent(): Boolean = isInstanceCurrent() && synchronized(gate) { stopReason == null }

    // ServiceCompat ignores the compile-time service-type constant below API 29.
    @SuppressLint("InlinedApi")
    private suspend fun produce() {
        try {
            val notification = withContext(Dispatchers.Main.immediate) {
                notifications.createForegroundNotification(service, id)
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            val ownedGrant = try {
                captureAccess.requestConsent(id, ::isCurrent).also { synchronized(gate) { grant = it } }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                fail(CaptureIssue.ConsentUnavailable, cause)
                return
            }
            if (ownedGrant == null) {
                requestStop(StopReason.User); return
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            when (val promotion = service.promoteForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, notification)) {
                StreamingModuleService.PromotionResult.ApiCompleted -> Unit
                is StreamingModuleService.PromotionResult.Failed -> {
                    fail(CaptureIssue.ForegroundUnavailable, promotion.cause); return
                }

                StreamingModuleService.PromotionResult.Rejected -> {
                    if (isCurrent()) fail(CaptureIssue.ForegroundUnavailable)
                    return
                }
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            val rawProjection = try {
                captureAccess.createProjection(ownedGrant).also { synchronized(gate) { projection = it } }
            } catch (cause: Throwable) {
                fail(CaptureIssue.ProjectionUnavailable, cause)
                return
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            val ownedSession = ScreenCaptureEngine.createSession(service, rawProjection, config).also {
                synchronized(gate) { session = it; projection = null }
            }
            captureAccess.finishProjectionCreation(id)
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            workScope.launch { observe(ownedSession) }
            workScope.launch {
                // The engine publishes changed statistics at approximately one-second intervals, not per frame.
                ownedSession.stats.collect { value -> logEngine("Engine.stats", value.toString()) }
            }
            workScope.launch(start = CoroutineStart.UNDISPATCHED) {
                ownedSession.diagnosticEvents.collect { event ->
                    logEngine(
                        "Engine.diagnostic",
                        "sequence=${event.sequence}, source=${event.source}, event=${event.eventName}, message=${event.message}",
                        event.cause
                    )
                }
            }
            ownedSession.start(initialParameters)
            synchronized(gate) { startSucceeded = true }
            changed()
        } catch (_: CancellationException) {
            if (isCurrent()) requestStop(StopReason.System)
        } catch (cause: Throwable) {
            fail(cause.captureIssue(), cause)
        } finally {
            if (!isCurrent()) requestStop(synchronized(gate) { stopReason } ?: StopReason.Shutdown)
        }
    }

    private suspend fun observe(ownedSession: ScreenCaptureSession) {
        try {
            ownedSession.state.collect { value ->
                logEngine("Engine.state", value.toString())
                val terminal = synchronized(gate) {
                    if (stopReason != null) return@collect
                    when (value) {
                        is ScreenCaptureState.Active -> recovery = null
                        is ScreenCaptureState.Suspended -> {
                            if (recovery == null && value.requestedParameters == sentParameters) {
                                recovery = Recovery(SystemClock.elapsedRealtime(), sentParameters)
                            }
                        }

                        is ScreenCaptureState.Failed, is ScreenCaptureState.Stopped -> recovery = null
                        else -> Unit
                    }
                    value is ScreenCaptureState.Failed || value is ScreenCaptureState.Stopped
                }
                changed()
                if (terminal) {
                    if (value is ScreenCaptureState.Failed) fail(value.problem.captureIssue())
                    else requestStop(StopReason.System)
                }
            }
        } catch (cause: CancellationException) {
            if (isCurrent()) fail(CaptureIssue.Unknown, cause)
        } catch (cause: Throwable) {
            fail(CaptureIssue.Unknown, cause)
        }
    }

    /** Called only by the controller loop; parameter updates and recovery never await engine work. */
    fun reconcile(image: ImageSettings, now: Long) {
        val parameters = image.parameters()
        val update = synchronized(gate) {
            if (stopReason != null) return
            val ownedSession = session ?: return
            if (parameters != sentParameters && (startSucceeded || ownedSession.state.value is ScreenCaptureState.Running)) {
                sentParameters = parameters
                recovery = null
                ownedSession to parameters
            } else null
        }
        if (update != null) {
            updateParameters(update.first, update.second)
        }
        val retry = synchronized(gate) {
            if (stopReason != null) return
            val episode = recovery ?: return
            val current = session?.state?.value ?: return
            if (current is ScreenCaptureState.Active || current is ScreenCaptureState.Stopped || current is ScreenCaptureState.Failed) {
                recovery = null
                return
            }
            if (now - episode.beganAt >= RECOVERY_TOTAL_MILLIS) {
                issue = CaptureIssue.ResumeFailed
                null
            } else if (now - episode.beganAt >= RECOVERY_NATURAL_MILLIS && !episode.retried &&
                current is ScreenCaptureState.Suspended && current.requestedParameters == sentParameters && sentParameters == episode.parameters
            ) {
                episode.retried = true
                session to sentParameters
            } else return
        }
        if (retry == null) requestStop(StopReason.Error)
        else retry.first?.let { updateParameters(it, retry.second) }
    }

    /** Engine admission can close before its terminal state is published; let its observer settle that race. */
    private fun updateParameters(ownedSession: ScreenCaptureSession, parameters: ScreenCaptureParameters) {
        try {
            ownedSession.updateParameters(parameters)
        } catch (cause: Throwable) {
            if (!isCurrent()) return
            when (val current = ownedSession.state.value) {
                is ScreenCaptureState.Stopped -> requestStop(StopReason.System)
                is ScreenCaptureState.Failed -> fail(current.problem.captureIssue(), cause)
                else -> if (cause !is IllegalStateException) fail(cause.captureIssue(), cause)
            }
        }
    }

    /** Delay for the loop's existing select, using elapsed time without an extra recovery worker. */
    fun recoveryDelay(now: Long): Long? = synchronized(gate) {
        val episode = recovery ?: return null
        val naturalDeadline = episode.beganAt + RECOVERY_NATURAL_MILLIS
        val deadline = if (episode.retried || now >= naturalDeadline) episode.beganAt + RECOVERY_TOTAL_MILLIS else naturalDeadline
        (deadline - now).coerceAtLeast(0L)
    }

    private fun fail(problem: CaptureIssue, cause: Throwable? = null) {
        synchronized(gate) {
            if (stopReason != null) return
            if (issue == null) issue = problem
        }
        if (cause != null) Log.e(TAG, "Capture failed: $problem", cause)
        requestStop(StopReason.Error)
    }

    private suspend fun finishCleanup(): Boolean {
        workJob.join()
        val ownedSession = synchronized(gate) { session }
        var succeeded = try {
            if (ownedSession != null) ownedSession.stop() else synchronized(gate) { projection }?.stop()
            true
        } catch (cause: Throwable) {
            Log.e(TAG, "Capture media cleanup failed", cause)
            false
        }
        // Work collectors are cancelled before media stop; retain one final snapshot without extending observation.
        if (ownedSession != null) {
            logEngine("Engine.cleanup", "mediaStopSucceeded=$succeeded, state=${ownedSession.state.value}, stats=${ownedSession.stats.value}")
        }
        // A failed session stop retains foreground on ordinary Stop; controller Shutdown performs final release.
        if (ownedSession == null || succeeded) {
            try {
                when (service.removeForeground()) {
                    is StreamingModuleService.ReleaseResult.Failed, StreamingModuleService.ReleaseResult.Unconfirmed -> succeeded = false
                    else -> Unit
                }
            } catch (cause: Throwable) {
                Log.e(TAG, "Capture foreground cleanup failed", cause)
                succeeded = false
            }
        }
        // Safe after the producer join and rollback, including attempts that never entered projection creation.
        try {
            captureAccess.finishProjectionCreation(id)
        } catch (cause: Throwable) {
            Log.e(TAG, "Capture projection creation finish failed", cause)
            succeeded = false
        }
        val reusableGrant = synchronized(gate) {
            val normalStop = (ownedSession?.state?.value as? ScreenCaptureState.Stopped)?.reason == ScreenCaptureStopReason.Requested
            grant.takeIf {
                succeeded && startSucceeded && (stopReason == StopReason.User || stopReason == StopReason.Shutdown) && issue == null && normalStop
            }
        }
        // Core can call this attempt's admission check, so never enter core while holding the attempt gate.
        if (reusableGrant != null) {
            try {
                captureAccess.saveConsentForReuse(reusableGrant)
            } catch (cause: Throwable) {
                Log.e(TAG, "Capture consent reuse save failed", cause)
                succeeded = false
            }
        }
        synchronized(gate) {
            grant = null
            projection = null
            session = null
            cleanupResult = succeeded
        }
        return succeeded
    }

    /** Best-effort logging cannot change capture control or the cleanup result. */
    private fun logEngine(event: String, message: String, cause: Throwable? = null) {
        try {
            XLog.v(getLog(event, "attempt=$id, $message"), cause)
        } catch (_: Throwable) {
            // A logger failure has no capture meaning.
        }
    }

    private fun ImageSettings.parameters(): ScreenCaptureParameters = ScreenCaptureParameters(
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

    private fun Throwable.captureIssue(): CaptureIssue = (this as? ScreenCaptureException)?.problem?.captureIssue() ?: CaptureIssue.Unknown
    private fun ScreenCaptureProblem.captureIssue(): CaptureIssue = when (this) {
        ScreenCaptureProblem.InvalidRequest -> CaptureIssue.InvalidRequest
        ScreenCaptureProblem.CaptureUnavailable -> CaptureIssue.CaptureUnavailable
        ScreenCaptureProblem.ResourceExhausted -> CaptureIssue.ResourceExhausted
        ScreenCaptureProblem.InternalFailure, ScreenCaptureProblem.UnsupportedColorSpace -> CaptureIssue.Unknown
    }

    private companion object {
        const val TAG = "MjpegCaptureAttempt"
        const val RECOVERY_NATURAL_MILLIS = 2_000L
        const val RECOVERY_TOTAL_MILLIS = 5_000L
    }
}
