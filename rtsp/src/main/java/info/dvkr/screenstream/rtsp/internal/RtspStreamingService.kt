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
import info.dvkr.screenstream.rtsp.internal.video.VideoEncoder
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.ConnectionError
import info.dvkr.screenstream.rtsp.ui.ConnectionState
import info.dvkr.screenstream.rtsp.ui.RtspError
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URISyntaxException

internal class RtspStreamingService(
    private val service: RtspModuleService,
    private val mutableRtspStateFlow: MutableStateFlow<RtspState>,
    private val rtspSettings: RtspSettings
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
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var deviceConfiguration: Configuration = Configuration(service.resources.configuration)
    private var isStreaming: Boolean = false
    private var serverConnectionState: ConnectionState = ConnectionState.Disconnected
    private var waitingForPermission: Boolean = false
    private var mediaProjectionIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var currentError: RtspError? = null
    private var previousError: RtspError? = null
    // All vars must be read/write on this (RTSP_HT) thread

    internal sealed class InternalEvent(priority: Int) : RtspEvent(priority) {
        data class InitState(val clearIntent: Boolean = true) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnVideoCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnAudioCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data object StartStream : InternalEvent(Priority.RECOVER_IGNORE)
        data class RtspClientOnError(val error: ConnectionError) : InternalEvent(Priority.RECOVER_IGNORE)
        data object RtspClientOnConnectionSuccess : InternalEvent(Priority.RECOVER_IGNORE)
        data object RtspClientOnDisconnect : InternalEvent(Priority.DESTROY_IGNORE)
        data class RtspClientOnBitrate(val bitrate: Long) : InternalEvent(Priority.DESTROY_IGNORE)
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

        rtspSettings.data.map { it.videoCodecAutoSelect }.listenForChange(coroutineScope) {
            if (it) sendEvent(InternalEvent.OnVideoCodecChange(null))
            else sendEvent(InternalEvent.OnVideoCodecChange(rtspSettings.data.value.videoCodec))
        }

        rtspSettings.data.map { it.videoCodec }.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.OnVideoCodecChange(it))
        }

        rtspSettings.data.map { it.audioCodecAutoSelect }.listenForChange(coroutineScope) {
            if (it) sendEvent(InternalEvent.OnAudioCodecChange(null))
            else sendEvent(InternalEvent.OnAudioCodecChange(rtspSettings.data.value.audioCodec))
        }

        rtspSettings.data.map { it.audioCodec }.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.OnAudioCodecChange(it))
        }
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

            mutableRtspStateFlow.value = RtspState(
                isBusy = destroyPending || waitingForPermission || currentError != null,
                waitingCastPermission = waitingForPermission,
                isStreaming = isStreaming,
                selectedVideoEncoder = selectedVideoEncoderInfo,
                selectedAudioEncoder = selectedAudioEncoderInfo,
                connectionState = serverConnectionState,
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
                videoEncoder = null
                audioEncoder = null
                deviceConfiguration = Configuration(service.resources.configuration)
                serverConnectionState = ConnectionState.Disconnected
                isStreaming = false
                waitingForPermission = false
                if (event.clearIntent) mediaProjectionIntent = null
                mediaProjection = null

                currentError = null
            }

            is InternalEvent.OnVideoCodecChange -> {
                require(isStreaming.not()) { "Cannot change codec while streaming" }

                selectedVideoEncoderInfo = null
                selectedVideoEncoderInfo = when {
                    // Auto select
                    event.name.isNullOrEmpty() -> EncoderUtils.availableVideoEncoders.first()

                    // We have saved codec, checking if it's available
                    else -> EncoderUtils.availableVideoEncoders
                        .firstOrNull { it.name.equals(event.name, ignoreCase = true) } ?: EncoderUtils.availableVideoEncoders.first()
                }

                require(selectedVideoEncoderInfo != null) { "No valid video encoder found" }
            }

            is InternalEvent.OnAudioCodecChange -> {
                require(isStreaming.not()) { "Cannot change codec while streaming" }

                selectedAudioEncoderInfo = null
                selectedAudioEncoderInfo = when {
                    // Auto select
                    event.name.isNullOrEmpty() -> EncoderUtils.availableAudioEncoders.first()

                    // We have saved codec, checking if it's available
                    else -> EncoderUtils.availableAudioEncoders
                        .firstOrNull { it.name.equals(event.name, ignoreCase = true) } ?: EncoderUtils.availableAudioEncoders.first()
                }

                require(selectedAudioEncoderInfo != null) { "No valid audio encoder found" }
            }

            is InternalEvent.StartStream -> {
                mediaProjectionIntent?.let {
                    check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "RtspEvent.StartStream: UPSIDE_DOWN_CAKE" }
                    sendEvent(RtspEvent.StartProjection(it))
                } ?: run {
                    waitingForPermission = true
                }
            }

            is RtspEvent.CastPermissionsDenied -> waitingForPermission = false

            is RtspEvent.StartProjection -> {
                waitingForPermission = false

                if (isStreaming) {
                    XLog.w(getLog("RtspEvent.StartProjection", "Already streaming"))
                    return
                }

                val rtspUrl = try {
                    RtspUrl.parse(rtspSettings.data.value.serverAddress)
                } catch (e: URISyntaxException) {
                    sendEvent(InternalEvent.Error(RtspError.UnknownError(e)))
                    return
                }

                service.startForeground()

                // TODO Starting from Android R, if your application requests the SYSTEM_ALERT_WINDOW permission, and the user has
                //  not explicitly denied it, the permission will be automatically granted until the projection is stopped.
                //  The permission allows your app to display user controls on top of the screen being captured.
                mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, event.intent).apply {
                    registerCallback(projectionCallback, Handler(this@RtspStreamingService.looper))
                }

                MasterClock.reset()
                MasterClock.ensureStarted()

                val onlyVideo = rtspSettings.data.value.enableMic.not() && rtspSettings.data.value.enableDeviceAudio.not()

                rtspClient = RtspClient(service.getVersionName(), rtspUrl, Protocol.valueOf(rtspSettings.data.value.protocol), onlyVideo) {
                    XLog.w(getLog("RtspClient.sendEvent", it.toString()))
                    sendEvent(it)
                }

                videoEncoder = VideoEncoder(
                    codecInfo = selectedVideoEncoderInfo!!,
                    onVideoInfo = { sps, pps, vps -> rtspClient?.setVideoData(selectedVideoEncoderInfo!!.codec, sps, pps, vps) },
                    onVideoFrame = { frame -> rtspClient?.enqueueFrame(frame) },
                    onFps = { sendEvent(InternalEvent.OnVideoFps(it)) },
                    onError = {
                        XLog.e(getLog("VideoEncoder.onError", it.message), it)
                        sendEvent(InternalEvent.Error(RtspError.UnknownError(it)))
                    }
                ).apply {
                    val videoCapabilities = selectedVideoEncoderInfo!!.capabilities.videoCapabilities
                    val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                    val (_, width, height) = videoCapabilities.adjustResizeFactor(
                        bounds.width(), bounds.height(), rtspSettings.data.value.videoResizeFactor / 100
                    )

                    val fps = rtspSettings.data.value.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange())
                    val bitRate = rtspSettings.data.value.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())
                    prepare(width, height, fps, bitRate)

                    virtualDisplay?.release()
                    virtualDisplay?.surface?.release()
                    virtualDisplay = mediaProjection!!.createVirtualDisplay(
                        "ScreenStreamVirtualDisplay",
                        width,
                        height,
                        service.resources.displayMetrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        Surface(inputSurfaceTexture),
                        null,
                        null
                    )

                    start()
                }

                if (onlyVideo)
                    audioEncoder = null
                else
                    audioEncoder = AudioEncoder(
                        codecInfo = selectedAudioEncoderInfo!!,
                        onAudioInfo = { rtspClient?.setAudioData(selectedAudioEncoderInfo!!.codec, it) },
                        onAudioFrame = { frame -> rtspClient?.enqueueFrame(frame) },
                        onError = {
                            XLog.e(getLog("AudioEncoder.onError", it.message), it)
                            sendEvent(InternalEvent.Error(RtspError.UnknownError(it)))
                        }
                    ).apply {
                        prepare(
                            enableMic = rtspSettings.data.value.enableMic,
                            enableDeviceAudio = rtspSettings.data.value.enableDeviceAudio,
                            dispatcher = Dispatchers.IO,
                            audioParams = when (selectedAudioEncoderInfo!!.codec) {
                                is Codec.Audio.G711 -> AudioSource.Params.DEFAULT_G711
                                is Codec.Audio.OPUS -> AudioSource.Params.DEFAULT_OPUS
                                else -> AudioSource.Params.DEFAULT
                            },
                            audioSource = MediaRecorder.AudioSource.DEFAULT,
                            mediaProjection = mediaProjection!!,
                        )
                        start()
                    }

                rtspClient!!.connect()
                serverConnectionState = ConnectionState.Connecting

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    mediaProjectionIntent = event.intent
                    service.registerComponentCallbacks(componentCallback)
                }

                this@RtspStreamingService.isStreaming = true
            }

            is InternalEvent.ConfigurationChange -> {
                if (isStreaming) {
                    val configDiff = deviceConfiguration.diff(event.newConfig)
                    if (
                        configDiff and ActivityInfo.CONFIG_ORIENTATION != 0 || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0 ||
                        configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0 || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                    ) {
                        val videoCapabilities = selectedVideoEncoderInfo!!.capabilities.videoCapabilities
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
                    val videoCapabilities = selectedVideoEncoderInfo!!.capabilities.videoCapabilities
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
                stopStream()

                if (event is InternalEvent.RtspClientOnError) serverConnectionState = ConnectionState.Error(event.error)
                if (event is InternalEvent.Error) currentError = event.error

                if (event is RtspEvent.Intentable.RecoverError) {
                    handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
                    sendEvent(InternalEvent.InitState(true))
                }
            }

            is InternalEvent.RtspClientOnConnectionSuccess -> {
                serverConnectionState = ConnectionState.Connected
            }

            is InternalEvent.RtspClientOnDisconnect -> {
                serverConnectionState = ConnectionState.Disconnected
            }

            is InternalEvent.RtspClientOnBitrate -> Unit //TODO
            is InternalEvent.OnVideoFps -> Unit //TODO

            else -> throw IllegalArgumentException("Unknown RtspEvent: ${event::class.java}")
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream() {
        if (isStreaming) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.unregisterComponentCallbacks(componentCallback)
            }

            rtspClient?.destroy()
            serverConnectionState = ConnectionState.Disconnected

            videoEncoder?.stop()
            videoEncoder = null
            virtualDisplay?.release()
            virtualDisplay?.surface?.release()
            virtualDisplay = null

            audioEncoder?.stop()
            audioEncoder = null

            rtspClient = null

            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null

            isStreaming = false
        } else {
            XLog.d(getLog("stopStream", "Not streaming. Ignoring."))
        }

        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        service.stopForeground()
    }
}