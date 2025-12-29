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
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import info.dvkr.screenstream.rtsp.internal.rtsp.server.NetworkHelper
import info.dvkr.screenstream.rtsp.internal.rtsp.server.RtspServer
import info.dvkr.screenstream.rtsp.internal.video.VideoEncoder
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspBinding
import info.dvkr.screenstream.rtsp.ui.RtspError
import info.dvkr.screenstream.rtsp.ui.RtspModeState
import info.dvkr.screenstream.rtsp.ui.RtspState
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

    // All vars must be read/write on this (RTSP_HT) thread
    private var selectedVideoEncoderInfo: VideoCodecInfo? = null
    private var selectedAudioEncoderInfo: AudioCodecInfo? = null
    private var modeState: RtspModeState = RtspModeState(mode = rtspSettings.data.value.mode)
    private var rtspClient: RtspClient? = null
    private var clientGeneration: Long = 0L
    private var rtspServer: RtspServer? = null
    private var serverGeneration: Long = 0L
    private var waitingForPermission: Boolean = false
    private var mediaProjectionIntent: Intent? = null
    private var activeProjection: ActiveProjection? = null
    private var lastVideoParams: VideoParams? = null
    private var lastAudioParams: AudioParams? = null

    private var currentError: RtspError? = null
    private var previousError: RtspError? = null
    private var statsHeartbeatJob: Job? = null
    // All vars must be read/write on this (RTSP_HT) thread

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
    }

    internal sealed class InternalEvent(priority: Int) : RtspEvent(priority) {
        data class InitState(val clearIntent: Boolean) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnVideoCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnAudioCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class ModeChanged(val mode: RtspSettings.Values.Mode) : InternalEvent(Priority.RECOVER_IGNORE)
        data class DiscoverAddress(val reason: String, val attempt: Int) : InternalEvent(Priority.RECOVER_IGNORE)
        data object StartStream : InternalEvent(Priority.RECOVER_IGNORE)

        data class OnAudioParamsChange(val micMute: Boolean, val deviceMute: Boolean, val micVolume: Float, val deviceVolume: Float) :
            InternalEvent(Priority.DESTROY_IGNORE)

        data class ConfigurationChange(val newConfig: Configuration) : InternalEvent(Priority.RECOVER_IGNORE) {
            override fun toString(): String = "ConfigurationChange"
        }

        data class CapturedContentResize(val width: Int, val height: Int) : InternalEvent(Priority.RECOVER_IGNORE)
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

            data class OnStart(override val generation: Long) : RtspServer(Priority.RECOVER_IGNORE)
            data class OnStop(override val generation: Long) : RtspServer(Priority.DESTROY_IGNORE)
        }

        data class OnVideoFps(val fps: Int) : InternalEvent(Priority.DESTROY_IGNORE)
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

        sendEvent(InternalEvent.InitState(true))

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
        rtspSettings.data.map { it.serverProtocol }.listenForChange(coroutineScope, 1) {
            if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                XLog.i(getLog("SettingsChanged", "ServerProtocol -> ${it}"))
                sendEvent(InternalEvent.DiscoverAddress("SettingsChanged:ServerProtocol", 0))
            }
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

        if (event is RtspEvent.Intentable.RecoverError) {
            handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(RtspEvent.Priority.DESTROY_IGNORE)
        }

        val wasSent = handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
        if (!wasSent) XLog.e(getLog("sendEvent", "Failed to send event: $event"))
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking(Dispatchers.Unconfined) {
        val event: RtspEvent = msg.obj as RtspEvent
        try {
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@RtspStreamingService.getLog("handleMessage.catch", cause.toString()), cause)

            mediaProjectionIntent = null
            waitingForPermission = false
            stopStream(true)

            currentError = RtspError.UnknownError(cause)
            modeState = modeState.copy(status = RtspModeState.Status.Error(currentError!!))
        } finally {
            if (event is InternalEvent.Destroy) event.destroyJob.complete()

            val isBusy = when (modeState.mode) {
                RtspSettings.Values.Mode.SERVER -> modeState.status !is RtspModeState.Status.Server.Active
                RtspSettings.Values.Mode.CLIENT -> {
                    val audioEnabled = rtspSettings.data.value.enableMic || rtspSettings.data.value.enableDeviceAudio
                    val videoReady = selectedVideoEncoderInfo != null
                    val audioReady = audioEnabled.not() || selectedAudioEncoderInfo != null
                    (videoReady && audioReady).not()
                }
            }

            mutableRtspStateFlow.value = RtspState(
                isBusy = destroyPending || waitingForPermission || currentError != null || isBusy,
                waitingCastPermission = waitingForPermission,
                isStreaming = activeProjection != null,
                selectedVideoEncoder = selectedVideoEncoderInfo,
                selectedAudioEncoder = selectedAudioEncoderInfo,
                modeState = modeState,
                serverClientStats = rtspServer?.getClientStatsSnapshot().orEmpty(),
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
                modeState = RtspModeState(mode = rtspSettings.data.value.mode)
                rtspClient = null
                rtspServer = null
                waitingForPermission = false
                if (event.clearIntent) mediaProjectionIntent = null
                activeProjection = null
                lastVideoParams = null
                lastAudioParams = null

                currentError = null
                previousError = null
                statsHeartbeatJob = null
            }

            is InternalEvent.OnVideoCodecChange -> {
                require(activeProjection == null) { "Cannot change codec while streaming" }

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
                require(activeProjection == null) { "Cannot change codec while streaming" }

                selectedAudioEncoderInfo = null
                val available = EncoderUtils.availableAudioEncoders
                selectedAudioEncoderInfo = when {
                    available.isEmpty() -> {
                        if (rtspSettings.data.value.enableMic || rtspSettings.data.value.enableDeviceAudio) {
                            currentError = RtspError.UnknownError(IllegalStateException("No suitable audio encoders available"))
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
                if (modeState.mode == event.mode) return

                stopStream(true)

                modeState = RtspModeState(mode = event.mode)
                sendEvent(InternalEvent.InitState(false))
                if (event.mode == RtspSettings.Values.Mode.SERVER) {
                    sendEvent(InternalEvent.DiscoverAddress("ModeChanged", 0))
                }
            }

            is InternalEvent.DiscoverAddress -> {
                rtspServer?.disconnectAllClients()
                rtspServer?.stop()
                rtspServer = null

                if (modeState.mode == RtspSettings.Values.Mode.SERVER) {
                    runCatching {
                        val netInterfaces = networkHelper.getNetInterfaces(
                            rtspSettings.data.value.interfaceFilter,
                            rtspSettings.data.value.addressFilter,
                            rtspSettings.data.value.enableIPv4,
                            rtspSettings.data.value.enableIPv6,
                        )

                        XLog.d(getLog("DiscoverAddress", "${netInterfaces.size} interfaces discovered (${event.reason})"))

                        if (netInterfaces.isEmpty()) {
                            if (event.attempt < 3) {
                                sendEvent(InternalEvent.DiscoverAddress(event.reason, event.attempt + 1), 1000)
                            } else {
                                XLog.w(getLog("RtspServer", "No interfaces to bind. Stopping stream and give up."))
                                stopStream(true)
                                modeState = RtspModeState(
                                    mode = RtspSettings.Values.Mode.SERVER,
                                    status = RtspModeState.Status.Error(RtspError.ServerError.AddressNotFoundException())
                                )
                                currentError = RtspError.ServerError.AddressNotFoundException()
                            }
                        } else {
                            if (currentError is RtspError.ServerError.AddressNotFoundException) currentError = null

                            val port = rtspSettings.data.value.serverPort
                            val path = rtspSettings.data.value.serverPath
                            val protocolPolicy = rtspSettings.data.value.serverProtocol

                            rtspServer = RtspServer(
                                appVersion = service.getVersionName(),
                                generation = ++serverGeneration,
                                onEvent = {
                                    XLog.d(getLog("RtspServer.onEvent", it.toString()))
                                    sendEvent(it)
                                },
                                onRequestKeyFrame = {
                                    coroutineScope.launch { activeProjection?.videoEncoder?.requestKeyFrame() }
                                }
                            ).apply {
                                start(netInterfaces.toList(), port, path, protocolPolicy)
                            }

                            lastVideoParams?.let { params ->
                                rtspServer?.setVideoData(params.codec, params.sps, params.pps, params.vps)
                                coroutineScope.launch { activeProjection?.videoEncoder?.requestKeyFrame() }
                            }
                            lastAudioParams?.let { params ->
                                rtspServer?.setAudioData(params)
                            }

                            val bindings = netInterfaces.map { RtspBinding(label = it.label, fullAddress = it.buildUrl(port, path)) }

                            modeState = RtspModeState(
                                mode = RtspSettings.Values.Mode.SERVER,
                                status = RtspModeState.Status.Server.Starting(bindings)
                            )

                            XLog.d(getLog("RtspServer", "(Re)start on ${netInterfaces.size} interfaces, protocol=$protocolPolicy"))
                        }
                    }.onFailure {
                        XLog.w(getLog("DiscoverAddress", "Failed: ${it.message}"), it)
                        stopStream(true)
                        modeState = RtspModeState(
                            mode = RtspSettings.Values.Mode.SERVER,
                            status = RtspModeState.Status.Error(RtspError.UnknownError(it))
                        )
                        currentError = RtspError.UnknownError(it)
                    }
                }
            }

            is InternalEvent.StartStream -> {
                mediaProjectionIntent?.let {
                    check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "RtspEvent.StartStream: UPSIDE_DOWN_CAKE" }
                    if (modeState.mode == RtspSettings.Values.Mode.SERVER && modeState.status is RtspModeState.Status.Server.Active) {
                        sendEvent(RtspEvent.StartProjection(it))
                    } else {
                        throw IllegalStateException("StartStream called for Serve in state: ${modeState.status}")
                    }

                    if (modeState.mode == RtspSettings.Values.Mode.CLIENT) {
                        sendEvent(RtspEvent.StartProjection(it))
                    }
                } ?: run {
                    waitingForPermission = true
                }
            }

            is RtspEvent.CastPermissionsDenied -> waitingForPermission = false

            is RtspEvent.StartProjection -> {
                waitingForPermission = false

                if (activeProjection != null) {
                    sendEvent(InternalEvent.Error(RtspError.UnknownError(IllegalStateException("Already streaming"))))
                    return
                }

                if (selectedVideoEncoderInfo == null) {
                    sendEvent(InternalEvent.Error(RtspError.UnknownError(IllegalStateException("No video encoder selected"))))
                    return
                }

                val audioEnabled = rtspSettings.data.value.enableMic || rtspSettings.data.value.enableDeviceAudio
                if (audioEnabled && selectedAudioEncoderInfo == null) {
                    sendEvent(InternalEvent.Error(RtspError.UnknownError(IllegalStateException("No suitable audio encoders available"))))
                    return
                }

                val modeStateLocal = modeState

                if (modeStateLocal.mode == RtspSettings.Values.Mode.SERVER) {
                    if (rtspServer == null) {
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(IllegalStateException("RtspServer is null"))))
                        return
                    }

                    if (modeStateLocal.status !is RtspModeState.Status.Server.Active) {
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(IllegalStateException("RtspServer is not ready"))))
                        return
                    }
                } else {
                    val rtspUrl = try {
                        RtspUrl.parse(rtspSettings.data.value.serverAddress)
                    } catch (e: URISyntaxException) {
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(e)))
                        return
                    }
                    val onlyVideo = rtspSettings.data.value.enableMic.not() && rtspSettings.data.value.enableDeviceAudio.not()

                    rtspClient = RtspClient(
                        appVersion = service.getVersionName(),
                        generation = ++clientGeneration,
                        rtspUrl = rtspUrl,
                        protocolPolicy = rtspSettings.data.value.clientProtocol,
                        onlyVideo = onlyVideo
                    ) {
                        XLog.d(getLog("RtspClient.sendEvent", it.toString()))
                        sendEvent(it)
                    }
                }

                service.startForeground()

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
                        lastVideoParams = VideoParams(videoEncoderInfo.codec, sps, pps, vps)
                        if (modeStateLocal.mode == RtspSettings.Values.Mode.SERVER) {
                            rtspServer?.setVideoData(videoEncoderInfo.codec, sps, pps, vps)
                        } else {
                            rtspClient?.setVideoData(videoEncoderInfo.codec, sps, pps, vps)
                        }
                    },
                    onVideoFrame = { frame ->
                        if (modeStateLocal.mode == RtspSettings.Values.Mode.SERVER) {
                            rtspServer?.onVideoFrame(frame) ?: frame.release()
                        } else {
                            rtspClient?.enqueueFrame(frame) ?: frame.release()
                        }
                    },
                    onFps = { sendEvent(InternalEvent.OnVideoFps(it)) },
                    onError = {
                        XLog.w(getLog("VideoEncoder.onError", it.message), it)
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(it)))
                    }
                ).apply {
                    val videoCapabilities = videoEncoderInfo.capabilities.videoCapabilities!!
                    val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                    val (_, width, height) = videoCapabilities.adjustResizeFactor(
                        bounds.width(), bounds.height(), rtspSettings.data.value.videoResizeFactor / 100
                    )

                    prepare(
                        width,
                        height,
                        fps = rtspSettings.data.value.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange()),
                        bitRate = rtspSettings.data.value.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())
                    )

                    this.inputSurfaceTexture?.let { surfaceTexture ->
                        virtualDisplay = mediaProjection.createVirtualDisplay(
                            "ScreenStreamVirtualDisplay",
                            width,
                            height,
                            service.resources.displayMetrics.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            Surface(surfaceTexture),
                            null,
                            null
                        )
                    }

                    if (virtualDisplay == null) {
                        XLog.w(getLog("startDisplayCapture", "virtualDisplay is null"))

                        rtspClient?.destroy(); rtspClient = null

                        stop()

                        mediaProjection.unregisterCallback(projectionCallback)
                        mediaProjection.stop()

                        service.stopForeground()
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(IllegalStateException("virtualDisplay is null"))))
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
                            lastAudioParams = AudioParams(audioEncoderInfo.codec, params.sampleRate, params.isStereo)
                            if (modeStateLocal.mode == RtspSettings.Values.Mode.SERVER) {
                                rtspServer?.setAudioData(lastAudioParams!!)
                            } else {
                                rtspClient?.setAudioData(audioEncoderInfo.codec, params)
                            }
                        },
                        onAudioFrame = { frame ->
                            if (modeStateLocal.mode == RtspSettings.Values.Mode.SERVER) {
                                rtspServer?.onAudioFrame(frame) ?: frame.release()
                            } else {
                                rtspClient?.enqueueFrame(frame) ?: frame.release()
                            }
                        },
                        onError = {
                            XLog.w(getLog("AudioEncoder.onError", it.message), it)
                            sendEvent(InternalEvent.Error(RtspError.UnknownError(it)))
                        }
                    ).apply {
                        val settings = rtspSettings.data.value
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
                            enableMic = rtspSettings.data.value.enableMic,
                            enableDeviceAudio = rtspSettings.data.value.enableDeviceAudio,
                            dispatcher = Dispatchers.IO,
                            audioParams = paramsFromSettings,
                            audioSource = MediaRecorder.AudioSource.DEFAULT,
                            mediaProjection = mediaProjection,
                        )

                        setMute(rtspSettings.data.value.muteMic, rtspSettings.data.value.muteDeviceAudio)
                        setVolume(rtspSettings.data.value.volumeMic, rtspSettings.data.value.volumeDeviceAudio)

                        start()
                    }
                }

                activeProjection = ActiveProjection(
                    mediaProjection = mediaProjection,
                    virtualDisplay = virtualDisplay!!,
                    videoEncoder = videoEncoder,
                    audioEncoder = audioEncoder,
                    deviceConfiguration = deviceConfiguration
                )

                if (modeStateLocal.mode == RtspSettings.Values.Mode.CLIENT) {
                    modeState = modeStateLocal.copy(status = RtspModeState.Status.Client.Starting)
                    rtspClient!!.connect()
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    mediaProjectionIntent = event.intent
                    service.registerComponentCallbacks(componentCallback)
                }

                statsHeartbeatJob?.cancel()
                statsHeartbeatJob = coroutineScope.launch {
                    while (isActive) {
                        if (activeProjection != null && modeStateLocal.mode == RtspSettings.Values.Mode.SERVER) {
                            val stats = rtspServer?.getClientStatsSnapshot().orEmpty()
                            mutableRtspStateFlow.value = mutableRtspStateFlow.value.copy(serverClientStats = stats)
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }

            is InternalEvent.OnAudioParamsChange -> {
                activeProjection?.audioEncoder?.setVolume(event.micVolume, event.deviceVolume)
                activeProjection?.audioEncoder?.setMute(event.micMute, event.deviceMute)
            }

            is InternalEvent.ConfigurationChange -> {
                val projection = activeProjection ?: run {
                    XLog.d(getLog("ConfigurationChange", "Not streaming. Ignoring."))
                    return
                }

                if (rtspSettings.data.value.stopOnConfigurationChange) {
                    sendEvent(RtspEvent.Intentable.StopStream("ConfigurationChange"))
                    return
                }
                val newConfig = Configuration(event.newConfig)
                val configDiff = projection.deviceConfiguration.diff(newConfig)
                if (configDiff and ActivityInfo.CONFIG_ORIENTATION != 0
                    || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0
                    || configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0
                    || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                    ) {
                    val videoCapabilities = selectedVideoEncoderInfo!!.capabilities.videoCapabilities!!
                    val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                    val (_, width, height) = videoCapabilities.adjustResizeFactor(
                        bounds.width(), bounds.height(), rtspSettings.data.value.videoResizeFactor / 100
                    )
                    val fps = rtspSettings.data.value.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange())
                    val bitRate = rtspSettings.data.value.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())
                    projection.videoEncoder.stop()
                    projection.virtualDisplay.surface?.release()
                    projection.videoEncoder.prepare(width, height, fps, bitRate)
                    projection.virtualDisplay.resize(width, height, service.resources.displayMetrics.densityDpi)
                    projection.virtualDisplay.surface = Surface(projection.videoEncoder.inputSurfaceTexture)
                    projection.videoEncoder.start()
                    activeProjection = projection.copy(deviceConfiguration = newConfig)
                } else {
                    XLog.d(getLog("ConfigurationChange", "No change relevant for streaming. Ignoring."))
                }
            }

            is InternalEvent.CapturedContentResize -> {
                val projection = activeProjection ?: run {
                    XLog.d(getLog("CapturedContentResize", "Not streaming. Ignoring."))
                    return
                }

                val videoCapabilities = selectedVideoEncoderInfo!!.capabilities.videoCapabilities!!
                val (_, width, height) = videoCapabilities.adjustResizeFactor(
                    event.width, event.height, rtspSettings.data.value.videoResizeFactor / 100
                )

                if (width == projection.videoEncoder.width && height == projection.videoEncoder.height) {
                    XLog.w(getLog("CapturedContentResize", "No change relevant for streaming. Ignoring."))
                    return
                }

                val fps = rtspSettings.data.value.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange())
                val bitRate = rtspSettings.data.value.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())
                projection.videoEncoder.stop()
                projection.virtualDisplay.surface?.release()
                projection.videoEncoder.prepare(width, height, fps, bitRate)
                projection.virtualDisplay.resize(width, height, service.resources.displayMetrics.densityDpi)
                projection.virtualDisplay.surface = Surface(projection.videoEncoder.inputSurfaceTexture)
                projection.videoEncoder.start()
            }

            is RtspEvent.Intentable.StopStream -> {
                stopStream(false)
            }

            is RtspEvent.Intentable.RecoverError,
            is InternalEvent.Destroy,
            is InternalEvent.Error -> {
                stopStream(true)

                if (event is InternalEvent.Error) {
                    currentError = event.error
                    modeState = modeState.copy(status = RtspModeState.Status.Error(event.error))
                }

                if (event is RtspEvent.Intentable.RecoverError) {
                    handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
                    sendEvent(InternalEvent.InitState(true))
                }
            }

            is InternalEvent.RtspClient -> {
                if (event.generation != clientGeneration) {
                    XLog.d(getLog("RtspClient:${event::class.simpleName}", "Stale generation=${event.generation}. Ignoring."))
                    return
                }
                if (modeState.mode == RtspSettings.Values.Mode.CLIENT) {
                    when (event) {
                        is InternalEvent.RtspClient.OnConnectionSuccess -> {
                            modeState = modeState.copy(status = RtspModeState.Status.Client.Active)
                        }

                        is InternalEvent.RtspClient.OnDisconnect ->
                            modeState = modeState.copy(status = RtspModeState.Status.Idle)

                        is InternalEvent.RtspClient.OnBitrate -> Unit //TODO Skip for now

                        is InternalEvent.RtspClient.OnError -> {
                            stopStream(true)
                            modeState = modeState.copy(status = RtspModeState.Status.Error(event.error))
                        }
                    }
                } else {
                    XLog.d(getLog("RtspClient:${event::class.simpleName}", "Not in client mode. Ignoring."))
                }
            }

            is InternalEvent.RtspServer -> {
                if (event.generation != serverGeneration) {
                    XLog.d(getLog("RtspServer:${event::class.simpleName}", "Stale generation=${event.generation}. Ignoring."))
                    return
                }
                if (modeState.mode == RtspSettings.Values.Mode.SERVER) {
                    when (event) {
                        is InternalEvent.RtspServer.OnStart -> {
                            val status = modeState.status
                            if (status is RtspModeState.Status.Server.Starting) {
                                modeState = modeState.copy(status = RtspModeState.Status.Server.Active(status.bindings))
                            } else {
                                XLog.w(getLog("RtspServerOnStart", "Not in Server.Starting status. Ignoring."))
                            }
                        }

                        is InternalEvent.RtspServer.OnStop -> {
                            // TODO drop to Idle ?????
                            modeState = modeState.copy(status = RtspModeState.Status.Idle)
                            //TODO What else to do ????
                        }
                    }
                } else {
                    XLog.d(getLog("RtspServer:${event::class.simpleName}", "Not in server mode. Ignoring."))
                }
            }

            is InternalEvent.OnVideoFps -> Unit //TODO Skipp for now

            else -> throw IllegalArgumentException("Unknown RtspEvent: ${event::class.java}")
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream(stopServer: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { service.unregisterComponentCallbacks(componentCallback) }
        }

        if (rtspClient != null) {
            clientGeneration++
            rtspClient?.destroy(); rtspClient = null
        }

        if (rtspServer != null) {
            rtspServer?.disconnectAllClients()

            if (modeState.mode != RtspSettings.Values.Mode.SERVER || stopServer) {
                serverGeneration++
                rtspServer?.stop(); rtspServer = null
            } else {
                rtspServer?.clearMediaParams()
            }
        }

        activeProjection?.stop(projectionCallback)
        activeProjection = null
        lastVideoParams = null
        lastAudioParams = null

        when (modeState.mode) {
            RtspSettings.Values.Mode.SERVER -> Unit
            RtspSettings.Values.Mode.CLIENT -> modeState = modeState.copy(status = RtspModeState.Status.Idle)
        }

        // Stop periodic stats updates and clear snapshot in UI state
        statsHeartbeatJob?.cancel(); statsHeartbeatJob = null
        mutableRtspStateFlow.value = mutableRtspStateFlow.value.copy(serverClientStats = emptyList())

        service.stopForeground()
    }
}
