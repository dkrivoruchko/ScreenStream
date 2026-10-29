package info.dvkr.screenstream.webrtc.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceTypeException
import android.app.ServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjection
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.window.core.layout.WindowSizeClass
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.SessionKey
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsFactory
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.getVersionName
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.WebRtcModuleService
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings
import info.dvkr.screenstream.webrtc.ui.WebRtcError
import info.dvkr.screenstream.webrtc.ui.WebRtcMainScreenUI
import info.dvkr.screenstream.webrtc.ui.WebRtcState
import info.dvkr.screenstream.webrtc.ui.isExpectedEnvironmentIssue
import info.dvkr.screenstream.webrtc.ui.isStartupPolicyError
import io.screenstream.streaming.capture.ConsentLaunchException
import io.screenstream.streaming.capture.ScreenCaptureAccess
import io.screenstream.streaming.manager.StreamingModuleActionReceiver
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.IceServer
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Factory(binds = [])
internal class WebRtcStreamingService(
    @InjectedParam private val runtime: StreamingModule.Controller.Runtime,
    @InjectedParam private val service: WebRtcModuleService,
    private val environmentProvider: Lazy<WebRtcEnvironment>,
    private val captureAccess: ScreenCaptureAccess,
    private val notifications: StreamingModuleNotifications,
    private val webRtcSettings: WebRtcSettings,
    private val analyticsFactory: StreamingSessionAnalyticsFactory
) : HandlerThread("WebRTC-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback, StreamingModule.Controller {

    private data class ClientSession(val key: ClientSessionKey, val client: WebRtcClient)

    override val instanceId: StreamingModule.InstanceId = runtime.instanceId
    private val mutableWebRtcStateFlow = MutableStateFlow(WebRtcState())
    private val environment: WebRtcEnvironment get() = environmentProvider.value
    private val versionName by lazy { service.getVersionName("com.google.android.gms", "-") }
    private val powerManager: PowerManager by lazy { service.application.getSystemService(PowerManager::class.java) }
    private val connectivityManager: ConnectivityManager by lazy { service.application.getSystemService(ConnectivityManager::class.java) }
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val handler: Handler by lazy { Handler(looper, this) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy { handler.asCoroutineDispatcher("WebRTC-HT_Dispatcher") }
    private val coroutineScope by lazy { CoroutineScope(SupervisorJob() + coroutineDispatcher) }
    private val okHttpClient by lazy {
        OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val playIntegrity by lazy { PlayIntegrity(service, environment, okHttpClient) }
    private val currentError: AtomicReference<WebRtcError?> = AtomicReference(null)
    private val ownerGate = Any()
    private val errorGate = Any()
    private var started = false
    @Volatile
    private var workerStarted = false
    @Volatile
    private var signalingEpoch = 0L
    private val startupSettled = CompletableDeferred<Unit>()
    @Volatile
    private var captureAttempt: CaptureAttempt? = null
    @Volatile
    private var captureAnalytics: WebRtcSessionAnalytics? = null
    private var captureGeneration = 0L
    @Volatile
    private var currentErrorToken: Uuid? = null
    private var errorPublicationClosed = false
    private var showCaptureRetry = false
    private var receiverRegistered = false
    private var networkRegistered = false
    @Volatile
    private var shutdownSucceeded = true
    private val cleanup: Deferred<Boolean> = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
        startupSettled.await()
        var succeeded = true
        if (started) {
            succeeded = runCatching {
                withContext(Dispatchers.Main.immediate) { !workerStarted || stopWorker() }
            }.onFailure { XLog.w(getLog("cleanup", "Best-effort worker shutdown failed"), it) }.getOrDefault(false)
        }
        val serviceStopped = runCatching { service.stopAndAwaitDestruction() }.getOrDefault(false)
        succeeded && serviceStopped
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (destroyPending || !runtime.isCurrent()) return
            runCatching {
                publishState()
                reportStatus()
            }.onFailure {
                runtime.reportFailed()
                requestModuleShutdown()
            }
            if (!destroyPending) handler.postDelayed(this, 1_000L)
        }
    }

    override fun startModule() {
        synchronized(ownerGate) {
            if (destroyPending || started || !runtime.isCurrent()) {
                if (!started) startupSettled.complete(Unit)
                return
            }
            started = true
        }
        try {
            start()
        } catch (cause: Throwable) {
            try {
                runtime.reportFailed()
            } finally {
                requestModuleShutdown()
            }
            throw cause
        } finally {
            startupSettled.complete(Unit)
        }
    }

    override fun requestStreamStart() {
        sendEvent(InternalEvent.StartStream(clearStartupPolicyError = true))
    }

    override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean) {
        val original = captureAttempt ?: return
        val reason = if (fromNotification) "WebRtcModuleService. User action: Notification" else "Coordinator Stop"
        if (original.id == attempt) sendEvent(WebRtcEvent.StopStream(reason, original.generation))
    }

    override fun requestRecovery(errorToken: Uuid) {
        sendEvent(WebRtcEvent.RecoverError(errorToken))
    }

    override fun requestModuleShutdown() {
        synchronized(ownerGate) {
            if (destroyPending) return
            destroyPending = true
            captureAttempt?.stopRequested = true
            captureAnalytics?.closeIfUnadmitted()
            captureAttempt?.job?.cancel()
            if (!started) startupSettled.complete(Unit)
        }
        try {
            service.closeForegroundAdmission()
        } finally {
            try {
                synchronized(errorGate) {
                    errorPublicationClosed = true
                    notifications.updateError(210, null)
                }
            } finally {
                cleanup.start()
            }
        }
    }

    override suspend fun awaitCleanup(): Boolean = cleanup.await()

    override fun onServiceDestroyed() {
        try {
            runtime.reportFailed()
        } finally {
            requestModuleShutdown()
        }
    }

    @Composable
    override fun Content(window: WindowSizeClass, modifier: Modifier) {
        WebRtcMainScreenUI(
            instanceId = instanceId,
            webRtcStateFlow = mutableWebRtcStateFlow,
            sendEvent = { sendEvent(it) },
            requestStart = ::requestStreamStart,
            requestStop = { id ->
                val attempt = captureAttempt
                if (attempt?.id == id) sendEvent(WebRtcEvent.StopStream("User action: Button", attempt.generation))
            },
            requestRecovery = ::requestRecovery,
            dismissCaptureRetry = { sendEvent(InternalEvent.DismissCaptureRetry) },
            modifier = modifier,
        )
    }

    private fun reportStatus() {
        if (destroyPending) return
        runtime.reportRunning(
            StreamingModule.Status(isStreaming(), clients.isNotEmpty(), captureAttempt?.id, error = null),
            SystemClock.uptimeMillis(),
        )
    }

    internal class CaptureAttempt(
        val id: StreamingModule.CaptureAttemptId,
        val generation: Long,
        val enableMic: Boolean,
        val enableDeviceAudio: Boolean,
    ) {
        val job: Job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        @Volatile
        var stopRequested = false
        var grant: ScreenCaptureAccess.Grant? = null
        var mediaProjection: MediaProjection? = null
        var callback: MediaProjection.Callback? = null
        var started = false
        @Volatile
        var revoked = false
        @Volatile
        var failed = false
        val audioEnabled: Boolean get() = enableMic || enableDeviceAudio
    }

    // All Volatile vars must be write on this (WebRTC-HT) thread
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var currentStreamId: StreamId = StreamId.EMPTY
    @Volatile
    private var currentStreamPassword: StreamPassword = StreamPassword.EMPTY
    @Volatile
    private var projection: WebRtcProjection? = null
    // All Volatile vars must be write on this (WebRTC-HT) thread

    // All vars must be read/write on this (WebRTC-HT) thread
    private var deviceConfiguration: Configuration = Configuration(service.resources.configuration)
    private var signalingRecoveryAttempts: Int = 0
    private var signaling: SocketSignaling? = null
    private var signalingSocket: Any? = null
    @Volatile
    private var pendingStartAttemptId: String? = null
    private var waitingForPermission: Boolean = false
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
        data class NonceResult(val result: Result<String>, val attempt: Int, val forceTokenUpdate: Boolean) : InternalEvent(Priority.RECOVER_IGNORE)
        data class TokenResult(val result: Result<PlayIntegrityToken>, val nonce: String, val attempt: Int, val forceUpdate: Boolean) :
            InternalEvent(Priority.RECOVER_IGNORE)

        data class GetToken(val nonce: String, val attempt: Int, val forceUpdate: Boolean) : InternalEvent(Priority.RECOVER_IGNORE)
        data class OpenSocket(val token: PlayIntegrityToken) : InternalEvent(Priority.RECOVER_IGNORE)
        data class RecoverSignaling(val forceTokenUpdate: Boolean, val reason: String) : InternalEvent(Priority.RECOVER_IGNORE)
        data object StreamCreate : InternalEvent(Priority.RECOVER_IGNORE)
        data class StreamCreated(val streamId: StreamId) : InternalEvent(Priority.RECOVER_IGNORE)
        data object StreamRemoved : InternalEvent(Priority.RECOVER_IGNORE)
        data class ClientJoin(val clientId: ClientId, val joinAttemptId: AttemptId, val iceServers: List<IceServer>) : InternalEvent(Priority.RECOVER_IGNORE)
        data class ClientLeave(val clientId: ClientId, val joinAttemptId: AttemptId) : InternalEvent(Priority.RECOVER_IGNORE)
        data class SocketSignalingError(val error: SocketSignaling.Error) : InternalEvent(Priority.RECOVER_IGNORE)
        data class CaptureFatal(val generation: Long, val cause: Throwable) : InternalEvent(Priority.STOP_IGNORE)
        data class StartStream(val clearStartupPolicyError: Boolean = false) : InternalEvent(Priority.STOP_IGNORE)
        data class StartProjection(val attempt: CaptureAttempt, val grant: ScreenCaptureAccess.Grant) : InternalEvent(Priority.START_PROJECTION)
        data class ConsentNotGranted(val attempt: CaptureAttempt, val reason: ScreenCaptureAccess.NoGrantReason?) : InternalEvent(Priority.RECOVER_IGNORE)
        data class ConsentFailed(val attempt: CaptureAttempt, val cause: Throwable) : InternalEvent(Priority.RECOVER_IGNORE)
        data object DismissCaptureRetry : InternalEvent(Priority.RECOVER_IGNORE)
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

        data class ScreenOff(val generation: Long) : InternalEvent(Priority.STOP_IGNORE)
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

    private fun signalingListener(epoch: Long, socket: Any) = object : SocketSignaling.EventListener {
        private fun sendSocketEvent(event: WebRtcEvent) {
            sendEvent(event, expectedSignalingEpoch = epoch, expectedSignalingSocket = socket)
        }

        override fun onSocketConnected() {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onSocketConnected"))
            sendSocketEvent(InternalEvent.StreamCreate)
        }

        override fun onTokenExpired() {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onTokenExpired"))
            sendSocketEvent(InternalEvent.RecoverSignaling(forceTokenUpdate = true, reason = "TokenExpired"))
        }

        override fun onSocketDisconnected(reason: String) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onSocketDisconnected", reason))
            sendSocketEvent(InternalEvent.RecoverSignaling(forceTokenUpdate = false, reason = reason))
        }

        override fun onStreamCreated(streamId: StreamId) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onStreamCreated", "$streamId"))
            sendSocketEvent(InternalEvent.StreamCreated(streamId))
        }

        override fun onStreamRemoved() {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onStreamRemoved"))
            sendSocketEvent(InternalEvent.StreamRemoved)
        }

        override fun onClientJoin(clientId: ClientId, joinAttemptId: AttemptId, iceServers: List<IceServer>) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientJoin", "$clientId joinAttemptId=$joinAttemptId IceServers: ${iceServers.size}"))
            sendSocketEvent(InternalEvent.ClientJoin(clientId, joinAttemptId, iceServers))
        }

        override fun onClientLeave(clientId: ClientId, joinAttemptId: AttemptId) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientLeave", "$clientId joinAttemptId=$joinAttemptId"))
            sendSocketEvent(InternalEvent.ClientLeave(clientId, joinAttemptId))
        }

        override fun onClientNotFound(key: NegotiationKey, reason: String) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientNotFound", "${key.clientId} negotiationAttemptId=${key.attemptId}"))
            sendSocketEvent(InternalEvent.ClientNotFound(key, reason))
        }

        override fun onClientStartNotFound(key: ClientSessionKey, reason: String) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientStartNotFound", "${key.clientId} joinAttemptId=${key.joinAttemptId}"))
            sendSocketEvent(InternalEvent.ClientStartNotFound(key, reason))
        }

        override fun onClientAnswer(clientId: ClientId, negotiationAttemptId: AttemptId, answer: Answer) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientAnswer", "$clientId negotiationAttemptId=$negotiationAttemptId"))
            sendSocketEvent(InternalEvent.SocketClientAnswer(clientId, negotiationAttemptId, answer))
        }

        override fun onClientCandidate(clientId: ClientId, negotiationAttemptId: AttemptId, candidate: IceCandidate) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientCandidate", "$clientId negotiationAttemptId=$negotiationAttemptId"))
            sendSocketEvent(InternalEvent.SocketClientCandidate(clientId, negotiationAttemptId, candidate))
        }

        override fun onError(cause: SocketSignaling.Error) {
            if (cause.log) XLog.e(this@WebRtcStreamingService.getLog("SocketSignaling.onError", cause.message), cause)
            else XLog.e(this@WebRtcStreamingService.getLog("SocketSignaling.onError", cause.message))
            sendSocketEvent(InternalEvent.SocketSignalingError(cause))
        }
    }

    private fun clientListener(epoch: Long) = object : WebRtcClient.EventListener {
        override fun onHostOffer(key: NegotiationKey, offer: Offer) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onHostOffer", "Client: ${key.clientId} negotiationAttemptId=${key.attemptId}"))
            sendEvent(InternalEvent.SendHostOffer(key, offer), expectedSignalingEpoch = epoch)
        }

        override fun onHostCandidates(key: NegotiationKey, candidates: List<IceCandidate>) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onHostCandidates", "Client: ${key.clientId} negotiationAttemptId=${key.attemptId}"))
            sendEvent(InternalEvent.SendHostCandidates(key, candidates), expectedSignalingEpoch = epoch)
        }

        override fun onClientAddress(key: ClientSessionKey) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onClientAddress", "Client: ${key.clientId}"))
            sendEvent(InternalEvent.ClientAddress(key), expectedSignalingEpoch = epoch)
        }

        override fun onClientAnswerApplied(key: NegotiationKey) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onClientAnswerApplied", "Client: ${key.clientId} negotiationAttemptId=${key.attemptId}"))
            sendEvent(InternalEvent.ClientAnswerApplied(key), expectedSignalingEpoch = epoch)
        }

        override fun onPeerDisconnected(key: NegotiationKey, connectionStateEpoch: Long) {
            XLog.w(
                this@WebRtcStreamingService.getLog(
                    "WebRTCClient.onPeerDisconnected",
                    "Client: ${key.clientId} disconnected. Scheduling grace removal, negotiationAttemptId=${key.attemptId}"
                )
            )
            sendEvent(InternalEvent.PeerDisconnected(key, connectionStateEpoch), peerDisconnectedGraceMs, expectedSignalingEpoch = epoch)
        }

        override fun onError(key: NegotiationKey, cause: Throwable) {
            val isPeerFailed = cause.message?.startsWith("onPeerFailed") == true
            if (isPeerFailed) {
                XLog.w(this@WebRtcStreamingService.getLog("WebRTCClient.onError", "Client: ${key.clientId}: ${cause.message}"))
                sendEvent(InternalEvent.ClientError(key, false, "onError:${cause.message}"), expectedSignalingEpoch = epoch)
            } else {
                XLog.e(this@WebRtcStreamingService.getLog("WebRTCClient.onError", "Client: ${key.clientId}"), cause)
                sendEvent(InternalEvent.ClientError(key, true, "onError:${cause.message}"), expectedSignalingEpoch = epoch)
            }
        }
    }

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            XLog.d(this@WebRtcStreamingService.getLog("onReceive", "Action: ${intent?.action}"))
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                captureAttempt?.let { sendEvent(InternalEvent.ScreenOff(it.generation)) }
            }
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
        workerStarted = true
        XLog.d(getLog("start"))

        mutableWebRtcStateFlow.value = WebRtcState()
        sendEvent(InternalEvent.InitState)
        handler.post(heartbeat)

        val intentFilter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            service.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        else
            service.registerReceiver(broadcastReceiver, intentFilter)
        receiverRegistered = true

        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build(),
            networkCallback
        )
        networkRegistered = true

        networkAvailable.onEach { available ->
            XLog.d(getLog("start", "networkAvailable: $available"))
            if (available) sendEvent(InternalEvent.GetNonce(0, false))
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

    /** Existing worker teardown remains best effort; the native owner stops the Android Service. */
    @MainThread
    private suspend fun stopWorker(): Boolean {
        var succeeded = true
        fun attempt(action: () -> Unit) {
            runCatching(action).onFailure { cause ->
                succeeded = false
                XLog.w(getLog("stopWorker", "Best-effort shutdown failed"), cause)
            }
        }
        attempt { wakeLock?.let { if (it.isHeld) it.release() } }
        if (receiverRegistered) attempt { service.unregisterReceiver(broadcastReceiver) }
        if (networkRegistered) attempt { connectivityManager.unregisterNetworkCallback(networkCallback) }
        attempt { coroutineScope.cancel() }
        val destroyJob = Job()
        sendEvent(InternalEvent.Destroy(destroyJob))
        if (withTimeoutOrNull(3000.milliseconds) { destroyJob.join(); true } != true) {
            succeeded = false
            captureAttempt?.failed = true
            XLog.w(getLog("stopWorker", "Best-effort worker shutdown timed out"))
        }
        handler.removeCallbacksAndMessages(null)
        quit()
        return succeeded && shutdownSucceeded
    }

    @Volatile
    private var destroyPending: Boolean = false

    private data class QueuedEvent(
        val event: WebRtcEvent,
        val signalingEpoch: Long? = null,
        val signalingSocket: Any? = null,
    )

    @AnyThread
    @Synchronized
    internal fun sendEvent(
        event: WebRtcEvent,
        timeout: Long = 0,
        expectedSignalingEpoch: Long? = null,
        expectedSignalingSocket: Any? = null,
    ) {
        if (!workerStarted || (destroyPending && event !is InternalEvent.Destroy) ||
            (event !is InternalEvent.Destroy && !runtime.isCurrent())
        ) return
        if (event is InternalEvent.Destroy) {
            handler.removeCallbacksAndMessages(null)
        }
        handler.sendMessageDelayed(handler.obtainMessage(event.priority, QueuedEvent(event, expectedSignalingEpoch, expectedSignalingSocket)), timeout)
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking {
        val queued = msg.obj as QueuedEvent
        val event = queued.event
        if (queued.signalingEpoch != null && queued.signalingEpoch != signalingEpoch) return@runBlocking true
        if (queued.signalingSocket != null && queued.signalingSocket !== signalingSocket) return@runBlocking true
        if (event !is InternalEvent.Destroy && (destroyPending || !runtime.isCurrent())) {
            requestModuleShutdown()
            return@runBlocking true
        }
        try {
            XLog.d(this@WebRtcStreamingService.getLog("handleMessage", "Event [$event] Current state: [${getStateString()}]"))
            processEvent(event)
        } catch (cause: Throwable) {
            if (event is InternalEvent.Destroy) shutdownSucceeded = false
            XLog.e(this@WebRtcStreamingService.getLog("handleMessage.catch", cause.toString()), cause)

            captureAnalytics?.unhandledFailure()
            clearPendingStart()
            stopStream("HandleMessageException")

            if (cause is WebRtcError) currentError.set(cause) else currentError.set(WebRtcError.UnknownError(cause))
        } finally {
            XLog.d(this@WebRtcStreamingService.getLog("handleMessage", "Done [$event] New state: [${getStateString()}]"))
            if (event is InternalEvent.Destroy) event.destroyJob.complete()
            captureAnalytics?.consumersChanged(currentActiveConsumersCount(), SystemClock.elapsedRealtime())
            publishState()
            reportStatus()
        }

        true
    }

    // On WebRTC-HT only
    private suspend fun processEvent(event: WebRtcEvent) {
        if (event is WebRtcEvent.StopStream && event.expectedGeneration != null &&
            event.expectedGeneration != captureAttempt?.generation
        ) return
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
                signalingSocket = null
                currentStreamId = StreamId.EMPTY
                currentStreamPassword = StreamPassword.EMPTY
                waitingForPermission = false
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

                val operationEpoch = signalingEpoch
                playIntegrity.getNonce {
                    sendEvent(InternalEvent.NonceResult(this, event.attempt, event.forceTokenUpdate), expectedSignalingEpoch = operationEpoch)
                }
            }

            is InternalEvent.NonceResult -> {
                event.result.run {
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

                val operationEpoch = signalingEpoch
                playIntegrity.getToken(event.nonce, event.forceUpdate) {
                    sendEvent(InternalEvent.TokenResult(this, event.nonce, event.attempt, event.forceUpdate), expectedSignalingEpoch = operationEpoch)
                }
            }

            is InternalEvent.TokenResult -> {
                event.result.run {
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
                val socket = Any()
                signalingSocket = socket
                signaling = SocketSignaling(environment, okHttpClient, signalingListener(signalingEpoch, socket), passwordVerifier)
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
                if (destroyPending || !runtime.isCurrent()) return

                if (currentStreamId.isEmpty().not()) {
                    if (currentStreamId != event.streamId) {
                        stopStream("StreamCreated: New StreamID")
                        if (destroyPending || !runtime.isCurrent()) return
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
                    if (synchronized(ownerGate) { destroyPending || !runtime.isCurrent() }) return
                    val created = try {
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
                    val installed = synchronized(ownerGate) {
                        if (destroyPending || !runtime.isCurrent() || projection != null) false else {
                            projection = created
                            true
                        }
                    }
                    if (!installed) {
                        runCatching { created.destroy() }.onFailure { cause ->
                            shutdownSucceeded = false
                            XLog.w(getLog("StreamCreated", "Unadmitted media cleanup failed"), cause)
                        }
                        return
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
                        clientListener(signalingEpoch)
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

            is InternalEvent.ConsentNotGranted -> {
                val attempt = event.attempt
                if (captureAttempt !== attempt) return
                analyticsFor(attempt)?.consentNotGranted(event.reason)
                if (event.reason == ScreenCaptureAccess.NoGrantReason.Declined) showCaptureRetry = true
                stopStream("ConsentNotGranted")
            }

            is InternalEvent.ConsentFailed -> {
                val attempt = event.attempt
                if (captureAttempt !== attempt) return
                attempt.failed = true
                analyticsFor(attempt)?.consentFailed(event.cause)
                if (event.cause is ConsentLaunchException) {
                    showCaptureRetry = true
                } else {
                    currentError.set(
                        when {
                            event.cause is WebRtcError -> event.cause
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && attempt.audioEnabled && event.cause is SecurityException ->
                                WebRtcError.AudioStartBlocked(event.cause)

                            else -> WebRtcError.ScreenCaptureStartBlocked(event.cause)
                        }
                    )
                }
                stopStream("ConsentFailed")
            }

            InternalEvent.DismissCaptureRetry -> showCaptureRetry = false

            is InternalEvent.StartProjection -> {
                val attempt = event.attempt
                if (!isCurrent(attempt)) return
                waitingForPermission = false
                clearPendingStart()
                val media = checkNotNull(projection)
                var acquired = false
                try {
                    if (attempt.audioEnabled && !hasAudioPermission()) throw WebRtcError.AudioPermissionRequired()
                    // Store ownership before registration, suspension or the first fallible pipeline call.
                    val rawProjection = captureAccess.createProjection(event.grant, retryAfterRecentForegroundStart = true)
                    attempt.mediaProjection = rawProjection
                    acquired = true
                    val callback = object : MediaProjection.Callback() {
                        override fun onStop() {
                            attempt.revoked = true
                            sendEvent(WebRtcEvent.StopStream("MediaProjection.onStop", attempt.generation))
                        }
                    }
                    attempt.callback = callback
                    rawProjection.registerCallback(callback, handler)
                    if (!isCurrent(attempt)) throw CancellationException("Capture startup was stopped")
                    var startupInvalidated = false
                    val pipelineStarted = media.start(
                        currentStreamId,
                        rawProjection,
                        { cause -> sendEvent(InternalEvent.CaptureFatal(attempt.generation, cause)) },
                        {
                            val valid = isCurrent(attempt) && !attempt.revoked
                            if (!valid) startupInvalidated = true
                            valid
                        },
                    )
                    check(pipelineStarted || startupInvalidated) { "Capture pipeline did not start" }
                    if (startupInvalidated || !isCurrent(attempt) || attempt.revoked) {
                        throw CancellationException("Capture startup was interrupted")
                    }
                    media.setMicrophoneMute(!attempt.enableMic)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) media.setDeviceAudioMute(!attempt.enableDeviceAudio)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) service.registerComponentCallbacks(componentCallback)
                    @Suppress("DEPRECATION")
                    @SuppressLint("WakelockTimeout")
                    if (Build.MANUFACTURER !in listOf("OnePlus", "OPPO") && webRtcSettings.data.value.keepAwake) {
                        val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                        wakeLock = powerManager.newWakeLock(flags, "ScreenStream::WebRTC-Tag").apply { acquire() }
                    }
                    captureAccess.finishProjectionCreation(attempt.id)
                    attempt.started = true
                    service.updateCaptureNotification(attempt.id, true)
                    if (!isCurrent(attempt)) throw CancellationException("Capture startup was stopped")
                    requireNotNull(signaling).sendStreamStart()
                    clients.values.forEach { it.client.start(checkNotNull(media.localMediaSteam)) }
                    currentError.set(null)
                    analyticsFor(attempt)?.started(currentActiveConsumersCount(), SystemClock.elapsedRealtime())
                } catch (_: CancellationException) {
                    analyticsFor(attempt)?.startAborted()
                    stopStream("StartProjectionInterrupted")
                } catch (cause: Throwable) {
                    attempt.failed = true
                    analyticsFor(attempt)?.projectionStartFailed(cause)
                    currentError.set(
                        when {
                            cause is WebRtcError -> cause
                            !acquired -> WebRtcError.ProjectionAcquireRejected(cause)
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && attempt.audioEnabled && cause is SecurityException ->
                                WebRtcError.AudioStartBlocked(cause)

                            else -> WebRtcError.UnknownError(cause)
                        }
                    )
                    stopStream("StartProjectionFailed")
                }
            }

            is InternalEvent.CaptureFatal -> {
                if (destroyPending) {
                    XLog.i(getLog("CaptureFatal", "DestroyPending. Ignoring"), event.cause)
                    return
                }
                if (captureAttempt?.generation != event.generation) return
                captureAttempt?.failed = true
                XLog.e(getLog("CaptureFatal", "Stopping stream"), event.cause)
                sendEvent(WebRtcEvent.StopStream("CaptureFatal:${event.cause.javaClass.simpleName}:${event.cause.message}", event.generation))
            }

            is WebRtcEvent.StopStream -> stopStream(
                event.reason,
                reuseConsent = event.reason == "Coordinator Stop" || event.reason == "User action: Button" ||
                        event.reason == "WebRtcModuleService. User action: Notification",
            )

            is WebRtcEvent.RecoverError -> {
                if (event.errorToken != currentErrorToken) return
                signalingEpoch++
                if (destroyPending) {
                    XLog.i(getLog("RecoverError", "DestroyPending. Ignoring"))
                    return
                }

                stopStream("RecoverError")

                signaling?.destroy()
                signaling = null
                signalingSocket = null
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
                if (destroyPending || !runtime.isCurrent()) return
                if (event.clearStartupPolicyError && currentError.get()?.isStartupPolicyError() == true) currentError.set(null)
                if (captureAttempt != null) return
                val settings = webRtcSettings.data.value
                if ((settings.enableMic || settings.enableDeviceAudio) && !hasAudioPermission()) {
                    // No consent has been admitted or consumed and no education has been shown.
                    WebRtcSessionAnalytics.audioPreflightFailed(analyticsFactory)
                    currentError.set(WebRtcError.AudioPermissionRequired())
                    return
                }
                if (isStreaming() || currentError.get() != null || currentStreamId.isEmpty() || projection == null ||
                    (signaling?.socketId() == null && !networkRecovery.value)
                ) return
                val attempt = synchronized(ownerGate) {
                    if (destroyPending || !runtime.isCurrent()) return
                    val accepted =
                        CaptureAttempt(StreamingModule.CaptureAttemptId(instanceId), ++captureGeneration, settings.enableMic, settings.enableDeviceAudio)
                    captureAnalytics = WebRtcSessionAnalytics(analyticsFactory, accepted.id)
                    captureAttempt = accepted
                    accepted
                }
                showCaptureRetry = false
                waitingForPermission = true
                pendingStartAttemptId = attempt.id.uuid.toString()
                val analytics = analyticsFor(attempt)
                attempt.scope.launch {
                    try {
                        analytics?.beginConsent()
                        val result = captureAccess.requestConsent(attempt.id, attempt.job) {
                            analytics?.beginForegroundCall()
                            var foregroundFailure: Throwable? = null
                            try {
                                if (!isCurrent(attempt)) false
                                else {
                                    if (attempt.audioEnabled && !hasAudioPermission()) throw WebRtcError.AudioPermissionRequired()
                                    service.enterCaptureForeground(attempt.id, requiresMicrophoneType = attempt.audioEnabled)
                                }
                            } catch (cause: Throwable) {
                                foregroundFailure = cause
                                throw cause
                            } finally {
                                analytics?.finishForegroundCall(foregroundFailure, attempt.audioEnabled)
                            }
                        }
                        when (result) {
                            is ScreenCaptureAccess.ConsentResult.Granted -> {
                                attempt.grant = result.grant
                                sendEvent(InternalEvent.StartProjection(attempt, result.grant))
                            }

                            is ScreenCaptureAccess.ConsentResult.NotGranted ->
                                sendEvent(InternalEvent.ConsentNotGranted(attempt, result.reason))
                        }
                    } catch (cause: CancellationException) {
                        // A host can cancel an unsubmitted request while this attempt remains admitted.
                        if (isCurrent(attempt)) sendEvent(InternalEvent.ConsentNotGranted(attempt, null))
                        throw cause
                    } catch (cause: Throwable) {
                        sendEvent(InternalEvent.ConsentFailed(attempt, cause))
                    } finally {
                        analytics?.consentCallFinished()
                    }
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
                    // Keyframe is requested by WebRtcClient through the patched RtpSender API.
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
                if (captureAttempt?.generation != event.generation) return
                if (destroyPending) {
                    XLog.i(getLog("ScreenOff", "DestroyPending. Ignoring"), IllegalStateException("ScreenOff: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("ScreenOff", "Not streaming. Ignoring."))
                    return
                }

                if (webRtcSettings.data.value.stopOnSleep) sendEvent(WebRtcEvent.StopStream("ScreenOff", event.generation))
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
                captureAnalytics?.startAborted()
                stopStream("Destroy", reuseConsent = true)

                signaling?.destroy()
                signaling = null
                signalingSocket = null
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

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isCurrent(attempt: CaptureAttempt): Boolean =
        !destroyPending && !attempt.stopRequested && captureAttempt === attempt && runtime.isCurrent()

    private fun analyticsFor(attempt: CaptureAttempt): WebRtcSessionAnalytics? =
        captureAnalytics?.takeIf { it.belongsTo(attempt.id) }

    /** Existing media teardown is best effort; the attempt identity remains installed until it settles. */
    private suspend fun stopStream(stopReason: String? = null, reuseConsent: Boolean = false) {
        val attempt = captureAttempt
        val analytics = attempt?.let(::analyticsFor)
        attempt?.stopRequested = true
        analytics?.closeIfUnadmitted()
        attempt?.job?.cancel()
        val wasStreaming = isStreaming()
        val consumers = currentActiveConsumersCount()
        var succeeded = true
        fun stop(action: () -> Unit) {
            runCatching(action).onFailure { cause ->
                succeeded = false
                attempt?.failed = true
                if (destroyPending) shutdownSucceeded = false
                XLog.w(getLog("stopStream", "Best-effort media shutdown failed"), cause)
            }
        }
        if (attempt != null) {
            runCatching { service.updateCaptureNotification(attempt.id, false) }.onFailure { succeeded = false }
        }
        audioIssueToastShown = false
        waitingForPermission = false
        clearPendingStart()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { service.unregisterComponentCallbacks(componentCallback) }
        }
        if (wasStreaming) {
            stop { signaling?.sendStreamStop() }
            clients.values.forEach { session -> stop { session.client.stop() } }
        }
        stop { projection?.stop() }
        if (attempt != null) {
            stop { attempt.callback?.let { attempt.mediaProjection?.unregisterCallback(it) } }
            stop { attempt.mediaProjection?.stop() }
            stop { captureAccess.finishProjectionCreation(attempt.id) }
        }
        analytics?.stopped(stopReason, wasStreaming, consumers, SystemClock.elapsedRealtime())
        stop { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        if (attempt != null) {
            try {
                service.leaveCaptureForeground(attempt.id)
                if (reuseConsent && succeeded && attempt.started && !attempt.failed && !attempt.revoked) {
                    attempt.grant?.let { grant -> stop { captureAccess.saveConsentForReuse(grant) } }
                }
            } finally {
                synchronized(ownerGate) {
                    if (captureAttempt === attempt) {
                        captureAttempt = null
                        captureAnalytics = null
                    }
                }
            }
        }
    }

    private fun currentActiveConsumersCount(): Int = clients.size

    private fun clearPendingStart() {
        pendingStartAttemptId = null
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

    private fun publishState() {
        val error = currentError.get()
        if (previousError !== error) {
            previousError = error
            currentErrorToken = error?.let { Uuid.random() }
            publishError(error)
        }
        mutableWebRtcStateFlow.value = WebRtcState(
            isBusy = (signaling?.socketId() == null && !networkRecovery.value) || currentStreamId.isEmpty() ||
                    captureAttempt != null || error != null || destroyPending || (!isStreaming() && projection == null),
            signalingServerUrl = environment.signalingServerUrl,
            streamId = currentStreamId.value,
            streamPassword = currentStreamPassword.value,
            waitingCastPermission = waitingForPermission,
            startAttemptId = pendingStartAttemptId,
            isStreaming = isStreaming(),
            networkRecovery = networkRecovery.value,
            clients = clients.values.map { it.client.toClient() },
            error = error,
            captureAttempt = captureAttempt?.id,
            errorToken = currentErrorToken,
            showCaptureRetry = showCaptureRetry,
        )
    }

    private fun publishError(error: WebRtcError?) {
        synchronized(errorGate) {
            if (errorPublicationClosed) return
            if (error == null || error is WebRtcError.NotificationPermissionRequired) {
                notifications.updateError(210, null)
                return
            }
            if (error is WebRtcError.NetworkError && (error.cause is UnknownHostException || error.cause is ConnectException) ||
                error is WebRtcError.PlayIntegrityError && error.isExpectedEnvironmentIssue() || error.isStartupPolicyError()
            ) {
                XLog.i(getLog("publishError", error.message ?: error.javaClass.simpleName))
            } else XLog.e(getLog("publishError"), error)
            val recover = !(isStreaming() && error is WebRtcError.SignalingServerUnavailable) && !error.isStartupPolicyError()
            val action = if (recover) currentErrorToken?.let { StreamingModuleActionReceiver.recoveryAction(service, instanceId, it) } else null
            notifications.updateError(210, StreamingModuleNotifications.ErrorContent.Text(error.toString(ContextCompat.getContextForLanguage(service)), action))
        }
    }
}

/** Keeps product reporting and its terminal state separate from capture resource ownership. */
private class WebRtcSessionAnalytics(
    private val factory: StreamingSessionAnalyticsFactory,
    attempt: StreamingModule.CaptureAttemptId,
) {
    private val key = SessionKey(attempt.instanceId.moduleId.value, attempt.instanceId.uuid, attempt.uuid)
    private val gate = Any()
    private var handle: StreamingSessionAnalytics? = null
    private var started = false
    private var closed = false
    private var foregroundCallInFlight = false
    private var abortDeferred = false

    fun belongsTo(attempt: StreamingModule.CaptureAttemptId): Boolean =
        key.moduleId == attempt.instanceId.moduleId.value && key.instanceId == attempt.instanceId.uuid && key.attemptId == attempt.uuid

    /** Called only inside the entered original consent worker, immediately before its request. */
    fun beginConsent() {
        synchronized(gate) {
            if (closed || handle != null) return
            observe { handle = factory.beginConsent(key, StreamMode.WEBRTC, EntryPoint.BUTTON) }
        }
    }

    fun consentCallFinished() {
        synchronized(gate) { observe { handle?.consentCallFinished() } }
    }

    fun beginForegroundCall() {
        synchronized(gate) {
            if (closed || started || handle == null) return
            foregroundCallInFlight = true
        }
    }

    fun finishForegroundCall(cause: Throwable?, requiresMicrophoneType: Boolean) {
        synchronized(gate) {
            if (!foregroundCallInFlight) return
            foregroundCallInFlight = false
            val deferredAbort = abortDeferred
            abortDeferred = false
            if (closed || started) return
            when {
                cause != null && cause !is CancellationException -> {
                    val group = when {
                        cause is WebRtcError.AudioPermissionRequired -> StartFailGroup.BLOCKED
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && requiresMicrophoneType && cause is SecurityException ->
                            StartFailGroup.BLOCKED

                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && cause is ForegroundServiceTypeException -> StartFailGroup.FATAL
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cause is ServiceStartNotAllowedException -> StartFailGroup.BLOCKED
                        else -> StartFailGroup.FATAL
                    }
                    startFailed(group)
                }

                cause is CancellationException || deferredAbort -> startAborted()
            }
        }
    }

    /** Stop can win before the consent worker has entered; it must never register afterward. */
    fun closeIfUnadmitted() {
        synchronized(gate) { if (handle == null) closed = true }
    }

    fun consentNotGranted(reason: ScreenCaptureAccess.NoGrantReason?) {
        when (reason) {
            ScreenCaptureAccess.NoGrantReason.Declined -> startFailed(StartFailGroup.PERMISSION_DENIED)
            ScreenCaptureAccess.NoGrantReason.Busy -> startFailed(StartFailGroup.BUSY)
            ScreenCaptureAccess.NoGrantReason.EducationDeclined,
            ScreenCaptureAccess.NoGrantReason.HostUnavailable,
            ScreenCaptureAccess.NoGrantReason.ForegroundRejected,
            null -> startAborted()
        }
    }

    fun consentFailed(cause: Throwable) {
        startFailed(if (cause is ConsentLaunchException) StartFailGroup.PERMISSION_DENIED else StartFailGroup.BLOCKED)
    }

    fun projectionStartFailed(cause: Throwable) {
        startFailed(if (cause is SecurityException || cause is WebRtcError) StartFailGroup.BLOCKED else StartFailGroup.FATAL)
    }

    fun unhandledFailure() {
        startFailed(StartFailGroup.UNKNOWN)
    }

    private fun startFailed(group: StartFailGroup) {
        synchronized(gate) {
            if (closed || started) return
            closed = true
            observe { handle?.startFailed(group) }
        }
    }

    fun startAborted() {
        synchronized(gate) {
            if (closed || started) return
            if (foregroundCallInFlight) {
                abortDeferred = true
                return
            }
            closed = true
            observe { handle?.startAborted() }
        }
    }

    fun started(activeConsumers: Int, atElapsedRealtimeMs: Long) {
        synchronized(gate) {
            if (closed || started) return
            started = true
            observe { handle?.started(activeConsumers, atElapsedRealtimeMs) }
        }
    }

    fun consumersChanged(activeConsumers: Int, atElapsedRealtimeMs: Long) {
        synchronized(gate) {
            if (closed || !started) return
            observe { handle?.consumersChanged(activeConsumers, atElapsedRealtimeMs) }
        }
    }

    fun stopped(stopReason: String?, wasStreaming: Boolean, activeConsumers: Int, atElapsedRealtimeMs: Long) {
        synchronized(gate) {
            if (closed) return
            if (!started && foregroundCallInFlight) {
                abortDeferred = true
                return
            }
            closed = true
            observe {
                val analyticsStopReason = if (stopReason == "MediaProjection.onStop") "ProjectionCoordinator.onStop" else stopReason
                if (started && wasStreaming) handle?.ended(analyticsStopReason, activeConsumers, atElapsedRealtimeMs)
                else if (!started) handle?.startAborted()
            }
        }
    }

    companion object {
        fun audioPreflightFailed(factory: StreamingSessionAnalyticsFactory) {
            observe { factory.preflightFailed(StreamMode.WEBRTC, EntryPoint.BUTTON, StartFailGroup.BLOCKED) }
        }

        /** Observation failures, including their own cancellation, cannot change native media work. */
        private inline fun observe(action: () -> Unit) {
            try {
                action()
            } catch (cause: Throwable) {
                runCatching { android.util.Log.w("WebRtcSessionAnalytics", "Reporting failed", cause) }
            }
        }
    }
}
