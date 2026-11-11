package info.dvkr.screenstream.vnc.internal

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.window.core.layout.WindowSizeClass
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsFactory
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.isLocalNetworkPermissionGranted
import info.dvkr.screenstream.vnc.R
import info.dvkr.screenstream.vnc.VncModuleService
import info.dvkr.screenstream.vnc.settings.VncSettings
import info.dvkr.screenstream.vnc.ui.VncError
import info.dvkr.screenstream.vnc.ui.VncMainScreenUI
import info.dvkr.screenstream.vnc.ui.VncReverseStatus
import info.dvkr.screenstream.vnc.ui.VncState
import info.dvkr.screenstream.vnc.ui.isStartupPolicyError
import io.screenstream.core.capture.ConsentLaunchException
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.manager.StreamingModuleActionReceiver
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Factory(binds = [])
internal class VncStreamingService(
    @InjectedParam private val managerHandle: StreamingModule.Controller.ManagerHandle,
    @InjectedParam private val service: VncModuleService,
    private val captureAccess: ScreenCaptureAccess,
    private val notifications: StreamingModuleNotifications,
    private val settings: Lazy<VncSettings>,
    private val networkHelper: VncNetworkHelper,
    analyticsFactory: StreamingSessionAnalyticsFactory,
) : HandlerThread("VNC-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback, StreamingModule.Controller {

    private val vncSettings: VncSettings get() = settings.value
    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper, this) }
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val powerManager: PowerManager = service.application.getSystemService(PowerManager::class.java)
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("VNC-HT_Dispatcher") }
    private val supervisorJob = SupervisorJob()
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(supervisorJob + coroutineDispatcher) }
    override val instanceId: StreamingModule.InstanceId = managerHandle.instanceId
    private val mutableVncStateFlow = MutableStateFlow(VncState())
    private val vncStateFlow = mutableVncStateFlow.asStateFlow()
    private val bitmapStateFlow = MutableStateFlow(createBitmap(1, 1))
    private val admissionGate = Any()
    private val errorGate = Any()
    private val sessionAnalytics = VncSessionAnalytics(analyticsFactory)

    @Volatile
    private var closing = false
    private var workerStarted = false

    @Volatile
    private var cleanupFailed = false
    private var initialLocalNetworkRequestClaimed = false
    private var errorNotificationsClosed = false
    private var errorToken: Uuid? = null
    private var consentRetryToken: Uuid? = null

    @Volatile
    private var captureAttempt: CaptureAttempt? = null
    private val idleProjectionState = ProjectionState()
    private val projectionState: ProjectionState
        get() = captureAttempt?.projectionState ?: idleProjectionState
    private val cleanupResult: Deferred<Boolean> = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
        var succeeded = true
        if (synchronized(admissionGate) { workerStarted }) {
            runCatching { destroyWorker() }.onFailure { succeeded = false; XLog.e(getLog("cleanup"), it) }
        }
        val serviceStopped = runCatching { service.stopAndAwaitDestruction() }.getOrDefault(false)
        succeeded && serviceStopped && !cleanupFailed
    }

    private class ProjectionState(
        var pendingStartAttemptId: String? = null,
        var waitingForPermission: Boolean = false,
        var active: ActiveCapture? = null,
    )

    private class ActiveCapture(
        val mediaProjection: MediaProjection,
        val bitmapCapture: BitmapCapture,
        var deviceConfiguration: Configuration,
    )

    private class CaptureAttempt(
        val id: StreamingModule.CaptureAttemptId,
        val settings: VncSettings.Data,
        val job: CompletableJob,
    ) {
        val projectionState = ProjectionState(pendingStartAttemptId = id.uuid.toString(), waitingForPermission = true)
        var grant: ScreenCaptureAccess.Grant? = null
        var projection: MediaProjection? = null
        var callback: MediaProjection.Callback? = null
        var componentCallback: ComponentCallbacks? = null
        var bitmapCapture: BitmapCapture? = null

        @Volatile
        var stopRequested = false

        @Volatile
        var preflightError: VncError? = null
        var started = false
        var consentReuseIneligible = false
    }

    private data class ServerBindConfig(
        val interfaceFilter: Int,
        val addressFilter: Int,
        val enableIPv4: Boolean,
        val enableIPv6: Boolean,
        val serverPort: Int
    )

    private data class ReverseConnectConfig(
        val host: String,
        val port: Int,
        val reconnectDelaySeconds: Int
    )

    // All vars must be read/write on this (VNC-HT) thread
    private var initializedMode: VncSettings.Values.Mode? = null
    private var settingsLoaded: Boolean = false
    private var serverActive: Boolean = false
    private var server: RfbServer? = null
    private var serverEndpoints: List<VncServerEndpoint> = emptyList()
    private var reverseClient: RfbReverseClient? = null
    private var reverseStatus: VncReverseStatus = VncReverseStatus.IDLE
    private var reverseMessage: String? = null
    private var clients: List<String> = emptyList()
    private var currentError: VncError? = null
    private var previousError: VncError? = null
    // All vars must be read/write on this (VNC-HT) thread

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    private fun isStreaming(): Boolean = captureAttempt?.let { it.started && !it.stopRequested } == true

    internal sealed class InternalEvent(priority: Int) : VncEvent(priority) {
        data class InitState(val mode: VncSettings.Values.Mode) : InternalEvent(Priority.DESTROY_IGNORE)
        data class ModeChanged(val mode: VncSettings.Values.Mode) : InternalEvent(Priority.RECOVER_IGNORE)
        data class DiscoverAddress(val reason: String, val attempt: Int) : InternalEvent(Priority.RESTART_IGNORE)
        data class StartServer(val endpoints: List<VncServerEndpoint>) : InternalEvent(Priority.RESTART_IGNORE)
        data class StartStream(val clearStartupPolicyError: Boolean = false, val expectedErrorToken: Uuid? = null) : InternalEvent(Priority.RECOVER_IGNORE)
        data class Clients(val clients: List<String>) : InternalEvent(Priority.RESTART_IGNORE)
        data class ReverseStatus(val status: VncReverseStatus, val message: String?) : InternalEvent(Priority.RESTART_IGNORE)
        data class ConfigurationChange(val newConfig: Configuration, val attempt: StreamingModule.CaptureAttemptId) : InternalEvent(Priority.RECOVER_IGNORE) {
            override fun toString(): String = "ConfigurationChange"
        }
        data class CapturedContentResize(val width: Int, val height: Int, val attempt: StreamingModule.CaptureAttemptId) :
            InternalEvent(Priority.RECOVER_IGNORE)
        data class RestartServer(val reason: String) : InternalEvent(Priority.RESTART_IGNORE)
        data class RestartReverseClient(val reason: String) : InternalEvent(Priority.RESTART_IGNORE)
        data class Error(val error: VncError) : InternalEvent(Priority.RECOVER_IGNORE)
        data object Heartbeat : InternalEvent(Priority.DESTROY_IGNORE)
        data class Destroy(val destroyJob: CompletableJob) : InternalEvent(Priority.DESTROY_IGNORE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private fun componentCallback(attempt: CaptureAttempt): ComponentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = sendEvent(InternalEvent.ConfigurationChange(newConfig, attempt.id))
        override fun onLowMemory() = Unit
    }

    private fun projectionCallback(attempt: CaptureAttempt): MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.i(getLog("MediaProjection.Callback", "onStop"))
            invalidateConsentReuse(attempt.id)
            if (!isAttemptCurrent(attempt)) return
            requestAttemptStop(attempt)
            sendEvent(VncEvent.StopStream("MediaProjection.onStop", attempt.id))
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) = Unit

        override fun onCapturedContentResize(width: Int, height: Int) {
            XLog.v(getLog("MediaProjection.Callback", "onCapturedContentResize: width: $width, height: $height"))
            if (isAttemptCurrent(attempt)) sendEvent(InternalEvent.CapturedContentResize(width, height, attempt.id))
        }
    }

    init {
        XLog.d(getLog("init"))
    }

    @MainThread
    override fun startModule() {
        synchronized(admissionGate) {
            if (closing || !managerHandle.isCurrent() || workerStarted) return
            workerStarted = true
            start()
        }
    }

    override fun requestStreamStart() = sendEvent(InternalEvent.StartStream())

    override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean) {
        requestAttemptStopInternal(attempt, if (fromNotification) "User action: Notification" else "Coordinator Stop")
    }

    private fun requestAttemptStopInternal(attempt: StreamingModule.CaptureAttemptId, reason: String) {
        synchronized(admissionGate) {
            val capture = captureAttempt ?: return
            if (closing || capture.id != attempt || capture.stopRequested) return
            capture.stopRequested = true
            sessionAnalytics.closeUnentered(capture.id)
            capture.job.cancel()
        }
        sendEvent(VncEvent.StopStream(reason, attempt))
    }

    override fun requestRecovery(errorToken: Uuid) {
        synchronized(errorGate) {
            if (closing || this.errorToken != errorToken) return
        }
        sendEvent(VncEvent.RecoverError(errorToken))
    }

    override fun requestModuleShutdown() {
        synchronized(admissionGate) {
            if (closing) return
            closing = true
            captureAttempt?.let {
                it.stopRequested = true
                sessionAnalytics.closeUnentered(it.id)
                it.job.cancel()
            }
        }
        try {
            service.closeForegroundAdmission()
        } finally {
            synchronized(errorGate) {
                errorNotificationsClosed = true
                notifications.updateError(ERROR_NOTIFICATION_ID, null)
            }
            supervisorJob.cancel()
            cleanupResult.start()
        }
    }

    override suspend fun awaitCleanup(): Boolean = cleanupResult.await()

    override fun onServiceDestroyed() {
        invalidateConsentReuse()
        try {
            managerHandle.reportFailed()
        } finally {
            requestModuleShutdown()
        }
    }

    @Composable
    override fun Content(window: WindowSizeClass, modifier: Modifier) {
        VncMainScreenUI(
            instanceId = instanceId,
            vncStateFlow = vncStateFlow,
            sendEvent = ::sendUiEvent,
            windowSizeClass = window,
            claimInitialLocalNetworkRequest = { request ->
                if (!initialLocalNetworkRequestClaimed && !closing) initialLocalNetworkRequestClaimed = request()
            },
            modifier = modifier,
        )
    }

    private fun sendUiEvent(event: VncEvent) {
        when (event) {
            is VncEvent.StopStream -> event.expectedAttempt?.let { requestAttemptStopInternal(it, event.reason) }
            is VncEvent.RecoverError -> event.errorToken?.let(::requestRecovery)
            else -> sendEvent(event)
        }
    }

    private fun isAttemptCurrent(attempt: CaptureAttempt): Boolean = synchronized(admissionGate) {
        !closing && managerHandle.isCurrent() && captureAttempt === attempt && !attempt.stopRequested
    }

    private fun invalidateConsentReuse(attemptId: StreamingModule.CaptureAttemptId? = null) {
        synchronized(admissionGate) {
            captureAttempt?.takeIf { attemptId == null || it.id == attemptId }?.consentReuseIneligible = true
        }
    }

    @MainThread
    private fun prepareForeground(attempt: CaptureAttempt): Boolean = synchronized(admissionGate) {
        if (!isAttemptCurrent(attempt)) return false
        service.enterCaptureForeground(attempt.id, requiresMicrophoneType = false)
    }

    private fun requestAttemptStop(attempt: CaptureAttempt) {
        synchronized(admissionGate) {
            if (captureAttempt !== attempt) return
            attempt.stopRequested = true
            sessionAnalytics.closeUnentered(attempt.id)
            attempt.job.cancel()
        }
    }

    @MainThread
    override fun start() {
        super.start()
        XLog.d(getLog("start"))

        mutableVncStateFlow.value = buildViewState()
        sendEvent(InternalEvent.Heartbeat)

        service.startListening(
            supervisorJob,
            onScreenOff = {
                if (vncSettings.data.value.stopOnSleep) captureAttempt?.let { sendEvent(VncEvent.StopStream("ScreenOff", it.id)) }
            },
            onConnectionChanged = {
                if (initializedMode == VncSettings.Values.Mode.SERVER)
                    sendEvent(InternalEvent.RestartServer("ConnectionChanged"), timeout = 150)
            }
        )

        vncSettings.data.map { it.mode }.listenForChange(coroutineScope, 1) { mode ->
            if (!settingsLoaded) {
                settingsLoaded = true
                sendEvent(InternalEvent.InitState(mode = mode))
            } else {
                sendEvent(InternalEvent.ModeChanged(mode))
            }
        }

        vncSettings.data.map {
            ServerBindConfig(
                interfaceFilter = it.interfaceFilter,
                addressFilter = it.addressFilter,
                enableIPv4 = it.enableIPv4,
                enableIPv6 = it.enableIPv6,
                serverPort = it.serverPort
            )
        }.listenForChange(coroutineScope, 1) {
            if (initializedMode == VncSettings.Values.Mode.SERVER) {
                sendEvent(InternalEvent.RestartServer("SettingsChanged"))
            }
        }

        vncSettings.data.map {
            ReverseConnectConfig(host = it.reverseConnectHost, port = it.reverseConnectPort, reconnectDelaySeconds = it.reconnectDelaySeconds)
        }.listenForChange(coroutineScope, 1) {
            if (initializedMode == VncSettings.Values.Mode.CLIENT) {
                sendEvent(InternalEvent.RestartReverseClient("SettingsChanged"))
            }
        }

        coroutineScope.launch {
            delay(250.milliseconds)
            if (settingsLoaded) return@launch

            settingsLoaded = true
            sendEvent(InternalEvent.InitState(mode = vncSettings.data.value.mode))
        }
    }

    /** Existing thread-affine, bounded best-effort shutdown; physical media release is not confirmed. */
    private suspend fun destroyWorker() {
        supervisorJob.cancel()
        wakeLock?.apply { if (isHeld) release() }
        val destroyJob = Job()
        sendEvent(InternalEvent.Destroy(destroyJob))
        val processed = withTimeoutOrNull(3000.milliseconds) { destroyJob.join(); true } == true
        if (!processed) invalidateConsentReuse()
        handler.removeCallbacksAndMessages(null)
        quit()
        check(processed) { "VNC best-effort shutdown timed out" }
    }

    private var destroyPending: Boolean = false

    @AnyThread
    @Synchronized
    internal fun sendEvent(event: VncEvent, timeout: Long = 0) {
        if (destroyPending || (closing && event !is InternalEvent.Destroy)) {
            XLog.w(getLog("sendEvent", "Pending destroy: Ignoring event => $event"))
            return
        }
        if (event is InternalEvent.Destroy) destroyPending = true

        if (timeout > 0) XLog.d(getLog("sendEvent", "New event [Timeout: $timeout] => $event"))
        else XLog.v(getLog("sendEvent", "New event => $event"))

        if (event is InternalEvent.RestartServer || event is InternalEvent.DiscoverAddress) {
            handler.removeMessages(VncEvent.Priority.RESTART_IGNORE)
        }
        if (event is VncEvent.RecoverError) {
            handler.removeMessages(VncEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(VncEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(VncEvent.Priority.START_PROJECTION)
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(VncEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(VncEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(VncEvent.Priority.DESTROY_IGNORE)
            handler.removeMessages(VncEvent.Priority.START_PROJECTION)
        }
        if (event is VncEvent.StartProjection) {
            if (handler.hasMessages(VncEvent.Priority.START_PROJECTION)) {
                XLog.i(getLog("sendEvent", "Replacing pending StartProjection"))
            }
            handler.removeMessages(VncEvent.Priority.START_PROJECTION)
        }

        val wasSent = handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
        if (!wasSent) XLog.e(getLog("sendEvent", "Failed to send event: $event"))
    }

    private fun buildViewState(): VncState {
        val selectedMode = initializedMode ?: vncSettings.data.value.mode
        val readinessBusy = when (selectedMode) {
            VncSettings.Values.Mode.SERVER -> serverActive.not()
            VncSettings.Values.Mode.CLIENT -> false
        }
        val errorBlocks = currentError != null
        val isBusy =
            closing || destroyPending || captureAttempt?.stopRequested == true || !settingsLoaded || initializedMode == null ||
                    projectionState.pendingStartAttemptId != null || errorBlocks || readinessBusy

        return VncState(
            mode = selectedMode,
            isBusy = isBusy,
            waitingCastPermission = projectionState.waitingForPermission,
            startAttemptId = projectionState.pendingStartAttemptId,
            isStreaming = isStreaming(),
            serverNetInterfaces = if (selectedMode == VncSettings.Values.Mode.SERVER) {
                serverEndpoints.map { VncState.VncNetInterface(it.label, it.fullAddress) }.sortedBy { it.fullAddress }
            } else {
                emptyList()
            },
            clients = clients.toList(),
            reverseStatus = reverseStatus,
            reverseMessage = reverseMessage,
            captureAttempt = captureAttempt?.id,
            consentRetryToken = consentRetryToken,
            errorToken = errorToken,
            error = currentError
        )
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking {
        val event: VncEvent = msg.obj as VncEvent
        try {
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@VncStreamingService.getLog("handleMessage.catch", cause.toString()), cause)
            if (event is InternalEvent.Destroy) cleanupFailed = true

            invalidateConsentReuse()
            sessionAnalytics.workerFailed(captureAttempt?.id)
            projectionState.pendingStartAttemptId = null
            projectionState.waitingForPermission = false
            stopStream("HandleMessageException")

            currentError = cause as? VncError ?: VncError.UnknownError(cause)
        } finally {
            if (event is InternalEvent.Destroy) event.destroyJob.complete()
            sessionAnalytics.consumersChanged(captureAttempt?.id, currentActiveConsumersCount(), SystemClock.elapsedRealtime())

            publishError()
            mutableVncStateFlow.value = buildViewState()
            if (!closing && managerHandle.isCurrent()) {
                managerHandle.reportRunning(
                    StreamingModule.Status(
                        isStreaming = isStreaming(),
                        hasConsumer = currentActiveConsumersCount() > 0,
                        captureAttempt = captureAttempt?.id,
                        error = null,
                    ),
                    SystemClock.uptimeMillis(),
                )
            }
        }

        true
    }

    // On VNC-HT only
    private suspend fun processEvent(event: VncEvent) {
        if ((closing || !managerHandle.isCurrent()) && event !is InternalEvent.Destroy) return
        if (event is VncEvent.StopStream && event.expectedAttempt != null && event.expectedAttempt != captureAttempt?.id) return
        if (event is VncEvent.RecoverError && event.errorToken != null && event.errorToken != errorToken) return
        val observationAttempt = when (event) {
            is InternalEvent.CapturedContentResize -> event.attempt
            is InternalEvent.ConfigurationChange -> event.attempt
            else -> null
        }
        if (observationAttempt != null && (captureAttempt?.id != observationAttempt || captureAttempt?.stopRequested == true)) return
        when (event) {
            InternalEvent.Heartbeat -> {
                if (!closing) sendEvent(InternalEvent.Heartbeat, timeout = 1000)
            }

            is InternalEvent.InitState -> {
                stopStream("InitState")
                server?.stop()
                server = null
                serverEndpoints = emptyList()
                serverActive = false
                reverseClient?.stop()
                reverseClient = null
                reverseStatus = VncReverseStatus.IDLE
                reverseMessage = null
                clients = emptyList()
                initializedMode = event.mode
                consentRetryToken = null
                currentError = null
                if (event.mode == VncSettings.Values.Mode.SERVER) {
                    sendEvent(InternalEvent.DiscoverAddress("InitState", 0))
                }
            }

            is InternalEvent.ModeChanged -> {
                if (event.mode == initializedMode) {
                    XLog.d(getLog("ModeChanged", "Already initialized for mode=$event.mode. Ignoring."))
                    return
                }
                stopStream("ModeChanged")
                sendEvent(InternalEvent.InitState(mode = event.mode))
            }

            is InternalEvent.DiscoverAddress -> {
                if (initializedMode != VncSettings.Values.Mode.SERVER) return
                if (service.isLocalNetworkPermissionGranted().not()) {
                    server?.stop()
                    server = null
                    serverEndpoints = emptyList()
                    serverActive = false
                    clients = emptyList()
                    currentError = VncError.LocalNetworkPermissionRequired()
                    return
                }

                server?.stop()
                server = null
                serverActive = false

                val newInterfaces = networkHelper.getNetInterfaces(
                    vncSettings.data.value.interfaceFilter,
                    vncSettings.data.value.addressFilter,
                    vncSettings.data.value.enableIPv4,
                    vncSettings.data.value.enableIPv6,
                )

                XLog.d(getLog("DiscoverAddress", "${newInterfaces.size} interfaces discovered (${event.reason})"))

                if (newInterfaces.isNotEmpty()) {
                    sendEvent(InternalEvent.StartServer(newInterfaces))
                } else {
                    if (event.attempt < 3) {
                        sendEvent(InternalEvent.DiscoverAddress(event.reason, event.attempt + 1), 1000)
                    } else {
                        XLog.w(getLog("DiscoverAddress", "No interfaces to bind. Giving up."))
                        currentError = VncError.AddressNotFoundException()
                    }
                }
            }

            is InternalEvent.StartServer -> {
                if (initializedMode != VncSettings.Values.Mode.SERVER) return
                if (service.isLocalNetworkPermissionGranted().not()) {
                    serverActive = false
                    clients = emptyList()
                    currentError = VncError.LocalNetworkPermissionRequired()
                    return
                }

                server?.stop()
                serverEndpoints = event.endpoints
                server = RfbServer(
                    bitmapStateFlow = bitmapStateFlow.asStateFlow(),
                    maxFps = { vncSettings.data.value.maxFPS },
                    zlibEnabled = { vncSettings.data.value.zlibEncoding },
                    onClientsChange = { sendEvent(InternalEvent.Clients(it)) },
                    onBindFailure = { sendEvent(InternalEvent.Error(toBindError(it))) }
                ).also { it.start(event.endpoints, vncSettings.data.value.serverPort) }
                serverActive = true
                if (currentError is VncError.AddressNotFoundException) currentError = null
            }

            is InternalEvent.StartStream -> {
                if (!settingsLoaded || initializedMode == null || captureAttempt != null) return
                if (event.expectedErrorToken != null && event.expectedErrorToken != errorToken) return
                if (event.clearStartupPolicyError && currentError?.isStartupPolicyError() == true) currentError = null
                val settings = vncSettings.data.value
                if (settings.mode == VncSettings.Values.Mode.SERVER && !serverActive) return
                if (currentError != null) return
                if (!service.isLocalNetworkPermissionGranted()) {
                    currentError = VncError.LocalNetworkPermissionRequired()
                    return
                }
                val attempt = synchronized(admissionGate) {
                    if (closing || !managerHandle.isCurrent() || captureAttempt != null) return
                    CaptureAttempt(
                        id = StreamingModule.CaptureAttemptId(instanceId),
                        settings = settings,
                        job = SupervisorJob(supervisorJob),
                    ).also {
                        sessionAnalytics.accepted(it.id, settings.mode)
                        captureAttempt = it
                    }
                }
                consentRetryToken = null
                CoroutineScope(attempt.job + Dispatchers.Main.immediate).launch {
                    try {
                        sessionAnalytics.beginConsent(attempt.id)
                        val consent = captureAccess.requestConsent(
                            attempt = attempt.id,
                            attemptJob = attempt.job,
                            prepareForeground = {
                                sessionAnalytics.beginForegroundCall(attempt.id)
                                var foregroundFailure: Throwable? = null
                                try {
                                    prepareForeground(attempt)
                                } catch (cause: Throwable) {
                                    foregroundFailure = cause
                                    throw cause
                                } finally {
                                    sessionAnalytics.finishForegroundCall(attempt.id, foregroundFailure)
                                }
                            },
                        )
                        when (consent) {
                            is ScreenCaptureAccess.ConsentResult.Granted -> {
                                attempt.grant = consent.grant
                                sendEvent(VncEvent.StartProjection(attempt.id, consent.grant))
                            }

                            is ScreenCaptureAccess.ConsentResult.NotGranted ->
                                sendEvent(VncEvent.ConsentNotGranted(attempt.id, consent.reason))
                        }
                    } catch (cause: CancellationException) {
                        // Stop/shutdown owns the thread-affine best-effort cleanup.
                        throw cause
                    } catch (cause: Throwable) {
                        sendEvent(VncEvent.ConsentFailed(attempt.id, cause))
                    } finally {
                        sessionAnalytics.consentCallFinished(attempt.id)
                    }
                }
            }

            is VncEvent.ConsentNotGranted -> {
                val attempt = captureAttempt?.takeIf { it.id == event.attempt } ?: return
                sessionAnalytics.consentNotGranted(attempt.id, event.reason)
                stopStream()
                attempt.preflightError?.let { currentError = it }
                if (event.reason == ScreenCaptureAccess.NoGrantReason.Declined) consentRetryToken = Uuid.random()
            }

            is VncEvent.ConsentFailed -> {
                if (captureAttempt?.id != event.attempt) return
                sessionAnalytics.consentFailed(event.attempt, event.cause)
                stopStream()
                if (event.cause is ConsentLaunchException) {
                    consentRetryToken = Uuid.random()
                } else {
                    currentError = event.cause as? VncError ?: VncError.ScreenCaptureStartBlocked(event.cause)
                }
            }

            is VncEvent.RetryConsent -> {
                if (event.token != consentRetryToken) return
                consentRetryToken = null
                if (event.retry) sendEvent(InternalEvent.StartStream(clearStartupPolicyError = true))
            }

            is VncEvent.StartProjection -> {
                val attempt = captureAttempt?.takeIf { it.id == event.attempt } ?: return
                if (!isAttemptCurrent(attempt)) return
                projectionState.pendingStartAttemptId = null
                projectionState.waitingForPermission = false
                try {
                    val mediaProjection = captureAccess.createProjection(event.grant, retryAfterRecentForegroundStart = true)
                    attempt.projection = mediaProjection
                    check(isAttemptCurrent(attempt)) { "Capture admission closed during projection acquisition" }
                    captureAccess.finishProjectionCreation(attempt.id)
                    val projectionCallback = projectionCallback(attempt)
                    attempt.callback = projectionCallback
                    mediaProjection.registerCallback(projectionCallback, mainHandler)
                    val isStartupStillValid = { isAttemptCurrent(attempt) }

                    val bitmapCapture = BitmapCapture(
                        serviceContext = service,
                        maxFps = { vncSettings.data.value.maxFPS },
                        scaleFactor = { vncSettings.data.value.scaleFactor },
                        mediaProjection = mediaProjection,
                        bitmapStateFlow = bitmapStateFlow,
                    ) { error -> sendEvent(InternalEvent.Error(error)) }
                    val captureStarted = bitmapCapture.start(isStartupStillValid)
                    if (!captureStarted) {
                        XLog.i(getLog("StartProjection", "Capture not started. Stopping projection."))
                        bitmapCapture.destroy()
                        mediaProjection.unregisterCallback(projectionCallback)
                        stopStream()
                        return
                    }
                    if (!isStartupStillValid()) {
                        XLog.i(getLog("StartProjection", "Startup invalidated after capture start."))
                        bitmapCapture.destroy()
                        mediaProjection.unregisterCallback(projectionCallback)
                        stopStream()
                        return
                    }

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val componentCallback = componentCallback(attempt)
                        attempt.componentCallback = componentCallback
                        service.registerComponentCallbacks(componentCallback)
                    }

                    @Suppress("DEPRECATION")
                    @SuppressLint("WakelockTimeout")
                    if (vncSettings.data.value.keepAwake) {
                        val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                        wakeLock = powerManager.newWakeLock(flags, "ScreenStream::VNC-Tag").apply { acquire() }
                    }

                    val activeCapture = ActiveCapture(
                        mediaProjection = mediaProjection,
                        bitmapCapture = bitmapCapture,
                        deviceConfiguration = Configuration(service.resources.configuration)
                    )
                    attempt.projectionState.active = activeCapture
                    attempt.bitmapCapture = bitmapCapture
                    attempt.started = true
                    withContext(Dispatchers.Main.immediate) {
                        if (isAttemptCurrent(attempt)) service.updateCaptureNotification(attempt.id, active = true)
                    }
                    if (!isAttemptCurrent(attempt)) {
                        sessionAnalytics.aborted(attempt.id)
                        stopStream()
                        return
                    }
                    when (attempt.settings.mode) {
                        VncSettings.Values.Mode.SERVER -> Unit // RfbServer is already running; it reads bitmapStateFlow
                        VncSettings.Values.Mode.CLIENT -> startReverseClient()
                    }
                    currentError = null
                    sessionAnalytics.started(attempt.id, currentActiveConsumersCount(), SystemClock.elapsedRealtime())
                } catch (cause: Throwable) {
                    if (attempt.stopRequested || closing) {
                        sessionAnalytics.aborted(attempt.id)
                        stopStream()
                        return
                    }
                    sessionAnalytics.projectionFailed(attempt.id, cause)
                    stopStream()
                    currentError = when (cause) {
                        is VncError -> cause
                        is SecurityException -> VncError.ProjectionAcquireRejected(cause)
                        else -> VncError.UnknownError(cause)
                    }
                }
            }

            is VncEvent.StopStream -> stopStream(event.reason)

            is InternalEvent.ConfigurationChange -> {
                val active = projectionState.active ?: run {
                    XLog.d(getLog("ConfigurationChange", "Not streaming. Ignoring."))
                    return
                }
                val newConfig = Configuration(event.newConfig)
                val configDiff = active.deviceConfiguration.diff(newConfig)
                active.deviceConfiguration = newConfig
                if (vncSettings.data.value.stopOnConfigurationChange) {
                    sendEvent(VncEvent.StopStream("ConfigurationChange", event.attempt))
                } else if (
                    configDiff and ActivityInfo.CONFIG_ORIENTATION != 0 || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0 ||
                    configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0 || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                ) {
                    active.bitmapCapture.resize()
                } else {
                    XLog.d(getLog("ConfigurationChange", "No change relevant for streaming. Ignoring."))
                }
            }

            is InternalEvent.CapturedContentResize -> {
                if (projectionState.active == null) {
                    XLog.d(getLog("CapturedContentResize", "Not streaming. Ignoring."))
                    return
                }
                if (event.width <= 0 || event.height <= 0) {
                    XLog.e(
                        getLog("CapturedContentResize", "Invalid size: ${event.width} x ${event.height}. Ignoring."),
                        IllegalArgumentException("Invalid capture size: ${event.width} x ${event.height}")
                    )
                    return
                }
                projectionState.active?.bitmapCapture?.resize(event.width, event.height)
            }

            is InternalEvent.RestartServer -> {
                if (initializedMode != VncSettings.Values.Mode.SERVER) {
                    XLog.d(getLog("RestartServer", "Not in server mode. Ignoring."))
                    return
                }
                server?.stop()
                server = null
                serverActive = false
                if (currentError is VncError.AddressNotFoundException) currentError = null
                sendEvent(InternalEvent.DiscoverAddress(event.reason, 0))
            }

            is InternalEvent.RestartReverseClient -> {
                if (initializedMode != VncSettings.Values.Mode.CLIENT) {
                    XLog.d(getLog("RestartReverseClient", "Not in client mode. Ignoring."))
                    return
                }
                if (isStreaming()) {
                    startReverseClient()
                }
            }

            is InternalEvent.Error -> currentError = event.error

            is InternalEvent.Clients -> clients = event.clients

            is InternalEvent.ReverseStatus -> {
                reverseStatus = event.status
                reverseMessage = event.message
            }

            is VncEvent.RecoverError -> {
                consentRetryToken = null
                stopStream("RecoverError")
                server?.stop()
                server = null
                serverActive = false
                reverseClient?.stop()
                reverseClient = null
                handler.removeMessages(VncEvent.Priority.RESTART_IGNORE)
                handler.removeMessages(VncEvent.Priority.RECOVER_IGNORE)
                handler.removeMessages(VncEvent.Priority.START_PROJECTION)
                sendEvent(InternalEvent.InitState(mode = vncSettings.data.value.mode))
            }

            is InternalEvent.Destroy -> {
                captureAttempt?.let { sessionAnalytics.aborted(it.id) }
                stopStream("Destroy")
                server?.stop()
                server = null
                serverActive = false
                reverseClient?.stop()
                reverseClient = null
                currentError = null
            }

            else -> throw IllegalArgumentException("Unknown VncEvent: ${event::class.java}")
        }
    }

    private fun startReverseClient() {
        val settings = vncSettings.data.value
        reverseClient?.stop()
        reverseClient = RfbReverseClient(
            bitmapStateFlow = bitmapStateFlow.asStateFlow(),
            maxFps = { vncSettings.data.value.maxFPS },
            zlibEnabled = { vncSettings.data.value.zlibEncoding },
            onStatus = { status, message -> sendEvent(InternalEvent.ReverseStatus(status, message)) },
            onClientsChange = { sendEvent(InternalEvent.Clients(it)) }
        ).also { it.start(settings.reverseConnectHost, settings.reverseConnectPort, settings.reconnectDelaySeconds) }
    }

    /** Existing best-effort media shutdown; completion does not prove physical release. */
    private suspend fun stopStream(stopReason: String? = null) {
        val attempt = captureAttempt
        val wasStreaming = attempt?.started == true
        if (attempt != null && !wasStreaming) sessionAnalytics.aborted(attempt.id)
        if (attempt != null) {
            requestAttemptStop(attempt)
            if (!closing) service.updateCaptureNotification(attempt.id, active = false)
        }
        val activeConsumersAtStop = currentActiveConsumersCount()
        var succeeded = true
        fun bestEffort(action: () -> Unit) {
            runCatching(action).onFailure {
                succeeded = false
                attempt?.let { invalidateConsentReuse(it.id) }
                XLog.w(getLog("stopStream", "Best-effort cleanup failed"), it)
            }
        }
        bestEffort { attempt?.bitmapCapture?.destroy() }
        val projection = attempt?.projection
        val callback = attempt?.callback
        if (callback != null && projection != null) bestEffort { projection.unregisterCallback(callback) }
        bestEffort { projection?.stop() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            attempt?.componentCallback?.let { componentCallback -> bestEffort { service.unregisterComponentCallbacks(componentCallback) } }
            attempt?.componentCallback = null
        }
        if (wasStreaming) {
            XLog.i(getLog("stopStream", "stop=$stopReason, consumers=$activeConsumersAtStop"))
        } else {
            XLog.d(getLog("stopStream", "skip. stop=$stopReason"))
        }

        reverseClient?.let { client ->
            bestEffort { client.stop() }
        }
        reverseClient = null
        reverseStatus = VncReverseStatus.IDLE
        reverseMessage = null
        clients = emptyList()

        wakeLock?.let { lock ->
            bestEffort { if (lock.isHeld) lock.release() }
        }
        wakeLock = null

        if (attempt != null) {
            attempt.projectionState.pendingStartAttemptId = null
            attempt.projectionState.waitingForPermission = false
            attempt.projectionState.active = null
            attempt.bitmapCapture = null
            if (wasStreaming) sessionAnalytics.ended(attempt.id, stopReason, activeConsumersAtStop, SystemClock.elapsedRealtime())
            val requestedStop = stopReason?.contains("User action") == true || stopReason == "Coordinator Stop" ||
                    stopReason == "Destroy" || stopReason == "ModeChanged"
            bestEffort {
                synchronized(admissionGate) {
                    if (captureAttempt === attempt && succeeded && attempt.started && requestedStop && !attempt.consentReuseIneligible) {
                        attempt.grant?.let { grant -> captureAccess.saveConsentForReuse(grant) }
                    }
                }
            }
            if (!closing) service.leaveCaptureForeground(attempt.id)
            synchronized(admissionGate) { if (captureAttempt === attempt) captureAttempt = null }
        }
        if (!succeeded) cleanupFailed = true
    }

    private fun publishError() {
        synchronized(errorGate) {
            if (errorNotificationsClosed || closing || !managerHandle.isCurrent() || previousError === currentError) return
            previousError = currentError
            errorToken = currentError?.let { Uuid.random() }
            val error = currentError?.takeUnless {
                it is VncError.NotificationPermissionRequired || it is VncError.LocalNetworkPermissionRequired
            }
            val content = error?.let {
                val recovery = if (it.isStartupPolicyError()) null else
                    StreamingModuleActionReceiver.recoveryAction(service, instanceId, checkNotNull(errorToken))
                StreamingModuleNotifications.ErrorContent.Text(it.toString(ContextCompat.getContextForLanguage(service)), recovery)
            }
            notifications.updateError(ERROR_NOTIFICATION_ID, content)
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun currentActiveConsumersCount(): Int = clients.size

    private fun toBindError(error: Throwable): VncError {
        val normalized = error.message?.lowercase().orEmpty()
        return when {
            normalized.contains("address already in use") || normalized.contains("eaddrinuse") -> VncError.AddressInUseException()
            else -> VncError.UnknownError(error)
        }
    }

    private companion object {
        private const val ERROR_NOTIFICATION_ID: Int = 510
    }
}
