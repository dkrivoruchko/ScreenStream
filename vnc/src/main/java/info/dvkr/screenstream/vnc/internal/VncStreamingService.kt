package info.dvkr.screenstream.vnc.internal

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.graphics.createBitmap
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsTracker
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.isLocalNetworkPermissionGranted
import info.dvkr.screenstream.common.module.ProjectionCoordinator
import info.dvkr.screenstream.common.module.isStreamingModuleStartBlocked
import info.dvkr.screenstream.vnc.VncModuleService
import info.dvkr.screenstream.vnc.settings.VncSettings
import info.dvkr.screenstream.vnc.ui.VncError
import info.dvkr.screenstream.vnc.ui.VncReverseStatus
import info.dvkr.screenstream.vnc.ui.VncState
import info.dvkr.screenstream.vnc.ui.isStartupPolicyError
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

internal class VncStreamingService(
    private val service: VncModuleService,
    private val mutableVncStateFlow: MutableStateFlow<VncState>,
    private val vncSettings: VncSettings,
    private val networkHelper: VncNetworkHelper,
    private val streamingAnalytics: StreamingAnalytics
) : HandlerThread("VNC-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback {

    private val powerManager: PowerManager = service.application.getSystemService(PowerManager::class.java)
    private val projectionManager = service.application.getSystemService(MediaProjectionManager::class.java)
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper, this) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("VNC-HT_Dispatcher") }
    private val supervisorJob = SupervisorJob()
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(supervisorJob + coroutineDispatcher) }
    private val bitmapStateFlow = MutableStateFlow(createBitmap(1, 1))
    private val projectionCoordinator by lazy(mode = LazyThreadSafetyMode.NONE) {
        ProjectionCoordinator(
            tag = "VNC",
            projectionManager = projectionManager,
            callbackHandler = mainHandler,
            startForeground = { fgsType -> service.startForeground(fgsType) },
            onProjectionStopped = { generation ->
                XLog.i(getLog("ProjectionCoordinator.onStop", "g=$generation, streaming=$isStreaming"))
                sendEvent(VncEvent.Intentable.StopStream("ProjectionCoordinator.onStop[generation=$generation]"))
            }
        )
    }

    @MainThread
    internal fun prepareStartProjectionForeground(startAttemptId: String): Boolean {
        val currentStartAttemptId = pendingStartAttemptId
        if (currentStartAttemptId != startAttemptId) {
            XLog.i(getLog("prepareStartProjectionForeground", "MP_UI stale id=$startAttemptId current=${currentStartAttemptId ?: "none"}"))
            return false
        }
        val currentForegroundPreflightStartAttemptId = foregroundPreflightStartAttemptId
        if (currentForegroundPreflightStartAttemptId != null) {
            XLog.i(getLog("prepareStartProjectionForeground", "Foreground preflight already pending id=$currentForegroundPreflightStartAttemptId"))
            return false
        }
        foregroundPreflightStartAttemptId = startAttemptId
        return true
    }

    @MainThread
    internal fun tryStartProjectionForeground(): Throwable? {
        val foregroundStartError = projectionCoordinator.startForegroundForProjection(requiresAudioForegroundService = false)
        XLog.i(getLog("tryStartProjectionForeground", "SP_TRACE route=preflight_v1 stage=foreground_preflight audioMode=none result=${foregroundStartError?.javaClass?.simpleName ?: "ok"}"))
        return foregroundStartError
    }

    private fun clearPreparedProjectionStartIfNeeded(foregroundStartProcessed: Boolean, foregroundStartError: Throwable?) {
        if (!foregroundStartProcessed || foregroundStartError != null) return
        projectionCoordinator.stop()
        service.stopForeground()
    }

    private val sessionAnalyticsTracker by lazy(LazyThreadSafetyMode.NONE) {
        StreamingSessionAnalyticsTracker(
            analytics = streamingAnalytics,
            streamModeProvider = {
                when (initializedMode ?: vncSettings.data.value.mode) {
                    VncSettings.Values.Mode.SERVER -> StreamMode.VNC_SERVER
                    VncSettings.Values.Mode.CLIENT -> StreamMode.VNC_CLIENT
                }
            },
            nowElapsedRealtimeMs = { SystemClock.elapsedRealtime() }
        )
    }

    internal sealed class InternalEvent(priority: Int) : VncEvent(priority) {
        data class InitState(val clearIntent: Boolean, val mode: VncSettings.Values.Mode) : InternalEvent(Priority.RESTART_IGNORE)
        data class ModeChanged(val mode: VncSettings.Values.Mode) : InternalEvent(Priority.RECOVER_IGNORE)
        data class DiscoverAddress(val reason: String, val attempt: Int) : InternalEvent(Priority.RESTART_IGNORE)
        data class StartServer(val endpoints: List<VncServerEndpoint>) : InternalEvent(Priority.RESTART_IGNORE)
        data class StartStream(val permissionEducationShown: Boolean, val clearStartupPolicyError: Boolean = false) : InternalEvent(Priority.RESTART_IGNORE)
        data class Clients(val clients: List<String>) : InternalEvent(Priority.RESTART_IGNORE)
        data class ReverseStatus(val status: VncReverseStatus, val message: String?) : InternalEvent(Priority.RESTART_IGNORE)
        data class ConfigurationChange(val newConfig: Configuration) : InternalEvent(Priority.RESTART_IGNORE) {
            override fun toString(): String = "ConfigurationChange"
        }
        data class CapturedContentResize(val width: Int, val height: Int) : InternalEvent(Priority.RESTART_IGNORE)
        data class RestartServer(val reason: String) : InternalEvent(Priority.RESTART_IGNORE)
        data class RestartReverseClient(val reason: String) : InternalEvent(Priority.RESTART_IGNORE)
        data class Error(val error: VncError) : InternalEvent(Priority.RECOVER_IGNORE)
        data object ScreenOff : InternalEvent(Priority.RESTART_IGNORE)
        data class Destroy(val destroyJob: CompletableJob) : InternalEvent(Priority.DESTROY_IGNORE)
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
    private var isStreaming: Boolean = false
    @Volatile private var pendingStartAttemptId: String? = null
    @Volatile private var foregroundPreflightStartAttemptId: String? = null
    private var waitingForPermission: Boolean = false
    private var mediaProjectionIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var bitmapCapture: BitmapCapture? = null
    private var currentError: VncError? = null
    private var previousError: VncError? = null
    private var deviceConfiguration: Configuration = Configuration(service.resources.configuration)
    // All vars must be read/write on this (VNC-HT) thread

    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("OVERRIDE_DEPRECATION")
    private val componentCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = sendEvent(InternalEvent.ConfigurationChange(newConfig))
        override fun onLowMemory() = Unit
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.v(this@VncStreamingService.getLog("MediaProjection.Callback", "onStop (handled by coordinator)"))
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            XLog.v(this@VncStreamingService.getLog("MediaProjection.Callback", "onCapturedContentResize: width: $width, height: $height"))
            sendEvent(InternalEvent.CapturedContentResize(width, height))
        }
    }

    init {
        XLog.d(getLog("init"))
    }

    @MainThread
    override fun start() {
        super.start()
        XLog.d(getLog("start"))

        mutableVncStateFlow.value = VncState()

        service.startListening(
            supervisorJob,
            onScreenOff = { if (vncSettings.data.value.stopOnSleep) sendEvent(VncEvent.Intentable.StopStream("ScreenOff")) },
            onConnectionChanged = {
                if (initializedMode == VncSettings.Values.Mode.SERVER)
                    sendEvent(InternalEvent.RestartServer("ConnectionChanged"))
            }
        )

        vncSettings.data.map { it.mode }.listenForChange(coroutineScope, 1) { mode ->
            if (!settingsLoaded) {
                settingsLoaded = true
                sendEvent(InternalEvent.InitState(clearIntent = true, mode = mode))
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
            val mode = vncSettings.data.value.mode
            sendEvent(InternalEvent.InitState(clearIntent = true, mode = mode))
        }
    }

    @MainThread
    suspend fun destroyService() {
        XLog.d(getLog("destroyService"))

        wakeLock?.apply { if (isHeld) release() }
        supervisorJob.cancel()

        val destroyJob = Job()
        sendEvent(InternalEvent.Destroy(destroyJob))
        withTimeoutOrNull(3000.milliseconds) { destroyJob.join() } ?: XLog.w(getLog("destroyService", "Timeout"))

        handler.removeCallbacksAndMessages(null)

        service.stopSelf()

        quit() // Only after everything else is destroyed
    }

    private var destroyPending: Boolean = false

    @AnyThread
    @Synchronized
    internal fun sendEvent(event: VncEvent, timeout: Long = 0) {
        if (destroyPending) {
            when (event) {
                is InternalEvent.StartStream,
                is VncEvent.CastPermissionsDenied,
                is VncEvent.StartProjection -> sessionAnalyticsTracker.onStartAborted()
            }
            XLog.w(getLog("sendEvent", "Pending destroy: Ignoring event => $event"))
            return
        }
        if (event is InternalEvent.Destroy) destroyPending = true

        if (timeout > 0) XLog.d(getLog("sendEvent", "New event [Timeout: $timeout] => $event"))
        else XLog.v(getLog("sendEvent", "New event => $event"))

        if (event is InternalEvent.RestartServer || event is InternalEvent.DiscoverAddress) {
            handler.removeMessages(VncEvent.Priority.RESTART_IGNORE)
        }
        if (event is VncEvent.Intentable.RecoverError) {
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

        handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking(Dispatchers.Unconfined) {
        val event: VncEvent = msg.obj as VncEvent
        try {
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@VncStreamingService.getLog("handleMessage.catch", cause.toString()), cause)

            sessionAnalyticsTracker.onStartFailedIfPending(StartFailGroup.UNKNOWN)
            mediaProjectionIntent = null
            pendingStartAttemptId = null
            foregroundPreflightStartAttemptId = null
            waitingForPermission = false
            stopStream("HandleMessageException")

            currentError = cause as? VncError ?: VncError.UnknownError(cause)
        } finally {
            if (event is InternalEvent.Destroy) event.destroyJob.complete()
            sessionAnalyticsTracker.onActiveConsumersChanged(currentActiveConsumersCount())
            publishState()
        }

        true
    }

    // On VNC-HT only
    private suspend fun processEvent(event: VncEvent) {
        when (event) {
            is InternalEvent.InitState -> {
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
                if (event.clearIntent) mediaProjectionIntent = null
                currentError = null
                previousError = null
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
                sendEvent(InternalEvent.InitState(clearIntent = false, mode = event.mode))
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
                if (event.clearStartupPolicyError && currentError?.isStartupPolicyError() == true) currentError = null
                if (pendingStartAttemptId != null) {
                    XLog.i(getLog("StartStream", "Permission already pending id=${pendingStartAttemptId ?: "none"}"))
                    return
                }
                val mode = initializedMode ?: vncSettings.data.value.mode
                val notReady = currentError != null ||
                        when (mode) {
                            VncSettings.Values.Mode.SERVER -> serverActive.not()
                            VncSettings.Values.Mode.CLIENT -> false
                        }
                if (notReady || isStreaming) {
                    XLog.i(getLog("StartStream", "Not ready. mode=$mode notReady=$notReady isStreaming=$isStreaming"))
                    return
                }
                if (service.isLocalNetworkPermissionGranted().not()) {
                    currentError = VncError.LocalNetworkPermissionRequired()
                    return
                }
                sessionAnalyticsTracker.onStartAttempt(
                    entryPoint = EntryPoint.BUTTON,
                    usedCachedPermission = mediaProjectionIntent != null,
                    permissionEducationShown = event.permissionEducationShown
                )
                pendingStartAttemptId = Uuid.random().toString()
                val startAttemptId = pendingStartAttemptId!!
                mediaProjectionIntent?.let {
                    check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "VncEvent.StartStream: UPSIDE_DOWN_CAKE" }
                    waitingForPermission = false
                    XLog.i(getLog("StartStream", "SP_TRACE route=service_cached_permission stage=dispatch_source source=button startAttemptId=$startAttemptId"))
                    try {
                        VncModuleService.dispatchProjectionIntent(service, startAttemptId, it)
                    } catch (cause: Throwable) {
                        if (!cause.isStreamingModuleStartBlocked()) throw cause
                        pendingStartAttemptId = null
                        waitingForPermission = false
                        sessionAnalyticsTracker.onStartFailed(StartFailGroup.BLOCKED)
                        val error = VncError.ScreenCaptureStartBlocked(cause)
                        XLog.w(getLog("StartStream", "Cached projection dispatch blocked. source=button startAttemptId=$startAttemptId"), error)
                        currentError = error
                        return
                    }
                } ?: run {
                    waitingForPermission = true
                    XLog.i(getLog("Permission", "MP_UI request id=$startAttemptId source=button"))
                }
            }

            is VncEvent.CastPermissionsDenied -> {
                val currentStartAttemptId = pendingStartAttemptId
                if (currentStartAttemptId != event.startAttemptId) {
                    XLog.i(getLog("CastPermissionsDenied", "MP_UI stale id=${event.startAttemptId} current=${currentStartAttemptId ?: "none"}"))
                    return
                }
                pendingStartAttemptId = null
                foregroundPreflightStartAttemptId = null
                waitingForPermission = false
                sessionAnalyticsTracker.onStartFailed(StartFailGroup.PERMISSION_DENIED)
            }

            is VncEvent.StartProjection -> {
                val currentStartAttemptId = pendingStartAttemptId
                if (currentStartAttemptId != event.startAttemptId) {
                    XLog.i(getLog("StartProjection", "MP_UI stale id=${event.startAttemptId} current=${currentStartAttemptId ?: "none"}"))
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    if (foregroundPreflightStartAttemptId == event.startAttemptId) foregroundPreflightStartAttemptId = null
                    return
                }
                waitingForPermission = false
                foregroundPreflightStartAttemptId = null
                XLog.i(
                    getLog(
                        "StartProjection",
                        "SP_TRACE route=preflight_v1 stage=async_start startAttemptId=${event.startAttemptId} mode=${initializedMode ?: vncSettings.data.value.mode} isStreaming=$isStreaming cachedIntent=${mediaProjectionIntent != null}"
                    )
                )

                pendingStartAttemptId = null

                val startProjection = {
                    projectionCoordinator.startProjection(event.intent) { _, mediaProjection, _, isStartupStillValid ->
                        mediaProjection.registerCallback(projectionCallback, mainHandler)

                        val bitmapCapture = BitmapCapture(
                            serviceContext = service,
                            maxFps = { vncSettings.data.value.maxFPS },
                            scaleFactor = { vncSettings.data.value.scaleFactor },
                            mediaProjection = mediaProjection,
                            bitmapStateFlow = bitmapStateFlow
                        ) { error -> sendEvent(InternalEvent.Error(error)) }
                        val captureStarted = bitmapCapture.start(isStartupStillValid)
                        if (!captureStarted) {
                            XLog.i(getLog("StartProjection", "Capture not started. Stopping projection."))
                            bitmapCapture.destroy()
                            mediaProjection.unregisterCallback(projectionCallback)
                            return@startProjection false
                        }
                        if (!isStartupStillValid()) {
                            XLog.i(getLog("StartProjection", "Startup invalidated after capture start."))
                            bitmapCapture.destroy()
                            mediaProjection.unregisterCallback(projectionCallback)
                            return@startProjection false
                        }

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            mediaProjectionIntent = event.intent
                            service.registerComponentCallbacks(componentCallback)
                        }

                        @Suppress("DEPRECATION")
                        @SuppressLint("WakelockTimeout")
                        if (vncSettings.data.value.keepAwake) {
                            val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                            wakeLock = powerManager.newWakeLock(flags, "ScreenStream::VNC-Tag").apply { acquire() }
                        }

                        if (initializedMode == VncSettings.Values.Mode.CLIENT) {
                            startReverseClient()
                        }

                        this@VncStreamingService.isStreaming = true
                        this@VncStreamingService.mediaProjection = mediaProjection
                        this@VncStreamingService.bitmapCapture = bitmapCapture
                        true
                    }
                }

                val startPhase: String
                val result = if (event.foregroundStartProcessed) {
                    val foregroundStartError = event.foregroundStartError
                    if (foregroundStartError != null) {
                        startPhase = "foreground promotion"
                        projectionCoordinator.asForegroundStartResult(foregroundStartError)
                    } else {
                        startPhase = "projection startup"
                        startProjection()
                    }
                } else {
                    val foregroundError = projectionCoordinator.startForegroundForProjection(requiresAudioForegroundService = false)
                    if (foregroundError != null) {
                        startPhase = "foreground promotion"
                        projectionCoordinator.asForegroundStartResult(foregroundError)
                    } else {
                        startPhase = "projection startup"
                        startProjection()
                    }
                }
                when (result) {
                    is ProjectionCoordinator.StartResult.Started -> {
                        currentError = null
                        sessionAnalyticsTracker.onStarted(currentActiveConsumersCount())
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=started startAttemptId=${event.startAttemptId} phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        XLog.i(getLog("StartProjection", "Started. g=${result.generation}"))
                    }

                    is ProjectionCoordinator.StartResult.Interrupted -> {
                        if (result.cachedIntentAction == ProjectionCoordinator.CachedIntentAction.INVALIDATE) {
                            mediaProjectionIntent = null
                        }
                        sessionAnalyticsTracker.onStartAborted()
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "Interrupted. intent=${result.cachedIntentAction}/${mediaProjectionIntent != null}"
                            ), result.cause
                        )
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=interrupted startAttemptId=${event.startAttemptId} phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        currentError = null
                        sendEvent(VncEvent.Intentable.StopStream("StartProjectionInterrupted"))
                    }

                    ProjectionCoordinator.StartResult.Busy -> {
                        sessionAnalyticsTracker.onStartFailed(StartFailGroup.BUSY)
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=busy startAttemptId=${event.startAttemptId} phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        XLog.w(getLog("StartProjection", "Busy during $startPhase. intent=${mediaProjectionIntent != null}"))
                    }

                    is ProjectionCoordinator.StartResult.Blocked, is ProjectionCoordinator.StartResult.Fatal -> {
                        val cause = result.cause ?: error("Missing cause for failed start result")
                        if (result.cachedIntentAction == ProjectionCoordinator.CachedIntentAction.INVALIDATE) {
                            mediaProjectionIntent = null
                        }
                        if (result.failureReason == ProjectionCoordinator.FailureReason.PROJECTION_ACQUIRE_REJECTED) {
                            sessionAnalyticsTracker.onStartFailed(StartFailGroup.BLOCKED)
                            val error = VncError.ProjectionAcquireRejected(cause)
                            XLog.w(getLog("StartProjection", "Projection acquire rejected during $startPhase. intent=${result.cachedIntentAction}/${mediaProjectionIntent != null}"), error)
                            currentError = error
                            stopStream("ProjectionAcquireRejected")
                            return
                        }
                        val failedAction =
                            if (result is ProjectionCoordinator.StartResult.Blocked) {
                                sessionAnalyticsTracker.onStartFailed(StartFailGroup.BLOCKED)
                                "Blocked"
                            } else {
                                sessionAnalyticsTracker.onStartFailed(StartFailGroup.FATAL)
                                "Fatal"
                            }
                        val logMessage = "$failedAction during $startPhase. intent=${result.cachedIntentAction}/${mediaProjectionIntent != null}"
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=${if (result is ProjectionCoordinator.StartResult.Blocked) "blocked" else "fatal"} startAttemptId=${event.startAttemptId} phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        if (result is ProjectionCoordinator.StartResult.Blocked) {
                            val error = VncError.ScreenCaptureStartBlocked(cause)
                            XLog.w(getLog("StartProjection", logMessage), error)
                            currentError = error
                        } else {
                            XLog.e(getLog("StartProjection", logMessage), cause)
                            stopStream("StartProjectionFatal")
                            currentError = cause as? VncError ?: VncError.UnknownError(cause)
                        }
                    }
                }
            }

            is VncEvent.Intentable.StopStream -> {
                stopStream(event.reason)
            }

            is InternalEvent.ScreenOff -> if (isStreaming && vncSettings.data.value.stopOnSleep)
                sendEvent(VncEvent.Intentable.StopStream("ScreenOff"))

            is InternalEvent.ConfigurationChange -> {
                val newConfig = Configuration(event.newConfig)
                if (isStreaming) {
                    val configDiff = deviceConfiguration.diff(newConfig)
                    if (
                        configDiff and ActivityInfo.CONFIG_ORIENTATION != 0 || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0 ||
                        configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0 || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                    ) {
                        if (vncSettings.data.value.stopOnConfigurationChange) {
                            sendEvent(VncEvent.Intentable.StopStream("ConfigurationChange"))
                        } else {
                            bitmapCapture?.resize()
                        }
                    } else {
                        XLog.d(getLog("ConfigurationChange", "No change relevant for streaming. Ignoring."))
                    }
                } else {
                    XLog.d(getLog("ConfigurationChange", "Not streaming. Ignoring."))
                }
                deviceConfiguration = Configuration(newConfig)
            }

            is InternalEvent.CapturedContentResize -> {
                if (event.width <= 0 || event.height <= 0) {
                    XLog.e(
                        getLog("CapturedContentResize", "Invalid size: ${event.width} x ${event.height}. Ignoring."),
                        IllegalArgumentException("Invalid capture size: ${event.width} x ${event.height}")
                    )
                    return
                }
                if (isStreaming) {
                    bitmapCapture?.resize(event.width, event.height)
                } else {
                    XLog.d(getLog("CapturedContentResize", "Not streaming. Ignoring."))
                }
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
                if (isStreaming) {
                    startReverseClient()
                }
            }

            is InternalEvent.Error -> currentError = event.error

            is InternalEvent.Clients -> clients = event.clients

            is InternalEvent.ReverseStatus -> {
                reverseStatus = event.status
                reverseMessage = event.message
            }

            is VncEvent.Intentable.RecoverError -> {
                stopStream("RecoverError")
                server?.stop()
                server = null
                serverActive = false
                reverseClient?.stop()
                reverseClient = null

                handler.removeMessages(VncEvent.Priority.RESTART_IGNORE)
                handler.removeMessages(VncEvent.Priority.RECOVER_IGNORE)
                handler.removeMessages(VncEvent.Priority.START_PROJECTION)

                sendEvent(InternalEvent.InitState(clearIntent = true, mode = initializedMode ?: vncSettings.data.value.mode))
            }

            is InternalEvent.Destroy -> {
                sessionAnalyticsTracker.onStartAborted()
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

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream(stopReason: String? = null): Boolean {
        val wasStreaming = isStreaming
        val activeConsumersAtStop = currentActiveConsumersCount()
        pendingStartAttemptId = null
        foregroundPreflightStartAttemptId = null
        waitingForPermission = false
        if (wasStreaming) {
            XLog.i(
                getLog(
                    "stopStream",
                    "stop=$stopReason, consumers=$activeConsumersAtStop, intent=${mediaProjectionIntent != null}"
                )
            )
        } else {
            XLog.d(getLog("stopStream", "skip. stop=$stopReason, intent=${mediaProjectionIntent != null}"))
        }

        if (wasStreaming) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.unregisterComponentCallbacks(componentCallback)
            }
            bitmapCapture?.destroy()
            bitmapCapture = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection = null
            projectionCoordinator.stop()

            isStreaming = false
            sessionAnalyticsTracker.onEnded(stopReason, activeConsumersAtStop)
        }

        if (initializedMode == VncSettings.Values.Mode.CLIENT) {
            reverseClient?.stop()
            reverseClient = null
            reverseStatus = VncReverseStatus.IDLE
            reverseMessage = null
            clients = emptyList()
        }

        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        service.stopForeground()

        return wasStreaming
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun currentActiveConsumersCount(): Int = clients.size

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun publishState() {
        val mode = initializedMode ?: vncSettings.data.value.mode
        val state = VncState(
            mode = mode,
            isBusy = destroyPending || !settingsLoaded || initializedMode == null || pendingStartAttemptId != null ||
                    currentError != null || (mode == VncSettings.Values.Mode.SERVER && serverActive.not()),
            waitingCastPermission = waitingForPermission,
            startAttemptId = pendingStartAttemptId,
            isStreaming = isStreaming,
            serverNetInterfaces = if (mode == VncSettings.Values.Mode.SERVER) {
                serverEndpoints.map { VncState.VncNetInterface(it.label, it.fullAddress) }.sortedBy { it.fullAddress }
            } else {
                emptyList()
            },
            clients = clients.toList(),
            reverseStatus = reverseStatus,
            reverseMessage = reverseMessage,
            error = currentError
        )

        mutableVncStateFlow.value = state

        if (previousError != currentError) {
            previousError = currentError
            currentError?.let { service.showErrorNotification(it) } ?: service.hideErrorNotification()
        }
    }

    private fun toBindError(error: Throwable): VncError {
        val normalized = error.message?.lowercase().orEmpty()
        return when {
            normalized.contains("address already in use") || normalized.contains("eaddrinuse") -> VncError.AddressInUseException()
            else -> VncError.UnknownError(error)
        }
    }
}
