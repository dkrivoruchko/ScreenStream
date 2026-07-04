package info.dvkr.screenstream.webrtc.internal

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsTracker
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.getVersionName
import info.dvkr.screenstream.common.module.ProjectionCoordinator
import info.dvkr.screenstream.common.module.isStreamingModuleStartBlocked
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.WebRtcModuleService
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings
import info.dvkr.screenstream.webrtc.ui.WebRtcError
import info.dvkr.screenstream.webrtc.ui.WebRtcState
import info.dvkr.screenstream.webrtc.ui.isExpectedEnvironmentIssue
import info.dvkr.screenstream.webrtc.ui.isStartupPolicyError
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.IceServer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

internal class WebRtcStreamingService(
    private val service: WebRtcModuleService,
    private val mutableWebRtcStateFlow: MutableStateFlow<WebRtcState>,
    private val environment: WebRtcEnvironment,
    private val webRtcSettings: WebRtcSettings,
    private val streamingAnalytics: StreamingAnalytics
) : HandlerThread("WebRTC-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback {

    private data class ClientSession(val key: ClientSessionKey, val client: WebRtcClient)

    private val versionName = service.getVersionName("com.google.android.gms", "-")
    private val powerManager: PowerManager = service.application.getSystemService(PowerManager::class.java)
    private val connectivityManager: ConnectivityManager = service.application.getSystemService(ConnectivityManager::class.java)
    private val projectionManager: MediaProjectionManager = service.application.getSystemService(MediaProjectionManager::class.java)
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper, this) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("WebRTC-HT_Dispatcher") }
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(SupervisorJob() + coroutineDispatcher) }
    private val okHttpClient = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val playIntegrity = PlayIntegrity(service, environment, okHttpClient)
    private val currentError: AtomicReference<WebRtcError?> = AtomicReference(null)
    private val projectionCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        ProjectionCoordinator(
            tag = "WEBRTC",
            projectionManager = projectionManager,
            callbackHandler = handler,
            startForeground = { fgsType -> service.startForeground(fgsType) },
            onProjectionStopped = { generation ->
                XLog.i(getLog("ProjectionCoordinator.onStop", "g=$generation, streaming=${isStreaming()}"))
                sendEvent(WebRtcEvent.Intentable.StopStream("ProjectionCoordinator.onStop[generation=$generation]"))
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
        val settings = webRtcSettings.data.value
        val usePendingAudio = pendingStartAttemptId != null
        val userRequestedMic = if (usePendingAudio) pendingStartMicRequested else settings.enableMic
        val userRequestedDeviceAudio = if (usePendingAudio) pendingStartDeviceAudioRequested else settings.enableDeviceAudio
        val hasAudioPermission =
            ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission && (userRequestedMic || userRequestedDeviceAudio)) {
            return WebRtcError.AudioPermissionRequired()
        }
        val wantsAudioForegroundService = hasAudioPermission && (userRequestedMic || userRequestedDeviceAudio)
        val foregroundStartError = projectionCoordinator.startForegroundForProjection(wantsAudioForegroundService)
        val audioMode = when {
            hasAudioPermission && userRequestedMic && userRequestedDeviceAudio -> "both"
            hasAudioPermission && userRequestedMic -> "mic"
            hasAudioPermission && userRequestedDeviceAudio -> "device"
            else -> "none"
        }
        XLog.i(
            getLog(
                "tryStartProjectionForeground",
                "SP_TRACE route=preflight_v1 stage=foreground_preflight audioMode=$audioMode hasAudioPermission=$hasAudioPermission result=${foregroundStartError?.javaClass?.simpleName ?: "ok"}"
            )
        )
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
            streamModeProvider = { StreamMode.WEBRTC },
            nowElapsedRealtimeMs = { SystemClock.elapsedRealtime() }
        )
    }

    // All Volatile vars must be write on this (WebRTC-HT) thread
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var currentStreamId: StreamId = StreamId.EMPTY
    @Volatile private var currentStreamPassword: StreamPassword = StreamPassword.EMPTY
    @Volatile private var projection: WebRtcProjection? = null
    // All Volatile vars must be write on this (WebRTC-HT) thread

    // All vars must be read/write on this (WebRTC-HT) thread
    private var deviceConfiguration: Configuration = Configuration(service.resources.configuration)
    private var signalingRecoveryAttempts: Int = 0
    private var signaling: SocketSignaling? = null
    @Volatile private var pendingStartAttemptId: String? = null
    private var waitingForPermission: Boolean = false
    private var mediaProjectionIntent: Intent? = null
    @Volatile private var pendingStartMicRequested: Boolean = false
    @Volatile private var pendingStartDeviceAudioRequested: Boolean = false
    @Volatile private var foregroundPreflightStartAttemptId: String? = null
    private var clients: MutableMap<ClientId, ClientSession> = HashMap()
    private var previousError: WebRtcError? = null
    private var audioIssueToastShown: Boolean = false
    private var streamRecreateInFlight: Boolean = false
    private var signalingTerminalError: Boolean = false
    private val signalingRecoveryMaxAttempts: Int = 20
    private val negotiationTimeoutMs: Long = 20_000L
    private val peerDisconnectedGraceMs: Long = 10_000L
    // All vars must be read/write on this (WebRTC-HT) thread

    internal sealed class InternalEvent(priority: Int) : WebRtcEvent(priority) {
        data object InitState : InternalEvent(Priority.RECOVER_IGNORE)
        data class GetNonce(val attempt: Int, val forceTokenUpdate: Boolean) : InternalEvent(Priority.RECOVER_IGNORE)
        data class GetToken(val nonce: String, val attempt: Int, val forceUpdate: Boolean) : InternalEvent(Priority.RECOVER_IGNORE)
        data class OpenSocket(val token: PlayIntegrityToken) : InternalEvent(Priority.RECOVER_IGNORE)
        data class RecoverSignaling(val forceTokenUpdate: Boolean, val reason: String) : InternalEvent(Priority.RECOVER_IGNORE)
        data object StreamCreate : InternalEvent(Priority.RECOVER_IGNORE)
        data class StreamCreated(val streamId: StreamId) : InternalEvent(Priority.RECOVER_IGNORE)
        data object StreamRemoved : InternalEvent(Priority.RECOVER_IGNORE)
        data class ClientJoin(val clientId: ClientId, val joinAttemptId: AttemptId, val iceServers: List<IceServer>) : InternalEvent(Priority.RECOVER_IGNORE)
        data class ClientLeave(val clientId: ClientId, val joinAttemptId: AttemptId) : InternalEvent(Priority.RECOVER_IGNORE)
        data class SocketSignalingError(val error: SocketSignaling.Error) : InternalEvent(Priority.RECOVER_IGNORE)
        data class CaptureFatal(val cause: Throwable) : InternalEvent(Priority.STOP_IGNORE)
        data class StartStream(val permissionEducationShown: Boolean, val clearStartupPolicyError: Boolean = false) : InternalEvent(Priority.STOP_IGNORE)
        data class SendHostOffer(val key: NegotiationKey, val offer: Offer) : InternalEvent(Priority.STOP_IGNORE)
        data class NegotiationTimeout(val key: NegotiationKey) : InternalEvent(Priority.STOP_IGNORE)
        data class ClientAddress(val key: ClientSessionKey) : InternalEvent(Priority.STOP_IGNORE)
        data class SocketClientAnswer(val clientId: ClientId, val negotiationAttemptId: AttemptId, val answer: Answer) : InternalEvent(Priority.STOP_IGNORE)
        data class SetClientAnswer(val key: NegotiationKey, val answer: Answer) : InternalEvent(Priority.STOP_IGNORE)
        data class ClientNotFound(val key: NegotiationKey, val reason: String) : InternalEvent(Priority.STOP_IGNORE)
        data class ClientStartNotFound(val key: ClientSessionKey, val reason: String) : InternalEvent(Priority.STOP_IGNORE)
        data class ClientAnswerApplied(val key: NegotiationKey) : InternalEvent(Priority.STOP_IGNORE)
        data class SendHostCandidates(val key: NegotiationKey, val candidates: List<IceCandidate>) : InternalEvent(Priority.STOP_IGNORE) {
            override fun toString(): String = "SendHostCandidates(clientId=${key.clientId})"
        }

        data class PeerDisconnected(val key: NegotiationKey, val connectionStateEpoch: Long) : InternalEvent(Priority.STOP_IGNORE)
        data class ClientError(val key: NegotiationKey, val notifyServer: Boolean, val reason: String) : InternalEvent(Priority.STOP_IGNORE)
        data class SocketClientCandidate(val clientId: ClientId, val negotiationAttemptId: AttemptId, val candidate: IceCandidate) :
            InternalEvent(Priority.STOP_IGNORE) {
            override fun toString(): String = "SocketClientCandidate(clientId=$clientId)"
        }

        data class SetClientCandidate(val key: NegotiationKey, val candidate: IceCandidate) : InternalEvent(Priority.STOP_IGNORE) {
            override fun toString(): String = "SetClientCandidate(clientId=${key.clientId})"
        }

        data object ScreenOff : InternalEvent(Priority.STOP_IGNORE)
        data class EnableMic(val enableMic: Boolean) : InternalEvent(Priority.STOP_IGNORE)
        data class EnableDeviceAudio(val enableDeviceAudio: Boolean) : InternalEvent(Priority.STOP_IGNORE)
        data class ConfigurationChange(val newConfig: Configuration) : InternalEvent(Priority.STOP_IGNORE)

        data class Destroy(val destroyJob: CompletableJob) : InternalEvent(Priority.DESTROY_IGNORE)
    }

    private val passwordVerifier = SocketSignaling.PasswordVerifier { clientId, passwordHash ->
        XLog.d(this@WebRtcStreamingService.getLog("SocketSignaling.PasswordVerifier.isValid"))
        currentStreamPassword.isValid(clientId, currentStreamId, passwordHash)
    }

    private fun requireClientSessionKey(clientId: ClientId, source: String): ClientSessionKey? {
        val clientKey = clients[clientId]?.key
        if (clientKey == null) {
            XLog.i(getLog(source, "Client $clientId has no active session. Ignoring stale event."))
            return null
        }
        return clientKey
    }

    private fun withActiveClient(source: String, key: ClientSessionKey, block: (ClientSession) -> Unit) {
        val currentClient = clients[key.clientId]
        if (currentClient == null) {
            XLog.i(getLog(source, "Client ${key.clientId} not found"))
            return
        }

        if (currentClient.key != key) {
            XLog.i(
                getLog(
                    source,
                    "Stale client event for ${key.clientId}. Event(join=${key.joinAttemptId}), current(join=${currentClient.key.joinAttemptId})"
                )
            )
            return
        }

        block(currentClient)
    }

    private fun withActiveNegotiation(source: String, key: NegotiationKey, block: (ClientSession) -> Unit) {
        withActiveClient(source, key.session) { currentClient ->
            if (key.attemptId != currentClient.client.negotiationAttemptId) {
                XLog.i(getLog(source, "Stale negotiationAttemptId=${key.attemptId}, active=${currentClient.client.negotiationAttemptId}. Ignoring."))
                return@withActiveClient
            }

            block(currentClient)
        }
    }

    private fun removeClientNow(clientId: ClientId, notifyServer: Boolean, reason: String) {
        val removedClient = clients.remove(clientId)
        removedClient?.client?.stop()
        if (notifyServer)
            requireNotNull(signaling) { "signaling==null" }
                .sendRemoveClients(listOf(clientId), "RemoveClient:$reason")
    }

    private val ssEventListener = object : SocketSignaling.EventListener {
        override fun onSocketConnected() {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onSocketConnected"))
            sendEvent(InternalEvent.StreamCreate)
        }

        override fun onTokenExpired() {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onTokenExpired"))
            sendEvent(InternalEvent.RecoverSignaling(forceTokenUpdate = true, reason = "TokenExpired"))
        }

        override fun onSocketDisconnected(reason: String) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onSocketDisconnected", reason))
            sendEvent(InternalEvent.RecoverSignaling(forceTokenUpdate = false, reason = reason))
        }

        override fun onStreamCreated(streamId: StreamId) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onStreamCreated", "$streamId"))
            sendEvent(InternalEvent.StreamCreated(streamId))
        }

        override fun onStreamRemoved() {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onStreamRemoved"))
            sendEvent(InternalEvent.StreamRemoved)
        }

        override fun onClientJoin(clientId: ClientId, joinAttemptId: AttemptId, iceServers: List<IceServer>) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientJoin", "$clientId joinAttemptId=$joinAttemptId IceServers: ${iceServers.size}"))
            sendEvent(InternalEvent.ClientJoin(clientId, joinAttemptId, iceServers))
        }

        override fun onClientLeave(clientId: ClientId, joinAttemptId: AttemptId) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientLeave", "$clientId joinAttemptId=$joinAttemptId"))
            sendEvent(InternalEvent.ClientLeave(clientId, joinAttemptId))
        }

        override fun onClientNotFound(key: NegotiationKey, reason: String) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientNotFound", "${key.clientId} negotiationAttemptId=${key.attemptId}"))
            sendEvent(InternalEvent.ClientNotFound(key, reason))
        }

        override fun onClientStartNotFound(key: ClientSessionKey, reason: String) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientStartNotFound", "${key.clientId} joinAttemptId=${key.joinAttemptId}"))
            sendEvent(InternalEvent.ClientStartNotFound(key, reason))
        }

        override fun onClientAnswer(clientId: ClientId, negotiationAttemptId: AttemptId, answer: Answer) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientAnswer", "$clientId negotiationAttemptId=$negotiationAttemptId"))
            sendEvent(InternalEvent.SocketClientAnswer(clientId, negotiationAttemptId, answer))
        }

        override fun onClientCandidate(clientId: ClientId, negotiationAttemptId: AttemptId, candidate: IceCandidate) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientCandidate", "$clientId negotiationAttemptId=$negotiationAttemptId"))
            sendEvent(InternalEvent.SocketClientCandidate(clientId, negotiationAttemptId, candidate))
        }

        override fun onError(cause: SocketSignaling.Error) {
            if (cause.log) XLog.e(this@WebRtcStreamingService.getLog("SocketSignaling.onError", cause.message), cause)
            else XLog.e(this@WebRtcStreamingService.getLog("SocketSignaling.onError", cause.message))
            sendEvent(InternalEvent.SocketSignalingError(cause))
        }
    }

    private val webRtcClientEventListener = object : WebRtcClient.EventListener {
        override fun onHostOffer(key: NegotiationKey, offer: Offer) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onHostOffer", "Client: ${key.clientId} negotiationAttemptId=${key.attemptId}"))
            sendEvent(InternalEvent.SendHostOffer(key, offer))
        }

        override fun onHostCandidates(key: NegotiationKey, candidates: List<IceCandidate>) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onHostCandidates", "Client: ${key.clientId} negotiationAttemptId=${key.attemptId}"))
            sendEvent(InternalEvent.SendHostCandidates(key, candidates))
        }

        override fun onClientAddress(key: ClientSessionKey) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onClientAddress", "Client: ${key.clientId}"))
            sendEvent(InternalEvent.ClientAddress(key))
        }

        override fun onClientAnswerApplied(key: NegotiationKey) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onClientAnswerApplied", "Client: ${key.clientId} negotiationAttemptId=${key.attemptId}"))
            sendEvent(InternalEvent.ClientAnswerApplied(key))
        }

        override fun onPeerDisconnected(key: NegotiationKey, connectionStateEpoch: Long) {
            XLog.w(
                this@WebRtcStreamingService.getLog(
                    "WebRTCClient.onPeerDisconnected",
                    "Client: ${key.clientId} disconnected. Scheduling grace removal, negotiationAttemptId=${key.attemptId}"
                )
            )
            sendEvent(InternalEvent.PeerDisconnected(key, connectionStateEpoch), peerDisconnectedGraceMs)
        }

        override fun onError(key: NegotiationKey, cause: Throwable) {
            val isPeerFailed = cause.message?.startsWith("onPeerFailed") == true
            if (isPeerFailed) {
                XLog.w(this@WebRtcStreamingService.getLog("WebRTCClient.onError", "Client: ${key.clientId}: ${cause.message}"))
                sendEvent(InternalEvent.ClientError(key, false, "onError:${cause.message}"))
            } else {
                XLog.e(this@WebRtcStreamingService.getLog("WebRTCClient.onError", "Client: ${key.clientId}"), cause)
                sendEvent(InternalEvent.ClientError(key, true, "onError:${cause.message}"))
            }
        }
    }

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            XLog.d(this@WebRtcStreamingService.getLog("onReceive", "Action: ${intent?.action}"))
            if (intent?.action == Intent.ACTION_SCREEN_OFF) sendEvent(InternalEvent.ScreenOff)
        }
    }

    private val networkAvailable = MutableStateFlow(true)
    private val networkRecovery = MutableStateFlow(false)
    private val networkLock = Any()
    private val validatedNetworks: MutableSet<Network> = HashSet()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            val size = synchronized(networkLock) {
                validatedNetworks.add(network)
                validatedNetworks.size
            }
            val available = size > 0
            XLog.d(this@WebRtcStreamingService.getLog("onAvailable", "validatedNetworks=$size"))
            networkAvailable.value = available
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            val size = synchronized(networkLock) {
                validatedNetworks.remove(network)
                validatedNetworks.size
            }
            val available = size > 0
            XLog.d(this@WebRtcStreamingService.getLog("onLost", "validatedNetworks=$size"))
            networkAvailable.value = available
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private val componentCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = sendEvent(InternalEvent.ConfigurationChange(newConfig))
        override fun onLowMemory() = Unit
    }

    init {
        XLog.d(getLog("init"))
    }

    @MainThread
    override fun start() {
        super.start()
        XLog.d(getLog("start"))

        mutableWebRtcStateFlow.value = WebRtcState()
        sendEvent(InternalEvent.InitState)

        val intentFilter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            service.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        else
            service.registerReceiver(broadcastReceiver, intentFilter)

        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build(),
            networkCallback
        )

        networkAvailable.onEach { available ->
            XLog.d(getLog("start", "networkAvailable: $available"))
            if (available) sendEvent(InternalEvent.GetNonce(0, false))
//            else sendEvent(WebRtcEvent.Intentable.StopStream("networkAvailableFlow: false"))
        }.launchIn(coroutineScope)

        webRtcSettings.data.map { it.enableMic }.distinctUntilChanged()
            .onEach { enableMic ->
                XLog.d(this@WebRtcStreamingService.getLog("enableMicFlow", "$enableMic"))
                sendEvent(InternalEvent.EnableMic(enableMic))
            }.launchIn(coroutineScope)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webRtcSettings.data.map { it.enableDeviceAudio }.distinctUntilChanged()
                .onEach { enableDeviceAudio ->
                    XLog.d(this@WebRtcStreamingService.getLog("enableDeviceAudioFlow", "$enableDeviceAudio"))
                    sendEvent(InternalEvent.EnableDeviceAudio(enableDeviceAudio))
                }.launchIn(coroutineScope)
        }

    }

    @MainThread
    suspend fun destroyService() {
        XLog.d(getLog("destroyService"))

        wakeLock?.apply { if (isHeld) release() }
        runCatching { service.unregisterReceiver(broadcastReceiver) }
        connectivityManager.unregisterNetworkCallback(networkCallback)
        coroutineScope.cancel()

        val destroyJob = Job()
        sendEvent(InternalEvent.Destroy(destroyJob))
        withTimeoutOrNull(3000.milliseconds) { destroyJob.join() } ?: XLog.w(getLog("destroyService", "Timeout"))

        handler.removeCallbacksAndMessages(null)

        service.stopSelf()

        quit() // Only after everything else is destroyed
    }

    @Volatile
    private var destroyPending: Boolean = false

    @AnyThread
    @Synchronized
    internal fun sendEvent(event: WebRtcEvent, timeout: Long = 0) {
        if (destroyPending) {
            when (event) {
                is InternalEvent.StartStream,
                is WebRtcEvent.CastPermissionsDenied,
                is WebRtcEvent.StartProjection -> sessionAnalyticsTracker.onStartAborted()
            }
            XLog.i(getLog("sendEvent", "Pending destroy: Ignoring event => $event"))
            return
        }
        if (event is InternalEvent.Destroy) destroyPending = true

        if (timeout > 0) XLog.d(getLog("sendEvent", "New event [Timeout: $timeout] => $event"))
        else XLog.d(getLog("sendEvent", "New event => $event"))

        if (event is WebRtcEvent.Intentable.StopStream) {
            handler.removeMessages(WebRtcEvent.Priority.STOP_IGNORE)
        }
        if (event is WebRtcEvent.Intentable.RecoverError) {
            handler.removeMessages(WebRtcEvent.Priority.STOP_IGNORE)
            handler.removeMessages(WebRtcEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(WebRtcEvent.Priority.START_PROJECTION)
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(WebRtcEvent.Priority.STOP_IGNORE)
            handler.removeMessages(WebRtcEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(WebRtcEvent.Priority.DESTROY_IGNORE)
            handler.removeMessages(WebRtcEvent.Priority.START_PROJECTION)
        }
        if (event is WebRtcEvent.StartProjection) {
            if (handler.hasMessages(WebRtcEvent.Priority.START_PROJECTION)) {
                XLog.i(getLog("sendEvent", "Replacing pending StartProjection"))
            }
            handler.removeMessages(WebRtcEvent.Priority.START_PROJECTION)
        }

        handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking {
        val event: WebRtcEvent = msg.obj as WebRtcEvent
        try {
            XLog.d(this@WebRtcStreamingService.getLog("handleMessage", "Event [$event] Current state: [${getStateString()}]"))
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@WebRtcStreamingService.getLog("handleMessage.catch", cause.toString()), cause)

            sessionAnalyticsTracker.onStartFailedIfPending(StartFailGroup.UNKNOWN)
            mediaProjectionIntent = null
            clearPendingStart()
            stopStream("HandleMessageException")

            if (cause is WebRtcError) currentError.set(cause) else currentError.set(WebRtcError.UnknownError(cause))
        } finally {
            XLog.d(this@WebRtcStreamingService.getLog("handleMessage", "Done [$event] New state: [${getStateString()}]"))
            if (event is InternalEvent.Destroy) event.destroyJob.complete()
            sessionAnalyticsTracker.onActiveConsumersChanged(currentActiveConsumersCount())
            publishState()
        }

        true
    }

    // On WebRTC-HT only
    private suspend fun processEvent(event: WebRtcEvent) {
        when (event) {
            is InternalEvent.InitState -> {
                if (destroyPending) {
                    XLog.i(getLog("InitState", "DestroyPending. Ignoring"), IllegalStateException("InitState: DestroyPending"))
                    return
                }

                deviceConfiguration = Configuration(service.resources.configuration)
                signalingRecoveryAttempts = 0
                signalingTerminalError = false
                signaling = null
                currentStreamId = StreamId.EMPTY
                currentStreamPassword = StreamPassword.EMPTY
                waitingForPermission = false
                mediaProjectionIntent = null
                clearPendingStart()
                projection = null
                wakeLock = null
                clients = HashMap()
                audioIssueToastShown = false
                streamRecreateInFlight = false

                currentError.set(null)
                networkRecovery.value = false
                synchronized(networkLock) { validatedNetworks.clear() }
            }

            is InternalEvent.GetNonce -> {
                if (destroyPending) {
                    XLog.i(getLog("GetNonce", "DestroyPending. Ignoring"))
                    return
                }

                currentError.get()?.let { error ->
                    if (error !is WebRtcError.NetworkError) {
                        XLog.w(getLog("GetNonce", "Error present. Ignoring. [$error]"))
                        if (error is WebRtcError.PlayIntegrityError && error.isExpectedEnvironmentIssue()) {
                            XLog.i(getLog("GetNonce", "Expected Play Integrity environment issue. code=${error.code}."))
                        }
                        return
                    }
                }
                currentError.set(null)

                val precheckResult = playIntegrity.checkEnvironment()
                if (precheckResult.isFailure) {
                    val cause = precheckResult.exceptionOrNull()!!
                    networkRecovery.value = false
                    if (cause is WebRtcError.PlayIntegrityError) {
                        XLog.i(getLog("GetNonce", "Play Integrity precheck failed. code=${cause.code}, msg=${cause.message}"))
                        currentError.set(cause)
                    } else {
                        XLog.w(getLog("GetNonce", "Play Integrity precheck failed: ${cause.message}"), cause)
                        currentError.set(WebRtcError.UnknownError(cause))
                    }
                    sendEvent(WebRtcEvent.UpdateState)
                    return
                }

                playIntegrity.getNonce {
                    // OkHttp thread
                    if (destroyPending) return@getNonce
                    onSuccess { nonce -> sendEvent(InternalEvent.GetToken(nonce, 0, event.forceTokenUpdate)) }
                    onFailure { cause ->
                        if (cause !is WebRtcError.NetworkError) {
                            networkRecovery.value = false
                            currentError.set(WebRtcError.UnknownError(cause))
                            sendEvent(WebRtcEvent.UpdateState)
                        } else if (cause.isNonRetryable() || event.attempt >= 20) {
                            if (cause.isConnectivityIssue()) networkAvailable.value = false
                            networkRecovery.value = false
                            if (isStreaming()) signalingTerminalError = true
                            currentError.set(
                                if (isStreaming()) WebRtcError.SignalingServerUnavailable(cause.message, cause)
                                else cause
                            )
                            sendEvent(WebRtcEvent.UpdateState)
                        } else {
                            networkRecovery.value = isStreaming()
                            val attempt = event.attempt + 1
                            val delay = min((2000L * (1.1).pow(attempt - 1)).toLong(), 15_000L)
                            sendEvent(InternalEvent.GetNonce(attempt, event.forceTokenUpdate), delay)
                        }
                    }
                }
            }

            is InternalEvent.GetToken -> {
                if (destroyPending) {
                    XLog.i(getLog("GetToken", "DestroyPending. Ignoring"))
                    return
                }

                currentError.get()?.let { error ->
                    if (error !is WebRtcError.PlayIntegrityError || error.isAutoRetryable.not()) {
                        XLog.w(getLog("GetToken", "Error present. Ignoring. [$error]"))
                        return
                    }
                }
                currentError.set(null)

                playIntegrity.getToken(event.nonce, event.forceUpdate) {
                    // MainThread
                    if (destroyPending) return@getToken
                    onSuccess { token -> sendEvent(InternalEvent.OpenSocket(token)) }
                    onFailure { cause ->
                        when {
                            cause !is WebRtcError.PlayIntegrityError -> {
                                XLog.i(this@WebRtcStreamingService.getLog("getToken", "Got error. Stopping: ${cause.message}"))
                                currentError.set(WebRtcError.UnknownError(cause))
                                networkRecovery.value = false
                                sendEvent(WebRtcEvent.UpdateState)
                            }

                            cause.isAutoRetryable.not() -> {
                                XLog.i(this@WebRtcStreamingService.getLog("getToken", "Got error. Stopping: ${cause.message}"))
                                currentError.set(cause)
                                networkRecovery.value = false
                                sendEvent(WebRtcEvent.UpdateState)
                            }

                            event.attempt >= 3 -> {
                                XLog.i(this@WebRtcStreamingService.getLog("getToken", "Got error. Max attempts. Stopping: ${cause.message}"))
                                networkAvailable.value = false
                                networkRecovery.value = false
                                if (isStreaming()) signalingTerminalError = true
                                currentError.set(
                                    if (isStreaming()) WebRtcError.SignalingServerUnavailable(cause.message, cause)
                                    else cause
                                )
                                sendEvent(WebRtcEvent.UpdateState)
                            }

                            else -> {
                                XLog.i(this@WebRtcStreamingService.getLog("getToken", "Got error. Retrying: ${cause.message}"))
                                val attempt = event.attempt + 1
                                val delay = (5000L * (2.00).pow(attempt - 1)).toLong()
                                sendEvent(InternalEvent.GetToken(event.nonce, attempt, event.forceUpdate), delay)
                            }
                        }
                    }
                }
            }

            is InternalEvent.OpenSocket -> {
                if (destroyPending) {
                    XLog.i(getLog("OpenSocket", "DestroyPending. Ignoring"))
                    return
                }

                if (signalingTerminalError) {
                    XLog.i(getLog("OpenSocket", "Terminal signaling error is active. Ignoring stale open socket."))
                    return
                }

                signalingTerminalError = false
                signaling?.destroy()
                signaling = SocketSignaling(environment, okHttpClient, ssEventListener, passwordVerifier)
                    .apply { openSocket(event.token, versionName) }
            }

            is InternalEvent.RecoverSignaling -> {
                if (destroyPending) {
                    XLog.i(getLog("RecoverSignaling", "DestroyPending. Ignoring"))
                    return
                }

                if (signalingTerminalError) {
                    XLog.i(getLog("RecoverSignaling", "Terminal signaling error is active. Ignoring recovery. reason=${event.reason}"))
                    networkRecovery.value = false
                    sendEvent(WebRtcEvent.UpdateState)
                    return
                }

                val attempt = ++signalingRecoveryAttempts
                if (attempt > signalingRecoveryMaxAttempts) {
                    XLog.w(getLog("RecoverSignaling", "Max attempts. reason=${event.reason}"))
                    networkRecovery.value = false
                    signalingTerminalError = true
                    currentError.set(WebRtcError.SignalingServerUnavailable(event.reason, null))
                    sendEvent(WebRtcEvent.UpdateState)
                    return
                }

                networkRecovery.value = isStreaming()
                currentError.set(null)
                val delay = if (attempt == 1) 0L else min((2000L * (1.1).pow(attempt - 2)).toLong(), 15_000L)
                XLog.i(getLog("RecoverSignaling", "Scheduling signaling recovery. attempt=$attempt delay_ms=$delay reason=${event.reason}"))
                sendEvent(InternalEvent.GetNonce(0, event.forceTokenUpdate), delay)
            }

            is InternalEvent.StreamCreate -> {
                if (destroyPending) {
                    XLog.i(getLog("StreamCreate", "DestroyPending. Ignoring"))
                    return
                }

                val currentStreamId = StreamId(webRtcSettings.data.value.lastStreamId)
                requireNotNull(signaling).sendStreamCreate(currentStreamId)
            }

            is InternalEvent.StreamCreated -> {
                if (destroyPending) {
                    XLog.i(getLog("StreamCreated", "DestroyPending. Ignoring"))
                    return
                }

                check(signaling != null) { "StreamCreated: signaling is null" }
                require(event.streamId.isEmpty().not())

                webRtcSettings.updateData { copy(lastStreamId = event.streamId.value) }

                if (currentStreamId.isEmpty().not()) {
                    if (currentStreamId != event.streamId) {
                        stopStream("StreamCreated: New StreamID")
                        currentStreamPassword = StreamPassword.EMPTY
                    }

                    XLog.i(getLog("StreamCreated", "New host session accepted. Clearing stale clients."))
                    clients.values.forEach { it.client.stop() }
                    clients = HashMap()
                }

                networkRecovery.value = false
                signalingRecoveryAttempts = 0
                streamRecreateInFlight = false
                currentStreamId = event.streamId
                if (currentStreamPassword.isEmpty()) currentStreamPassword = StreamPassword.generateNew()
                if (projection == null) {
                    projection = try {
                        WebRtcProjection(service)
                    } catch (cause: UnsatisfiedLinkError) {
                        if (cause.message?.contains("libjingle_peerconnection_so.so") != true) throw cause
                        XLog.w(
                            getLog(
                                "StreamCreated",
                                "Missing WebRTC native library. abi=${Build.SUPPORTED_ABIS.joinToString()} nativeLibraryDir=${service.applicationInfo.nativeLibraryDir}"
                            ), cause
                        )
                        throw WebRtcError.IncompleteInstallation(cause)
                    }
                }
                projection!!.setMicrophoneMute(webRtcSettings.data.value.enableMic.not())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    projection!!.setDeviceAudioMute(webRtcSettings.data.value.enableDeviceAudio.not())
                }
            }

            is InternalEvent.StreamRemoved -> {
                if (destroyPending) {
                    XLog.i(getLog("StreamRemoved", "DestroyPending. Ignoring"))
                    return
                }

                if (streamRecreateInFlight.not()) {
                    XLog.i(getLog("StreamRemoved", "No recreate in flight. Ignoring stale callback."))
                    return
                }

                streamRecreateInFlight = false
                sendEvent(InternalEvent.StreamCreate)
            }

            is InternalEvent.ClientJoin -> {
                if (destroyPending) {
                    XLog.i(getLog("ClientJoin", "DestroyPending. Ignoring"), IllegalStateException("ClientJoin: DestroyPending. Ignoring"))
                    return
                }

                if (projection == null) {
                    XLog.i(getLog("ClientJoin", "projection == null. Ignoring"))
                    return
                }

                val prj = projection!!
                val existingClient = clients[event.clientId]
                if (existingClient?.key?.joinAttemptId == event.joinAttemptId) {
                    XLog.i(getLog("ClientJoin", "Duplicate join for ${event.clientId}, joinAttemptId=${event.joinAttemptId}. Ignoring."))
                    return
                }

                val clientKey = ClientSessionKey(event.clientId, event.joinAttemptId)
                existingClient?.client?.stop()
                clients[event.clientId] = ClientSession(
                    key = clientKey,
                    client = WebRtcClient(
                        event.clientId,
                        event.joinAttemptId,
                        event.iceServers,
                        prj.peerConnectionFactory, prj.videoCodecs, prj.audioCodecs,
                        webRtcClientEventListener
                    )
                )

                if (isStreaming()) {
                    clients[event.clientId]?.let { clientSession ->
                        requireNotNull(signaling).sendStreamStart(clientSession.key)
                        clientSession.client.start(prj.localMediaSteam!!)
                    }
                }
            }

            is WebRtcEvent.RemoveClient -> {
                if (destroyPending) {
                    XLog.i(getLog("RemoveClient", "DestroyPending. Ignoring"), IllegalStateException("RemoveClient: DestroyPending"))
                    return
                }

                removeClientNow(event.clientId, event.notifyServer, event.reason)
            }

            is InternalEvent.ClientLeave -> {
                if (destroyPending) {
                    XLog.i(getLog("ClientLeave", "DestroyPending. Ignoring"), IllegalStateException("ClientLeave: DestroyPending"))
                    return
                }

                val key = ClientSessionKey(event.clientId, event.joinAttemptId)
                val currentClient = clients[event.clientId]
                if (currentClient?.key == key) {
                    removeClientNow(event.clientId, false, "onClientLeave")
                } else {
                    XLog.i(
                        getLog(
                            "ClientLeave",
                            "Stale joinAttemptId=${event.joinAttemptId} for ${event.clientId}. Current=${currentClient?.key?.joinAttemptId ?: "-"}. Ignoring."
                        )
                    )
                }
            }

            is InternalEvent.SocketSignalingError -> {
                if (destroyPending) {
                    XLog.i(getLog("SocketSignalingError", "DestroyPending. Ignoring"))
                    return
                }

                if (event.error.retry) {
                    val forceTokenUpdate = event.error is SocketSignaling.Error.SocketAuthError
                    sendEvent(
                        InternalEvent.RecoverSignaling(
                            forceTokenUpdate = forceTokenUpdate,
                            reason = event.error.message ?: event.error::class.java.simpleName
                        )
                    )
                    return
                }

                if (event.error is SocketSignaling.Error.StreamRemoveError) {
                    streamRecreateInFlight = false
                }
                signalingTerminalError = true
                networkRecovery.value = false
                handler.removeMessages(WebRtcEvent.Priority.RECOVER_IGNORE)
                currentError.set(WebRtcError.SocketError(event.error.message ?: "Unknown error", event.error.cause))
            }

            is WebRtcEvent.CastPermissionsDenied -> {
                val currentStartAttemptId = pendingStartAttemptId
                if (currentStartAttemptId != event.startAttemptId) {
                    XLog.i(getLog("CastPermissionsDenied", "MP_UI stale id=${event.startAttemptId} current=${currentStartAttemptId ?: "none"}"))
                    return
                }
                clearPendingStart()
                waitingForPermission = false
                if (destroyPending) {
                    sessionAnalyticsTracker.onStartAborted()
                    XLog.i(getLog("CastPermissionsDenied", "DestroyPending. abort"))
                    return
                }
                sessionAnalyticsTracker.onStartFailed(StartFailGroup.PERMISSION_DENIED)
            }

            is WebRtcEvent.StartProjection -> {
                val currentStartAttemptId = pendingStartAttemptId
                if (currentStartAttemptId != event.startAttemptId) {
                    XLog.i(getLog("StartProjection", "MP_UI stale id=${event.startAttemptId} current=${currentStartAttemptId ?: "none"}"))
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    if (foregroundPreflightStartAttemptId == event.startAttemptId) foregroundPreflightStartAttemptId = null
                    return
                }
                waitingForPermission = false
                if (destroyPending) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    clearPendingStart()
                    sessionAnalyticsTracker.onStartAborted()
                    XLog.i(getLog("StartProjection", "DestroyPending. abort"), IllegalStateException("StartProjection: DestroyPending"))
                    return
                }

                if (isStreaming()) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    clearPendingStart()
                    sessionAnalyticsTracker.onStartAborted()
                    XLog.d(getLog("StartProjection", "Already streaming. stale callback"))
                    return
                }

                val userRequestedMic = pendingStartMicRequested
                val userRequestedDeviceAudio = pendingStartDeviceAudioRequested
                val hasAudioPermission =
                    ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!hasAudioPermission && (userRequestedMic || userRequestedDeviceAudio)) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    clearPendingStart()
                    sessionAnalyticsTracker.onStartFailed(StartFailGroup.BLOCKED)
                    val error = WebRtcError.AudioPermissionRequired()
                    currentError.set(error)
                    XLog.w(getLog("StartProjection", "Audio permission required before projection startup."), error)
                    return
                }
                if (event.foregroundStartError is WebRtcError.AudioPermissionRequired) {
                    clearPendingStart()
                    sessionAnalyticsTracker.onStartFailed(StartFailGroup.BLOCKED)
                    currentError.set(event.foregroundStartError)
                    XLog.w(getLog("StartProjection", "Audio permission required before foreground startup."), event.foregroundStartError)
                    return
                }
                val prj = projection ?: run {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    clearPendingStart()
                    throw IllegalStateException("StartProjection: projection == null")
                }
                val wantsAudioForegroundService = hasAudioPermission && (userRequestedMic || userRequestedDeviceAudio)
                val audioMode = when {
                    hasAudioPermission && userRequestedMic && userRequestedDeviceAudio -> "both"
                    hasAudioPermission && userRequestedMic -> "mic"
                    hasAudioPermission && userRequestedDeviceAudio -> "device"
                    else -> "none"
                }
                clearPendingStart()
                XLog.i(
                    getLog(
                        "StartProjection",
                        "SP_TRACE route=preflight_v1 stage=async_start startAttemptId=${event.startAttemptId} audioMode=$audioMode cachedIntent=${mediaProjectionIntent != null}"
                    )
                )
                val startProjection = {
                    projectionCoordinator.startProjection(event.intent) { _, mediaProjection, audioCaptureAllowed, isStartupStillValid ->
                        val projectionStarted = prj.start(currentStreamId, mediaProjection, { cause ->
                            sendEvent(InternalEvent.CaptureFatal(cause))
                        }, isStartupStillValid)
                        if (!projectionStarted) {
                            XLog.w(
                                this@WebRtcStreamingService.getLog("StartProjection", "projection.start returned false"),
                                IllegalStateException("projection.start returned false")
                            )
                            return@startProjection false
                        }
                        if (!isStartupStillValid()) {
                            XLog.i(getLog("StartProjection", "Startup invalidated after projection start."))
                            return@startProjection false
                        }

                        prj.setMicrophoneMute(!(userRequestedMic && audioCaptureAllowed))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            prj.setDeviceAudioMute(!(userRequestedDeviceAudio && audioCaptureAllowed))
                        }

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            mediaProjectionIntent = event.intent
                            service.registerComponentCallbacks(componentCallback)
                        }

                        @Suppress("DEPRECATION")
                        @SuppressLint("WakelockTimeout")
                        if (Build.MANUFACTURER !in listOf("OnePlus", "OPPO") && webRtcSettings.data.value.keepAwake) {
                            val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                            wakeLock = powerManager.newWakeLock(flags, "ScreenStream::WebRTC-Tag").apply { acquire() }
                        }
                        true
                    }
                }
                val startPhase: String
                val startResult = if (event.foregroundStartProcessed) {
                    val foregroundStartError = event.foregroundStartError
                    if (foregroundStartError != null) {
                        startPhase = "foreground promotion"
                        projectionCoordinator.asForegroundStartResult(foregroundStartError)
                    } else {
                        startPhase = "projection startup"
                        startProjection()
                    }
                } else {
                    val foregroundError = projectionCoordinator.startForegroundForProjection(wantsAudioForegroundService)
                    if (foregroundError != null) {
                        startPhase = "foreground promotion"
                        projectionCoordinator.asForegroundStartResult(foregroundError)
                    } else {
                        startPhase = "projection startup"
                        startProjection()
                    }
                }
                when (startResult) {
                    is ProjectionCoordinator.StartResult.Started -> {
                        requireNotNull(signaling).sendStreamStart()
                        clients.forEach { (_, session) ->
                            session.client.start(prj.localMediaSteam!!)
                        }

                        currentError.set(null)
                        sessionAnalyticsTracker.onStarted(currentActiveConsumersCount())
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=started startAttemptId=${event.startAttemptId} audioMode=$audioMode phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "Started. intent=${mediaProjectionIntent != null}, audioFgs=${startResult.audioCaptureAllowed}"
                            )
                        )
                    }

                    is ProjectionCoordinator.StartResult.Interrupted -> {
                        if (startResult.cachedIntentAction == ProjectionCoordinator.CachedIntentAction.INVALIDATE) {
                            mediaProjectionIntent = null
                        }
                        sessionAnalyticsTracker.onStartAborted()
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=interrupted startAttemptId=${event.startAttemptId} audioMode=$audioMode phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "Interrupted. intent=${startResult.cachedIntentAction}/${mediaProjectionIntent != null}"
                            ), startResult.cause
                        )
                        currentError.set(null)
                        stopStream("StartProjectionInterrupted")
                    }

                    ProjectionCoordinator.StartResult.Busy -> {
                        sessionAnalyticsTracker.onStartFailed(StartFailGroup.BUSY)
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=busy startAttemptId=${event.startAttemptId} audioMode=$audioMode phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        XLog.w(getLog("StartProjection", "Busy during $startPhase. intent=${mediaProjectionIntent != null}"))
                    }

                    is ProjectionCoordinator.StartResult.Blocked,
                    is ProjectionCoordinator.StartResult.Fatal -> {
                        val cause = startResult.cause ?: error("Missing cause for failed start result")
                        if (startResult.cachedIntentAction == ProjectionCoordinator.CachedIntentAction.INVALIDATE) {
                            mediaProjectionIntent = null
                        }
                        val logMessage = if (startResult is ProjectionCoordinator.StartResult.Blocked) {
                            "Blocked during $startPhase. intent=${startResult.cachedIntentAction}/${mediaProjectionIntent != null}"
                        } else {
                            "Fatal during $startPhase. intent=${startResult.cachedIntentAction}/${mediaProjectionIntent != null}"
                        }
                        XLog.i(
                            getLog(
                                "StartProjection",
                                "SP_TRACE route=preflight_v1 stage=result status=${if (startResult is ProjectionCoordinator.StartResult.Blocked) "blocked" else "fatal"} startAttemptId=${event.startAttemptId} audioMode=$audioMode phase=$startPhase cachedIntent=${mediaProjectionIntent != null}"
                            )
                        )
                        if (startResult.failureReason == ProjectionCoordinator.FailureReason.PROJECTION_ACQUIRE_REJECTED) {
                            sessionAnalyticsTracker.onStartFailed(StartFailGroup.BLOCKED)
                            currentError.set(WebRtcError.ProjectionAcquireRejected(cause))
                            XLog.w(getLog("StartProjection", logMessage), currentError.get())
                            stopStream("ProjectionAcquireRejected")
                            return
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && wantsAudioForegroundService && cause is SecurityException) {
                            sessionAnalyticsTracker.onStartFailed(StartFailGroup.BLOCKED)
                            currentError.set(WebRtcError.AudioStartBlocked(cause))
                            XLog.w(getLog("StartProjection", logMessage), currentError.get())
                            stopStream("AudioStartBlocked")
                            return
                        }
                        sessionAnalyticsTracker.onStartFailed(
                            if (startResult is ProjectionCoordinator.StartResult.Blocked) StartFailGroup.BLOCKED else StartFailGroup.FATAL
                        )
                        if (startResult is ProjectionCoordinator.StartResult.Blocked) {
                            val error = cause as? WebRtcError ?: WebRtcError.ScreenCaptureStartBlocked(cause)
                            XLog.w(getLog("StartProjection", logMessage), error)
                            currentError.set(error)
                        } else {
                            XLog.e(getLog("StartProjection", logMessage), cause)
                            stopStream("StartProjectionFailed")
                            currentError.set(cause as? WebRtcError ?: WebRtcError.UnknownError(cause))
                        }
                    }
                }
            }

            is InternalEvent.CaptureFatal -> {
                if (destroyPending) {
                    XLog.i(getLog("CaptureFatal", "DestroyPending. Ignoring"), event.cause)
                    return
                }
                XLog.e(getLog("CaptureFatal", "Stopping stream"), event.cause)
                sendEvent(WebRtcEvent.Intentable.StopStream("CaptureFatal:${event.cause.javaClass.simpleName}:${event.cause.message}"))
            }

            is WebRtcEvent.Intentable.StopStream -> stopStream(event.reason)

            is WebRtcEvent.Intentable.RecoverError -> {
                if (destroyPending) {
                    XLog.i(getLog("RecoverError", "DestroyPending. Ignoring"))
                    return
                }

                stopStream("RecoverError")

                signaling?.destroy()
                signaling = null
                projection?.destroy()
                projection = null
                waitingForPermission = false
                clearPendingStart()

                currentError.set(null)
                signalingTerminalError = false

                handler.removeMessages(WebRtcEvent.Priority.STOP_IGNORE)
                handler.removeMessages(WebRtcEvent.Priority.RECOVER_IGNORE)
                handler.removeMessages(WebRtcEvent.Priority.START_PROJECTION)

                sendEvent(InternalEvent.InitState)
                sendEvent(InternalEvent.GetNonce(0, true))
            }

            is InternalEvent.StartStream -> {
                if (destroyPending) {
                    XLog.i(getLog("StartStream", "DestroyPending. Ignoring"), IllegalStateException("StartStream: DestroyPending"))
                    return
                }
                if (event.clearStartupPolicyError && currentError.get()?.isStartupPolicyError() == true) currentError.set(null)
                if (pendingStartAttemptId != null) {
                    XLog.i(getLog("StartStream", "Permission already pending id=${pendingStartAttemptId ?: "none"}"))
                    return
                }
                val settings = webRtcSettings.data.value
                val audioEnabled = settings.enableMic || settings.enableDeviceAudio
                val audioPermissionGranted =
                    ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (audioEnabled && !audioPermissionGranted) {
                    sessionAnalyticsTracker.onStartAttempt(
                        entryPoint = EntryPoint.BUTTON,
                        usedCachedPermission = mediaProjectionIntent != null,
                        permissionEducationShown = event.permissionEducationShown
                    )
                    sessionAnalyticsTracker.onStartFailed(StartFailGroup.BLOCKED)
                    val error = WebRtcError.AudioPermissionRequired()
                    currentError.set(error)
                    XLog.w(getLog("StartStream", "Audio permission required before startup."), error)
                    return
                }
                if (isStreaming() || currentError.get() != null || currentStreamId.isEmpty() || projection == null || (signaling?.socketId() == null && !networkRecovery.value)) {
                    XLog.i(
                        getLog(
                            "StartStream",
                            "Not ready. isStreaming=${isStreaming()} error=${currentError.get() != null} streamIdReady=${
                                currentStreamId.isEmpty().not()
                            } projectionReady=${projection != null} socketReady=${signaling?.socketId() != null || networkRecovery.value}"
                        )
                    )
                    return
                }
                sessionAnalyticsTracker.onStartAttempt(
                    entryPoint = EntryPoint.BUTTON,
                    usedCachedPermission = mediaProjectionIntent != null,
                    permissionEducationShown = event.permissionEducationShown
                )
                pendingStartMicRequested = settings.enableMic
                pendingStartDeviceAudioRequested = settings.enableDeviceAudio
                val startAttemptId = Uuid.random().toString()
                pendingStartAttemptId = startAttemptId

                mediaProjectionIntent?.let {
                    check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "WebRtcEvent.StartStream: UPSIDE_DOWN_CAKE" }
                    waitingForPermission = false
                    XLog.i(getLog("StartStream", "SP_TRACE route=service_cached_permission stage=dispatch_source source=button startAttemptId=$startAttemptId"))
                    try {
                        WebRtcModuleService.dispatchProjectionIntent(service, startAttemptId, it)
                    } catch (cause: Throwable) {
                        if (!cause.isStreamingModuleStartBlocked()) throw cause
                        clearPendingStart()
                        sessionAnalyticsTracker.onStartFailed(StartFailGroup.BLOCKED)
                        currentError.set(WebRtcError.ScreenCaptureStartBlocked(cause))
                        XLog.w(getLog("StartStream", "Cached projection dispatch blocked by Android policy."), currentError.get())
                        return
                    }
                } ?: run {
                    waitingForPermission = true
                    XLog.i(getLog("Permission", "MP_UI request id=$startAttemptId source=button"))
                }
            }

            is InternalEvent.SendHostOffer -> {
                if (destroyPending) {
                    XLog.i(getLog("SendHostOffer", "DestroyPending. Ignoring"), IllegalStateException("SendHostOffer: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.i(getLog("SendHostOffer", "Not streaming. Ignoring."))
                    return
                }

                withActiveNegotiation("SendHostOffer", event.key) {
                    if (requireNotNull(signaling).sendHostOffer(event.key, event.offer))
                        sendEvent(InternalEvent.NegotiationTimeout(event.key), negotiationTimeoutMs)
                    else
                        removeClientNow(event.key.clientId, false, "HostSocketDisconnectedBeforeOffer")
                }
            }

            is InternalEvent.NegotiationTimeout -> {
                if (destroyPending) {
                    XLog.i(getLog("NegotiationTimeout", "DestroyPending. Ignoring"), IllegalStateException("NegotiationTimeout: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("NegotiationTimeout", "Not streaming. Ignoring."))
                    return
                }

                withActiveClient(
                    source = "NegotiationTimeout",
                    key = event.key.session
                ) { currentClient ->
                    if (currentClient.client.isNegotiationUnanswered(event.key.attemptId)) {
                        XLog.w(getLog("NegotiationTimeout", "Removing unanswered client ${event.key.clientId}, negotiationAttemptId=${event.key.attemptId}"))
                        removeClientNow(event.key.clientId, true, "NegotiationTimeout")
                    }
                }
            }

            is InternalEvent.ClientNotFound -> {
                if (destroyPending) {
                    XLog.i(getLog("ClientNotFound", "DestroyPending. Ignoring"), IllegalStateException("ClientNotFound: DestroyPending"))
                    return
                }

                withActiveNegotiation(
                    source = "ClientNotFound",
                    key = event.key
                ) {
                    removeClientNow(event.key.clientId, false, event.reason)
                }
            }

            is InternalEvent.ClientStartNotFound -> {
                if (destroyPending) {
                    XLog.i(getLog("ClientStartNotFound", "DestroyPending. Ignoring"), IllegalStateException("ClientStartNotFound: DestroyPending"))
                    return
                }

                val currentClient = clients[event.key.clientId]
                if (currentClient?.key == event.key) {
                    removeClientNow(event.key.clientId, false, event.reason)
                } else {
                    XLog.i(
                        getLog(
                            "ClientStartNotFound",
                            "Stale joinAttemptId=${event.key.joinAttemptId} for ${event.key.clientId}. Current=${currentClient?.key?.joinAttemptId ?: "-"}, reason=${event.reason}. Ignoring."
                        )
                    )
                }
            }

            is InternalEvent.ClientAddress -> {
                if (destroyPending) {
                    XLog.i(getLog("ClientAddress", "DestroyPending. Ignoring"), IllegalStateException("ClientAddress: DestroyPending"))
                    return
                }

                withActiveClient("ClientAddress", event.key) {
                    // State is published from handleMessage finally block.
                }
            }

            is InternalEvent.SocketClientAnswer -> {
                if (destroyPending) {
                    XLog.i(getLog("SocketClientAnswer", "DestroyPending. Ignoring"), IllegalStateException("SocketClientAnswer: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("SocketClientAnswer", "Not streaming. Ignoring."))
                    return
                }

                val clientKey = requireClientSessionKey(event.clientId, "SocketClientAnswer") ?: return
                withActiveNegotiation(
                    source = "SocketClientAnswer",
                    key = NegotiationKey(clientKey, event.negotiationAttemptId)
                ) { currentClient ->
                    currentClient.client.setClientAnswer(projection!!.localMediaSteam!!.id, event.negotiationAttemptId, event.answer)
                }
            }

            is InternalEvent.SetClientAnswer -> {
                if (destroyPending) {
                    XLog.i(getLog("SetClientAnswer", "DestroyPending. Ignoring"), IllegalStateException("SetClientAnswer: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("SetClientAnswer", "Not streaming. Ignoring."))
                    return
                }

                withActiveNegotiation(
                    source = "SetClientAnswer",
                    key = event.key
                ) { currentClient ->
                    currentClient.client.setClientAnswer(projection!!.localMediaSteam!!.id, event.key.attemptId, event.answer)
                }
            }

            is InternalEvent.ClientAnswerApplied -> {
                if (destroyPending) {
                    XLog.i(getLog("ClientAnswerApplied", "DestroyPending. Ignoring"), IllegalStateException("ClientAnswerApplied: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("ClientAnswerApplied", "Not streaming. Ignoring."))
                    return
                }

                withActiveNegotiation(source = "ClientAnswerApplied", key = event.key) { currentClient ->
                    val prj = projection ?: return@withActiveNegotiation
                    val mediaStream = prj.localMediaSteam ?: return@withActiveNegotiation
                    currentClient.client.onClientAnswerApplied(mediaStream.id, event.key.attemptId)
                    // Disabled: resize-based keyframe hack can deadlock WebRTC-HT with ScreenStreamSurfaceTexture.
                    // prj.forceKeyFrame()
                }
            }

            is InternalEvent.SendHostCandidates -> {
                if (destroyPending) {
                    XLog.i(
                        getLog("SendHostCandidates", "DestroyPending. Ignoring"),
                        IllegalStateException("SendHostCandidates: DestroyPending")
                    )
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("SendHostCandidates", "Not streaming. Ignoring."))
                    return
                }

                withActiveNegotiation("SendHostCandidates", event.key) {
                    requireNotNull(signaling).sendHostCandidates(event.key, event.candidates)
                }
            }

            is InternalEvent.PeerDisconnected -> {
                if (destroyPending) {
                    XLog.i(getLog("PeerDisconnected", "DestroyPending. Ignoring"), IllegalStateException("PeerDisconnected: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("PeerDisconnected", "Not streaming. Ignoring."))
                    return
                }

                withActiveClient("PeerDisconnected", event.key.session) { currentClient ->
                    if (currentClient.client.isProlongedDisconnected(event.key.attemptId, event.connectionStateEpoch)) {
                        XLog.w(
                            getLog(
                                "PeerDisconnected",
                                "Removing prolonged disconnected client ${event.key.clientId}, negotiationAttemptId=${event.key.attemptId}"
                            )
                        )
                        removeClientNow(event.key.clientId, false, "PeerDisconnected")
                    } else {
                        XLog.i(
                            getLog(
                                "PeerDisconnected",
                                "Client ${event.key.clientId} recovered or changed. Ignoring, negotiationAttemptId=${event.key.attemptId}"
                            )
                        )
                    }
                }
            }

            is InternalEvent.ClientError -> {
                if (destroyPending) {
                    XLog.i(getLog("ClientError", "DestroyPending. Ignoring"), IllegalStateException("ClientError: DestroyPending"))
                    return
                }

                withActiveNegotiation("ClientError", event.key) {
                    removeClientNow(event.key.clientId, event.notifyServer, event.reason)
                }
            }

            is InternalEvent.SocketClientCandidate -> {
                if (destroyPending) {
                    XLog.i(
                        getLog("SocketClientCandidate", "DestroyPending. Ignoring"),
                        IllegalStateException("SocketClientCandidate: DestroyPending")
                    )
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("SocketClientCandidate", "Not streaming. Ignoring."))
                    return
                }

                val clientKey = requireClientSessionKey(event.clientId, "SocketClientCandidate") ?: return
                withActiveNegotiation(
                    source = "SocketClientCandidate",
                    key = NegotiationKey(clientKey, event.negotiationAttemptId)
                ) { currentClient ->
                    currentClient.client.setClientCandidate(projection!!.localMediaSteam!!.id, event.candidate)
                }
            }

            is InternalEvent.SetClientCandidate -> {
                if (destroyPending) {
                    XLog.i(
                        getLog("SetClientCandidate", "DestroyPending. Ignoring"),
                        IllegalStateException("SetClientCandidate: DestroyPending")
                    )
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("SetClientCandidate", "Not streaming. Ignoring."))
                    return
                }

                withActiveNegotiation(
                    source = "SetClientCandidate",
                    key = event.key
                ) { currentClient ->
                    currentClient.client.setClientCandidate(projection!!.localMediaSteam!!.id, event.candidate)
                }
            }

            is InternalEvent.ScreenOff -> {
                if (destroyPending) {
                    XLog.i(getLog("ScreenOff", "DestroyPending. Ignoring"), IllegalStateException("ScreenOff: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("ScreenOff", "Not streaming. Ignoring."))
                    return
                }

                if (webRtcSettings.data.value.stopOnSleep) sendEvent(WebRtcEvent.Intentable.StopStream("ScreenOff"))
            }

            is InternalEvent.EnableMic -> {
                if (destroyPending) {
                    XLog.i(getLog("EnableMic", "DestroyPending. Ignoring"))
                    return
                }

                if (isStreaming()) {
                    XLog.i(getLog("EnableMic", "Streaming. Ignoring source change until next start."))
                    return
                }

                projection?.setMicrophoneMute(event.enableMic.not())
            }

            is InternalEvent.EnableDeviceAudio -> {
                if (destroyPending) {
                    XLog.i(getLog("EnableDeviceAudio", "DestroyPending. Ignoring"))
                    return
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (isStreaming()) {
                        XLog.i(getLog("EnableDeviceAudio", "Streaming. Ignoring source change until next start."))
                        return
                    }

                    projection?.setDeviceAudioMute(event.enableDeviceAudio.not())
                }
            }

            is InternalEvent.ConfigurationChange -> {
                if (destroyPending) {
                    XLog.i(
                        getLog("ConfigurationChange", "DestroyPending. Ignoring"),
                        IllegalStateException("ConfigurationChange: DestroyPending")
                    )
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("ConfigurationChange", "Not streaming. Ignoring."))
                    deviceConfiguration = Configuration(event.newConfig)
                    return
                }

                val configDiff = deviceConfiguration.diff(event.newConfig)
                if (
                    configDiff and ActivityInfo.CONFIG_ORIENTATION != 0 || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0 ||
                    configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0 || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                ) {
                    val screeSize = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                    projection!!.changeCaptureFormat(screeSize.width(), screeSize.height())
                } else {
                    XLog.d(getLog("ConfigurationChange", "No change relevant for streaming. Ignoring."))
                }

                deviceConfiguration = Configuration(event.newConfig)
            }

            is InternalEvent.Destroy -> {
                sessionAnalyticsTracker.onStartAborted()
                stopStream("Destroy")

                signaling?.destroy()
                signaling = null
                projection?.destroy()
                projection = null
                currentError.set(null)
            }

            is WebRtcEvent.GetNewStreamId -> {
                if (destroyPending) {
                    XLog.i(getLog("GetNewStreamId", "DestroyPending. Ignoring"), IllegalStateException("GetNewStreamId: DestroyPending"))
                    return
                }

                if (isStreaming()) {
                    XLog.i(getLog("GetNewStreamId", "Streaming. Ignoring."), IllegalStateException("GetNewStreamId: Streaming."))
                    return
                }

                if (streamRecreateInFlight) {
                    XLog.i(getLog("GetNewStreamId", "Recreate already in progress. Ignoring."))
                    return
                }

                streamRecreateInFlight = true
                requireNotNull(signaling).sendStreamRemove(currentStreamId)
                clients = HashMap()
                currentStreamId = StreamId.EMPTY
                currentStreamPassword = StreamPassword.EMPTY
                webRtcSettings.updateData { copy(lastStreamId = StreamId.EMPTY.value) }
            }

            is WebRtcEvent.CreateNewPassword -> {
                if (destroyPending) {
                    XLog.i(
                        getLog("CreateNewPassword", "DestroyPending. Ignoring"),
                        IllegalStateException("CreateNewPassword: DestroyPending")
                    )
                    return
                }

                if (isStreaming()) {
                    XLog.i(getLog("CreateNewPassword", "Streaming. Ignoring."))
                    return
                }

                if (streamRecreateInFlight) {
                    XLog.i(getLog("CreateNewPassword", "Stream recreate in progress. Ignoring."))
                    return
                }

                requireNotNull(signaling).sendRemoveClients(clients.map { it.value.client.clientId }, "CreateNewStreamPassword")
                clients = HashMap()
                currentStreamPassword = StreamPassword.generateNew()
            }

            is WebRtcEvent.UpdateState -> Unit // Expected

            else -> throw IllegalArgumentException("Unknown WebRtcEvent: ${event::class.java}")
        }
    }

    private fun isStreaming(): Boolean = projection?.isRunning ?: false

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream(stopReason: String? = null) {
        val wasStreaming = isStreaming()
        val activeConsumersAtStop = currentActiveConsumersCount()
        val logMessage = buildString {
            append(if (wasStreaming) "stop=" else "skip. stop=")
            append(stopReason)
            if (wasStreaming) append(", consumers=$activeConsumersAtStop")
            append(", intent=${mediaProjectionIntent != null}")
        }
        if (wasStreaming) XLog.i(getLog("stopStream", logMessage))
        else XLog.d(getLog("stopStream", logMessage))

        audioIssueToastShown = false
        waitingForPermission = false
        clearPendingStart()

        if (wasStreaming) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { service.unregisterComponentCallbacks(componentCallback) }
            }
            requireNotNull(signaling).sendStreamStop()
            clients.forEach { it.value.client.stop() }
        }
        projection?.stop()
        projectionCoordinator.stop()

        if (wasStreaming) {
            sessionAnalyticsTracker.onEnded(stopReason, activeConsumersAtStop)
        }

        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        service.stopForeground()
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun currentActiveConsumersCount(): Int = clients.size

    private fun clearPendingStart() {
        pendingStartAttemptId = null
        pendingStartMicRequested = false
        pendingStartDeviceAudioRequested = false
        foregroundPreflightStartAttemptId = null
    }

    private fun showAudioCaptureIssueToastOnce() {
        if (audioIssueToastShown) return
        audioIssueToastShown = true
        mainHandler.post { Toast.makeText(service, R.string.webrtc_stream_audio_capture_unavailable, Toast.LENGTH_LONG).show() }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun getStateString() =
        "d=$destroyPending sock=${signaling?.socketId()} stream=$currentStreamId str=${isStreaming()} start=${pendingStartAttemptId ?: "-"} rec=$streamRecreateInFlight netRec=${networkRecovery.value} clients=${clients.size}"

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun publishState() {
        val state = WebRtcState(
            (signaling?.socketId() == null && !networkRecovery.value) ||
                    currentStreamId.isEmpty() ||
                    pendingStartAttemptId != null ||
                    currentError.get() != null ||
                    destroyPending ||
                    (!isStreaming() && projection == null),
            environment.signalingServerUrl,
            currentStreamId.value,
            currentStreamPassword.value,
            waitingForPermission,
            pendingStartAttemptId,
            isStreaming(),
            networkRecovery.value,
            clients.map { it.value.client.toClient() },
            currentError.get()
        )

        mutableWebRtcStateFlow.value = state

        if (previousError != currentError.get()) {
            previousError = currentError.get()
            previousError?.let { error ->
                service.showErrorNotification(
                    error = error,
                    showRecoverAction = !(state.isStreaming && error is WebRtcError.SignalingServerUnavailable) && !error.isStartupPolicyError()
                )
            } ?: service.hideErrorNotification()
        }
    }
}
