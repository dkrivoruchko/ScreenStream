package info.dvkr.screenstream.mjpeg.internal

import android.annotation.SuppressLint
import android.Manifest
import android.app.ForegroundServiceTypeException
import android.app.ServiceStartNotAllowedException
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsTracker
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.ProjectionCoordinator
import info.dvkr.screenstream.mjpeg.MjpegModuleService
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.internal.audio.EncodedAudioPacket
import info.dvkr.screenstream.mjpeg.internal.audio.MjpegAudioEncoder
import info.dvkr.screenstream.mjpeg.internal.audio.MjpegAudioEncoderUtils
import info.dvkr.screenstream.mjpeg.internal.audio.MjpegAudioPacket
import info.dvkr.screenstream.mjpeg.internal.audio.MjpegAudioSource
import info.dvkr.screenstream.mjpeg.internal.audio.MjpegMasterClock
import info.dvkr.screenstream.mjpeg.internal.mp4.Mp4AudioPacket
import info.dvkr.screenstream.mjpeg.internal.mp4.Mp4Capture
import info.dvkr.screenstream.mjpeg.internal.mp4.Mp4StreamConfig
import info.dvkr.screenstream.mjpeg.internal.mp4.Mp4VideoPacket
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.MjpegError
import info.dvkr.screenstream.mjpeg.ui.MjpegState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class MjpegStreamingService(
    private val service: MjpegModuleService,
    private val mutableMjpegStateFlow: MutableStateFlow<MjpegState>,
    private val networkHelper: NetworkHelper,
    private val mjpegSettings: MjpegSettings,
    private val streamingAnalytics: StreamingAnalytics
) : HandlerThread("MJPEG-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback {

    private val powerManager: PowerManager = service.application.getSystemService(PowerManager::class.java)
    private val projectionManager = service.application.getSystemService(MediaProjectionManager::class.java)
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper, this) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("MJPEG-HT_Dispatcher") }
    private val supervisorJob = SupervisorJob()
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(supervisorJob + coroutineDispatcher) }
    private val bitmapStateFlow = MutableStateFlow(createBitmap(1, 1))
    private val audioPacketFlow = MutableSharedFlow<MjpegAudioPacket>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val mp4ConfigFlow = MutableStateFlow<Mp4StreamConfig?>(null)
    private val mp4VideoPacketFlow = MutableSharedFlow<Mp4VideoPacket>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val mp4AudioPacketFlow = MutableSharedFlow<Mp4AudioPacket>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val httpServer by lazy(mode = LazyThreadSafetyMode.NONE) {
        HttpServer(
            service,
            mjpegSettings,
            bitmapStateFlow.asStateFlow(),
            audioPacketFlow.asSharedFlow(),
            mp4ConfigFlow.asStateFlow(),
            mp4VideoPacketFlow.asSharedFlow(),
            mp4AudioPacketFlow.asSharedFlow(),
            ::sendEvent
        )
    }
    private val projectionCoordinator by lazy(mode = LazyThreadSafetyMode.NONE) {
        ProjectionCoordinator(
            tag = "MJPEG",
            projectionManager = projectionManager,
            callbackHandler = mainHandler,
            startForeground = { fgsType -> service.startForeground(fgsType) },
            onProjectionStopped = { generation ->
                XLog.i(getLog("ProjectionCoordinator.onStop", "g=$generation, streaming=$isStreaming"))
                sendEvent(MjpegEvent.Intentable.StopStream("ProjectionCoordinator.onStop[generation=$generation]"))
            }
        )
    }

    @MainThread
    internal fun tryStartProjectionForeground(): Throwable? {
        val settings = mjpegSettings.data.value
        val useMp4 = settings.streamFormat == MjpegSettings.Values.STREAM_FORMAT_MP4
        val audioPermissionGranted =
            ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val deviceAudioSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val wantsAudio = useMp4 && (settings.enableMic || (deviceAudioSupported && settings.enableDeviceAudio))
        if (!audioPermissionGranted && wantsAudio) {
            coroutineScope.launch {
                mjpegSettings.updateData { copy(enableMic = false, enableDeviceAudio = false) }
            }
        } else if (!deviceAudioSupported && settings.enableDeviceAudio) {
            coroutineScope.launch {
                mjpegSettings.updateData { copy(enableDeviceAudio = false) }
            }
        }
        val wantsAudioForegroundService = audioPermissionGranted && wantsAudio
        val foregroundStartError = projectionCoordinator.startForegroundForProjection(wantsAudioForegroundService)
        val audioMode = when {
            useMp4.not() -> "none"
            audioPermissionGranted && settings.enableMic && settings.enableDeviceAudio -> "both"
            audioPermissionGranted && settings.enableMic -> "mic"
            audioPermissionGranted && settings.enableDeviceAudio -> "device"
            else -> "none"
        }
        XLog.i(getLog("tryStartProjectionForeground", "SP_TRACE route=preflight_v1 stage=foreground_preflight audioMode=$audioMode result=${foregroundStartError?.javaClass?.simpleName ?: "ok"}"))
        return foregroundStartError
    }

    private fun clearPreparedProjectionStartIfNeeded(foregroundStartProcessed: Boolean, foregroundStartError: Throwable?) {
        if (!foregroundStartProcessed || foregroundStartError != null) return
        projectionCoordinator.stop()
        service.stopForeground()
    }

    private fun startForegroundForMicrophoneOnly(): Throwable? {
        val foregroundServiceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        return runCatching { service.startForeground(foregroundServiceType) }.exceptionOrNull()
    }

    private fun Throwable.toForegroundStartFailGroup(): StartFailGroup = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && this is ServiceStartNotAllowedException -> StartFailGroup.BLOCKED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && this is ForegroundServiceTypeException -> StartFailGroup.FATAL
        else -> StartFailGroup.FATAL
    }

    private val sessionAnalyticsTracker by lazy(LazyThreadSafetyMode.NONE) {
        StreamingSessionAnalyticsTracker(
            analytics = streamingAnalytics,
            streamModeProvider = { StreamMode.MJPEG },
            nowElapsedRealtimeMs = { SystemClock.elapsedRealtime() }
        )
    }

    // All Volatiles vars must be write on this (WebRTC-HT) thread
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    // All Volatiles vars must be write on this (WebRTC-HT) thread

    // All vars must be read/write on this (WebRTC-HT) thread
    private var startBitmap: Bitmap? = null
    private var pendingServer: Boolean = true
    private var deviceConfiguration: Configuration = Configuration(service.resources.configuration)
    private var netInterfaces: List<MjpegNetInterface> = emptyList()
    private var clients: List<MjpegState.Client> = emptyList()
    private var slowClients: List<MjpegState.Client> = emptyList()
    private var traffic: List<MjpegState.TrafficPoint> = emptyList()
    private var isStreaming: Boolean = false
    private var pendingStartAttemptId: String? = null
    private var waitingForPermission: Boolean = false
    private var mediaProjectionIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var bitmapCapture: BitmapCapture? = null
    private var audioEncoder: MjpegAudioEncoder? = null
    private var mp4Capture: Mp4Capture? = null
    private var audioGeneration: Long = 0L
    private var mp4Generation: Long = 0L
    private var componentCallbacksRegistered: Boolean = false
    private var currentError: MjpegError? = null
    private var previousError: MjpegError? = null
    // All vars must be read/write on this (WebRTC-HT) thread

    internal sealed class InternalEvent(priority: Int) : MjpegEvent(priority) {
        data class InitState(val clearIntent: Boolean = true) : InternalEvent(Priority.RESTART_IGNORE)
        data class DiscoverAddress(val reason: String, val attempt: Int) : InternalEvent(Priority.RESTART_IGNORE)
        data class StartServer(val interfaces: List<MjpegNetInterface>) : InternalEvent(Priority.RESTART_IGNORE)
        data class StartStream(val permissionEducationShown: Boolean) : InternalEvent(Priority.RESTART_IGNORE)
        data class StartMicrophoneAudioOnlyStream(val entryPoint: EntryPoint) : InternalEvent(Priority.RESTART_IGNORE)
        data object StartStopFromWebPage : InternalEvent(Priority.RESTART_IGNORE)
        data object ScreenOff : InternalEvent(Priority.RESTART_IGNORE)
        data class ConfigurationChange(val newConfig: Configuration) : InternalEvent(Priority.RESTART_IGNORE) {
            override fun toString(): String = "ConfigurationChange"
        }
        data class CapturedContentResize(val width: Int, val height: Int) : InternalEvent(Priority.RESTART_IGNORE)
        data class Clients(val clients: List<MjpegState.Client>) : InternalEvent(Priority.RESTART_IGNORE)
        data class RestartServer(val reason: RestartReason) : InternalEvent(Priority.RESTART_IGNORE)
        data object UpdateStartBitmap : InternalEvent(Priority.RESTART_IGNORE)

        data class AudioCaptureError(val cause: Throwable) : InternalEvent(Priority.RECOVER_IGNORE)
        data class Error(val error: MjpegError) : InternalEvent(Priority.RECOVER_IGNORE)

        data class Destroy(val destroyJob: CompletableJob) : InternalEvent(Priority.DESTROY_IGNORE)
        data class Traffic(val time: Long, val traffic: List<MjpegState.TrafficPoint>) : InternalEvent(Priority.DESTROY_IGNORE) {
            override fun toString(): String = "Traffic(time=$time)"
        }
    }

    internal sealed class RestartReason(private val msg: String) {
        object ConnectionChanged : RestartReason("")
        object StreamFormatChanged : RestartReason(MjpegSettings.Key.STREAM_FORMAT.name)
        class SettingsChanged(msg: String) : RestartReason(msg)
        class NetworkSettingsChanged(msg: String) : RestartReason(msg)

        override fun toString(): String = "${javaClass.simpleName}[$msg]"
    }

    private data class AudioSettings(
        val muteMic: Boolean,
        val muteDeviceAudio: Boolean,
        val volumeMic: Float,
        val volumeDeviceAudio: Float
    )

    @Suppress("OVERRIDE_DEPRECATION")
    private val componentCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = sendEvent(InternalEvent.ConfigurationChange(newConfig))
        override fun onLowMemory() = Unit
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.v(this@MjpegStreamingService.getLog("MediaProjection.Callback", "onStop (handled by coordinator)"))
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            XLog.v(this@MjpegStreamingService.getLog("MediaProjection.Callback", "onCapturedContentVisibilityChanged: $isVisible"))
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            XLog.v(this@MjpegStreamingService.getLog("MediaProjection.Callback", "onCapturedContentResize: width: $width, height: $height"))
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

        mutableMjpegStateFlow.value = MjpegState()
        sendEvent(InternalEvent.InitState())

        coroutineScope.launch {
            if (mjpegSettings.data.value.enablePin && mjpegSettings.data.value.newPinOnAppStart) {
                mjpegSettings.updateData { copy(pin = randomPin()) }
            }
        }

        service.startListening(
            supervisorJob,
            onScreenOff = { sendEvent(InternalEvent.ScreenOff) },
            onConnectionChanged = { sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged)) }
        )

        mjpegSettings.data.map { it.htmlBackColor }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.UpdateStartBitmap)
        }
        mjpegSettings.data.map { it.enablePin }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.ENABLE_PIN.name)))
        }
        mjpegSettings.data.map { it.pin }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.PIN.name)))
        }
        mjpegSettings.data.map { it.blockAddress }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.BLOCK_ADDRESS.name)))
        }
        mjpegSettings.data.map { it.interfaceFilter }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.INTERFACE_FILTER.name)))
        }
        mjpegSettings.data.map { it.addressFilter }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.ADDRESS_FILTER.name)))
        }
        mjpegSettings.data.map { it.enableIPv4 }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.ENABLE_IPV4.name)))
        }
        mjpegSettings.data.map { it.enableIPv6 }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.ENABLE_IPV6.name)))
        }
        mjpegSettings.data.map { it.serverPort }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.SERVER_PORT.name)))
        }
        mjpegSettings.data.map { it.streamFormat }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.StreamFormatChanged))
        }
        mjpegSettings.data.map { AudioSettings(it.muteMic, it.muteDeviceAudio, it.volumeMic, it.volumeDeviceAudio) }
            .listenForChange(coroutineScope, 1) { settings ->
                audioEncoder?.setMute(settings.muteMic, settings.muteDeviceAudio)
                audioEncoder?.setVolume(settings.volumeMic, settings.volumeDeviceAudio)
                mp4Capture?.setMute(settings.muteMic, settings.muteDeviceAudio)
                mp4Capture?.setVolume(settings.volumeMic, settings.volumeDeviceAudio)
            }
        mjpegSettings.data.map { it.videoBitrateBits }.listenForChange(coroutineScope, 1) { bitrateBits ->
            mp4Capture?.setVideoBitrate(bitrateBits)
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
    internal fun sendEvent(event: MjpegEvent, timeout: Long = 0) {
        if (destroyPending) {
            when (event) {
                is InternalEvent.StartStream,
                is InternalEvent.StartMicrophoneAudioOnlyStream,
                is MjpegEvent.CastPermissionsDenied,
                is MjpegEvent.StartProjection -> sessionAnalyticsTracker.onStartAborted()
            }
            XLog.w(getLog("sendEvent", "Pending destroy: Ignoring event => $event"))
            return
        }
        if (event is InternalEvent.Destroy) destroyPending = true

        if (timeout > 0) XLog.d(getLog("sendEvent", "New event [Timeout: $timeout] => $event"))
        else XLog.v(getLog("sendEvent", "New event => $event"))

        if (event is InternalEvent.RestartServer) {
            handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
        }
        if (event is MjpegEvent.Intentable.RecoverError) {
            handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.START_PROJECTION)
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.DESTROY_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.START_PROJECTION)
        }
        if (event is MjpegEvent.StartProjection) {
            if (handler.hasMessages(MjpegEvent.Priority.START_PROJECTION)) {
                XLog.i(getLog("sendEvent", "Replacing pending StartProjection"))
            }
            handler.removeMessages(MjpegEvent.Priority.START_PROJECTION)
        }

        handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking(Dispatchers.Unconfined) {
        val event: MjpegEvent = msg.obj as MjpegEvent
        try {
            if (event !is InternalEvent.Traffic) {
                XLog.d(this@MjpegStreamingService.getLog("handleMessage", "Event [$event] Current state: [${getStateString()}]"))
            }
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@MjpegStreamingService.getLog("handleMessage.catch", cause.toString()), cause)

            sessionAnalyticsTracker.onStartFailedIfPending(StartFailGroup.UNKNOWN)
            mediaProjectionIntent = null
            stopStream("HandleMessageException")

            currentError = cause as? MjpegError ?: MjpegError.UnknownError(cause)
        } finally {
            if (event !is InternalEvent.Traffic) {
                XLog.d(this@MjpegStreamingService.getLog("handleMessage", "Done [$event] New state: [${getStateString()}]"))
            }
            if (event is InternalEvent.Destroy) event.destroyJob.complete()
            sessionAnalyticsTracker.onActiveConsumersChanged(currentActiveConsumersCount())
            publishState()
        }

        true
    }

    private fun createAndStartAudioEncoder(
        settings: MjpegSettings.Data,
        enableMic: Boolean,
        enableDeviceAudio: Boolean,
        mediaProjection: MediaProjection?
    ): MjpegAudioEncoder {
        val encoderInfo = requireNotNull(MjpegAudioEncoderUtils.selectedOpusEncoder) { "No OPUS audio encoder available" }
        val generation = ++audioGeneration
        val params = MjpegAudioSource.Params.DEFAULT_OPUS.copy(
            bitrate = settings.audioBitrateBits,
            echoCanceler = settings.audioEchoCanceller,
            noiseSuppressor = settings.audioNoiseSuppressor,
            isStereo = true
        )

        val encoder = MjpegAudioEncoder(
            codecInfo = encoderInfo,
            onAudioPacket = { packet: EncodedAudioPacket ->
                audioPacketFlow.tryEmit(
                    MjpegAudioPacket(
                        generation = generation,
                        data = packet.data,
                        durationSamples = packet.durationSamples
                    )
                )
            },
            onAudioCaptureError = {
                XLog.w(getLog("AudioCapture.onError", it.message), it)
                sendEvent(InternalEvent.AudioCaptureError(it))
            },
            onError = {
                XLog.w(getLog("AudioEncoder.onError", it.message), it)
                sendEvent(InternalEvent.Error(MjpegError.UnknownError(it)))
            }
        )

        return encoder.apply {
            runCatching {
                prepare(
                    enableMic = enableMic,
                    enableDeviceAudio = enableDeviceAudio,
                    dispatcher = Dispatchers.IO,
                    audioParams = params,
                    mediaProjection = mediaProjection
                )
                setMute(settings.muteMic, settings.muteDeviceAudio)
                setVolume(settings.volumeMic, settings.volumeDeviceAudio)
                start()
                check(isCapturing) { "Audio capture did not start" }
            }.onFailure { cause ->
                stop()
                throw cause
            }
        }
    }

    private fun startMicrophoneAudioOnly(entryPoint: EntryPoint) {
        if (pendingStartAttemptId != null) {
            XLog.i(getLog("StartMicrophoneAudioOnly", "Permission already pending id=${pendingStartAttemptId ?: "none"}"))
            return
        }
        if (pendingServer || currentError != null || isStreaming) {
            XLog.i(getLog("StartMicrophoneAudioOnly", "Not ready. pendingServer=$pendingServer isStreaming=$isStreaming error=${currentError != null}"))
            return
        }

        val settings = mjpegSettings.data.value
        val deviceAudioSelected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && settings.enableDeviceAudio
        val validMode = settings.streamFormat == MjpegSettings.Values.STREAM_FORMAT_MP4 &&
                settings.streamAudioOnly && settings.enableMic && deviceAudioSelected.not()
        if (!validMode) {
            XLog.i(
                getLog(
                    "StartMicrophoneAudioOnly",
                    "Invalid mode. format=${settings.streamFormat} audioOnly=${settings.streamAudioOnly} mic=${settings.enableMic} device=${settings.enableDeviceAudio}"
                )
            )
            return
        }

        sessionAnalyticsTracker.onStartAttempt(
            entryPoint = entryPoint,
            usedCachedPermission = false,
            permissionEducationShown = false
        )

        val audioPermissionGranted =
            ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!audioPermissionGranted) {
            coroutineScope.launch { mjpegSettings.updateData { copy(enableMic = false) } }
            sessionAnalyticsTracker.onStartFailed(StartFailGroup.PERMISSION_DENIED)
            currentError = MjpegError.UnknownError(IllegalStateException("Audio-only stream requires audio recording permission"))
            return
        }

        startForegroundForMicrophoneOnly()?.let { foregroundError ->
            sessionAnalyticsTracker.onStartFailed(foregroundError.toForegroundStartFailGroup())
            currentError = MjpegError.UnknownError(foregroundError)
            return
        }

        runCatching {
            MjpegMasterClock.reset()
            MjpegMasterClock.ensureStarted()
            val capture = Mp4Capture(
                serviceContext = service,
                settings = settings,
                mediaProjection = null,
                generation = ++mp4Generation,
                enableMic = true,
                enableDeviceAudio = false,
                dispatcher = Dispatchers.IO,
                configFlow = mp4ConfigFlow,
                videoPacketFlow = mp4VideoPacketFlow,
                audioPacketFlow = mp4AudioPacketFlow,
                onError = { error -> sendEvent(InternalEvent.Error(error)) },
                onAudioCaptureError = { cause -> sendEvent(InternalEvent.AudioCaptureError(cause)) }
            )
            check(capture.start { true }) { "MP4 audio capture did not start" }
            mp4Capture = capture
            isStreaming = true
            currentError = null
            sessionAnalyticsTracker.onStarted(currentActiveConsumersCount())
            XLog.i(getLog("StartMicrophoneAudioOnly", "Started. entryPoint=$entryPoint"))
        }.onFailure { cause ->
            XLog.e(getLog("StartMicrophoneAudioOnly", "Failed: ${cause.message}"), cause)
            mp4Capture?.stop()
            mp4Capture = null
            mp4ConfigFlow.value = null
            isStreaming = false
            service.stopForeground()
            sessionAnalyticsTracker.onStartFailed(StartFailGroup.FATAL)
            currentError = MjpegError.UnknownError(cause)
        }
    }

    // On MJPEG-HT only
    private suspend fun processEvent(event: MjpegEvent) {
        when (event) {
            is InternalEvent.InitState -> {
                pendingServer = true
                deviceConfiguration = Configuration(service.resources.configuration)
                netInterfaces = emptyList()
                clients = emptyList()
                slowClients = emptyList()
                isStreaming = false
                pendingStartAttemptId = null
                waitingForPermission = false
                if (event.clearIntent) mediaProjectionIntent = null
                mediaProjection = null
                bitmapCapture = null
                audioEncoder = null
                mp4Capture = null
                mp4ConfigFlow.value = null
                componentCallbacksRegistered = false

                currentError = null
            }

            is InternalEvent.DiscoverAddress -> {
                if (pendingServer.not()) httpServer.stop(false)

                val newInterfaces = networkHelper.getNetInterfaces(
                    mjpegSettings.data.value.interfaceFilter,
                    mjpegSettings.data.value.addressFilter,
                    mjpegSettings.data.value.enableIPv4,
                    mjpegSettings.data.value.enableIPv6,
                )

                if (newInterfaces.isNotEmpty()) {
                    sendEvent(InternalEvent.StartServer(newInterfaces))
                } else {
                    if (event.attempt < 3) {
                        sendEvent(InternalEvent.DiscoverAddress(event.reason, event.attempt + 1), 1000)
                    } else {
                        netInterfaces = emptyList()
                        clients = emptyList()
                        slowClients = emptyList()
                        currentError = MjpegError.AddressNotFoundException()
                    }
                }
            }

            is InternalEvent.StartServer -> {
                if (pendingServer.not()) httpServer.stop(false)
                httpServer.start(event.interfaces.toList())

                if (isStreaming.not() && mjpegSettings.data.value.htmlShowPressStart) bitmapStateFlow.value = getStartBitmap()

                netInterfaces = event.interfaces
                pendingServer = false
            }

            is InternalEvent.StartStopFromWebPage -> when {
                isStreaming -> sendEvent(MjpegEvent.Intentable.StopStream("StartStopFromWebPage"))
                mjpegSettings.data.value.streamFormat == MjpegSettings.Values.STREAM_FORMAT_MP4 &&
                        mjpegSettings.data.value.streamAudioOnly &&
                        mjpegSettings.data.value.enableMic &&
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mjpegSettings.data.value.enableDeviceAudio.not()) ->
                    startMicrophoneAudioOnly(EntryPoint.WEB)

                pendingServer.not() && currentError == null -> {
                    if (pendingStartAttemptId != null) {
                        XLog.i(getLog("StartStopFromWebPage", "Permission already pending id=${pendingStartAttemptId ?: "none"}"))
                        return
                    }
                    sessionAnalyticsTracker.onStartAttempt(
                        entryPoint = EntryPoint.WEB,
                        usedCachedPermission = mediaProjectionIntent != null,
                        permissionEducationShown = false
                    )
                    pendingStartAttemptId = Uuid.random().toString()
                    val startAttemptId = pendingStartAttemptId!!
                    mediaProjectionIntent?.let {
                        check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "MjpegEvent.StartStopFromWebPage: UPSIDE_DOWN_CAKE" }
                        waitingForPermission = false
                        XLog.i(getLog("StartStopFromWebPage", "SP_TRACE route=service_cached_permission stage=dispatch_source source=web startAttemptId=$startAttemptId"))
                        MjpegModuleService.dispatchProjectionIntent(service, startAttemptId, it)
                    } ?: run {
                        waitingForPermission = true
                        XLog.i(getLog("Permission", "MP_UI request id=$startAttemptId source=web"))
                    }
                }
            }

            is InternalEvent.StartStream -> {
                if (pendingStartAttemptId != null) {
                    XLog.i(getLog("StartStream", "Permission already pending id=${pendingStartAttemptId ?: "none"}"))
                    return
                }
                if (pendingServer || currentError != null || isStreaming) {
                    XLog.i(getLog("StartStream", "Not ready. pendingServer=$pendingServer isStreaming=$isStreaming error=${currentError != null}"))
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
                    check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "MjpegEvent.StartStream: UPSIDE_DOWN_CAKE" }
                    waitingForPermission = false
                    XLog.i(getLog("StartStream", "SP_TRACE route=service_cached_permission stage=dispatch_source source=button startAttemptId=$startAttemptId"))
                    MjpegModuleService.dispatchProjectionIntent(service, startAttemptId, it)
                } ?: run {
                    waitingForPermission = true
                    XLog.i(getLog("Permission", "MP_UI request id=$startAttemptId source=button"))
                }
            }

            is InternalEvent.StartMicrophoneAudioOnlyStream -> startMicrophoneAudioOnly(event.entryPoint)

            is MjpegEvent.CastPermissionsDenied -> {
                val currentStartAttemptId = pendingStartAttemptId
                if (currentStartAttemptId != event.startAttemptId) {
                    XLog.i(getLog("CastPermissionsDenied", "MP_UI stale id=${event.startAttemptId} current=${currentStartAttemptId ?: "none"}"))
                    return
                }
                pendingStartAttemptId = null
                waitingForPermission = false
                sessionAnalyticsTracker.onStartFailed(StartFailGroup.PERMISSION_DENIED)
            }

            is MjpegEvent.StartProjection -> {
                val currentStartAttemptId = pendingStartAttemptId
                if (currentStartAttemptId != event.startAttemptId) {
                    XLog.i(getLog("MjpegEvent.StartProjection", "MP_UI stale id=${event.startAttemptId} current=${currentStartAttemptId ?: "none"}"))
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    return
                }
                waitingForPermission = false
                XLog.i(
                    getLog(
                        "MjpegEvent.StartProjection",
                        "SP_TRACE route=preflight_v1 stage=async_start startAttemptId=${event.startAttemptId} pendingServer=$pendingServer isStreaming=$isStreaming cachedIntent=${mediaProjectionIntent != null}"
                    )
                )

                if (pendingServer) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    pendingStartAttemptId = null
                    sessionAnalyticsTracker.onStartFailed(StartFailGroup.UNKNOWN)
                    val cause = IllegalStateException("StartProjection while server pending")
                    XLog.w(getLog("MjpegEvent.StartProjection", "Server pending"), cause)
                    return
                }

                if (isStreaming) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    pendingStartAttemptId = null
                    sessionAnalyticsTracker.onStartAborted()
                    XLog.w(getLog("MjpegEvent.StartProjection", "Already streaming"))
                    return
                }

                val settings = mjpegSettings.data.value
                val useMp4 = settings.streamFormat == MjpegSettings.Values.STREAM_FORMAT_MP4
                val streamAudioOnly = useMp4 && settings.streamAudioOnly
                val deviceAudioSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                val audioRequested = useMp4 && (settings.enableMic || (deviceAudioSupported && settings.enableDeviceAudio))
                if (streamAudioOnly && !audioRequested) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    pendingStartAttemptId = null
                    throw IllegalStateException("Audio-only stream requires microphone or device audio")
                }

                pendingStartAttemptId = null

                val startProjection = {
                    projectionCoordinator.startProjection(event.intent) { _, mediaProjection, audioCaptureAllowed, isStartupStillValid ->
                        mediaProjection.registerCallback(projectionCallback, mainHandler)

                        MjpegMasterClock.reset()
                        MjpegMasterClock.ensureStarted()

                        var bitmapCapture: BitmapCapture? = null
                        var audioEncoder: MjpegAudioEncoder? = null
                        var mp4Capture: Mp4Capture? = null

                        val audioPermissionGranted =
                            ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        val enableMic = useMp4 && audioPermissionGranted && settings.enableMic && audioCaptureAllowed
                        val enableDeviceAudio = useMp4 && audioPermissionGranted && deviceAudioSupported && settings.enableDeviceAudio
                        val enableAudio = enableMic || enableDeviceAudio
                        if (streamAudioOnly && !enableAudio) {
                            mediaProjection.unregisterCallback(projectionCallback)
                            throw IllegalStateException("Audio-only stream requires audio recording permission")
                        }

                        if (useMp4) {
                            mp4Capture = Mp4Capture(
                                serviceContext = service,
                                settings = settings,
                                mediaProjection = mediaProjection,
                                generation = ++mp4Generation,
                                enableMic = enableMic,
                                enableDeviceAudio = enableDeviceAudio,
                                dispatcher = Dispatchers.IO,
                                configFlow = mp4ConfigFlow,
                                videoPacketFlow = mp4VideoPacketFlow,
                                audioPacketFlow = mp4AudioPacketFlow,
                                onError = { error -> sendEvent(InternalEvent.Error(error)) },
                                onAudioCaptureError = { cause -> sendEvent(InternalEvent.AudioCaptureError(cause)) }
                            )
                            val captureStarted = mp4Capture.start(isStartupStillValid)
                            if (!captureStarted) {
                                XLog.i(getLog("StartProjection", "MP4 capture not started. Stopping projection."))
                                mp4Capture.stop()
                                mediaProjection.unregisterCallback(projectionCallback)
                                return@startProjection false
                            }
                            if (!isStartupStillValid()) {
                                XLog.i(getLog("StartProjection", "Startup invalidated after MP4 capture start."))
                                mp4Capture.stop()
                                mediaProjection.unregisterCallback(projectionCallback)
                                return@startProjection false
                            }
                        } else if (!streamAudioOnly) {
                            bitmapCapture = BitmapCapture(service, mjpegSettings, mediaProjection, bitmapStateFlow) { error ->
                                sendEvent(InternalEvent.Error(error))
                            }
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
                        }

                        if (!useMp4 && enableAudio) {
                            audioEncoder = runCatching {
                                createAndStartAudioEncoder(settings, enableMic, enableDeviceAudio, mediaProjection)
                            }.getOrElse { cause ->
                                if (streamAudioOnly) {
                                    bitmapCapture?.destroy()
                                    mediaProjection.unregisterCallback(projectionCallback)
                                    throw cause
                                }
                                XLog.w(getLog("StartProjection", "Audio capture unavailable. Continuing video-only: ${cause.message}"), cause)
                                null
                            }
                        }
                        if (!isStartupStillValid()) {
                            XLog.i(getLog("StartProjection", "Startup invalidated after audio start."))
                            bitmapCapture?.destroy()
                            audioEncoder?.stop()
                            mp4Capture?.stop()
                            mediaProjection.unregisterCallback(projectionCallback)
                            return@startProjection false
                        }

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            mediaProjectionIntent = event.intent
                            service.registerComponentCallbacks(componentCallback)
                            componentCallbacksRegistered = true
                        }

                        @Suppress("DEPRECATION")
                        @SuppressLint("WakelockTimeout")
                        if (!streamAudioOnly && Build.MANUFACTURER !in listOf("OnePlus", "OPPO") && settings.keepAwake) {
                            val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                            wakeLock = powerManager.newWakeLock(flags, "ScreenStream::MJPEG-Tag").apply { acquire() }
                        }

                        this@MjpegStreamingService.isStreaming = true
                        this@MjpegStreamingService.mediaProjection = mediaProjection
                        this@MjpegStreamingService.bitmapCapture = bitmapCapture
                        this@MjpegStreamingService.audioEncoder = audioEncoder
                        this@MjpegStreamingService.mp4Capture = mp4Capture
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
                    val audioPermissionGranted =
                        ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val foregroundError = projectionCoordinator.startForegroundForProjection(audioPermissionGranted && audioRequested)
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
                                "MjpegEvent.StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=started startAttemptId=${event.startAttemptId} phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        XLog.i(getLog("MjpegEvent.StartProjection", "Started. g=${result.generation}"))
                    }

                    is ProjectionCoordinator.StartResult.Interrupted -> {
                        if (result.cachedIntentAction == ProjectionCoordinator.CachedIntentAction.INVALIDATE) {
                            mediaProjectionIntent = null
                        }
                        sessionAnalyticsTracker.onStartAborted()
                        XLog.i(
                            getLog(
                                "MjpegEvent.StartProjection",
                                "Interrupted. intent=${result.cachedIntentAction}/${mediaProjectionIntent != null}"
                            ), result.cause
                        )
                        XLog.i(
                            getLog(
                                "MjpegEvent.StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=interrupted startAttemptId=${event.startAttemptId} phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        currentError = null
                        sendEvent(MjpegEvent.Intentable.StopStream("StartProjectionInterrupted"))
                    }

                    ProjectionCoordinator.StartResult.Busy -> {
                        sessionAnalyticsTracker.onStartFailed(StartFailGroup.BUSY)
                        XLog.i(
                            getLog(
                                "MjpegEvent.StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=busy startAttemptId=${event.startAttemptId} phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        XLog.w(getLog("MjpegEvent.StartProjection", "Busy during $startPhase. intent=${mediaProjectionIntent != null}"))
                    }

                    is ProjectionCoordinator.StartResult.Blocked, is ProjectionCoordinator.StartResult.Fatal -> {
                        val cause = result.cause ?: error("Missing cause for failed start result")
                        if (result.cachedIntentAction == ProjectionCoordinator.CachedIntentAction.INVALIDATE) {
                            mediaProjectionIntent = null
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
                                "MjpegEvent.StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=${if (result is ProjectionCoordinator.StartResult.Blocked) "blocked" else "fatal"} startAttemptId=${event.startAttemptId} phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        if (result is ProjectionCoordinator.StartResult.Blocked) {
                            XLog.w(getLog("MjpegEvent.StartProjection", logMessage), cause)
                            currentError = cause as? MjpegError ?: MjpegError.UnknownError(cause)
                        } else {
                            XLog.e(getLog("MjpegEvent.StartProjection", logMessage), cause)
                            stopStream("StartProjectionFatal")
                            currentError = cause as? MjpegError ?: MjpegError.UnknownError(cause)
                        }
                    }
                }
            }

            is MjpegEvent.Intentable.StopStream -> {
                val wasStreaming = stopStream(event.reason)

                if (wasStreaming && mjpegSettings.data.value.enablePin && mjpegSettings.data.value.autoChangePin)
                    mjpegSettings.updateData { copy(pin = randomPin()) }

                if (wasStreaming && mjpegSettings.data.value.htmlShowPressStart) bitmapStateFlow.value = getStartBitmap()
            }

            is InternalEvent.ScreenOff -> if (isStreaming && mjpegSettings.data.value.stopOnSleep)
                sendEvent(MjpegEvent.Intentable.StopStream("ScreenOff"))

            is InternalEvent.ConfigurationChange -> {
                val newConfig = Configuration(event.newConfig)
                if (isStreaming) {
                    val configDiff = deviceConfiguration.diff(newConfig)
                    if (
                        configDiff and ActivityInfo.CONFIG_ORIENTATION != 0 || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0 ||
                        configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0 || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                    ) {
                        if (mp4Capture != null) {
                            XLog.i(getLog("ConfigurationChange", "Active MP4 stream keeps existing virtual display. Ignoring resize."))
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
                    if (mp4Capture != null) {
                        XLog.i(getLog("CapturedContentResize", "Active MP4 stream keeps existing virtual display: ${event.width} x ${event.height}"))
                    } else {
                        bitmapCapture?.resize(event.width, event.height)
                    }
                } else {
                    XLog.d(getLog("CapturedContentResize", "Not streaming. Ignoring."))
                }
            }

            is InternalEvent.RestartServer -> {
                if (mjpegSettings.data.value.stopOnConfigurationChange || event.reason is RestartReason.StreamFormatChanged) {
                    stopStream("ConfigurationChange")
                }

                pendingStartAttemptId = null
                waitingForPermission = false
                if (pendingServer) {
                    XLog.d(getLog("processEvent", "RestartServer: No running server."))
                    if (currentError is MjpegError.AddressNotFoundException) currentError = null
                } else {
                    httpServer.stop(event.reason is RestartReason.SettingsChanged)
                    if (mjpegSettings.data.value.stopOnConfigurationChange) {
                        sendEvent(InternalEvent.InitState(false))
                    } else {
                        pendingServer = true
                        netInterfaces = emptyList()
                        clients = emptyList()
                        slowClients = emptyList()
                        currentError = null
                    }
                }
                sendEvent(InternalEvent.DiscoverAddress("RestartServer", 0))
            }

            InternalEvent.UpdateStartBitmap -> {
                startBitmap = null
                if (isStreaming.not() && mjpegSettings.data.value.htmlShowPressStart) bitmapStateFlow.value = getStartBitmap()
            }

            is InternalEvent.AudioCaptureError -> {
                if (mp4Capture != null) {
                    stopStream("Mp4AudioCaptureError")
                    currentError = MjpegError.UnknownError(event.cause)
                    return
                }
                audioEncoder?.stop()
                audioEncoder = null
                if (bitmapCapture == null) {
                    stopStream("AudioCaptureError")
                    currentError = MjpegError.UnknownError(event.cause)
                } else {
                    XLog.w(getLog("AudioCaptureError", "Continuing video-only: ${event.cause.message}"), event.cause)
                }
            }

            is MjpegEvent.Intentable.RecoverError -> {
                stopStream("RecoverError")
                httpServer.stop(true)

                handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
                handler.removeMessages(MjpegEvent.Priority.RECOVER_IGNORE)
                handler.removeMessages(MjpegEvent.Priority.START_PROJECTION)

                sendEvent(InternalEvent.InitState(true))
                sendEvent(InternalEvent.DiscoverAddress("RecoverError", 0))
            }

            is InternalEvent.Destroy -> {
                sessionAnalyticsTracker.onStartAborted()
                stopStream("Destroy")
                httpServer.destroy()
                currentError = null
            }

            is InternalEvent.Error -> currentError = event.error

            is InternalEvent.Clients -> {
                clients = event.clients
                if (mjpegSettings.data.value.notifySlowConnections) {
                    val currentSlowClients = event.clients.filter { it.state == MjpegState.Client.State.SLOW_CONNECTION }.toList()
                    if (slowClients.containsAll(currentSlowClients).not()) {
                        mainHandler.post { Toast.makeText(service, R.string.mjpeg_slow_client_connection, Toast.LENGTH_LONG).show() }
                    }
                    slowClients = currentSlowClients
                }
            }

            is InternalEvent.Traffic -> traffic = event.traffic

            is MjpegEvent.CreateNewPin -> when {
                destroyPending -> XLog.i(getLog("CreateNewPin", "DestroyPending. Ignoring"), IllegalStateException("CreateNewPin: DestroyPending"))
                isStreaming -> XLog.i(getLog("CreateNewPin", "Streaming. Ignoring."), IllegalStateException("CreateNewPin: Streaming."))
                mjpegSettings.data.value.enablePin -> mjpegSettings.updateData { copy(pin = randomPin()) } // will restart server
            }

            else -> throw IllegalArgumentException("Unknown MjpegEvent: ${event::class.java}")
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream(stopReason: String? = null): Boolean {
        val wasStreaming = isStreaming
        val activeConsumersAtStop = currentActiveConsumersCount()
        pendingStartAttemptId = null
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
            if (componentCallbacksRegistered && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { service.unregisterComponentCallbacks(componentCallback) }
                componentCallbacksRegistered = false
            }
            bitmapCapture?.destroy()
            bitmapCapture = null
            audioEncoder?.stop()
            audioEncoder = null
            mp4Capture?.stop()
            mp4Capture = null
            mp4ConfigFlow.value = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection = null
            projectionCoordinator.stop()

            isStreaming = false
        }

        if (wasStreaming) {
            sessionAnalyticsTracker.onEnded(stopReason, activeConsumersAtStop)
        }

        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        service.stopForeground()

        return wasStreaming
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun currentActiveConsumersCount(): Int =
        clients.count { it.state == MjpegState.Client.State.CONNECTED || it.state == MjpegState.Client.State.SLOW_CONNECTION }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun getStateString() =
        "d=$destroyPending srv=$pendingServer str=$isStreaming start=${pendingStartAttemptId ?: "-"} clients=${clients.size} err=$currentError"

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun publishState() {
        val state = MjpegState(
            isBusy = pendingServer || destroyPending || pendingStartAttemptId != null || currentError != null,
            serverNetInterfaces = netInterfaces.map {
                MjpegState.ServerNetInterface(it.label, it.buildUrl(mjpegSettings.data.value.serverPort))
            }.sortedBy { it.fullAddress },
            waitingCastPermission = waitingForPermission,
            startAttemptId = pendingStartAttemptId,
            isStreaming = isStreaming,
            pin = MjpegState.Pin(mjpegSettings.data.value.enablePin, mjpegSettings.data.value.pin, mjpegSettings.data.value.hidePinOnStart),
            clients = clients.toList(),
            traffic = traffic.toList(),
            error = currentError
        )

        mutableMjpegStateFlow.value = state

        if (previousError != currentError) {
            previousError = currentError
            currentError?.let { service.showErrorNotification(it) } ?: service.hideErrorNotification()
        }
    }

    private fun randomPin(): String = Random.nextInt(10).toString() + Random.nextInt(10).toString() +
            Random.nextInt(10).toString() + Random.nextInt(10).toString() +
            Random.nextInt(10).toString() + Random.nextInt(10).toString()

    private fun getStartBitmap(): Bitmap {
        startBitmap?.let { return it }

        val screenSize = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
        val bitmap = createBitmap(max(screenSize.width(), 600), max(screenSize.height(), 800))

        var width = min(bitmap.width.toFloat(), 1536F)
        val height = min(bitmap.height.toFloat(), width * 0.75F)
        width = height / 0.75F

        val left = max((bitmap.width - width) / 2F, 0F)
        val top = max((bitmap.height - height) / 2F, 0F)
        val right = bitmap.width - left
        val bottom = (bitmap.height + height) / 2
        val backRect = RectF(left, top, right, bottom)
        val canvas = Canvas(bitmap).apply {
            drawColor(mjpegSettings.data.value.htmlBackColor)
            val shader = LinearGradient(
                backRect.left, backRect.top, backRect.left, backRect.bottom,
                "#144A74".toColorInt(), "#001D34".toColorInt(), Shader.TileMode.CLAMP
            )
            drawRoundRect(backRect, 32F, 32F, Paint().apply { setShader(shader) })
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26F / 600 * backRect.width(); color = Color.WHITE }
        val logoSize = (min(backRect.width(), backRect.height()) * 0.7).toInt()
        val logo = service.getFileFromAssets("logo.png").run { BitmapFactory.decodeByteArray(this, 0, size) }.scale(logoSize, logoSize)
        canvas.drawBitmap(logo, backRect.left + (backRect.width() - logo.width) / 2, backRect.top, paint)

        val message = service.getString(R.string.mjpeg_start_image_text)
        val bounds = Rect().apply { paint.getTextBounds(message, 0, message.length, this) }
        val textX = backRect.left + (backRect.width() - bounds.width()) / 2
        val textY = backRect.top + logo.height + (backRect.height() - logo.height) / 2 - bounds.height() / 2
        canvas.drawText(message, textX, textY, paint)

        startBitmap = bitmap
        return bitmap
    }
}
