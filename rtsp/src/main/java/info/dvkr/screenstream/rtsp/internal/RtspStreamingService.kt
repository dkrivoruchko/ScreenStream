package info.dvkr.screenstream.rtsp.internal

import android.Manifest
import android.app.Activity
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.view.Surface
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.core.util.toClosedRange
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.getVersionName
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.RtspModuleService
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.adjustResizeFactor
import info.dvkr.screenstream.rtsp.internal.audio.AudioEncoder
import info.dvkr.screenstream.rtsp.internal.audio.AudioSource
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import info.dvkr.screenstream.rtsp.internal.rtsp.server.ClientStats
import info.dvkr.screenstream.rtsp.internal.rtsp.server.NetworkHelper
import info.dvkr.screenstream.rtsp.internal.rtsp.server.RtspServer
import info.dvkr.screenstream.rtsp.internal.video.VideoEncoder
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspBindError
import info.dvkr.screenstream.rtsp.ui.RtspBinding
import info.dvkr.screenstream.rtsp.ui.RtspClientStatus
import info.dvkr.screenstream.rtsp.ui.RtspError
import info.dvkr.screenstream.rtsp.ui.RtspState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URISyntaxException

internal class RtspStreamingService(
    private val service: RtspModuleService,
    private val mutableRtspStateFlow: MutableStateFlow<RtspState>,
    private val rtspSettings: RtspSettings,
    private val networkHelper: NetworkHelper
) : HandlerThread("RTSP-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback {

    private val appVersion = service.getVersionName()
    private val projectionManager = service.application.getSystemService(MediaProjectionManager::class.java)
    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper, this) }
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("RTSP-HT_Dispatcher") }
    private val supervisorJob = SupervisorJob()
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(supervisorJob + coroutineDispatcher) }

    internal data class ActiveProjection(
        val mediaProjection: MediaProjection,
        val virtualDisplay: VirtualDisplay,
        val videoEncoder: VideoEncoder,
        val audioEncoder: AudioEncoder? = null,
        val deviceConfiguration: Configuration
    ) {
        fun stop(projectionCallback: MediaProjection.Callback) {
            videoEncoder.stop()
            virtualDisplay.release()
            virtualDisplay.surface?.release()

            audioEncoder?.stop()

            mediaProjection.unregisterCallback(projectionCallback)
            mediaProjection.stop()
        }

        fun reconfigureVideo(width: Int, height: Int, fps: Int, bitRate: Int, densityDpi: Int) {
            videoEncoder.stop()
            virtualDisplay.surface?.release()
            videoEncoder.prepare(width, height, fps, bitRate)
            virtualDisplay.resize(width, height, densityDpi)
            virtualDisplay.surface = Surface(videoEncoder.inputSurfaceTexture)
            videoEncoder.start()
        }
    }

    private class ProjectionState(
        var waitingForPermission: Boolean = false,
        var cachedIntent: Intent? = null,
        var active: ActiveProjection? = null,
        var lastVideoParams: VideoParams? = null,
        var lastAudioParams: AudioParams? = null
    )

    private data class DiscoveredBinding(val bindKey: String, val label: String, val fullAddress: String)

    private data class ServerBindConfig(
        val interfaceFilter: Int,
        val addressFilter: Int,
        val enableIPv4: Boolean,
        val enableIPv6: Boolean,
        val serverPort: Int,
        val serverPath: String,
        val serverProtocol: RtspSettings.Values.ProtocolPolicy
    )

    // All vars must be read/write on this (RTSP_HT) thread
    private var selectedVideoEncoderInfo: VideoCodecInfo? = null
    private var selectedAudioEncoderInfo: AudioCodecInfo? = null
    private var projectionState: ProjectionState = ProjectionState()
    private var serverController: RtspServerController? = null
    private var clientController: RtspClientController? = null

    private var currentError: RtspError? = null
    private var previousError: RtspError? = null
    private var audioCaptureDisabled: Boolean = false
    private var audioIssueToastShown: Boolean = false
    // All vars must be read/write on this (RTSP_HT) thread

    private inner class RtspServerController() {
        var isActive: Boolean = false

        var bindings: List<RtspBinding> = emptyList()
            private set

        val statsSnapshot: List<ClientStats>
            get() = server?.getClientStatsSnapshot().orEmpty()

        private var generation: Long = 0L
        private var statsHeartbeatJob: Job? = null
        private var discoveredBindings: List<DiscoveredBinding> = emptyList()

        private var server: RtspServer? = null
            set(value) {
                if (value == null) {
                    statsHeartbeatJob?.cancel()
                    statsHeartbeatJob = null
                    generation++
                    field?.stop()
                    isActive = false
                    discoveredBindings = emptyList()
                    bindings = emptyList()
                }
                field = value
            }

        fun onEvent(event: InternalEvent.RtspServer) {
            if (event !is InternalEvent.RtspServer.DiscoverAddress && event.generation != generation) {
                XLog.d(getLog("RtspServer:${event::class.simpleName}", "Stale generation=${event.generation}. Ignoring."))
                return
            }

            when (event) {
                is InternalEvent.RtspServer.DiscoverAddress -> {
                    server = null

                    runCatching {
                        val netInterfaces = networkHelper.getNetInterfaces(
                            rtspSettings.data.value.interfaceFilter,
                            rtspSettings.data.value.addressFilter,
                            rtspSettings.data.value.enableIPv4,
                            rtspSettings.data.value.enableIPv6,
                        )

                        XLog.d(getLog("DiscoverAddress", "${netInterfaces.size} interfaces discovered (${event.reason})"))

                        if (netInterfaces.isEmpty()) {
                            if (event.attempt < 4) {
                                sendEvent(
                                    InternalEvent.RtspServer.DiscoverAddress(reason = event.reason, attempt = event.attempt + 1),
                                    1000
                                )
                            } else {
                                XLog.w(getLog("RtspServer", "No interfaces to bind. Stopping stream and give up."))
                                stopStream(true)
                                currentError = RtspError.ServerError.AddressNotFoundException()
                            }
                        } else {
                            if (currentError is RtspError.ServerError.AddressNotFoundException) currentError = null

                            val port = rtspSettings.data.value.serverPort
                            val path = rtspSettings.data.value.serverPath
                            val protocolPolicy = rtspSettings.data.value.serverProtocol

                            discoveredBindings = netInterfaces.map { netInterface ->
                                DiscoveredBinding(
                                    bindKey = netInterface.bindKey,
                                    label = netInterface.label,
                                    fullAddress = netInterface.buildUrl(port, path)
                                )
                            }
                            bindings = discoveredBindings.map { item ->
                                RtspBinding(label = item.label, fullAddress = item.fullAddress, bindError = null)
                            }

                            server = RtspServer(
                                appVersion = appVersion,
                                generation = ++generation,
                                onEvent = {
                                    XLog.d(getLog("RtspServer.onEvent", it.toString()))
                                    sendEvent(it)
                                },
                                onRequestKeyFrame = { projectionState.active?.videoEncoder?.requestKeyFrame() }
                            ).apply {
                                start(netInterfaces.toList(), port, path, protocolPolicy)
                            }

                            projectionState.lastVideoParams?.let { params ->
                                server?.setVideoData(params.codec, params.sps, params.pps, params.vps)
                                projectionState.active?.videoEncoder?.requestKeyFrame()
                            }
                            projectionState.lastAudioParams?.let { params ->
                                server?.setAudioData(params)
                            }

                            XLog.d(getLog("RtspServer", "(Re)start on ${netInterfaces.size} interfaces, protocol=$protocolPolicy"))
                        }
                    }.onFailure {
                        XLog.w(getLog("DiscoverAddress", "Failed: ${it.message}"), it)
                        stopStream(true)
                        currentError = RtspError.UnknownError(it)
                    }
                }

                is InternalEvent.RtspServer.OnStart -> {
                    isActive = true
                    currentError = null
                }

                is InternalEvent.RtspServer.OnBindFailures -> {
                    bindings = discoveredBindings.map { item ->
                        RtspBinding(label = item.label, fullAddress = item.fullAddress, bindError = event.failures[item.bindKey])
                    }
                }

                is InternalEvent.RtspServer.OnError -> {
                    stopStream(true)
                    currentError = event.error
                    isActive = false
                }

                is InternalEvent.RtspServer.OnStop -> server = null
                is InternalEvent.RtspServer.OnClientStats -> Unit // Intentional to trigger serverClientStats update
            }
        }

        fun start(coroutineScope: CoroutineScope) {
            statsHeartbeatJob?.cancel()
            statsHeartbeatJob = coroutineScope.launch {
                while (isActive) {
                    sendEvent(InternalEvent.RtspServer.OnClientStats(generation))
                    delay(1000)
                }
            }
        }

        fun stop(stopServer: Boolean) {
            if (server == null) return

            statsHeartbeatJob?.cancel()
            statsHeartbeatJob = null

            if (stopServer) {
                server = null
            } else {
                server?.disconnectAllClients()
                server?.clearMediaParams()
            }
        }

        fun setVideoParams(video: VideoParams) {
            server?.setVideoData(video.codec, video.sps, video.pps, video.vps)
        }

        fun setAudioParams(audio: AudioParams?) {
            server?.setAudioData(audio)
        }

        fun onFrame(frame: MediaFrame) = when (frame) {
            is MediaFrame.VideoFrame -> server?.onVideoFrame(frame) ?: frame.release()
            is MediaFrame.AudioFrame -> server?.onAudioFrame(frame) ?: frame.release()
        }
    }

    private inner class RtspClientController() {
        var status: RtspClientStatus = RtspClientStatus.IDLE

        private var client: RtspClient? = null
        private var generation: Long = 0L

        fun startClient(rtspUrl: RtspUrl, onlyVideo: Boolean) {
            currentError = null
            client = RtspClient(appVersion, ++generation, rtspUrl, rtspSettings.data.value.clientProtocol, onlyVideo) {
                XLog.d(getLog("RtspClient.sendEvent", it.toString()))
                sendEvent(it)
            }
        }

        fun connect() {
            currentError = null
            status = RtspClientStatus.STARTING
            client?.connect()
        }

        fun stop() {
            if (client != null) {
                generation++
                client?.destroy(); client = null
            }
            status = RtspClientStatus.IDLE
        }

        fun onEvent(event: InternalEvent.RtspClient) {
            if (event.generation != generation) {
                XLog.d(getLog("RtspClient:${event::class.simpleName}", "Stale generation=${event.generation}. Ignoring."))
                return
            }

            when (event) {
                is InternalEvent.RtspClient.OnConnectionSuccess -> {
                    status = RtspClientStatus.ACTIVE
                    currentError = null
                }

                is InternalEvent.RtspClient.OnDisconnect -> {
                    stopStream(true)
                    status = RtspClientStatus.IDLE
                }

                is InternalEvent.RtspClient.OnBitrate -> Unit //TODO Skip for now

                is InternalEvent.RtspClient.OnError -> {
                    stopStream(true)
                    status = RtspClientStatus.ERROR
                    currentError = event.error
                }
            }
        }

        fun setVideoParams(video: VideoParams) {
            client?.setVideoData(video.codec, video.sps, video.pps, video.vps)
        }

        fun setAudioParams(audio: AudioParams?) {
            client?.setAudioData(audio)
        }

        fun onFrame(frame: MediaFrame) {
            client?.enqueueFrame(frame) ?: frame.release()
        }
    }

    internal sealed class InternalEvent(priority: Int) : RtspEvent(priority) {
        data class InitState(val clearIntent: Boolean) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnVideoCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnAudioCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class ModeChanged(val mode: RtspSettings.Values.Mode) : InternalEvent(Priority.RECOVER_IGNORE)
        data object StartStream : InternalEvent(Priority.RECOVER_IGNORE)
        data object RetryBindings : InternalEvent(Priority.RECOVER_IGNORE)
        data class AudioCaptureError(val cause: Throwable) : InternalEvent(Priority.RECOVER_IGNORE)

        data class OnAudioParamsChange(val micMute: Boolean, val deviceMute: Boolean, val micVolume: Float, val deviceVolume: Float) :
            InternalEvent(Priority.DESTROY_IGNORE)

        data class ConfigurationChange(val newConfig: Configuration) : InternalEvent(Priority.RECOVER_IGNORE) {
            override fun toString(): String = "ConfigurationChange"
        }

        data class CapturedContentResize(val width: Int, val height: Int) : InternalEvent(Priority.RECOVER_IGNORE)
        data class ApplyVideoReconfigure(val width: Int, val height: Int) : InternalEvent(Priority.VIDEO_RECONFIGURE_IGNORE)
        data class Error(val error: RtspError) : InternalEvent(Priority.RECOVER_IGNORE)
        data class Destroy(val destroyJob: CompletableJob) : InternalEvent(Priority.DESTROY_IGNORE)

        sealed class RtspClient(priority: Int) : InternalEvent(priority) {
            abstract val generation: Long

            data class OnConnectionSuccess(override val generation: Long) : RtspClient(Priority.RECOVER_IGNORE)
            data class OnDisconnect(override val generation: Long) : RtspClient(Priority.DESTROY_IGNORE)
            data class OnBitrate(override val generation: Long, val bitrate: Long) : RtspClient(Priority.DESTROY_IGNORE)
            data class OnError(override val generation: Long, val error: RtspError.ClientError) : RtspClient(Priority.RECOVER_IGNORE)
        }

        sealed class RtspServer(priority: Int) : InternalEvent(priority) {
            abstract val generation: Long

            data class DiscoverAddress(override val generation: Long = -1L, val reason: String, val attempt: Int = 0) :
                RtspServer(Priority.RESTART_IGNORE)

            data class OnBindFailures(override val generation: Long, val failures: Map<String, RtspBindError>) :
                RtspServer(Priority.RECOVER_IGNORE)

            data class OnError(override val generation: Long, val error: RtspError) : RtspServer(Priority.RECOVER_IGNORE)
            data class OnStart(override val generation: Long) : RtspServer(Priority.RECOVER_IGNORE)
            data class OnStop(override val generation: Long) : RtspServer(Priority.DESTROY_IGNORE)
            data class OnClientStats(override val generation: Long) : RtspServer(Priority.DESTROY_IGNORE)
        }

        data class OnVideoFps(val fps: Int) : InternalEvent(Priority.DESTROY_IGNORE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private val componentCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = sendEvent(InternalEvent.ConfigurationChange(newConfig))
        override fun onLowMemory() = Unit
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.i(this@RtspStreamingService.getLog("MediaProjection.Callback", "onStop"))
            sendEvent(RtspEvent.Intentable.StopStream("MediaProjection.Callback"))
        }

        // TODO https://android-developers.googleblog.com/2024/03/enhanced-screen-sharing-capabilities-in-android-14.html
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            XLog.d(this@RtspStreamingService.getLog("MediaProjection.Callback", "onCapturedContentVisibilityChanged: $isVisible"))
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            XLog.d(this@RtspStreamingService.getLog("MediaProjection.Callback", "onCapturedContentResize: width: $width, height: $height"))
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

        mutableRtspStateFlow.value = buildViewState()

        sendEvent(InternalEvent.InitState(true))

        fun <T> Flow<T>.listenForChange(scope: CoroutineScope, drop: Int = 0, action: suspend (T) -> Unit) =
            distinctUntilChanged().drop(drop).onEach { action(it) }.launchIn(scope)

        service.startListening(
            supervisorJob,
            onScreenOff = { if (rtspSettings.data.value.stopOnSleep) sendEvent(RtspEvent.Intentable.StopStream("ScreenOff")) },
            onConnectionChanged = {
                if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER)
                    sendEvent(InternalEvent.RtspServer.DiscoverAddress(reason = "ConnectionChanged"), timeout = 150)
            }
        )

        rtspSettings.data.map { it.videoCodecAutoSelect to it.videoCodec }.listenForChange(coroutineScope) {
            if (it.first) sendEvent(InternalEvent.OnVideoCodecChange(null))
            else sendEvent(InternalEvent.OnVideoCodecChange(it.second))
        }

        rtspSettings.data.map { it.audioCodecAutoSelect to it.audioCodec }.listenForChange(coroutineScope) {
            if (it.first) sendEvent(InternalEvent.OnAudioCodecChange(null))
            else sendEvent(InternalEvent.OnAudioCodecChange(it.second))
        }

        rtspSettings.data.map { InternalEvent.OnAudioParamsChange(it.muteMic, it.muteDeviceAudio, it.volumeMic, it.volumeDeviceAudio) }
            .listenForChange(coroutineScope) { sendEvent(it) }

        rtspSettings.data.map { it.mode }.listenForChange(coroutineScope, 1) { mode ->
            sendEvent(InternalEvent.ModeChanged(mode))
        }

        rtspSettings.data.map {
            ServerBindConfig(
                interfaceFilter = it.interfaceFilter,
                addressFilter = it.addressFilter,
                enableIPv4 = it.enableIPv4,
                enableIPv6 = it.enableIPv6,
                serverPort = it.serverPort,
                serverPath = it.serverPath,
                serverProtocol = it.serverProtocol
            )
        }.listenForChange(coroutineScope, 1) { config ->
            XLog.i(getLog("SettingsChanged", config.toString()))
            sendEvent(InternalEvent.RtspServer.DiscoverAddress(reason = "SettingsChanged"), timeout = 200)
        }
    }

    @MainThread
    suspend fun destroyService() {
        XLog.d(getLog("destroyService"))

        supervisorJob.cancel()

        val destroyJob = Job()
        sendEvent(InternalEvent.Destroy(destroyJob))
        withTimeoutOrNull(3000) { destroyJob.join() } ?: XLog.w(getLog("destroyService", "Timeout"))

        handler.removeCallbacksAndMessages(null)

        service.stopSelf()

        quit() // Only after everything else is destroyed
    }

    private var destroyPending: Boolean = false

    @AnyThread
    @Synchronized
    internal fun sendEvent(event: RtspEvent, timeout: Long = 0) {
        if (destroyPending) {
            XLog.w(getLog("sendEvent", "Pending destroy: Ignoring event => $event"))
            return
        }
        if (event is InternalEvent.Destroy) destroyPending = true

        if (timeout > 0) XLog.d(getLog("sendEvent", "New event [Timeout: $timeout] => $event"))
        else XLog.v(getLog("sendEvent", "New event => $event"))

        if (event is InternalEvent.ApplyVideoReconfigure) {
            handler.removeMessages(RtspEvent.Priority.VIDEO_RECONFIGURE_IGNORE)
        }
        if (event is InternalEvent.RtspServer.DiscoverAddress) {
            handler.removeMessages(RtspEvent.Priority.RESTART_IGNORE)
        }
        if (event is RtspEvent.Intentable.RecoverError) {
            handler.removeMessages(RtspEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(RtspEvent.Priority.VIDEO_RECONFIGURE_IGNORE)
            handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(RtspEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(RtspEvent.Priority.VIDEO_RECONFIGURE_IGNORE)
            handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(RtspEvent.Priority.DESTROY_IGNORE)
        }

        val wasSent = handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
        if (!wasSent) XLog.e(getLog("sendEvent", "Failed to send event: $event"))
    }

    private fun scheduleVideoReconfigure(projection: ActiveProjection, sourceWidth: Int, sourceHeight: Int, source: String) {
        val videoCapabilities = selectedVideoEncoderInfo!!.capabilities.videoCapabilities!!
        val (_, width, height) = videoCapabilities.adjustResizeFactor(
            sourceWidth, sourceHeight, rtspSettings.data.value.videoResizeFactor / 100
        )

        if (width == projection.videoEncoder.width && height == projection.videoEncoder.height) {
            XLog.d(getLog(source, "No change relevant for streaming. Ignoring."))
            return
        }

        sendEvent(event = InternalEvent.ApplyVideoReconfigure(width = width, height = height), timeout = 120)
    }

    private fun buildViewState(): RtspState {
        val selectedMode = rtspSettings.data.value.mode
        val isServerMode = selectedMode == RtspSettings.Values.Mode.SERVER
        val serverActive = serverController?.isActive == true
        val status = if (isServerMode) {
            if (serverActive) RtspClientStatus.ACTIVE else RtspClientStatus.IDLE
        } else {
            clientController?.status ?: RtspClientStatus.IDLE
        }
        val readinessBusy = when (selectedMode) {
            RtspSettings.Values.Mode.SERVER -> serverActive.not()
            RtspSettings.Values.Mode.CLIENT -> {
                val audioEnabled = rtspSettings.data.value.enableMic || rtspSettings.data.value.enableDeviceAudio
                val videoReady = selectedVideoEncoderInfo != null
                val audioReady = audioEnabled.not() || selectedAudioEncoderInfo != null
                (videoReady && audioReady).not()
            }
        }
        val errorBlocks = currentError != null && currentError !is RtspError.ClientError
        val isBusy = destroyPending || projectionState.waitingForPermission || errorBlocks || readinessBusy

        return RtspState(
            mode = selectedMode,
            clientStatus = status,
            serverBindings = if (isServerMode) serverController?.bindings.orEmpty() else emptyList(),
            isBusy = isBusy,
            waitingCastPermission = projectionState.waitingForPermission,
            isStreaming = projectionState.active != null,
            selectedVideoEncoder = selectedVideoEncoderInfo,
            selectedAudioEncoder = selectedAudioEncoderInfo,
            serverClientStats = serverController?.statsSnapshot.orEmpty(),
            error = currentError
        )
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking(Dispatchers.Unconfined) {
        val event: RtspEvent = msg.obj as RtspEvent
        try {
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@RtspStreamingService.getLog("handleMessage.catch", cause.toString()), cause)

            projectionState.cachedIntent = null
            projectionState.waitingForPermission = false
            stopStream(true)

            currentError = cause as? RtspError ?: RtspError.UnknownError(cause)
            if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                serverController?.isActive = false
            } else {
                clientController?.status = RtspClientStatus.ERROR
            }
        } finally {
            if (event is InternalEvent.Destroy) event.destroyJob.complete()

            mutableRtspStateFlow.value = buildViewState()

            if (previousError != currentError) {
                previousError = currentError
                val notifyError = currentError?.takeUnless {
                    it is RtspError.ClientError || it is RtspError.NotificationPermissionRequired
                }
                notifyError?.let { service.showErrorNotification(it) } ?: service.hideErrorNotification()
            }
        }

        true
    }

    // On RTSP-HT only
    private fun processEvent(event: RtspEvent) {
        when (event) {
            is InternalEvent.InitState -> {
                serverController = null
                clientController = null
                if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                    serverController = RtspServerController()
                } else {
                    clientController = RtspClientController()
                }
                projectionState = ProjectionState(
                    waitingForPermission = false,
                    cachedIntent = if (event.clearIntent) null else projectionState.cachedIntent
                )
                currentError = null
                previousError = null
                audioCaptureDisabled = false
                audioIssueToastShown = false
            }

            is InternalEvent.OnVideoCodecChange -> {
                require(projectionState.active == null) { "Cannot change codec while streaming" }

                selectedVideoEncoderInfo = null
                val available = EncoderUtils.availableVideoEncoders
                selectedVideoEncoderInfo = when {
                    available.isEmpty() -> throw IllegalStateException("No suitable video encoders available")
                    // Auto select
                    event.name.isNullOrBlank() -> available.first()

                    // We have saved codec, checking if it's available
                    else -> available.firstOrNull { it.name.equals(event.name, ignoreCase = true) } ?: available.first()
                }
            }

            is InternalEvent.OnAudioCodecChange -> {
                require(projectionState.active == null) { "Cannot change codec while streaming" }

                selectedAudioEncoderInfo = null
                val available = EncoderUtils.availableAudioEncoders
                selectedAudioEncoderInfo = when {
                    available.isEmpty() -> {
                        if (rtspSettings.data.value.enableMic || rtspSettings.data.value.enableDeviceAudio) {
                            throw IllegalStateException("No suitable audio encoders available")
                        }
                        null
                    }
                    // Auto select
                    event.name.isNullOrBlank() -> available.first()

                    // We have saved codec, checking if it's available
                    else -> available.firstOrNull { it.name.equals(event.name, ignoreCase = true) } ?: available.first()
                }
            }

            is InternalEvent.ModeChanged -> {
                stopStream(true)

                sendEvent(InternalEvent.InitState(false))

                if (event.mode == RtspSettings.Values.Mode.SERVER) {
                    sendEvent(InternalEvent.RtspServer.DiscoverAddress(reason = "ModeChanged"))
                }
            }

            is InternalEvent.StartStream -> {
                if (projectionState.active != null) {
                    XLog.d(getLog("StartStream", "Already streaming. Ignoring."))
                    return
                }

                projectionState.cachedIntent?.let {
                    check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "RtspEvent.StartStream: UPSIDE_DOWN_CAKE" }
                    sendEvent(RtspEvent.StartProjection(it))
                } ?: run {
                    projectionState.waitingForPermission = true
                }
            }

            is InternalEvent.RetryBindings -> {
                if (rtspSettings.data.value.mode != RtspSettings.Values.Mode.SERVER) {
                    XLog.d(getLog("RetryBindings", "Not in server mode. Ignoring."))
                    return
                }
                if (projectionState.active != null) {
                    XLog.d(getLog("RetryBindings", "Streaming active. Ignoring."))
                    return
                }
                sendEvent(InternalEvent.RtspServer.DiscoverAddress(reason = "RetryBindings"))
            }

            is RtspEvent.CastPermissionsDenied -> projectionState.waitingForPermission = false

            is RtspEvent.StartProjection -> {
                projectionState.waitingForPermission = false

                if (projectionState.active != null) {
                    XLog.d(getLog("StartProjection", "Already streaming. Ignoring."))
                    return
                }
                check(selectedVideoEncoderInfo != null) { "No video encoder selected" }

                val settings = rtspSettings.data.value
                val audioEnabled = settings.enableMic || settings.enableDeviceAudio

                check((audioEnabled && selectedAudioEncoderInfo == null).not()) { "No audio encoder selected" }

                val modeLocal = settings.mode
                val serverController = if (modeLocal == RtspSettings.Values.Mode.SERVER) serverController else null
                val clientController = if (modeLocal == RtspSettings.Values.Mode.CLIENT) clientController else null
                var clientRtspUrl: RtspUrl? = null

                if (modeLocal == RtspSettings.Values.Mode.SERVER) {
                    check(serverController != null) { "RtspServer controller is null" }
                    check(serverController.isActive) { "RtspServer is not ready" }
                } else {
                    check(clientController != null) { "RtspClient controller is null" }

                    clientRtspUrl = try {
                        RtspUrl.parse(settings.serverAddress)
                    } catch (e: URISyntaxException) {
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(e)))
                        return
                    }
                }

                val setVideoParams: (VideoParams) -> Unit =
                    if (modeLocal == RtspSettings.Values.Mode.SERVER) {
                        { video -> serverController?.setVideoParams(video) }
                    } else {
                        { video -> clientController?.setVideoParams(video) }
                    }

                val setAudioParams: (AudioParams?) -> Unit =
                    if (modeLocal == RtspSettings.Values.Mode.SERVER) {
                        { audio -> serverController?.setAudioParams(audio) }
                    } else {
                        { audio -> clientController?.setAudioParams(audio) }
                    }

                val onFrame: (MediaFrame) -> Unit =
                    if (modeLocal == RtspSettings.Values.Mode.SERVER) {
                        { frame -> serverController?.onFrame(frame) ?: frame.release() }
                    } else {
                        { frame -> clientController?.onFrame(frame) ?: frame.release() }
                    }

                val audioPermissionGranted =
                    ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                val wantsAudio = settings.enableMic || settings.enableDeviceAudio
                if (!audioPermissionGranted && wantsAudio) {
                    coroutineScope.launch {
                        rtspSettings.updateData { copy(enableMic = false, enableDeviceAudio = false) }
                    }
                }
                val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or if (audioPermissionGranted && wantsAudio) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
                service.startForeground(fgsType)

                // TODO Starting from Android R, if your application requests the SYSTEM_ALERT_WINDOW permission, and the user has
                //  not explicitly denied it, the permission will be automatically granted until the projection is stopped.
                //  The permission allows your app to display user controls on top of the screen being captured.
                val mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, event.intent)!!.apply {
                    registerCallback(projectionCallback, Handler(this@RtspStreamingService.looper))
                }

                MasterClock.reset()
                MasterClock.ensureStarted()

                var virtualDisplay: VirtualDisplay? = null
                val deviceConfiguration = Configuration(service.resources.configuration)
                val videoEncoderInfo = selectedVideoEncoderInfo!!

                val videoEncoder = VideoEncoder(
                    codecInfo = videoEncoderInfo,
                    onVideoInfo = { sps, pps, vps ->
                        val params = VideoParams(videoEncoderInfo.codec, sps, pps, vps)
                        projectionState.lastVideoParams = params
                        setVideoParams(params)
                    },
                    onVideoFrame = onFrame,
                    onFps = { sendEvent(InternalEvent.OnVideoFps(it)) },
                    onError = {
                        XLog.w(getLog("VideoEncoder.onError", it.message), it)
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(it)))
                    }
                ).apply {
                    val videoCapabilities = videoEncoderInfo.capabilities.videoCapabilities!!
                    val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                    val (_, width, height) = videoCapabilities.adjustResizeFactor(
                        bounds.width(), bounds.height(), settings.videoResizeFactor / 100
                    )

                    prepare(
                        width,
                        height,
                        fps = settings.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange()),
                        bitRate = settings.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())
                    )

                    this.inputSurfaceTexture?.let { surfaceTexture ->
                        virtualDisplay = mediaProjection.createVirtualDisplay(
                            "ScreenStreamVirtualDisplay",
                            width,
                            height,
                            service.resources.displayMetrics.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                            Surface(surfaceTexture),
                            null,
                            null
                        )
                    }

                    if (virtualDisplay == null) {
                        XLog.i(getLog("startDisplayCapture", "virtualDisplay is null. Stopping projection."))

                        stop()

                        mediaProjection.unregisterCallback(projectionCallback)
                        mediaProjection.stop()

                        stopStream(false)
                        return
                    }

                    start()
                }

                var audioEncoder: AudioEncoder? = null
                if (audioEnabled) {
                    val audioEncoderInfo = selectedAudioEncoderInfo!!
                    audioEncoder = AudioEncoder(
                        codecInfo = audioEncoderInfo,
                        onAudioInfo = { params ->
                            val audioParams = AudioParams(audioEncoderInfo.codec, params.sampleRate, params.isStereo)
                            projectionState.lastAudioParams = audioParams
                            setAudioParams(audioParams)
                        },
                        onAudioFrame = onFrame,
                        onAudioCaptureError = { sendEvent(InternalEvent.AudioCaptureError(it)) },
                        onError = {
                            XLog.w(getLog("AudioEncoder.onError", it.message), it)
                            sendEvent(InternalEvent.Error(RtspError.UnknownError(it)))
                        }
                    ).apply {
                        val requestedBitrate = settings.audioBitrateBits
                        val requestedStereo = settings.stereoAudio
                        val paramsFromSettings = when (audioEncoderInfo.codec) {
                            is Codec.Audio.G711 -> AudioSource.Params.DEFAULT_G711.copy(
                                bitrate = 64 * 1000,
                                echoCanceler = settings.audioEchoCanceller,
                                noiseSuppressor = settings.audioNoiseSuppressor
                            )

                            is Codec.Audio.OPUS -> AudioSource.Params.DEFAULT_OPUS.copy(
                                bitrate = requestedBitrate,
                                echoCanceler = settings.audioEchoCanceller,
                                noiseSuppressor = settings.audioNoiseSuppressor,
                                isStereo = true
                            )

                            else -> AudioSource.Params.DEFAULT.copy(
                                bitrate = requestedBitrate,
                                isStereo = requestedStereo,
                                echoCanceler = settings.audioEchoCanceller,
                                noiseSuppressor = settings.audioNoiseSuppressor
                            )
                        }

                        prepare(
                            enableMic = settings.enableMic,
                            enableDeviceAudio = settings.enableDeviceAudio,
                            dispatcher = Dispatchers.IO,
                            audioParams = paramsFromSettings,
                            audioSource = MediaRecorder.AudioSource.DEFAULT,
                            mediaProjection = mediaProjection,
                        )

                        setMute(settings.muteMic, settings.muteDeviceAudio)
                        setVolume(settings.volumeMic, settings.volumeDeviceAudio)

                        start()
                    }
                }

                if (modeLocal == RtspSettings.Values.Mode.CLIENT) {
                    val onlyVideo = audioCaptureDisabled || (settings.enableMic.not() && settings.enableDeviceAudio.not())
                    clientController?.startClient(clientRtspUrl!!, onlyVideo)
                }

                projectionState.active = ActiveProjection(
                    mediaProjection = mediaProjection,
                    virtualDisplay = virtualDisplay!!,
                    videoEncoder = videoEncoder,
                    audioEncoder = audioEncoder,
                    deviceConfiguration = deviceConfiguration
                )

                if (modeLocal == RtspSettings.Values.Mode.SERVER) {
                    serverController?.start(coroutineScope)
                }
                if (modeLocal == RtspSettings.Values.Mode.CLIENT) {
                    clientController?.connect()
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    projectionState.cachedIntent = event.intent
                    service.registerComponentCallbacks(componentCallback)
                }
            }

            is InternalEvent.OnAudioParamsChange -> {
                projectionState.active?.audioEncoder?.setVolume(event.micVolume, event.deviceVolume)
                projectionState.active?.audioEncoder?.setMute(event.micMute, event.deviceMute)
            }

            is InternalEvent.AudioCaptureError -> {
                if (audioCaptureDisabled) return

                audioCaptureDisabled = true
                projectionState.lastAudioParams = null
                projectionState.active?.audioEncoder?.stop()
                projectionState.active = projectionState.active?.copy(audioEncoder = null)
                serverController?.setAudioParams(null)
                clientController?.setAudioParams(null)
                showAudioCaptureIssueToastOnce()
            }

            is InternalEvent.ConfigurationChange -> {
                val projection = projectionState.active ?: run {
                    XLog.d(getLog("ConfigurationChange", "Not streaming. Ignoring."))
                    return
                }

                if (rtspSettings.data.value.stopOnConfigurationChange) { //TODO Not yet exposed in UI
                    sendEvent(RtspEvent.Intentable.StopStream("ConfigurationChange"))
                    return
                }

                val newConfig = Configuration(event.newConfig)
                val configDiff = projection.deviceConfiguration.diff(newConfig)
                projectionState.active = projection.copy(deviceConfiguration = newConfig)
                if (configDiff and ActivityInfo.CONFIG_ORIENTATION != 0
                    || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0
                    || configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0
                    || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                ) {
                    val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                    scheduleVideoReconfigure(projection, bounds.width(), bounds.height(), source = "ConfigurationChange")
                } else {
                    XLog.d(getLog("ConfigurationChange", "No change relevant for streaming. Ignoring."))
                }
            }

            is InternalEvent.CapturedContentResize -> {
                val projection = projectionState.active ?: run {
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

                scheduleVideoReconfigure(projection, event.width, event.height, source = "CapturedContentResize")
            }

            is InternalEvent.ApplyVideoReconfigure -> {
                val projection = projectionState.active ?: run {
                    XLog.d(getLog("ApplyVideoReconfigure", "Not streaming. Ignoring."))
                    return
                }

                if (event.width == projection.videoEncoder.width && event.height == projection.videoEncoder.height) {
                    XLog.d(getLog("ApplyVideoReconfigure", "Same size ${event.width}x${event.height}. Ignoring."))
                    return
                }

                val videoCapabilities = selectedVideoEncoderInfo!!.capabilities.videoCapabilities!!
                projection.reconfigureVideo(
                    width = event.width,
                    height = event.height,
                    fps = rtspSettings.data.value.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange()),
                    bitRate = rtspSettings.data.value.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange()),
                    densityDpi = service.resources.displayMetrics.densityDpi
                )
            }

            is RtspEvent.Intentable.StopStream -> stopStream(false)

            is RtspEvent.Intentable.RecoverError,
            is InternalEvent.Destroy,
            is InternalEvent.Error -> {
                stopStream(true)

                if (event is InternalEvent.Error) {
                    currentError = event.error
                    if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                        serverController?.isActive = false
                    } else {
                        clientController?.status = RtspClientStatus.ERROR
                    }
                }

                if (event is RtspEvent.Intentable.RecoverError) {
                    handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
                    sendEvent(InternalEvent.InitState(true))
                    if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                        sendEvent(InternalEvent.RtspServer.DiscoverAddress(reason = "RecoverError"))
                    }
                }
            }

            is InternalEvent.RtspClient -> {
                val clientController = clientController ?: run {
                    XLog.d(getLog("RtspClient:${event::class.simpleName}", "Controller is null. Ignoring."))
                    return
                }
                clientController.onEvent(event)
            }

            is InternalEvent.RtspServer -> {
                val serverController = serverController ?: run {
                    XLog.d(getLog("RtspServer:${event::class.simpleName}", "Controller is null. Ignoring."))
                    return
                }
                serverController.onEvent(event)
            }

            is InternalEvent.OnVideoFps -> Unit //TODO Skipp for now

            else -> throw IllegalArgumentException("Unknown RtspEvent: ${event::class.java}")
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream(stopServer: Boolean) {
        handler.removeMessages(RtspEvent.Priority.VIDEO_RECONFIGURE_IGNORE)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { service.unregisterComponentCallbacks(componentCallback) }
        }

        audioCaptureDisabled = false
        audioIssueToastShown = false

        clientController?.stop()
        serverController?.stop(stopServer)

        projectionState.active?.stop(projectionCallback)
        projectionState.active = null
        projectionState.waitingForPermission = false
        projectionState.lastVideoParams = null
        projectionState.lastAudioParams = null

        service.stopForeground()
    }

    private fun showAudioCaptureIssueToastOnce() {
        if (audioIssueToastShown) return
        audioIssueToastShown = true
        mainHandler.post { Toast.makeText(service, R.string.rtsp_audio_capture_issue_detected, Toast.LENGTH_LONG).show() }
    }
}
