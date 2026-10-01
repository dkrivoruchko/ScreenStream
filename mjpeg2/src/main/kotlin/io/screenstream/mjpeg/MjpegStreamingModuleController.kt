package io.screenstream.mjpeg

import android.os.SystemClock
import android.util.Log
import info.dvkr.screenstream.common.notification.NotificationHelper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor
import io.screenstream.mjpeg.capture.CaptureIssue
import io.screenstream.mjpeg.capture.MjpegCaptureAttempt
import io.screenstream.mjpeg.ui.MjpegCaptureButton
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.UiController
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.capture.ScreenCaptureAccess
import io.screenstream.streaming.module.StreamingModuleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Owns ordinary control work, settings observation and one network monitor for an MJPEG instance.
 * Its exact Android Service creates it inertly before the manager adopts it and calls start.
 * One independent cleanup task closes observation and the monitor, stops that Service and waits for destruction.
 * Capture attempts run independently; HTTP and frame delivery are not part of this capture-only stage.
 */
@Factory(binds = [])
internal class MjpegStreamingModuleController(
    @InjectedParam private val runtime: StreamingModule.Controller.Runtime,
    @InjectedParam private val service: MjpegStreamingModuleService,
    private val settings: Lazy<MjpegSettings>,
    private val monitor: NetworkInterfaceMonitor,
    private val captureAccess: ScreenCaptureAccess,
    private val notifications: NotificationHelper,
) : StreamingModule.Controller, UiController {
    private enum class Problem {
        PreparationFailed,
        ObservationFailed,
        InternalFailure,
    }

    override val instanceId: StreamingModule.InstanceId = runtime.instanceId

    private val gate: Any = Any()
    private val wake: Channel<Unit> = Channel(Channel.CONFLATED)
    private val workJob: Job = SupervisorJob()
    private val workScope: CoroutineScope = CoroutineScope(workJob + Dispatchers.Default)

    /** Independent of ordinary work and waiters; completion includes exact Service destruction. */
    private val cleanupTask: Deferred<Boolean> = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
        finishCleanup()
    }
    private val uiState = MutableStateFlow(UiController.State(instanceId, UiController.Action.Start(enabled = false)))
    override val state: StateFlow<UiController.State> = uiState.asStateFlow()

    private var started: Boolean = false
    private var closing: Boolean = false
    private var requestedFilter: NetworkInterfaceMonitor.Filter? = null
    private var latestSettings: MjpegSettings.Data? = null
    private var firstProblem: Problem? = null
    private var capture: MjpegCaptureAttempt? = null
    private var captureErrorShown = false
    private var lastStatus: StreamingModule.Status? = null

    override fun startModule() {
        if (!runtime.isCurrent()) {
            requestModuleShutdown()
            return
        }
        val job = synchronized(gate) {
            if (started || closing) return
            started = true
            workScope.launch(start = CoroutineStart.LAZY) { runControlLoop() }
        }
        job.start()
    }

    /** Install one inert attempt immediately; repeated Start leaves its work and consent unchanged. */
    override fun requestStreamStart() {
        try {
            val attempt = synchronized(gate) {
                if (closing || capture != null || !runtime.isCurrent()) return
                val image = latestSettings?.image ?: return
                MjpegCaptureAttempt(
                    StreamingModule.CaptureAttemptId(instanceId, Uuid.random()), service, captureAccess, notifications,
                    image, runtime::isCurrent,
                ) { wake.trySend(Unit) }.also {
                    capture = it
                    captureErrorShown = false
                    uiState.value = UiController.State(instanceId, UiController.Action.Busy)
                }
            }
            attempt.start()
            wake.trySend(Unit)
        } catch (failure: Throwable) {
            fail(Problem.InternalFailure, failure)
        }
    }

    override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId) {
        if (attempt.instanceId != instanceId || !runtime.isCurrent()) return
        val owned = synchronized(gate) {
            capture?.takeIf { !closing && it.id == attempt }
        } ?: return
        owned.requestStop(MjpegCaptureAttempt.StopReason.User)
        synchronized(gate) {
            if (capture === owned) publishCapture()
        }
    }

    /** Close command admission and start cleanup without waiting; repeated calls share the same task. */
    override fun requestModuleShutdown() {
        val attempt = synchronized(gate) {
            closing = true
            uiState.value = UiController.State(instanceId, UiController.Action.Busy)
            capture
        }
        service.closeForegroundAdmission()
        attempt?.requestStop(MjpegCaptureAttempt.StopReason.Shutdown)
        workScope.cancel()
        cleanupTask.start()
    }

    /**
     * After shutdown, wait for work, foreground calls and exact Service destruction; false means cleanup failed.
     * Cancelling this caller ends only its wait and does not cancel the controller's cleanup task.
     */
    override suspend fun awaitCleanup(): Boolean = cleanupTask.await()

    /** Start independent monitor and platform cleanup, then join every obligation; false records any failure. */
    private suspend fun finishCleanup(): Boolean = coroutineScope {
        val monitorCleanup = async {
            try {
                monitor.close()
                true
            } catch (failure: Throwable) {
                Log.e(TAG, "MJPEG monitor cleanup failed", failure)
                false
            }
        }
        // Only the capture owner's pending media work may delay final foreground release and Service stop.
        val platformCleanup = async {
            val attemptSucceeded = synchronized(gate) { capture }?.awaitCleanup() ?: true
            var succeeded = try {
                when (service.removeForeground()) {
                    is StreamingModuleService.ReleaseResult.Failed, StreamingModuleService.ReleaseResult.Unconfirmed -> false
                    StreamingModuleService.ReleaseResult.NotRequired, StreamingModuleService.ReleaseResult.ApiCompleted -> true
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "MJPEG foreground cleanup failed", failure)
                false
            }
            try {
                service.stopSelf()
            } catch (failure: Throwable) {
                succeeded = false
                Log.e(TAG, "MJPEG Service stop failed", failure)
            }
            service.awaitDestroyed()
            attemptSucceeded && succeeded
        }
        workJob.join()
        val monitorSucceeded = monitorCleanup.await()
        val platformSucceeded = platformCleanup.await()
        monitorSucceeded && platformSucceeded
    }

    /** The shared Service records destruction; close this controller's work without awaiting cleanup. */
    override fun onServiceDestroyed() {
        try {
            runtime.reportFailed()
        } finally {
            requestModuleShutdown()
        }
    }

    @Composable
    override fun Content(window: WindowSizeClass, modifier: Modifier) {
        MjpegCaptureButton(this, ::requestStreamStart, ::requestStreamStop, modifier)
    }

    private suspend fun observeSettings() {
        var hasSnapshot = false
        try {
            currentCoroutineContext().ensureActive()
            if (!runtime.isCurrent() || isClosing()) return
            val observer = synchronized(gate) {
                if (closing) return
                workScope.launch(start = CoroutineStart.LAZY) { observeNetwork(monitor) }
            }
            observer.start()
            settings.value.data.collect { value ->
                synchronized(gate) {
                    if (closing) return@collect
                    latestSettings = value
                    hasSnapshot = true
                }
                wake.trySend(Unit)
            }
        } catch (failure: CancellationException) {
            if (!isClosing()) fail(if (hasSnapshot) Problem.ObservationFailed else Problem.PreparationFailed, failure)
        } catch (failure: Throwable) {
            fail(if (hasSnapshot) Problem.ObservationFailed else Problem.PreparationFailed, failure)
        }
    }

    private suspend fun observeNetwork(monitor: NetworkInterfaceMonitor) {
        try {
            monitor.state.collect { value ->
                val unexpectedClose = synchronized(gate) {
                    if (closing) return@collect
                    value is NetworkInterfaceMonitor.State.Closed
                }
                if (unexpectedClose) {
                    fail(Problem.ObservationFailed, IllegalStateException("Monitor closed while controller is open"))
                } else wake.trySend(Unit)
            }
        } catch (failure: CancellationException) {
            if (!isClosing()) fail(Problem.ObservationFailed, failure)
        } catch (failure: Throwable) {
            fail(Problem.ObservationFailed, failure)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runControlLoop() {
        try {
            val observation = workScope.launch(start = CoroutineStart.LAZY) { observeSettings() }
            var nextHeartbeat = SystemClock.uptimeMillis()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!runtime.isCurrent() || isClosing()) return
                wake.tryReceive()
                val now = SystemClock.uptimeMillis()
                if (now >= nextHeartbeat) {
                    reportStatus(now)
                    nextHeartbeat = now + HEARTBEAT_MILLIS
                    observation.start()
                }
                reconcileFilter()
                reconcileCapture()
                val status = publishCapture()
                if (status != lastStatus) reportStatus(SystemClock.uptimeMillis(), status)
                yield()
                val heartbeatWait = (nextHeartbeat - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                val recoveryWait = synchronized(gate) { capture }?.recoveryDelay(SystemClock.elapsedRealtime())
                val waitMillis = if (recoveryWait == null) heartbeatWait else minOf(heartbeatWait, recoveryWait)
                select {
                    wake.onReceiveCatching { }
                    onTimeout(waitMillis.milliseconds) { }
                }
            }
        } catch (failure: CancellationException) {
            if (!isClosing()) fail(Problem.InternalFailure, failure)
        } catch (failure: Throwable) {
            fail(Problem.InternalFailure, failure)
        } finally {
            requestModuleShutdown()
        }
    }

    private fun reconcileCapture() {
        val attempt = synchronized(gate) { capture } ?: return
        val image = synchronized(gate) { latestSettings?.image } ?: return
        attempt.reconcile(image, SystemClock.elapsedRealtime())
        val snapshot = attempt.snapshot()
        if (!captureErrorShown && (snapshot.issue != null || snapshot.cleanupResult == false)) {
            captureErrorShown = true
            val resource = if (snapshot.issue == CaptureIssue.ResumeFailed) R.string.mjpeg_capture_resume_error
                else R.string.mjpeg_capture_error
            workScope.launch(Dispatchers.Main.immediate) {
                notifications.showNotification(ERROR_NOTIFICATION_ID, notifications.getErrorNotification(service, service.getString(resource), null))
            }
        }
        if (snapshot.cleanupResult == true) {
            synchronized(gate) {
                if (capture !== attempt || closing) return
                capture = null
            }
        }
    }

    /** Publish controls and status from the same fresh attempt snapshot after outcome reconciliation. */
    private fun publishCapture(): StreamingModule.Status = synchronized(gate) {
        val attempt = capture
        val snapshot = attempt?.snapshot()
        val action = when {
            closing -> UiController.Action.Busy
            attempt == null || snapshot == null -> UiController.Action.Start(enabled = latestSettings != null)
            snapshot.cleanupResult == false -> UiController.Action.Start(enabled = false)
            snapshot.streaming -> UiController.Action.Stop(attempt.id)
            else -> UiController.Action.Busy
        }
        uiState.value = UiController.State(instanceId, action)
        StreamingModule.Status(snapshot?.streaming == true, hasConsumer = false, captureAttempt = attempt?.id)
    }

    private fun captureStatus(): StreamingModule.Status {
        val attempt = synchronized(gate) { capture }
        return StreamingModule.Status(attempt?.snapshot()?.streaming == true, hasConsumer = false, captureAttempt = attempt?.id)
    }

    private fun reportStatus(now: Long, status: StreamingModule.Status = captureStatus()) {
        runtime.reportRunning(status, now)
        lastStatus = status
    }

    private fun reconcileFilter() {
        val update = synchronized(gate) {
            if (closing) return
            val filter = latestSettings?.network?.filter ?: return
            if (requestedFilter == filter) return
            filter
        }
        if (!runtime.isCurrent()) return
        monitor.updateFilter(update)
        synchronized(gate) { requestedFilter = update }
    }

    private fun fail(problem: Problem, failure: Throwable) {
        synchronized(gate) {
            if (closing || firstProblem != null) return
            firstProblem = problem
        }
        Log.e(TAG, "MJPEG controller failed: $problem", failure)
        try {
            runtime.reportFailed()
        } finally {
            requestModuleShutdown()
        }
    }

    private fun isClosing(): Boolean = synchronized(gate) { closing }

    private companion object {
        private const val TAG: String = "MjpegStreamingModuleController"
        private const val ERROR_NOTIFICATION_ID: Int = 410
        private const val HEARTBEAT_MILLIS: Long = 1_000L
    }
}
