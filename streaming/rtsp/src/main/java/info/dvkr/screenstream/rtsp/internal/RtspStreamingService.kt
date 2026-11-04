package info.dvkr.screenstream.rtsp.internal

import android.Manifest
import android.content.ComponentCallbacks
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.Surface
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.util.toClosedRange
import androidx.window.core.layout.WindowSizeClass
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsFactory
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.getVersionName
import info.dvkr.screenstream.common.isLocalNetworkPermissionGranted
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.RtspModuleService
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.adjustResizeFactor
import info.dvkr.screenstream.rtsp.internal.audio.AudioEncoder
import info.dvkr.screenstream.rtsp.internal.audio.AudioSource
import info.dvkr.screenstream.rtsp.internal.onvif.OnvifServer
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
import info.dvkr.screenstream.rtsp.ui.RtspMainScreenUI
import info.dvkr.screenstream.rtsp.ui.RtspState
import info.dvkr.screenstream.rtsp.ui.isStartupPolicyError
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import java.net.URISyntaxException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Factory(binds = [])
internal class RtspStreamingService(
    @InjectedParam private val managerHandle: StreamingModule.Controller.ManagerHandle,
    @InjectedParam private val service: RtspModuleService,
    private val captureAccess: ScreenCaptureAccess,
    private val notifications: StreamingModuleNotifications,
    private val settings: Lazy<RtspSettings>,
    private val networkHelper: NetworkHelper,
    analyticsFactory: StreamingSessionAnalyticsFactory,
) : HandlerThread("RTSP-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback, StreamingModule.Controller {

    private val rtspSettings: RtspSettings get() = settings.value
    private val appVersion = service.getVersionName()
    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper, this) }
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("RTSP-HT_Dispatcher") }
    private val supervisorJob = SupervisorJob()
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(supervisorJob + coroutineDispatcher) }
    override val instanceId: StreamingModule.InstanceId = managerHandle.instanceId
    private val mutableRtspStateFlow = MutableStateFlow(RtspState())
    private val rtspStateFlow = mutableRtspStateFlow.asStateFlow()
    private val admissionGate = Any()
    private val errorGate = Any()
    private val sessionAnalytics = RtspSessionAnalytics(analyticsFactory)

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
    private val cleanupResult: Deferred<Boolean> = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
        var succeeded = true
        if (synchronized(admissionGate) { workerStarted }) {
            runCatching { destroyWorker() }.onFailure { succeeded = false; XLog.e(getLog("cleanup"), it) }
        }
        val serviceStopped = runCatching { service.stopAndAwaitDestruction() }.getOrDefault(false)
        succeeded && serviceStopped && !cleanupFailed
    }

    override fun startModule() {
        synchronized(admissionGate) {
            if (closing || !managerHandle.isCurrent() || workerStarted) return
            workerStarted = true
            start()
        }
    }

    override fun requestStreamStart() = sendEvent(InternalEvent.StartStream())

    override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean) {
        requestStreamStop(attempt, if (fromNotification) "User action: Notification" else "Coordinator Stop")
    }

    private fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, reason: String) {
        synchronized(admissionGate) {
            val capture = captureAttempt ?: return
            if (closing || capture.id != attempt || capture.stopRequested) return
            capture.stopRequested = true
            sessionAnalytics.closeUnentered(capture.id)
            capture.job.cancel()
        }
        sendEvent(RtspEvent.StopStream(reason, attempt))
    }

    override fun requestRecovery(errorToken: Uuid) {
        synchronized(errorGate) {
            if (closing || this.errorToken != errorToken) return
        }
        sendEvent(RtspEvent.RecoverError(errorToken))
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
                notifications.updateError(310, null)
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
        RtspMainScreenUI(
            instanceId = instanceId,
            rtspStateFlow = rtspStateFlow,
            sendEvent = ::sendUiEvent,
            windowSizeClass = window,
            claimInitialLocalNetworkRequest = { request ->
                if (!initialLocalNetworkRequestClaimed && !closing) initialLocalNetworkRequestClaimed = request()
            },
            modifier = modifier,
        )
    }

    private fun sendUiEvent(event: RtspEvent) {
        when (event) {
            is RtspEvent.StopStream -> event.expectedAttempt?.let { requestStreamStop(it, event.reason) }
            is RtspEvent.RecoverError -> event.errorToken?.let(::requestRecovery)
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
        val audioEnabled = attempt.settings.enableMic || attempt.settings.enableDeviceAudio
        if (audioEnabled && ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            attempt.preflightError = RtspError.AudioPermissionRequired()
            return false
        }
        service.enterCaptureForeground(attempt.id, requiresMicrophoneType = audioEnabled)
    }

    private class ActiveProjection(
        val mediaProjection: MediaProjection,
        val virtualDisplay: VirtualDisplay,
        val videoEncoder: VideoEncoder,
        var captureSurface: Surface,
        var audioEncoder: AudioEncoder? = null,
        var deviceConfiguration: Configuration,
        val onVideoReconfigureStart: () -> Unit = {}
    ) {
        fun stop(projectionCallback: MediaProjection.Callback) {
            videoEncoder.stop()
            virtualDisplay.surface = null
            virtualDisplay.release()
            runCatching { captureSurface.release() }

            audioEncoder?.stop()

            mediaProjection.unregisterCallback(projectionCallback)
        }

        fun reconfigureVideo(width: Int, height: Int, fps: Int, bitRate: Int, densityDpi: Int) {
            val oldSurface = captureSurface
            onVideoReconfigureStart()
            virtualDisplay.surface = null
            videoEncoder.stop()
            videoEncoder.prepare(width, height, fps, bitRate)
            val inputSurfaceTexture = videoEncoder.inputSurfaceTexture ?: throw IllegalStateException("VideoEncoder input surface is null")
            val newSurface = Surface(inputSurfaceTexture)
            virtualDisplay.resize(width, height, densityDpi)
            virtualDisplay.surface = newSurface
            captureSurface = newSurface
            runCatching { oldSurface.release() }
            videoEncoder.start()
        }
    }

    private class ProjectionState(
        var pendingStartAttemptId: String? = null,
        var waitingForPermission: Boolean = false,
        var active: ActiveProjection? = null,
        var lastVideoParams: VideoParams? = null,
        var lastAudioParams: AudioParams? = null,
    )

    private class CaptureAttempt(
        val id: StreamingModule.CaptureAttemptId,
        val settings: RtspSettings.Data,
        val videoEncoder: VideoCodecInfo,
        val audioEncoder: AudioCodecInfo?,
        val job: CompletableJob,
    ) {
        val projectionState = ProjectionState(pendingStartAttemptId = id.uuid.toString(), waitingForPermission = true)
        var grant: ScreenCaptureAccess.Grant? = null
        var projection: MediaProjection? = null
        var callback: MediaProjection.Callback? = null
        var componentCallback: ComponentCallbacks? = null

        @Volatile
        var stopRequested = false

        @Volatile
        var preflightError: RtspError? = null
        var started = false
        var consentReuseIneligible = false
    }

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
    private val projectionState: ProjectionState
        get() = captureAttempt?.projectionState ?: idleProjectionState
    private var serverController: RtspServerController? = null
    private var serverGeneration = 0L
    private var clientGeneration = 0L
    private var clientController: RtspClientController? = null

    private var currentError: RtspError? = null
    private var previousError: RtspError? = null
    private var audioCaptureDisabled: Boolean = false
    private var audioIssueToastShown: Boolean = false
    private var resizeActor: ResizeConflateActor? = null
    private var settingsLoaded: Boolean = false
    private var initializedMode: RtspSettings.Values.Mode? = null
    // All vars must be read/write on this (RTSP_HT) thread

    private inner class ResizeConflateActor(
        private val projection: ActiveProjection,
        initialEncodedWidth: Int,
        initialEncodedHeight: Int,
        private val attempt: CaptureAttempt,
    ) {
        private val resizeRequests = Channel<Pair<Int, Int>>(Channel.CONFLATED)
        private var encodedSize: Pair<Int, Int> = initialEncodedWidth to initialEncodedHeight
        private val job: Job = coroutineScope.launch {
            for (source in resizeRequests) {
                val activeProjection = projectionState.active
                if (activeProjection !== projection || !isAttemptCurrent(attempt)) continue

                val videoCapabilities = attempt.videoEncoder.capabilities.videoCapabilities ?: continue
                val settings = rtspSettings.data.value
                val (_, targetWidth, targetHeight) = videoCapabilities.adjustResizeFactor(
                    source.first, source.second, settings.videoResizeFactor / 100
                )
                if (targetWidth == encodedSize.first && targetHeight == encodedSize.second) continue

                try {
                    activeProjection.reconfigureVideo(
                        width = targetWidth,
                        height = targetHeight,
                        fps = settings.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange()),
                        bitRate = settings.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange()),
                        densityDpi = service.resources.displayMetrics.densityDpi
                    )
                    encodedSize = targetWidth to targetHeight
                } catch (cause: Throwable) {
                    sendEvent(InternalEvent.Error(cause.toVideoReconfigureError(), attempt.id))
                    return@launch
                }
            }
        }

        fun offer(sourceWidth: Int, sourceHeight: Int) {
            if (sourceWidth <= 0 || sourceHeight <= 0) return
            resizeRequests.trySend(sourceWidth to sourceHeight)
        }

        fun close() {
            resizeRequests.close()
            job.cancel()
        }
    }

    private inner class RtspServerController {
        var isActive: Boolean = false

        var bindings: List<RtspBinding> = emptyList()
            private set

        val statsSnapshot: List<ClientStats>
            get() = server?.getClientStatsSnapshot().orEmpty()

        @Volatile
        private var generation: Long = ++serverGeneration
        private var statsHeartbeatJob: Job? = null
        private var discoveredBindings: List<DiscoveredBinding> = emptyList()
        private var onvifServer: OnvifServer? = null

        private var server: RtspServer? = null

        suspend fun onEvent(event: InternalEvent.RtspServer) {
            if (event !is InternalEvent.RtspServer.DiscoverAddress &&
                event !is InternalEvent.RtspServer.OnvifDiscoveryChanged &&
                event.generation != generation
            ) {
                XLog.d(getLog("RtspServer:${event::class.simpleName}", "Stale generation=${event.generation}. Ignoring."))
                return
            }

            when (event) {
                is InternalEvent.RtspServer.DiscoverAddress -> {
                    if (service.isLocalNetworkPermissionGranted().not()) {
                        clearServer()
                        currentError = RtspError.LocalNetworkPermissionRequired()
                        return
                    }

                    clearServer()

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

                            synchronized(admissionGate) {
                                if (closing || !managerHandle.isCurrent()) return@runCatching
                                val admittedGeneration = ++serverGeneration
                                generation = admittedGeneration
                                server = RtspServer(
                                    appVersion = appVersion,
                                    generation = admittedGeneration,
                                    onEvent = {
                                        synchronized(admissionGate) {
                                            if (it is InternalEvent.RtspServer.OnError && it.generation == generation) {
                                                captureAttempt?.consentReuseIneligible = true
                                            }
                                        }
                                        XLog.d(getLog("RtspServer.onEvent", it.toString()))
                                        sendEvent(it)
                                    },
                                    onRequestKeyFrame = {
                                        if (!closing && managerHandle.isCurrent() && generation == admittedGeneration) {
                                            projectionState.active?.videoEncoder?.requestKeyFrame()
                                        }
                                    }
                                ).apply {
                                    start(netInterfaces.toList(), port, path, protocolPolicy)
                                }
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
                    if (closing || !managerHandle.isCurrent()) return
                    isActive = true
                    currentError = null
                    onvifServer = OnvifServer(
                        context = service,
                        deviceId = rtspSettings.data.value.onvifDeviceId,
                        appVersion = appVersion,
                        protocolPolicy = rtspSettings.data.value.serverProtocol,
                        endpoints = event.endpoints
                    ).also { server ->
                        updateOnvifVideoMetadata(server, projectionState.lastVideoParams)
                        server.setEnabled(rtspSettings.data.value.onvifDiscoveryEnabled)
                    }
                }

                is InternalEvent.RtspServer.OnBindFailures -> {
                    bindings = discoveredBindings.map { item ->
                        RtspBinding(label = item.label, fullAddress = item.fullAddress, bindError = event.failures[item.bindKey])
                    }
                }

                is InternalEvent.RtspServer.OnError -> {
                    stopStream(stopServer = true, stopReason = "RtspServerError")
                    currentError = event.error
                    isActive = false
                }

                is InternalEvent.RtspServer.OnStop -> clearServer()
                is InternalEvent.RtspServer.OnClientStats -> Unit // Intentional to trigger serverClientStats update
                is InternalEvent.RtspServer.OnvifDiscoveryChanged -> {
                    onvifServer?.setEnabled(rtspSettings.data.value.onvifDiscoveryEnabled)
                }
            }
        }

        fun start(coroutineScope: CoroutineScope) {
            statsHeartbeatJob?.cancel()
            statsHeartbeatJob = coroutineScope.launch {
                while (isActive) {
                    sendEvent(InternalEvent.RtspServer.OnClientStats(generation))
                    delay(1000.milliseconds)
                }
            }
        }

        suspend fun stop(stopServer: Boolean) {
            if (server == null) return

            statsHeartbeatJob?.cancel()
            statsHeartbeatJob = null

            if (stopServer) {
                clearServer()
            } else {
                server?.disconnectAllClients()
                server?.clearMediaParams()
                onvifServer?.let { updateOnvifVideoMetadata(it, null) }
            }
        }

        fun setVideoParams(video: VideoParams, width: Int = 0, height: Int = 0) {
            server?.setVideoData(video.codec, video.sps, video.pps, video.vps)
            val currentOnvifServer = onvifServer
            if (currentOnvifServer != null) {
                coroutineScope.launch {
                    if (onvifServer === currentOnvifServer && projectionState.lastVideoParams === video) {
                        updateOnvifVideoMetadata(currentOnvifServer, video, width, height)
                    }
                }
            }
        }

        fun setAudioParams(audio: AudioParams?) {
            server?.setAudioData(audio)
        }

        fun onFrame(frame: MediaFrame) = when (frame) {
            is MediaFrame.VideoFrame -> server?.onVideoFrame(frame) ?: frame.release()
            is MediaFrame.AudioFrame -> server?.onAudioFrame(frame) ?: frame.release()
        }

        private suspend fun updateOnvifVideoMetadata(onvifServer: OnvifServer, videoParams: VideoParams?, width: Int = 0, height: Int = 0) {
            val videoEncoder = projectionState.active?.videoEncoder
            onvifServer.setVideoMetadata(
                videoParams = videoParams,
                width = width.takeIf { it > 0 } ?: videoEncoder?.width ?: 0,
                height = height.takeIf { it > 0 } ?: videoEncoder?.height ?: 0,
                fps = rtspSettings.data.value.videoFps
            )
        }

        private suspend fun clearServer() {
            statsHeartbeatJob?.cancel()
            statsHeartbeatJob = null
            onvifServer?.close()
            onvifServer = null
            generation = ++serverGeneration
            server?.stop()
            server = null
            isActive = false
            discoveredBindings = emptyList()
            bindings = emptyList()
        }
    }

    private inner class RtspClientController {
        var status: RtspClientStatus = RtspClientStatus.IDLE

        private var client: RtspClient? = null

        @Volatile
        private var generation: Long = ++clientGeneration

        fun startClient(rtspUrl: RtspUrl, protocol: RtspSettings.Values.ProtocolPolicy, onlyVideo: Boolean, attempt: CaptureAttempt) {
            if (service.isLocalNetworkPermissionGranted().not()) {
                stop()
                currentError = RtspError.LocalNetworkPermissionRequired()
                status = RtspClientStatus.ERROR
                return
            }
            currentError = null
            synchronized(admissionGate) {
                if (!isAttemptCurrent(attempt)) return
                val admittedGeneration = ++clientGeneration
                generation = admittedGeneration
                client = RtspClient(appVersion, admittedGeneration, rtspUrl, protocol, onlyVideo) { event ->
                    if (generation == admittedGeneration && (event is InternalEvent.Error || event is InternalEvent.RtspClient.OnError)) {
                        invalidateConsentReuse(attempt.id)
                    }
                    if (!closing && managerHandle.isCurrent() && generation == admittedGeneration) {
                        XLog.d(getLog("RtspClient.sendEvent", event.toString()))
                        sendEvent(if (event is InternalEvent.Error) event.copy(attempt = attempt.id) else event)
                    }
                }
            }
        }

        fun connect(attempt: CaptureAttempt) {
            if (service.isLocalNetworkPermissionGranted().not()) {
                stop()
                currentError = RtspError.LocalNetworkPermissionRequired()
                status = RtspClientStatus.ERROR
                return
            }
            currentError = null
            status = RtspClientStatus.STARTING
            synchronized(admissionGate) {
                if (isAttemptCurrent(attempt)) client?.connect()
            }
        }

        fun stop() {
            if (client != null) {
                generation = ++clientGeneration
                client?.destroy(); client = null
            }
            status = RtspClientStatus.IDLE
        }

        fun beginVideoReconfigure() {
            client?.beginVideoReconfigure()
        }

        suspend fun onEvent(event: InternalEvent.RtspClient) {
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
                    stopStream(stopServer = true, stopReason = "RtspClientDisconnect")
                    status = RtspClientStatus.IDLE
                }

                is InternalEvent.RtspClient.OnBitrate -> Unit //TODO Skip for now

                is InternalEvent.RtspClient.OnError -> {
                    stopStream(stopServer = true, stopReason = "RtspClientError")
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
        data class InitState(val mode: RtspSettings.Values.Mode) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnVideoCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnAudioCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class ModeChanged(val mode: RtspSettings.Values.Mode) : InternalEvent(Priority.RECOVER_IGNORE)
        data class StartStream(val clearStartupPolicyError: Boolean = false, val expectedErrorToken: Uuid? = null) : InternalEvent(Priority.RECOVER_IGNORE)
        data object RetryBindings : InternalEvent(Priority.RECOVER_IGNORE)
        data class AudioCaptureError(val cause: Throwable, val attempt: StreamingModule.CaptureAttemptId) : InternalEvent(Priority.RECOVER_IGNORE)

        data class OnAudioParamsChange(val micMute: Boolean, val deviceMute: Boolean, val micVolume: Float, val deviceVolume: Float) :
            InternalEvent(Priority.DESTROY_IGNORE)

        data class ConfigurationChange(val newConfig: Configuration, val attempt: StreamingModule.CaptureAttemptId) : InternalEvent(Priority.RECOVER_IGNORE) {
            override fun toString(): String = "ConfigurationChange"
        }

        data class CapturedContentResize(val width: Int, val height: Int, val attempt: StreamingModule.CaptureAttemptId) :
            InternalEvent(Priority.RECOVER_IGNORE)

        data class Error(val error: RtspError, val attempt: StreamingModule.CaptureAttemptId? = null) : InternalEvent(Priority.RECOVER_IGNORE)
        data object Heartbeat : InternalEvent(Priority.DESTROY_IGNORE)
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
            data class OnStart(override val generation: Long, val endpoints: List<RtspServerEndpoint>) : RtspServer(Priority.RECOVER_IGNORE)
            data class OnStop(override val generation: Long) : RtspServer(Priority.DESTROY_IGNORE)
            data class OnClientStats(override val generation: Long) : RtspServer(Priority.DESTROY_IGNORE)
            data object OnvifDiscoveryChanged : RtspServer(Priority.RECOVER_IGNORE) {
                override val generation: Long = -1L
            }
        }

        data class OnVideoFps(val fps: Int, val attempt: StreamingModule.CaptureAttemptId) : InternalEvent(Priority.DESTROY_IGNORE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private fun componentCallback(attempt: CaptureAttempt): ComponentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = sendEvent(InternalEvent.ConfigurationChange(newConfig, attempt.id))
        override fun onLowMemory() = Unit
    }

    private fun projectionCallback(attempt: CaptureAttempt): MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onStop() {
            invalidateConsentReuse(attempt.id)
            if (!isAttemptCurrent(attempt)) return
            requestAttemptStop(attempt)
            sendEvent(RtspEvent.StopStream("ProjectionCoordinator.onStop", attempt.id))
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) = Unit
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (isAttemptCurrent(attempt)) sendEvent(InternalEvent.CapturedContentResize(width, height, attempt.id))
        }
    }

    private fun requestAttemptStop(attempt: CaptureAttempt) {
        synchronized(admissionGate) {
            if (captureAttempt !== attempt) return
            attempt.stopRequested = true
            sessionAnalytics.closeUnentered(attempt.id)
            attempt.job.cancel()
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
        sendEvent(InternalEvent.Heartbeat)

        fun <T> Flow<T>.listenForChange(scope: CoroutineScope, drop: Int = 0, action: suspend (T) -> Unit) =
            distinctUntilChanged().drop(drop).onEach { action(it) }.launchIn(scope)

        service.startListening(
            supervisorJob,
            onScreenOff = {
                if (rtspSettings.data.value.stopOnSleep) captureAttempt?.let { sendEvent(RtspEvent.StopStream("ScreenOff", it.id)) }
            },
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
            if (!settingsLoaded) {
                settingsLoaded = true
                sendEvent(InternalEvent.InitState(mode = mode))
                if (mode == RtspSettings.Values.Mode.SERVER) {
                    sendEvent(InternalEvent.RtspServer.DiscoverAddress(reason = "InitState"))
                }
            } else {
                sendEvent(InternalEvent.ModeChanged(mode))
            }
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

        rtspSettings.data.map { it.onvifDiscoveryEnabled }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RtspServer.OnvifDiscoveryChanged)
        }

        coroutineScope.launch {
            delay(250.milliseconds)
            if (settingsLoaded) return@launch

            settingsLoaded = true
            val mode = rtspSettings.data.value.mode
            sendEvent(InternalEvent.InitState(mode = mode))
            if (mode == RtspSettings.Values.Mode.SERVER) {
                sendEvent(InternalEvent.RtspServer.DiscoverAddress(reason = "InitStateFallback"))
            }
        }
    }

    /** Existing thread-affine, bounded best-effort shutdown; physical media release is not confirmed. */
    private suspend fun destroyWorker() {
        supervisorJob.cancel()
        val destroyJob = Job()
        sendEvent(InternalEvent.Destroy(destroyJob))
        val processed = withTimeoutOrNull(3000.milliseconds) { destroyJob.join(); true } == true
        if (!processed) invalidateConsentReuse()
        handler.removeCallbacksAndMessages(null)
        quit()
        check(processed) { "RTSP best-effort shutdown timed out" }
    }

    private var destroyPending: Boolean = false

    @AnyThread
    @Synchronized
    internal fun sendEvent(event: RtspEvent, timeout: Long = 0) {
        if (event is InternalEvent.Error) event.attempt?.let(::invalidateConsentReuse)
        if (destroyPending || (closing && event !is InternalEvent.Destroy)) {
            when (event) {
                is InternalEvent.StartStream,
                is RtspEvent.StartProjection -> Unit
            }
            XLog.w(getLog("sendEvent", "Pending destroy: Ignoring event => $event"))
            return
        }
        if (event is InternalEvent.Destroy) destroyPending = true

        if (timeout > 0) XLog.d(getLog("sendEvent", "New event [Timeout: $timeout] => $event"))
        else XLog.v(getLog("sendEvent", "New event => $event"))

        if (event is InternalEvent.RtspServer.DiscoverAddress) {
            handler.removeMessages(RtspEvent.Priority.RESTART_IGNORE)
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(RtspEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(RtspEvent.Priority.DESTROY_IGNORE)
            handler.removeMessages(RtspEvent.Priority.START_PROJECTION)
        }
        if (event is RtspEvent.StartProjection) {
            if (handler.hasMessages(RtspEvent.Priority.START_PROJECTION)) {
                XLog.i(getLog("sendEvent", "Replacing pending StartProjection"))
            }
            handler.removeMessages(RtspEvent.Priority.START_PROJECTION)
        }

        val wasSent = handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
        if (!wasSent) XLog.e(getLog("sendEvent", "Failed to send event: $event"))
    }

    private fun buildViewState(): RtspState {
        val selectedMode = initializedMode ?: rtspSettings.data.value.mode
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
                val clientReady = clientController != null
                val videoReady = selectedVideoEncoderInfo != null
                val audioReady = audioEnabled.not() || selectedAudioEncoderInfo != null
                (clientReady && videoReady && audioReady).not()
            }
        }
        val errorBlocks = currentError != null && currentError !is RtspError.ClientError
        val isBusy =
            closing || destroyPending || captureAttempt?.stopRequested == true || !settingsLoaded || initializedMode == null || projectionState.pendingStartAttemptId != null || errorBlocks || readinessBusy

        return RtspState(
            mode = selectedMode,
            clientStatus = status,
            serverBindings = if (isServerMode) serverController?.bindings.orEmpty() else emptyList(),
            isBusy = isBusy,
            waitingCastPermission = projectionState.waitingForPermission,
            startAttemptId = projectionState.pendingStartAttemptId,
            isStreaming = captureAttempt?.let { it.started && !it.stopRequested } == true,
            captureAttempt = captureAttempt?.id,
            consentRetryToken = consentRetryToken,
            errorToken = errorToken,
            selectedVideoEncoder = selectedVideoEncoderInfo,
            selectedAudioEncoder = selectedAudioEncoderInfo,
            serverClientStats = serverController?.statsSnapshot.orEmpty(),
            error = currentError
        )
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking {
        val event: RtspEvent = msg.obj as RtspEvent
        try {
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@RtspStreamingService.getLog("handleMessage.catch", cause.toString()), cause)
            if (event is InternalEvent.Destroy) cleanupFailed = true

            invalidateConsentReuse()
            sessionAnalytics.workerFailed(captureAttempt?.id)
            projectionState.pendingStartAttemptId = null
            projectionState.waitingForPermission = false
            stopStream(stopServer = true, stopReason = "HandleMessageException")

            currentError = cause as? RtspError ?: RtspError.UnknownError(cause)
            if ((initializedMode ?: rtspSettings.data.value.mode) == RtspSettings.Values.Mode.SERVER) {
                serverController?.isActive = false
            } else {
                clientController?.status = RtspClientStatus.ERROR
            }
        } finally {
            if (event is InternalEvent.Destroy) event.destroyJob.complete()
            sessionAnalytics.consumersChanged(captureAttempt?.id, currentActiveConsumersCount(), SystemClock.elapsedRealtime())

            publishError()
            mutableRtspStateFlow.value = buildViewState()
            if (!closing && managerHandle.isCurrent()) {
                managerHandle.reportRunning(
                    StreamingModule.Status(
                        isStreaming = captureAttempt?.let { it.started && !it.stopRequested } == true,
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

    // On RTSP-HT only
    private suspend fun processEvent(event: RtspEvent) {
        if ((closing || !managerHandle.isCurrent()) && event !is InternalEvent.Destroy) return
        if (event is RtspEvent.StopStream && event.expectedAttempt != null && event.expectedAttempt != captureAttempt?.id) return
        if (event is RtspEvent.RecoverError && event.errorToken != null && event.errorToken != errorToken) return
        val observationAttempt = when (event) {
            is InternalEvent.AudioCaptureError -> event.attempt
            is InternalEvent.CapturedContentResize -> event.attempt
            is InternalEvent.ConfigurationChange -> event.attempt
            is InternalEvent.OnVideoFps -> event.attempt
            is InternalEvent.Error -> event.attempt
            else -> null
        }
        if (observationAttempt != null && (captureAttempt?.id != observationAttempt || captureAttempt?.stopRequested == true)) return
        when (event) {
            InternalEvent.Heartbeat -> {
                if (!closing) sendEvent(InternalEvent.Heartbeat, timeout = 1000)
            }

            is InternalEvent.InitState -> {
                serverController = null
                clientController = null
                if (event.mode == RtspSettings.Values.Mode.SERVER) {
                    serverController = RtspServerController()
                } else {
                    clientController = RtspClientController()
                }
                initializedMode = event.mode
                consentRetryToken = null
                resizeActor?.close()
                resizeActor = null
                currentError = null
                audioCaptureDisabled = false
                audioIssueToastShown = false
            }

            is InternalEvent.OnVideoCodecChange -> {
                if (captureAttempt != null) return
                selectedVideoEncoderInfo = resolveVideoEncoder(event.name)
            }

            is InternalEvent.OnAudioCodecChange -> {
                if (captureAttempt != null) return
                val settings = rtspSettings.data.value
                selectedAudioEncoderInfo = resolveAudioEncoder(event.name, settings.enableMic || settings.enableDeviceAudio)
            }

            is InternalEvent.ModeChanged -> {
                if (event.mode == initializedMode) {
                    XLog.d(getLog("ModeChanged", "Already initialized for mode=$initializedMode. Ignoring."))
                    return
                }
                stopStream(stopServer = true, stopReason = "ModeChanged")

                sendEvent(InternalEvent.InitState(mode = event.mode))

                if (event.mode == RtspSettings.Values.Mode.SERVER) {
                    sendEvent(InternalEvent.RtspServer.DiscoverAddress(reason = "ModeChanged"))
                }
            }

            is InternalEvent.StartStream -> {
                if (!settingsLoaded || initializedMode == null || captureAttempt != null) return
                if (event.expectedErrorToken != null && event.expectedErrorToken != errorToken) return
                if (event.clearStartupPolicyError && currentError?.isStartupPolicyError() == true) currentError = null
                val settings = rtspSettings.data.value
                val audioEnabled = settings.enableMic || settings.enableDeviceAudio
                if (audioEnabled && ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    sessionAnalytics.audioPermissionMissingBeforeConsent(settings.mode)
                    currentError = RtspError.AudioPermissionRequired()
                    return
                }
                if (currentError != null && currentError !is RtspError.ClientError) return
                val videoEncoder = resolveVideoEncoder(settings.videoCodec.takeUnless { settings.videoCodecAutoSelect })
                val audioEncoder = resolveAudioEncoder(settings.audioCodec.takeUnless { settings.audioCodecAutoSelect }, audioEnabled)
                val ready = when (settings.mode) {
                    RtspSettings.Values.Mode.SERVER -> serverController?.isActive == true
                    RtspSettings.Values.Mode.CLIENT -> clientController != null
                }
                if (!ready) return
                if (!service.isLocalNetworkPermissionGranted()) {
                    currentError = RtspError.LocalNetworkPermissionRequired()
                    return
                }
                selectedVideoEncoderInfo = videoEncoder
                selectedAudioEncoderInfo = audioEncoder
                val attempt = synchronized(admissionGate) {
                    if (closing || !managerHandle.isCurrent() || captureAttempt != null) return
                    CaptureAttempt(
                        id = StreamingModule.CaptureAttemptId(instanceId),
                        settings = settings,
                        videoEncoder = videoEncoder,
                        audioEncoder = audioEncoder,
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
                                    sessionAnalytics.finishForegroundCall(
                                        attempt.id, foregroundFailure, attempt.settings.enableMic || attempt.settings.enableDeviceAudio
                                    )
                                }
                            },
                        )
                        when (consent) {
                            is ScreenCaptureAccess.ConsentResult.Granted -> {
                                attempt.grant = consent.grant
                                sendEvent(RtspEvent.StartProjection(attempt.id, consent.grant))
                            }

                            is ScreenCaptureAccess.ConsentResult.NotGranted ->
                                sendEvent(RtspEvent.ConsentNotGranted(attempt.id, consent.reason))
                        }
                    } catch (cause: CancellationException) {
                        // Stop/shutdown owns the thread-affine best-effort cleanup.
                        throw cause
                    } catch (cause: Throwable) {
                        sendEvent(RtspEvent.ConsentFailed(attempt.id, cause))
                    } finally {
                        sessionAnalytics.consentCallFinished(attempt.id)
                    }
                }
            }

            is RtspEvent.ConsentNotGranted -> {
                val attempt = captureAttempt?.takeIf { it.id == event.attempt } ?: return
                sessionAnalytics.consentNotGranted(attempt.id, event.reason)
                stopStream(stopServer = false)
                attempt.preflightError?.let { currentError = it }
                if (event.reason == ScreenCaptureAccess.NoGrantReason.Declined) consentRetryToken = Uuid.random()
            }

            is RtspEvent.ConsentFailed -> {
                if (captureAttempt?.id != event.attempt) return
                sessionAnalytics.consentFailed(event.attempt, event.cause)
                stopStream(stopServer = false)
                if (event.cause is ConsentLaunchException) {
                    consentRetryToken = Uuid.random()
                } else {
                    currentError = event.cause as? RtspError ?: RtspError.ScreenCaptureStartBlocked(event.cause)
                }
            }

            is RtspEvent.RetryConsent -> {
                if (event.token != consentRetryToken) return
                consentRetryToken = null
                if (event.retry) sendEvent(InternalEvent.StartStream(clearStartupPolicyError = true))
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

            is RtspEvent.StartProjection -> {
                val attempt = captureAttempt?.takeIf { it.id == event.attempt } ?: return
                if (!isAttemptCurrent(attempt)) return
                val settings = attempt.settings
                val modeLocal = settings.mode
                val audioPermissionGranted = ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if ((settings.enableMic || settings.enableDeviceAudio) && !audioPermissionGranted) {
                    sessionAnalytics.audioPermissionMissing(attempt.id)
                    stopStream(stopServer = false)
                    currentError = RtspError.AudioPermissionRequired()
                    return
                }
                val wantsMicrophoneForSession = audioPermissionGranted && settings.enableMic
                val wantsDeviceAudioForSession = audioPermissionGranted && settings.enableDeviceAudio
                val audioCaptureAllowed = wantsMicrophoneForSession || wantsDeviceAudioForSession
                val serverController = if (modeLocal == RtspSettings.Values.Mode.SERVER) serverController else null
                val clientController = if (modeLocal == RtspSettings.Values.Mode.CLIENT) clientController else null
                val clientRtspUrl = if (modeLocal == RtspSettings.Values.Mode.CLIENT) {
                    try {
                        RtspUrl.parse(settings.serverAddress)
                    } catch (cause: URISyntaxException) {
                        sessionAnalytics.invalidClientAddress(attempt.id)
                        stopStream(stopServer = true)
                        currentError = RtspError.ClientError.Failed(cause.reason ?: cause.message)
                        return
                    }
                } else null
                val setVideoParams: (VideoParams, Int, Int) -> Unit = { video, width, height ->
                    if (isAttemptCurrent(attempt)) {
                        if (modeLocal == RtspSettings.Values.Mode.SERVER) serverController?.setVideoParams(video, width, height)
                        else clientController?.setVideoParams(video)
                    }
                }
                val setAudioParams: (AudioParams?) -> Unit = { audio ->
                    if (isAttemptCurrent(attempt)) {
                        if (modeLocal == RtspSettings.Values.Mode.SERVER) serverController?.setAudioParams(audio)
                        else clientController?.setAudioParams(audio)
                    }
                }
                val onFrame: (MediaFrame) -> Unit = { frame ->
                    if (!isAttemptCurrent(attempt)) frame.release()
                    else if (modeLocal == RtspSettings.Values.Mode.SERVER) serverController?.onFrame(frame) ?: frame.release()
                    else clientController?.onFrame(frame) ?: frame.release()
                }
                projectionState.pendingStartAttemptId = null
                projectionState.waitingForPermission = false
                try {
                    val mediaProjection = captureAccess.createProjection(event.grant, retryAfterRecentForegroundStart = true)
                    attempt.projection = mediaProjection
                    check(isAttemptCurrent(attempt)) { "Capture admission closed during projection acquisition" }
                    captureAccess.finishProjectionCreation(attempt.id)
                    val projectionCallback = projectionCallback(attempt)
                    attempt.callback = projectionCallback
                    val isStartupStillValid = { isAttemptCurrent(attempt) }
                    val buildPipeline: () -> Boolean = buildPipeline@{
                        if (!isStartupStillValid()) return@buildPipeline false
                        // TODO Starting from Android R, if your application requests the SYSTEM_ALERT_WINDOW permission, and the user has
                        //  not explicitly denied it, the permission will be automatically granted until the projection is stopped.
                        //  The permission allows your app to display user controls on top of the screen being captured.
                        mediaProjection.registerCallback(projectionCallback, handler)

                        MasterClock.reset()
                        MasterClock.ensureStarted()

                        var virtualDisplay: VirtualDisplay? = null
                        var captureSurface: Surface? = null
                        val deviceConfiguration = Configuration(service.resources.configuration)
                        val videoEncoderInfo = attempt.videoEncoder
                        val videoCapabilities = videoEncoderInfo.capabilities.videoCapabilities!!
                        val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                        val sourceWidth = bounds.width()
                        val sourceHeight = bounds.height()
                        val (_, encodedWidth, encodedHeight) = videoCapabilities.adjustResizeFactor(
                            sourceWidth, sourceHeight, settings.videoResizeFactor / 100
                        )

                        val videoEncoder = VideoEncoder(
                            codecInfo = videoEncoderInfo,
                            onVideoInfo = { sps, pps, vps ->
                                val params = VideoParams(videoEncoderInfo.codec, sps, pps, vps)
                                if (isAttemptCurrent(attempt)) {
                                    attempt.projectionState.lastVideoParams = params
                                    setVideoParams(params, encodedWidth, encodedHeight)
                                }
                            },
                            onVideoFrame = onFrame,
                            onFps = { sendEvent(InternalEvent.OnVideoFps(it, attempt.id)) },
                            onError = {
                                XLog.w(getLog("VideoEncoder.onError", it.message), it)
                                sendEvent(InternalEvent.Error(it.toVideoPipelineError(), attempt.id))
                            }
                        ).apply {
                            if (!isStartupStillValid()) return@buildPipeline false
                            prepare(
                                encodedWidth,
                                encodedHeight,
                                fps = settings.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange()),
                                bitRate = settings.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())
                            )
                            if (!isStartupStillValid()) {
                                XLog.i(getLog("StartProjection", "Startup invalidated before virtual display creation."))
                                stop()
                                mediaProjection.unregisterCallback(projectionCallback)
                                return@buildPipeline false
                            }

                            this.inputSurfaceTexture?.let { surfaceTexture ->
                                captureSurface = Surface(surfaceTexture)
                                virtualDisplay = mediaProjection.createVirtualDisplay(
                                    "ScreenStreamVirtualDisplay",
                                    encodedWidth,
                                    encodedHeight,
                                    service.resources.displayMetrics.densityDpi,
                                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                    captureSurface,
                                    null,
                                    null
                                )
                            }

                            if (virtualDisplay == null || !isStartupStillValid()) {
                                val reason = if (virtualDisplay == null) "virtualDisplay is null" else "startup invalidated"
                                XLog.i(getLog("startDisplayCapture", "$reason. Stopping projection."))
                                stop()
                                mediaProjection.unregisterCallback(projectionCallback)
                                runCatching { captureSurface?.release() }
                                return@buildPipeline false
                            }

                            start()
                        }

                        val microphoneEnabledForSession = wantsMicrophoneForSession && audioCaptureAllowed
                        val deviceAudioEnabledForSession = wantsDeviceAudioForSession
                        val audioEnabledForSession = microphoneEnabledForSession || deviceAudioEnabledForSession
                        var audioEncoder: AudioEncoder? = null
                        if (audioEnabledForSession) {
                            val audioEncoderInfo = attempt.audioEncoder!!
                            audioEncoder = AudioEncoder(
                                codecInfo = audioEncoderInfo,
                                onAudioInfo = { params ->
                                    val audioParams = AudioParams(audioEncoderInfo.codec, params.sampleRate, params.isStereo)
                                    if (isAttemptCurrent(attempt)) {
                                        attempt.projectionState.lastAudioParams = audioParams
                                        setAudioParams(audioParams)
                                    }
                                },
                                onAudioFrame = onFrame,
                                onAudioCaptureError = { sendEvent(InternalEvent.AudioCaptureError(it, attempt.id)) },
                                onError = {
                                    XLog.w(getLog("AudioEncoder.onError", it.message), it)
                                    sendEvent(InternalEvent.Error(it.toAudioPipelineError(), attempt.id))
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
                                    enableMic = microphoneEnabledForSession,
                                    enableDeviceAudio = deviceAudioEnabledForSession,
                                    dispatcher = Dispatchers.IO,
                                    audioParams = paramsFromSettings,
                                    audioSource = MediaRecorder.AudioSource.DEFAULT,
                                    mediaProjection = mediaProjection,
                                )

                                val liveSettings = rtspSettings.data.value
                                setMute(liveSettings.muteMic, liveSettings.muteDeviceAudio)
                                setVolume(liveSettings.volumeMic, liveSettings.volumeDeviceAudio)
                                start()
                            }
                        } else {
                            projectionState.lastAudioParams = null
                            setAudioParams(null)
                        }

                        if (!isStartupStillValid()) {
                            XLog.i(getLog("StartProjection", "Startup invalidated after encoder startup."))
                            videoEncoder.stop()
                            virtualDisplay?.surface = null
                            virtualDisplay?.release()
                            runCatching { captureSurface?.release() }
                            audioEncoder?.stop()
                            mediaProjection.unregisterCallback(projectionCallback)
                            return@buildPipeline false
                        }

                        projectionState.active = ActiveProjection(
                            mediaProjection = mediaProjection,
                            virtualDisplay = virtualDisplay!!,
                            videoEncoder = videoEncoder,
                            captureSurface = captureSurface ?: run {
                                XLog.i(getLog("StartProjection", "captureSurface is null. Stopping projection."))
                                videoEncoder.stop()
                                mediaProjection.unregisterCallback(projectionCallback)
                                return@buildPipeline false
                            },
                            audioEncoder = audioEncoder,
                            deviceConfiguration = deviceConfiguration,
                            onVideoReconfigureStart = { clientController?.beginVideoReconfigure() }
                        )
                        resizeActor?.close()
                        resizeActor = ResizeConflateActor(
                            projection = projectionState.active!!,
                            initialEncodedWidth = encodedWidth,
                            initialEncodedHeight = encodedHeight,
                            attempt = attempt,
                        )
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val componentCallback = componentCallback(attempt)
                            attempt.componentCallback = componentCallback
                            service.registerComponentCallbacks(componentCallback)
                        }
                        true
                    }
                    check(buildPipeline()) { "RTSP projection startup interrupted" }
                    if (!isAttemptCurrent(attempt)) {
                        stopStream(stopServer = false)
                        return
                    }
                    attempt.started = true
                    withContext(Dispatchers.Main.immediate) {
                        if (isAttemptCurrent(attempt)) service.updateCaptureNotification(attempt.id, active = true)
                    }
                    if (!isAttemptCurrent(attempt)) {
                        sessionAnalytics.aborted(attempt.id)
                        return
                    }
                    when (modeLocal) {
                        RtspSettings.Values.Mode.SERVER -> serverController?.start(coroutineScope)
                        RtspSettings.Values.Mode.CLIENT -> {
                            clientController?.startClient(
                                checkNotNull(clientRtspUrl),
                                settings.clientProtocol,
                                audioCaptureDisabled || !audioCaptureAllowed,
                                attempt
                            )
                            projectionState.lastVideoParams?.let { clientController?.setVideoParams(it) }
                            clientController?.setAudioParams(projectionState.lastAudioParams)
                            clientController?.connect(attempt)
                        }
                    }
                    if (!isAttemptCurrent(attempt)) {
                        sessionAnalytics.aborted(attempt.id)
                        return
                    }
                    currentError = null
                    sessionAnalytics.started(attempt.id, currentActiveConsumersCount(), SystemClock.elapsedRealtime())
                } catch (cause: Throwable) {
                    if (attempt.stopRequested || closing) {
                        sessionAnalytics.aborted(attempt.id)
                        stopStream(stopServer = false)
                        return
                    }
                    sessionAnalytics.projectionFailed(attempt.id, cause)
                    stopStream(stopServer = false)
                    currentError = when (cause) {
                        is RtspError -> cause
                        is SecurityException -> RtspError.ProjectionAcquireRejected(cause)
                        else -> RtspError.UnknownError(cause)
                    }
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
                projectionState.active?.audioEncoder = null
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
                    sendEvent(RtspEvent.StopStream("ConfigurationChange", event.attempt))
                    return
                }

                val newConfig = Configuration(event.newConfig)
                val configDiff = projection.deviceConfiguration.diff(newConfig)
                projection.deviceConfiguration = newConfig
                if (configDiff and ActivityInfo.CONFIG_ORIENTATION != 0
                    || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0
                    || configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0
                    || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                ) {
                    val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                    resizeActor?.offer(sourceWidth = bounds.width(), sourceHeight = bounds.height())
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
                resizeActor?.offer(sourceWidth = event.width, sourceHeight = event.height)
            }

            is RtspEvent.StopStream -> stopStream(stopServer = false, stopReason = event.reason)

            is RtspEvent.RecoverError,
            is InternalEvent.Destroy,
            is InternalEvent.Error -> {
                val stopReason = when (event) {
                    is RtspEvent.RecoverError -> "RecoverError"
                    is InternalEvent.Destroy -> "Destroy"
                    is InternalEvent.Error -> "InternalError"
                    else -> null
                }
                stopStream(stopServer = true, stopReason = stopReason)

                if (event is InternalEvent.Error) {
                    currentError = event.error
                    if (rtspSettings.data.value.mode == RtspSettings.Values.Mode.SERVER) {
                        serverController?.isActive = false
                    } else {
                        clientController?.status = RtspClientStatus.ERROR
                    }
                }

                if (event is RtspEvent.RecoverError) {
                    consentRetryToken = null
                    handler.removeMessages(RtspEvent.Priority.RESTART_IGNORE)
                    handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
                    handler.removeMessages(RtspEvent.Priority.START_PROJECTION)
                    val mode = rtspSettings.data.value.mode
                    sendEvent(InternalEvent.InitState(mode = mode))
                    if (mode == RtspSettings.Values.Mode.SERVER) {
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

    /** Retains the existing best-effort media shutdown; completion does not prove physical release. */
    private suspend fun stopStream(stopServer: Boolean, stopReason: String? = null) {
        val attempt = captureAttempt
        val wasStreaming = attempt?.started == true
        if (attempt != null && !wasStreaming) sessionAnalytics.aborted(attempt.id)
        if (attempt != null) {
            requestAttemptStop(attempt)
            if (!closing) service.updateCaptureNotification(attempt.id, active = false)
        }
        val activeConsumersAtStop = currentActiveConsumersCount()
        resizeActor?.close()
        resizeActor = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            attempt?.componentCallback?.let { callback -> runCatching { service.unregisterComponentCallbacks(callback) } }
        }
        var succeeded = true
        fun bestEffort(action: () -> Unit) {
            runCatching(action).onFailure {
                succeeded = false
                attempt?.let { invalidateConsentReuse(it.id) }
                XLog.w(getLog("stopStream", "Best-effort cleanup failed"), it)
            }
        }
        bestEffort { clientController?.stop() }
        runCatching { serverController?.stop(stopServer) }
            .onFailure {
                succeeded = false
                attempt?.let { invalidateConsentReuse(it.id) }
                XLog.w(getLog("stopStream", "Server cleanup failed"), it)
            }
        if (attempt != null) {
            attempt.projectionState.pendingStartAttemptId = null
            attempt.projectionState.waitingForPermission = false
            val projection = attempt.projection
            val callback = attempt.callback
            if (callback != null) bestEffort { attempt.projectionState.active?.stop(callback) }
            if (callback != null && projection != null) bestEffort { projection.unregisterCallback(callback) }
            bestEffort { projection?.stop() }
            bestEffort { captureAccess.finishProjectionCreation(attempt.id) }
            attempt.projectionState.active = null
            attempt.projectionState.lastVideoParams = null
            attempt.projectionState.lastAudioParams = null
            if (wasStreaming) sessionAnalytics.ended(attempt.id, stopReason, activeConsumersAtStop, SystemClock.elapsedRealtime())
            val requestedStop = stopReason?.contains("User action") == true || stopReason == "Coordinator Stop" ||
                    stopReason == "Destroy" || stopReason == "ModeChanged"
            bestEffort {
                synchronized(admissionGate) {
                    if (captureAttempt === attempt && succeeded && attempt.started && requestedStop && !attempt.consentReuseIneligible) {
                        attempt.grant?.let { captureAccess.saveConsentForReuse(it) }
                    }
                }
            }
            if (!closing) service.leaveCaptureForeground(attempt.id)
            synchronized(admissionGate) { if (captureAttempt === attempt) captureAttempt = null }
            if (!closing) {
                val settings = rtspSettings.data.value
                selectedVideoEncoderInfo = resolveVideoEncoder(settings.videoCodec.takeUnless { settings.videoCodecAutoSelect })
                selectedAudioEncoderInfo = resolveAudioEncoder(
                    settings.audioCodec.takeUnless { settings.audioCodecAutoSelect }, settings.enableMic || settings.enableDeviceAudio
                )
            }
        }
        if (!succeeded) cleanupFailed = true
        audioCaptureDisabled = false
        audioIssueToastShown = false
    }

    private fun resolveVideoEncoder(name: String?): VideoCodecInfo {
        val available = EncoderUtils.availableVideoEncoders
        check(available.isNotEmpty()) { "No suitable video encoders available" }
        return available.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: available.first()
    }

    private fun resolveAudioEncoder(name: String?, audioEnabled: Boolean): AudioCodecInfo? {
        val available = EncoderUtils.availableAudioEncoders
        check(!audioEnabled || available.isNotEmpty()) { "No suitable audio encoders available" }
        return available.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: available.firstOrNull()
    }

    private fun publishError() {
        synchronized(errorGate) {
            if (errorNotificationsClosed || closing || !managerHandle.isCurrent() || previousError === currentError) return
            previousError = currentError
            errorToken = currentError?.let { Uuid.random() }
            val error = currentError?.takeUnless {
                it is RtspError.ClientError || it is RtspError.NotificationPermissionRequired || it is RtspError.LocalNetworkPermissionRequired
            }
            val content = error?.let {
                val recovery = if (it.isStartupPolicyError()) null else
                    StreamingModuleActionReceiver.recoveryAction(service, instanceId, checkNotNull(errorToken))
                StreamingModuleNotifications.ErrorContent.Text(it.toString(ContextCompat.getContextForLanguage(service)), recovery)
            }
            notifications.updateError(310, content)
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun currentActiveConsumersCount(): Int {
        val serverConsumers = serverController?.statsSnapshot?.count { it.lastSentAtMs > 0L } ?: 0
        val clientConsumers = if (clientController?.status == RtspClientStatus.ACTIVE) 1 else 0
        return maxOf(serverConsumers, clientConsumers)
    }

    private fun showAudioCaptureIssueToastOnce() {
        if (audioIssueToastShown) return
        audioIssueToastShown = true
        val attempt = captureAttempt?.id
        mainHandler.post {
            if (!closing && managerHandle.isCurrent() && captureAttempt?.id == attempt) {
                Toast.makeText(service, R.string.rtsp_audio_capture_issue_detected, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun Throwable.toVideoPipelineError(): RtspError.UnknownError =
        if (this is MediaCodec.CodecException) RtspError.VideoCodecError(this)
        else RtspError.VideoRendererError(this)

    private fun Throwable.toVideoReconfigureError(): RtspError.UnknownError = RtspError.VideoReconfigureError(this)

    private fun Throwable.toAudioPipelineError(): RtspError.UnknownError =
        if (this is MediaCodec.CodecException) RtspError.AudioCodecError(this)
        else RtspError.UnknownError(this)
}
