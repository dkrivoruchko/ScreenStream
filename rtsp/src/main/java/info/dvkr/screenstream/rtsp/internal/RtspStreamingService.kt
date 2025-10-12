package info.dvkr.screenstream.rtsp.internal

import android.app.Activity
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.PowerManager
import android.view.Surface
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.util.toClosedRange
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.getVersionName
import info.dvkr.screenstream.rtsp.RtspModuleService
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.adjustResizeFactor
import info.dvkr.screenstream.rtsp.internal.audio.AudioEncoder
import info.dvkr.screenstream.rtsp.internal.audio.AudioSource
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspClient
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.internal.rtsp.server.NetworkHelper
import info.dvkr.screenstream.rtsp.internal.rtsp.server.RtspServer
import info.dvkr.screenstream.rtsp.internal.rtsp.server.RtspServerConnection
import info.dvkr.screenstream.rtsp.internal.video.VideoEncoder
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.ConnectionError
import info.dvkr.screenstream.rtsp.ui.RtspBinding
import info.dvkr.screenstream.rtsp.ui.RtspError
import info.dvkr.screenstream.rtsp.ui.RtspState
import info.dvkr.screenstream.rtsp.ui.RtspTransportState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
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

    private val projectionManager = service.application.getSystemService(MediaProjectionManager::class.java)
    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper, this) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("RTSP-HT_Dispatcher") }
    private val supervisorJob = SupervisorJob()
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(supervisorJob + coroutineDispatcher) }

    // All Volatiles vars must be write on this (RTSP_HT) thread
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null
    // All Volatiles vars must be write on this (RTSP_HT) thread

    // All vars must be read/write on this (RTSP_HT) thread
    private var selectedVideoEncoderInfo: VideoCodecInfo? = null
    private var selectedAudioEncoderInfo: AudioCodecInfo? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var rtspClient: RtspClient? = null
    private var rtspServer: RtspServer? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var deviceConfiguration: Configuration = Configuration(service.resources.configuration)
    private var isStreaming: Boolean = false
    private var transportStatus: RtspTransportState.Status = RtspTransportState.Status.Idle
    private var activeServerClients: Int = 0
    private var waitingForPermission: Boolean = false
    private var mediaProjectionIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var currentError: RtspError? = null
    private var previousError: RtspError? = null
    private var netInterfaces: List<info.dvkr.screenstream.rtsp.internal.rtsp.server.RtspNetInterface> = emptyList()
    private var statsHeartbeatJob: Job? = null
    // All vars must be read/write on this (RTSP_HT) thread

    private fun currentBindings(): List<RtspBinding> {
        if (netInterfaces.isEmpty()) return emptyList()
        val settings = rtspSettings.data.value
        val port = settings.serverPort
        val path = settings.serverPath.trimStart('/')
        return netInterfaces.map { netInterface ->
            val baseUrl = netInterface.buildUrl(port)
            val fullAddress = if (path.isEmpty()) baseUrl else "$baseUrl/$path"
            RtspBinding(label = netInterface.label, fullAddress = fullAddress)
        }
    }

    private fun updateTransportStatus(status: RtspTransportState.Status) {
        transportStatus = status
    }

    internal sealed class InternalEvent(priority: Int) : RtspEvent(priority) {
        data class InitState(val clearIntent: Boolean = true) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnVideoCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnAudioCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnAudioParamsChange(val micMute: Boolean, val deviceMute: Boolean, val micVolume: Float, val deviceVolume: Float) :
            InternalEvent(Priority.DESTROY_IGNORE)

        data class ModeChanged(val mode: RtspSettings.Values.Mode) : InternalEvent(Priority.RECOVER_IGNORE)
        data class DiscoverAddress(val reason: String, val attempt: Int) : InternalEvent(Priority.RECOVER_IGNORE)
        data object StartStream : InternalEvent(Priority.RECOVER_IGNORE)
        data class RtspClientOnError(val error: ConnectionError) : InternalEvent(Priority.RECOVER_IGNORE)
        data object RtspClientOnConnectionSuccess : InternalEvent(Priority.RECOVER_IGNORE)
        data object RtspClientOnDisconnect : InternalEvent(Priority.DESTROY_IGNORE)
        data class RtspClientOnBitrate(val bitrate: Long) : InternalEvent(Priority.DESTROY_IGNORE)
        data object RtspServerOnStart : InternalEvent(Priority.RECOVER_IGNORE)
        data object RtspServerOnStop : InternalEvent(Priority.DESTROY_IGNORE)
        data class RtspServerOnClientConnected(val rtspServerConnection: RtspServerConnection) : InternalEvent(Priority.DESTROY_IGNORE)
        data class RtspServerOnClientDisconnected(val rtspServerConnection: RtspServerConnection) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnVideoFps(val fps: Int) : InternalEvent(Priority.DESTROY_IGNORE)

        data class ConfigurationChange(val newConfig: Configuration) : InternalEvent(Priority.RECOVER_IGNORE) {
            override fun toString(): String = "ConfigurationChange"
        }

        data class CapturedContentResize(val width: Int, val height: Int) : InternalEvent(Priority.RECOVER_IGNORE)
        data class Error(val error: RtspError) : InternalEvent(Priority.RECOVER_IGNORE)
        data class Destroy(val destroyJob: CompletableJob) : InternalEvent(Priority.DESTROY_IGNORE)
    }

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
            XLog.i(this@RtspStreamingService.getLog("MediaProjection.Callback", "onCapturedContentVisibilityChanged: $isVisible"))
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            XLog.i(this@RtspStreamingService.getLog("MediaProjection.Callback", "onCapturedContentResize: width: $width, height: $height"))
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

        mutableRtspStateFlow.value = RtspState()

        sendEvent(InternalEvent.InitState())

        fun <T> Flow<T>.listenForChange(scope: CoroutineScope, drop: Int = 0, action: suspend (T) -> Unit) =
            distinctUntilChanged().drop(drop).onEach { action(it) }.launchIn(scope)

        service.startListening(
            supervisorJob,
            onScreenOff = { if (rtspSettings.data.value.stopOnSleep) sendEvent(RtspEvent.Intentable.StopStream("ScreenOff")) },
            onConnectionChanged = {
                if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER)
                    sendEvent(InternalEvent.DiscoverAddress("ConnectionChanged", 0))
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

        rtspSettings.data.map { it.interfaceFilter }.listenForChange(coroutineScope, 1) {
            if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER)
                sendEvent(InternalEvent.DiscoverAddress("SettingsChanged:InterfaceFilter", 0))
        }
        rtspSettings.data.map { it.addressFilter }.listenForChange(coroutineScope, 1) {
            if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER)
                sendEvent(InternalEvent.DiscoverAddress("SettingsChanged:AddressFilter", 0))
        }
        rtspSettings.data.map { it.enableIPv4 }.listenForChange(coroutineScope, 1) {
            if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER)
                sendEvent(InternalEvent.DiscoverAddress("SettingsChanged:EnableIPv4", 0))
        }
        rtspSettings.data.map { it.enableIPv6 }.listenForChange(coroutineScope, 1) {
            if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER)
                sendEvent(InternalEvent.DiscoverAddress("SettingsChanged:EnableIPv6", 0))
        }

        rtspSettings.data.map { it.serverPort }.listenForChange(coroutineScope, 1) {
            if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                XLog.i(getLog("SettingsChanged", "ServerPort -> ${it}"))
                sendEvent(InternalEvent.DiscoverAddress("SettingsChanged:ServerPort", 0))
            }
        }
        rtspSettings.data.map { it.serverPath }.listenForChange(coroutineScope, 1) {
            if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                XLog.i(getLog("SettingsChanged", "ServerPath -> ${it}"))
                sendEvent(InternalEvent.DiscoverAddress("SettingsChanged:ServerPath", 0))
            }
        }
        rtspSettings.data.map { it.protocol }.listenForChange(coroutineScope, 1) {
            if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                XLog.i(getLog("SettingsChanged", "Protocol -> ${it}"))
                sendEvent(InternalEvent.DiscoverAddress("SettingsChanged:Protocol", 0))
            }
        }

        if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER)
            sendEvent(InternalEvent.DiscoverAddress("Start", 0))
    }

    @MainThread
    suspend fun destroyService() {
        XLog.d(getLog("destroyService"))

        wakeLock?.apply { if (isHeld) release() }
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

        if (event is RtspEvent.Intentable.RecoverError) {
            handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(RtspEvent.Priority.DESTROY_IGNORE)
        }

        handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking(Dispatchers.Unconfined) {
        val event: RtspEvent = msg.obj as RtspEvent
        try {
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@RtspStreamingService.getLog("handleMessage.catch", cause.toString()), cause)

            mediaProjectionIntent = null
            stopStream()

            currentError = RtspError.UnknownError(cause)
        } finally {
            if (event is InternalEvent.Destroy) event.destroyJob.complete()

            val settingsSnapshot = rtspSettings.data.value
            val statusForState = when (val status = transportStatus) {
                is RtspTransportState.Status.Active -> status.copy(bindings = currentBindings())
                is RtspTransportState.Status.Ready -> status.copy(bindings = currentBindings())
                else -> status
            }
            val resolvedStatus = when {
                currentError != null && statusForState !is RtspTransportState.Status.ClientError &&
                        statusForState !is RtspTransportState.Status.GenericError ->
                    RtspTransportState.Status.GenericError(currentError!!)

                else -> statusForState
            }

            val clientStatsSnapshot = if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                rtspServer?.getClientStatsSnapshot().orEmpty()
            } else emptyList()

            mutableRtspStateFlow.value = RtspState(
                isBusy = destroyPending || waitingForPermission || currentError != null ||
                        resolvedStatus is RtspTransportState.Status.Starting,
                waitingCastPermission = waitingForPermission,
                isStreaming = isStreaming,
                selectedVideoEncoder = selectedVideoEncoderInfo,
                selectedAudioEncoder = selectedAudioEncoderInfo,
                transport = RtspTransportState(
                    mode = settingsSnapshot.mode,
                    protocol = runCatching { Protocol.valueOf(settingsSnapshot.protocol) }.getOrDefault(Protocol.TCP),
                    status = resolvedStatus
                ),
                serverClientStats = clientStatsSnapshot,
                error = currentError
            )

            if (previousError != currentError) {
                previousError = currentError
                currentError?.let { service.showErrorNotification(it) } ?: service.hideErrorNotification()
            }
        }

        true
    }

    // On RTSP-HT only
    private fun processEvent(event: RtspEvent) {
        when (event) {
            is InternalEvent.InitState -> {
                virtualDisplay = null
                rtspClient = null
                rtspServer = null
                videoEncoder = null
                audioEncoder = null
                deviceConfiguration = Configuration(service.resources.configuration)
                updateTransportStatus(RtspTransportState.Status.Idle)
                activeServerClients = 0
                netInterfaces = emptyList()
                isStreaming = false
                waitingForPermission = false
                if (event.clearIntent) mediaProjectionIntent = null
                mediaProjection = null

                currentError = null
            }

            is InternalEvent.OnVideoCodecChange -> {
                require(isStreaming.not()) { "Cannot change codec while streaming" }

                selectedVideoEncoderInfo = null
                val available = EncoderUtils.availableVideoEncoders
                selectedVideoEncoderInfo = when {
                    available.isEmpty() -> {
                        currentError = RtspError.UnknownError(IllegalStateException("No suitable video encoders available"))
                        null
                    }
                    // Auto select
                    event.name.isNullOrBlank() -> available.first()

                    // We have saved codec, checking if it's available
                    else -> available.firstOrNull { it.name.equals(event.name, ignoreCase = true) } ?: available.first()
                }
            }

            is InternalEvent.OnAudioCodecChange -> {
                require(isStreaming.not()) { "Cannot change codec while streaming" }

                selectedAudioEncoderInfo = null
                selectedAudioEncoderInfo = when {
                    // Auto select
                    event.name.isNullOrBlank() -> EncoderUtils.availableAudioEncoders.first()

                    // We have saved codec, checking if it's available
                    else -> EncoderUtils.availableAudioEncoders
                        .firstOrNull { it.name.equals(event.name, ignoreCase = true) } ?: EncoderUtils.availableAudioEncoders.first()
                }
            }

            is InternalEvent.StartStream -> {
                mediaProjectionIntent?.let {
                    check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "RtspEvent.StartStream: UPSIDE_DOWN_CAKE" }
                    sendEvent(RtspEvent.StartProjection(it))
                } ?: run {
                    waitingForPermission = true
                }
            }

            is InternalEvent.DiscoverAddress -> {
                if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                    runCatching {
                        val newInterfaces = networkHelper.getNetInterfaces(
                            rtspSettings.data.value.interfaceFilter,
                            rtspSettings.data.value.addressFilter,
                            rtspSettings.data.value.enableIPv4,
                            rtspSettings.data.value.enableIPv6,
                        )
                        netInterfaces = newInterfaces
                        activeServerClients = 0
                        XLog.i(getLog("DiscoverAddress", "${newInterfaces.size} interfaces discovered (${event.reason})"))
                        if (rtspServer == null) rtspServer = RtspServer(service.getVersionName(), ::sendEvent)
                        val port = rtspSettings.data.value.serverPort
                        val path = rtspSettings.data.value.serverPath
                        val protocol = Protocol.valueOf(rtspSettings.data.value.protocol)
                        val bindings = currentBindings()
                        if (netInterfaces.isNotEmpty()) {
                            XLog.i(
                                getLog(
                                    "RtspServer",
                                    "(Re)start on ${netInterfaces.size} interfaces, port=$port, path=$path, protocol=$protocol"
                                )
                            )
                            updateTransportStatus(
                                if (isStreaming) RtspTransportState.Status.Active(activeServerClients, bindings)
                                else RtspTransportState.Status.Ready(bindings)
                            )
                            rtspServer!!.start(netInterfaces, port, path, protocol)
                        } else {
                            XLog.w(getLog("RtspServer", "No interfaces to bind. Stopping server to avoid stale listeners."))
                            updateTransportStatus(RtspTransportState.Status.Idle)
                            rtspServer?.stop()
                        }
                    }.onFailure {
                        XLog.w(getLog("DiscoverAddress", "Failed: ${it.message}"), it)
                    }
                }
            }

            is InternalEvent.ModeChanged -> {
                when (event.mode) {
                    RtspSettings.Values.Mode.SERVER -> {
                        sendEvent(InternalEvent.DiscoverAddress("SettingsChanged:Mode", 0))
                    }

                    RtspSettings.Values.Mode.CLIENT -> {
                        runCatching { rtspServer?.stop() }.onFailure {
                            XLog.w(getLog("ModeChanged", "Stop server failed: ${it.message}"), it)
                        }
                        rtspServer = null
                        netInterfaces = emptyList()
                        activeServerClients = 0
                        updateTransportStatus(RtspTransportState.Status.Idle)
                    }
                }
            }

            is RtspEvent.CastPermissionsDenied -> waitingForPermission = false

            is RtspEvent.StartProjection -> {
                waitingForPermission = false

                if (isStreaming) {
                    XLog.w(getLog("RtspEvent.StartProjection", "Already streaming"))
                    return
                }

                service.startForeground()

                // TODO Starting from Android R, if your application requests the SYSTEM_ALERT_WINDOW permission, and the user has
                //  not explicitly denied it, the permission will be automatically granted until the projection is stopped.
                //  The permission allows your app to display user controls on top of the screen being captured.
                mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, event.intent)!!.apply {
                    registerCallback(projectionCallback, Handler(this@RtspStreamingService.looper))
                }

                MasterClock.reset()
                MasterClock.ensureStarted()

                val onlyVideo = rtspSettings.data.value.enableMic.not() && rtspSettings.data.value.enableDeviceAudio.not()
                val isServerMode = rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER
                val protocol = Protocol.valueOf(rtspSettings.data.value.protocol)
                if (isServerMode) {
                    if (rtspServer == null) rtspServer = RtspServer(service.getVersionName(), ::sendEvent)
                    val port = rtspSettings.data.value.serverPort
                    val path = rtspSettings.data.value.serverPath
                    if (netInterfaces.isNotEmpty()) rtspServer!!.start(netInterfaces, port, path, protocol)
                } else {
                    val rtspUrl = try {
                        RtspUrl.parse(rtspSettings.data.value.serverAddress)
                    } catch (e: URISyntaxException) {
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(e)))
                        return
                    }
                    rtspClient = RtspClient(service.getVersionName(), rtspUrl, protocol, onlyVideo) {
                        XLog.w(getLog("RtspClient.sendEvent", it.toString()))
                        sendEvent(it)
                    }
                }

                if (selectedVideoEncoderInfo == null) { // TODO Maybe just send stop event
                    // Clean up projection and foreground if no encoder available
                    runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
                    runCatching { mediaProjection?.stop() }
                    mediaProjection = null
                    service.stopForeground()
                    sendEvent(InternalEvent.Error(RtspError.UnknownError(IllegalStateException("No video encoder selected"))))
                    return
                }

                fun ByteArray.stripAnnexBStartCode(): ByteArray = when {
                    size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte() ->
                        copyOfRange(4, size)

                    size >= 3 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 1.toByte() ->
                        copyOfRange(3, size)

                    else -> this
                }

                videoEncoder = VideoEncoder(
                    codecInfo = selectedVideoEncoderInfo!!,
                    onVideoInfo = { sps, pps, vps ->
                        if (isServerMode) {
                            val spsClean = sps.stripAnnexBStartCode()
                            val ppsClean = pps?.stripAnnexBStartCode()
                            val vpsClean = vps?.stripAnnexBStartCode()
                            rtspServer?.setVideoData(RtspClient.VideoParams(selectedVideoEncoderInfo!!.codec, spsClean, ppsClean, vpsClean))
                        } else {
                            val spsClean = sps.stripAnnexBStartCode()
                            val ppsClean = pps?.stripAnnexBStartCode()
                            val vpsClean = vps?.stripAnnexBStartCode()
                            rtspClient?.setVideoData(selectedVideoEncoderInfo!!.codec, spsClean, ppsClean, vpsClean)
                        }
                    },
                    onVideoFrame = { frame ->
                        if (isServerMode) rtspServer?.onVideoFrame(frame) else rtspClient?.enqueueFrame(frame)
                    },
                    onFps = { sendEvent(InternalEvent.OnVideoFps(it)) },
                    onError = {
                        XLog.w(getLog("VideoEncoder.onError", it.message), it)
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(it)))
                    }
                ).apply {
                    val videoCapabilities = selectedVideoEncoderInfo!!.capabilities.videoCapabilities!!
                    val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                    val (_, width, height) = videoCapabilities.adjustResizeFactor(
                        bounds.width(), bounds.height(), rtspSettings.data.value.videoResizeFactor / 100
                    )

                    val fps = rtspSettings.data.value.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange())
                    val bitRate = rtspSettings.data.value.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())
                    prepare(width, height, fps, bitRate)

                    if (inputSurfaceTexture == null) {
                        rtspClient?.destroy()
                        rtspClient = null

                        val readyStatus =
                            if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER && netInterfaces.isNotEmpty()) {
                                RtspTransportState.Status.Ready(currentBindings())
                            } else {
                                RtspTransportState.Status.Idle
                            }
                        updateTransportStatus(readyStatus)

                        videoEncoder?.stop()
                        videoEncoder = null

                        mediaProjection?.unregisterCallback(projectionCallback)
                        mediaProjection?.stop()
                        mediaProjection = null

                        service.stopForeground()
                        return
                    }

                    virtualDisplay?.release()
                    virtualDisplay?.surface?.release()
                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "ScreenStreamVirtualDisplay",
                        width,
                        height,
                        service.resources.displayMetrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        Surface(inputSurfaceTexture),
                        null,
                        null
                    )

                    if (virtualDisplay == null) {
                        XLog.w(getLog("startDisplayCapture", "virtualDisplay is null"))
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(IllegalStateException("virtualDisplay is null"))))
                        return
                    }

                    start()
                }

                if (onlyVideo) {
                    audioEncoder = null
                } else {
                    audioEncoder = AudioEncoder(
                        codecInfo = selectedAudioEncoderInfo!!,
                        onAudioInfo = { params ->
                            if (isServerMode) {
                                rtspServer?.setAudioData(
                                    RtspClient.AudioParams(selectedAudioEncoderInfo!!.codec, params.sampleRate, params.isStereo)
                                )
                            } else {
                                rtspClient?.setAudioData(selectedAudioEncoderInfo!!.codec, params)
                            }
                        },
                        onAudioFrame = { frame -> if (isServerMode) rtspServer?.onAudioFrame(frame) else rtspClient?.enqueueFrame(frame) },
                        onError = {
                            XLog.w(getLog("AudioEncoder.onError", it.message), it)
                            sendEvent(InternalEvent.Error(RtspError.UnknownError(it)))
                        }
                    ).apply {
                        val settings = rtspSettings.data.value
                        val requestedBitrate = settings.audioBitrateBits
                        val requestedStereo = settings.stereoAudio
                        val paramsFromSettings = when (selectedAudioEncoderInfo!!.codec) {
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
                            enableMic = rtspSettings.data.value.enableMic,
                            enableDeviceAudio = rtspSettings.data.value.enableDeviceAudio,
                            dispatcher = Dispatchers.IO,
                            audioParams = paramsFromSettings,
                            audioSource = MediaRecorder.AudioSource.DEFAULT,
                            mediaProjection = mediaProjection!!,
                        )

                        setMute(rtspSettings.data.value.muteMic, rtspSettings.data.value.muteDeviceAudio)
                        setVolume(rtspSettings.data.value.volumeMic, rtspSettings.data.value.volumeDeviceAudio)

                        start()
                    }
                }

                if (isServerMode.not()) {
                    updateTransportStatus(RtspTransportState.Status.Starting)
                    rtspClient!!.connect()
                } else {
                    updateTransportStatus(RtspTransportState.Status.Active(activeServerClients, currentBindings()))
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    mediaProjectionIntent = event.intent
                    service.registerComponentCallbacks(componentCallback)
                }

                this@RtspStreamingService.isStreaming = true
                statsHeartbeatJob?.cancel()
                statsHeartbeatJob = coroutineScope.launch {
                    while (isActive) {
                        if (isStreaming && rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                            val stats = rtspServer?.getClientStatsSnapshot().orEmpty()
                            mutableRtspStateFlow.value = mutableRtspStateFlow.value.copy(serverClientStats = stats)
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }

            is InternalEvent.OnAudioParamsChange -> {
                if (isStreaming.not()) return
                audioEncoder?.setVolume(event.micVolume, event.deviceVolume)
                audioEncoder?.setMute(event.micMute, event.deviceMute)
            }

            is InternalEvent.ConfigurationChange -> {
                if (isStreaming) {
                    if (rtspSettings.data.value.stopOnConfigurationChange) {
                        sendEvent(RtspEvent.Intentable.StopStream("ConfigurationChange"))
                        deviceConfiguration = Configuration(event.newConfig)
                        return
                    }
                    val configDiff = deviceConfiguration.diff(event.newConfig)
                    if (
                        configDiff and ActivityInfo.CONFIG_ORIENTATION != 0 || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0 ||
                        configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0 || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                    ) {
                        val videoCapabilities = selectedVideoEncoderInfo!!.capabilities.videoCapabilities!!
                        val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                        val (_, width, height) = videoCapabilities.adjustResizeFactor(
                            bounds.width(), bounds.height(), rtspSettings.data.value.videoResizeFactor / 100
                        )
                        val fps = rtspSettings.data.value.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange())
                        val bitRate = rtspSettings.data.value.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())
                        videoEncoder?.stop()
                        virtualDisplay?.surface?.release()
                        videoEncoder!!.prepare(width, height, fps, bitRate)
                        virtualDisplay?.resize(width, height, service.resources.displayMetrics.densityDpi)
                        virtualDisplay?.surface = Surface(videoEncoder!!.inputSurfaceTexture)
                        videoEncoder!!.start()
                    } else {
                        XLog.d(getLog("ConfigurationChange", "No change relevant for streaming. Ignoring."))
                    }
                } else {
                    XLog.d(getLog("ConfigurationChange", "Not streaming. Ignoring."))
                }
                deviceConfiguration = Configuration(event.newConfig)
            }

            is InternalEvent.CapturedContentResize -> {
                if (isStreaming) {
                    val videoCapabilities = selectedVideoEncoderInfo!!.capabilities.videoCapabilities!!
                    val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                    val (_, width, height) = videoCapabilities.adjustResizeFactor(
                        bounds.width(), bounds.height(), rtspSettings.data.value.videoResizeFactor / 100
                    )

                    if (width == videoEncoder!!.width && height == videoEncoder!!.height) {
                        XLog.e(getLog("CapturedContentResize", "No change relevant for streaming. Ignoring."))
                        return
                    }

                    val fps = rtspSettings.data.value.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange())
                    val bitRate = rtspSettings.data.value.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())
                    videoEncoder?.stop()
                    virtualDisplay?.surface?.release()
                    videoEncoder!!.prepare(width, height, fps, bitRate)
                    virtualDisplay?.resize(width, height, service.resources.displayMetrics.densityDpi)
                    virtualDisplay?.surface = Surface(videoEncoder!!.inputSurfaceTexture)
                    videoEncoder!!.start()
                } else {
                    XLog.d(getLog("CapturedContentResize", "Not streaming. Ignoring."))
                }
            }

            is RtspEvent.Intentable.RecoverError,
            is RtspEvent.Intentable.StopStream,
            is InternalEvent.Destroy,
            is InternalEvent.RtspClientOnError,
            is InternalEvent.Error -> {
                val forceStopServer = when (event) {
                    is RtspEvent.Intentable.StopStream -> false
                    else -> true
                }
                stopStream(forceStopServer)

                if (event is InternalEvent.RtspClientOnError) updateTransportStatus(RtspTransportState.Status.ClientError(event.error))
                if (event is InternalEvent.Error) {
                    currentError = event.error
                    updateTransportStatus(RtspTransportState.Status.GenericError(event.error))
                }

                if (event is RtspEvent.Intentable.RecoverError) {
                    handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
                    sendEvent(InternalEvent.InitState(true))
                }
            }

            is InternalEvent.RtspClientOnConnectionSuccess -> updateTransportStatus(RtspTransportState.Status.Active())

            is InternalEvent.RtspClientOnDisconnect -> updateTransportStatus(RtspTransportState.Status.Idle)

            is InternalEvent.RtspClientOnBitrate -> Unit //TODO
            is InternalEvent.OnVideoFps -> Unit //TODO

            is InternalEvent.RtspServerOnStart -> {
                val status = if (isStreaming) RtspTransportState.Status.Active(activeServerClients, currentBindings())
                else RtspTransportState.Status.Ready(currentBindings())
                updateTransportStatus(status)
            }

            is InternalEvent.RtspServerOnStop -> {
                activeServerClients = 0
                updateTransportStatus(RtspTransportState.Status.Idle)
            }

            is InternalEvent.RtspServerOnClientConnected -> {
                activeServerClients += 1
                updateTransportStatus(RtspTransportState.Status.Active(activeServerClients, currentBindings()))
                videoEncoder?.requestKeyFrame()
            }

            is InternalEvent.RtspServerOnClientDisconnected -> {
                activeServerClients = (activeServerClients - 1).coerceAtLeast(0)
                val status = if (isStreaming) RtspTransportState.Status.Active(activeServerClients, currentBindings())
                else RtspTransportState.Status.Ready(currentBindings())
                updateTransportStatus(status)
            }

            else -> throw IllegalArgumentException("Unknown RtspEvent: ${event::class.java}")
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream(stopServer: Boolean = false) {
        if (isStreaming) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.unregisterComponentCallbacks(componentCallback)
            }

            rtspClient?.destroy()
            rtspClient = null
            val isServerMode = rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER
            if (!isServerMode || stopServer) {
                rtspServer?.stop()
                rtspServer = null
            }

            videoEncoder?.stop()
            videoEncoder = null
            virtualDisplay?.release()
            virtualDisplay?.surface?.release()
            virtualDisplay = null

            audioEncoder?.stop()
            audioEncoder = null

            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null

            isStreaming = false
            activeServerClients = 0
            val statusAfterStop = when {
                isServerMode && stopServer.not() && netInterfaces.isNotEmpty() -> RtspTransportState.Status.Ready(currentBindings())
                else -> RtspTransportState.Status.Idle
            }
            updateTransportStatus(statusAfterStop)
        } else {
            XLog.d(getLog("stopStream", "Not streaming. Ignoring."))
        }

        // Stop periodic stats updates and clear snapshot in UI state
        statsHeartbeatJob?.cancel(); statsHeartbeatJob = null
        mutableRtspStateFlow.value = mutableRtspStateFlow.value.copy(serverClientStats = emptyList())

        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        service.stopForeground()
    }
}
