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
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.getVersionName
import info.dvkr.screenstream.common.module.ProjectionCoordinator
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.WebRtcModuleService
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings
import info.dvkr.screenstream.webrtc.ui.WebRtcError
import info.dvkr.screenstream.webrtc.ui.WebRtcState
import info.dvkr.screenstream.webrtc.ui.isExpectedEnvironmentIssue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.IceServer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.pow

internal class WebRtcStreamingService(
    private val service: WebRtcModuleService,
    private val mutableWebRtcStateFlow: MutableStateFlow<WebRtcState>,
    private val environment: WebRtcEnvironment,
    private val webRtcSettings: WebRtcSettings
) : HandlerThread("WebRTC-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback {

    private data class ClientSession(val generation: Long, val epoch: Long, val client: WebRtcClient)
    private data class ClientTag(val generation: Long, val epoch: Long)

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
                sendEvent(WebRtcEvent.Intentable.StopStream("ProjectionCoordinator.onStop[generation=$generation]"))
            }
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
    private var socketErrorRetryAttempts: Int = 0
    private var signaling: SocketSignaling? = null
    private var waitingForPermission: Boolean = false
    private var mediaProjectionIntent: Intent? = null
    private var clients: MutableMap<ClientId, ClientSession> = HashMap()
    private var clientGenerationCounter: Long = 0
    private var streamEpoch: Long = 0
    private val clientGenerations: ConcurrentHashMap<ClientId, ClientTag> = ConcurrentHashMap()
    private var previousError: WebRtcError? = null
    private var audioIssueToastShown: Boolean = false
    // All vars must be read/write on this (WebRTC-HT) thread

    internal sealed class InternalEvent(priority: Int) : WebRtcEvent(priority) {
        data object InitState : InternalEvent(Priority.RECOVER_IGNORE)
        data class GetNonce(val attempt: Int, val forceTokenUpdate: Boolean) : InternalEvent(Priority.RECOVER_IGNORE)
        data class GetToken(val nonce: String, val attempt: Int, val forceUpdate: Boolean) : InternalEvent(Priority.RECOVER_IGNORE)
        data class OpenSocket(val token: PlayIntegrityToken) : InternalEvent(Priority.RECOVER_IGNORE)
        data object StreamCreate : InternalEvent(Priority.RECOVER_IGNORE)
        data class StreamCreated(val streamId: StreamId) : InternalEvent(Priority.RECOVER_IGNORE)
        data class ClientJoin(val clientId: ClientId, val iceServers: List<IceServer>) : InternalEvent(Priority.RECOVER_IGNORE)
        data class SocketSignalingError(val error: SocketSignaling.Error) : InternalEvent(Priority.RECOVER_IGNORE)
        data class CaptureFatal(val cause: Throwable) : InternalEvent(Priority.STOP_IGNORE)
        data object StartStream : InternalEvent(Priority.STOP_IGNORE)
        data class SendHostOffer(val clientId: ClientId, val generation: Long, val epoch: Long, val offer: Offer) : InternalEvent(Priority.STOP_IGNORE)
        data class HostOfferConfirmed(val clientId: ClientId, val generation: Long, val epoch: Long) : InternalEvent(Priority.STOP_IGNORE)
        data class SetClientAnswer(val clientId: ClientId, val generation: Long, val epoch: Long, val answer: Answer) : InternalEvent(Priority.STOP_IGNORE)
        data class SendHostCandidates(val clientId: ClientId, val generation: Long, val epoch: Long, val candidates: List<IceCandidate>) : InternalEvent(Priority.STOP_IGNORE) {
            override fun toString(): String = "SendHostCandidates(clientId=$clientId)"
        }
        data class SetClientCandidate(val clientId: ClientId, val generation: Long, val epoch: Long, val candidate: IceCandidate) : InternalEvent(Priority.STOP_IGNORE) {
            override fun toString(): String = "SetClientCandidate(clientId=$clientId)"
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

    private fun requireClientGeneration(clientId: ClientId, source: String): ClientTag? {
        val clientTag = clientGenerations[clientId]
        if (clientTag == null) {
            XLog.i(getLog(source, "Client $clientId has no active generation. Ignoring stale event."))
            return null
        }
        return clientTag
    }

    private fun withActiveClient(source: String, clientId: ClientId, generation: Long, epoch: Long, onMissing: (() -> Unit)? = null, block: (ClientSession) -> Unit) {
        val currentClient = clients[clientId]
        if (currentClient == null) {
            XLog.i(getLog(source, "Client $clientId not found"))
            onMissing?.invoke()
            return
        }

        if (currentClient.generation != generation || currentClient.epoch != epoch || epoch != streamEpoch) {
            XLog.i(getLog(source, "Stale client event for $clientId. Event(g=$generation,e=$epoch), Current(g=${currentClient.generation},e=${currentClient.epoch}), streamEpoch=$streamEpoch"))
            return
        }

        block(currentClient)
    }

    private fun bumpStreamEpoch(source: String): Long {
        streamEpoch += 1
        XLog.d(getLog(source, "streamEpoch=$streamEpoch"))
        return streamEpoch
    }

    private fun synchronizeClientEpoch(epoch: Long) {
        clients = clients.mapValuesTo(HashMap(clients.size)) { (_, session) ->
            session.copy(epoch = epoch)
        }
        clients.forEach { (clientId, session) ->
            clientGenerations[clientId] = ClientTag(session.generation, session.epoch)
        }
    }

    private val ssEventListener = object : SocketSignaling.EventListener {
        override fun onSocketConnected() {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onSocketConnected"))
            sendEvent(InternalEvent.StreamCreate)
        }

        override fun onTokenExpired() {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onTokenExpired"))
            sendEvent(InternalEvent.GetNonce(0, true))
        }

        override fun onSocketDisconnected(reason: String) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onSocketDisconnected", reason))
            networkRecovery.value = isStreaming()
            sendEvent(InternalEvent.GetNonce(0, false))
        }

        override fun onStreamCreated(streamId: StreamId) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onStreamCreated", "$streamId"))
            sendEvent(InternalEvent.StreamCreated(streamId))
        }

        override fun onStreamRemoved() {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onStreamRemoved"))
            sendEvent(InternalEvent.StreamCreate)
        }

        override fun onClientJoin(clientId: ClientId, iceServers: List<IceServer>) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientJoin", "$clientId IceServers: ${iceServers.size}"))
            sendEvent(InternalEvent.ClientJoin(clientId, iceServers))
        }

        override fun onClientLeave(clientId: ClientId) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientLeave", "$clientId"))
            sendEvent(WebRtcEvent.RemoveClient(clientId, false, "onClientLeave"))
        }

        override fun onClientNotFound(clientId: ClientId, reason: String) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientNotFound", "$clientId"))
            sendEvent(WebRtcEvent.RemoveClient(clientId, false, reason))
        }

        override fun onClientAnswer(clientId: ClientId, answer: Answer) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientAnswer", "$clientId"))
            val clientTag = requireClientGeneration(clientId, "SocketSignaling.onClientAnswer") ?: return
            sendEvent(InternalEvent.SetClientAnswer(clientId, clientTag.generation, clientTag.epoch, answer))
        }

        override fun onClientCandidate(clientId: ClientId, candidate: IceCandidate) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientCandidate", "$clientId"))
            val clientTag = requireClientGeneration(clientId, "SocketSignaling.onClientCandidate") ?: return
            sendEvent(InternalEvent.SetClientCandidate(clientId, clientTag.generation, clientTag.epoch, candidate))
        }

        override fun onHostOfferConfirmed(clientId: ClientId) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onHostOfferConfirmed", "$clientId"))
            val clientTag = requireClientGeneration(clientId, "SocketSignaling.onHostOfferConfirmed") ?: return
            sendEvent(InternalEvent.HostOfferConfirmed(clientId, clientTag.generation, clientTag.epoch))
        }

        override fun onError(cause: SocketSignaling.Error) {
            if (cause.log) XLog.e(this@WebRtcStreamingService.getLog("SocketSignaling.onError", cause.message), cause)
            else XLog.e(this@WebRtcStreamingService.getLog("SocketSignaling.onError", cause.message))
            sendEvent(InternalEvent.SocketSignalingError(cause))
        }
    }

    private val webRtcClientEventListener = object : WebRtcClient.EventListener {
        override fun onHostOffer(clientId: ClientId, generation: Long, epoch: Long, offer: Offer) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onHostOffer", "Client: $clientId"))
            sendEvent(InternalEvent.SendHostOffer(clientId, generation, epoch, offer))
        }

        override fun onHostCandidates(clientId: ClientId, generation: Long, epoch: Long, candidates: List<IceCandidate>) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onHostCandidates", "Client: $clientId"))
            sendEvent(InternalEvent.SendHostCandidates(clientId, generation, epoch, candidates))
        }

        override fun onClientAddress(clientId: ClientId, generation: Long, epoch: Long) {
            if (clientGenerations[clientId] != ClientTag(generation, epoch) || epoch != streamEpoch) {
                XLog.i(this@WebRtcStreamingService.getLog("WebRTCClient.onClientAddress", "Stale generation for client: $clientId. Ignoring."))
                return
            }
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onClientAddress", "Client: $clientId"))
            sendEvent(WebRtcEvent.UpdateState)
        }

        override fun onError(clientId: ClientId, generation: Long, epoch: Long, cause: Throwable) {
            if (clientGenerations[clientId] != ClientTag(generation, epoch) || epoch != streamEpoch) {
                XLog.i(this@WebRtcStreamingService.getLog("WebRTCClient.onError", "Stale generation for client: $clientId. Ignoring."), cause)
                return
            }
            if (cause.message?.startsWith("onPeerDisconnected") == true) {
                XLog.w(this@WebRtcStreamingService.getLog("WebRTCClient.onError", "Client: $clientId: ${cause.message}"), cause)
                sendEvent(WebRtcEvent.RemoveClient(clientId, false, "onError:${cause.message}"))
            } else {
                XLog.e(this@WebRtcStreamingService.getLog("WebRTCClient.onError", "Client: $clientId"), cause)
                sendEvent(WebRtcEvent.RemoveClient(clientId, true, "onError:${cause.message}"))
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

        val recordAudioPermission = ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO)
        if (recordAudioPermission == PackageManager.PERMISSION_DENIED) {
            coroutineScope.launch { webRtcSettings.updateData { copy(enableMic = false, enableDeviceAudio = false) } }
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
        withTimeoutOrNull(3000) { destroyJob.join() } ?: XLog.w(getLog("destroyService", "Timeout"))

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
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(WebRtcEvent.Priority.STOP_IGNORE)
            handler.removeMessages(WebRtcEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(WebRtcEvent.Priority.DESTROY_IGNORE)
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

            mediaProjectionIntent = null
            stopStream()

            if (cause is WebRtcError) currentError.set(cause) else currentError.set(WebRtcError.UnknownError(cause))
        } finally {
            XLog.d(this@WebRtcStreamingService.getLog("handleMessage", "Done [$event] New state: [${getStateString()}]"))
            if (event is InternalEvent.Destroy) event.destroyJob.complete()
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
                socketErrorRetryAttempts = 0
                signaling = null
                currentStreamId = StreamId.EMPTY
                currentStreamPassword = StreamPassword.EMPTY
                waitingForPermission = false
                mediaProjectionIntent = null
                projection = null
                wakeLock = null
                clients = HashMap()
                clientGenerations.clear()
                clientGenerationCounter = 0
                audioIssueToastShown = false

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
                        } else {
                            XLog.w(
                                getLog("GetNonce", "Error present. Ignoring. [$error]"),
                                RuntimeException("GetNonce: Error present. Ignoring. [$error]", error)
                            )
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
                            networkAvailable.value = false
                            networkRecovery.value = false
                            currentError.set(cause)
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
                                currentError.set(cause)
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

                signaling?.destroy()
                signaling = SocketSignaling(environment, okHttpClient, ssEventListener, passwordVerifier)
                    .apply { openSocket(event.token, versionName) }
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

                if (currentStreamId.isEmpty().not() && currentStreamId != event.streamId) { // We got new streamId while we have another one
                    //TODO maybe notify user and clients?
                    stopStream()
                    requireNotNull(signaling) { "signaling==null" }
                        .sendRemoveClients(clients.map { it.value.client.clientId }, "StreamCreated: New StreamID")
                    clients = HashMap()
                    clientGenerations.clear()
                    currentStreamPassword = StreamPassword.EMPTY
                }

                networkRecovery.value = false
                currentStreamId = event.streamId
                if (currentStreamPassword.isEmpty()) currentStreamPassword = StreamPassword.generateNew()
                projection = projection ?: WebRtcProjection(service)
                projection!!.setMicrophoneMute(webRtcSettings.data.value.enableMic.not())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    projection!!.setDeviceAudioMute(webRtcSettings.data.value.enableDeviceAudio.not())
                }
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
                val generation = ++clientGenerationCounter
                val epoch = streamEpoch
                clients[event.clientId]?.client?.stop()
                clients[event.clientId] = ClientSession(
                    generation = generation,
                    epoch = epoch,
                    client = WebRtcClient(
                        event.clientId,
                        generation,
                        event.iceServers,
                        prj.peerConnectionFactory, prj.videoCodecs, prj.audioCodecs,
                        webRtcClientEventListener
                    )
                )
                clientGenerations[event.clientId] = ClientTag(generation, epoch)

                if (isStreaming()) {
                    prj.forceKeyFrame()
                    clients[event.clientId]?.let { clientSession ->
                        clientSession.client.start(prj.localMediaSteam!!, clientSession.epoch)
                    }
                    requireNotNull(signaling) { "signaling==null" }.sendStreamStart(event.clientId)
                }
            }

            is WebRtcEvent.RemoveClient -> {
                if (destroyPending) {
                    XLog.i(getLog("RemoveClient", "DestroyPending. Ignoring"), IllegalStateException("RemoveClient: DestroyPending"))
                    return
                }

                val removedClient = clients.remove(event.clientId)
                removedClient?.client?.stop()
                if (removedClient != null) {
                    clientGenerations.remove(event.clientId, ClientTag(removedClient.generation, removedClient.epoch))
                } else {
                    clientGenerations.remove(event.clientId)
                }
                if (event.notifyServer)
                    requireNotNull(signaling) { "signaling==null" }
                        .sendRemoveClients(listOf(event.clientId), "RemoveClient:${event.reason}")
            }

            is InternalEvent.SocketSignalingError -> {
                if (destroyPending) {
                    XLog.i(getLog("SocketSignalingError", "DestroyPending. Ignoring"))
                    return
                }

                if (event.error is SocketSignaling.Error.StreamStartError && socketErrorRetryAttempts < 2) {
                    socketErrorRetryAttempts++
                    sendEvent(WebRtcEvent.Intentable.StopStream("SocketSignalingError"))
                    return
                }

                if (event.error.retry && socketErrorRetryAttempts < 2) {
                    socketErrorRetryAttempts++
                    sendEvent(WebRtcEvent.Intentable.RecoverError, 2000L * socketErrorRetryAttempts)
                    return
                }

                currentError.set(WebRtcError.SocketError(event.error.message ?: "Unknown error", event.error.cause))
            }

            is WebRtcEvent.CastPermissionsDenied -> waitingForPermission = false

            is WebRtcEvent.StartProjection -> {
                if (destroyPending) {
                    XLog.i(getLog("StartProjection", "DestroyPending. Ignoring"), IllegalStateException("StartProjection: DestroyPending"))
                    return
                }

                waitingForPermission = false
                if (isStreaming()) {
                    XLog.d(getLog("StartProjection", "Already streaming. Ignoring."))
                    return
                }

                val settings = webRtcSettings.data.value
                val audioPermissionGranted =
                    ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                val wantsAudio = settings.enableMic || settings.enableDeviceAudio
                if (!audioPermissionGranted && wantsAudio) {
                    coroutineScope.launch {
                        webRtcSettings.updateData { copy(enableMic = false, enableDeviceAudio = false) }
                    }
                }
                val prj = requireNotNull(projection)
                val sessionEpoch = bumpStreamEpoch("StartProjection")
                val wantsMicrophoneForSession = audioPermissionGranted && settings.enableMic
                val wantsDeviceAudioForSession = audioPermissionGranted && settings.enableDeviceAudio
                val startResult = projectionCoordinator.start(event.intent, wantsMicrophoneForSession) { _, mediaProjection, microphoneEnabled ->
                    val projectionStarted = prj.start(currentStreamId, mediaProjection) { cause ->
                        sendEvent(InternalEvent.CaptureFatal(cause))
                    }
                    if (!projectionStarted) {
                        XLog.w(this@WebRtcStreamingService.getLog("StartProjection", "projection.start returned false"), IllegalStateException("projection.start returned false"))
                        return@start false
                    }

                    val microphoneEnabledForSession = wantsMicrophoneForSession && microphoneEnabled
                    val deviceAudioEnabledForSession = wantsDeviceAudioForSession
                    prj.setMicrophoneMute(!microphoneEnabledForSession)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        prj.setDeviceAudioMute(!deviceAudioEnabledForSession)
                    }

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        mediaProjectionIntent = event.intent
                        service.registerComponentCallbacks(componentCallback)
                    }

                    synchronizeClientEpoch(sessionEpoch)
                    requireNotNull(signaling).sendStreamStart()
                    clients.forEach { (_, session) ->
                        session.client.start(prj.localMediaSteam!!, session.epoch)
                    }

                    @Suppress("DEPRECATION")
                    @SuppressLint("WakelockTimeout")
                    if (Build.MANUFACTURER !in listOf("OnePlus", "OPPO") && webRtcSettings.data.value.keepAwake) {
                        val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                        wakeLock = powerManager.newWakeLock(flags, "ScreenStream::WebRTC-Tag").apply { acquire() }
                    }
                    true
                }
                when (startResult) {
                    is ProjectionCoordinator.StartResult.Started -> {
                        if (wantsMicrophoneForSession && !startResult.microphoneEnabled) {
                            XLog.w(getLog("StartProjection", "Microphone FGS upgrade failed. Streaming without microphone."))
                            showAudioCaptureIssueToastOnce()
                        }
                        currentError.set(null)
                    }

                    ProjectionCoordinator.StartResult.Busy -> {
                        XLog.w(getLog("StartProjection", "Coordinator is busy. Ignoring."))
                    }

                    is ProjectionCoordinator.StartResult.Blocked, is ProjectionCoordinator.StartResult.Fatal -> {
                        val cause = startResult.cause ?: error("Missing cause for failed start result")
                        mediaProjectionIntent = null
                        waitingForPermission = false
                        if (startResult is ProjectionCoordinator.StartResult.Fatal) {
                            XLog.e(getLog("StartProjection", "Fatal start error"), cause)
                        } else {
                            XLog.w(getLog("StartProjection", "Blocked by system"), cause)
                        }
                        stopStream()
                        currentError.set(cause as? WebRtcError ?: WebRtcError.UnknownError(cause))
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

            is WebRtcEvent.Intentable.StopStream -> stopStream()

            is WebRtcEvent.Intentable.RecoverError -> {
                if (destroyPending) {
                    XLog.i(getLog("RecoverError", "DestroyPending. Ignoring"))
                    return
                }

                stopStream()

                signaling?.destroy()
                signaling = null
                projection?.destroy()
                projection = null

                currentError.set(null)

                handler.removeMessages(WebRtcEvent.Priority.STOP_IGNORE)
                handler.removeMessages(WebRtcEvent.Priority.RECOVER_IGNORE)

                sendEvent(InternalEvent.InitState)
                sendEvent(InternalEvent.GetNonce(0, true))
            }

            is InternalEvent.StartStream -> {
                if (destroyPending) {
                    XLog.i(getLog("StartStream", "DestroyPending. Ignoring"), IllegalStateException("StartStream: DestroyPending"))
                    return
                }

                mediaProjectionIntent?.let {
                    check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "WebRtcEvent.StartStream: UPSIDE_DOWN_CAKE" }
                    sendEvent(WebRtcEvent.StartProjection(it))
                } ?: run { waitingForPermission = true }
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

                withActiveClient("SendHostOffer", event.clientId, event.generation, event.epoch) {
                    requireNotNull(signaling).sendHostOffer(event.clientId, event.offer)
                }
            }

            is InternalEvent.HostOfferConfirmed -> {
                if (destroyPending) {
                    XLog.i(getLog("HostOfferConfirmed", "DestroyPending. Ignoring"), IllegalStateException("HostOfferConfirmed: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("HostOfferConfirmed", "Not streaming. Ignoring."))
                    return
                }

                withActiveClient(
                    source = "HostOfferConfirmed",
                    clientId = event.clientId,
                    generation = event.generation,
                    epoch = event.epoch,
                    onMissing = { sendEvent(WebRtcEvent.RemoveClient(event.clientId, true, "HostOfferConfirmed")) }
                ) { currentClient ->
                    currentClient.client.onHostOfferConfirmed(currentClient.epoch)
                    currentClient.client.requestKeyFrame()
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

                withActiveClient(
                    source = "SetClientAnswer",
                    clientId = event.clientId,
                    generation = event.generation,
                    epoch = event.epoch,
                    onMissing = { sendEvent(WebRtcEvent.RemoveClient(event.clientId, true, "SetClientAnswer")) }
                ) { currentClient ->
                    currentClient.client.setClientAnswer(projection!!.localMediaSteam!!.id, event.answer, currentClient.epoch)
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

                withActiveClient("SendHostCandidates", event.clientId, event.generation, event.epoch) {
                    requireNotNull(signaling).sendHostCandidates(event.clientId, event.candidates)
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

                withActiveClient(
                    source = "SetClientCandidate",
                    clientId = event.clientId,
                    generation = event.generation,
                    epoch = event.epoch,
                    onMissing = { sendEvent(WebRtcEvent.RemoveClient(event.clientId, true, "SetClientCandidate")) }
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
                stopStream()

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

                requireNotNull(signaling).sendStreamRemove(currentStreamId)
                clients = HashMap()
                clientGenerations.clear()
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

                requireNotNull(signaling).sendRemoveClients(clients.map { it.value.client.clientId }, "CreateNewStreamPassword")
                clients = HashMap()
                clientGenerations.clear()
                currentStreamPassword = StreamPassword.generateNew()
            }

            is WebRtcEvent.UpdateState -> Unit // Expected

            else -> throw IllegalArgumentException("Unknown WebRtcEvent: ${event::class.java}")
        }
    }

    private fun isStreaming(): Boolean = projection?.isRunning ?: false

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream() {
        val stopEpoch = bumpStreamEpoch("stopStream")
        synchronizeClientEpoch(stopEpoch)
        audioIssueToastShown = false

        if (isStreaming()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { service.unregisterComponentCallbacks(componentCallback) }
            }
            requireNotNull(signaling).sendStreamStop()
            clients.forEach { it.value.client.stop() }
            requireNotNull(projection).stop()
        } else {
            XLog.d(getLog("stopStream", "Not streaming. Ignoring."))
        }
        projectionCoordinator.stop()

        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        service.stopForeground()
    }

    private fun showAudioCaptureIssueToastOnce() {
        if (audioIssueToastShown) return
        audioIssueToastShown = true
        mainHandler.post { Toast.makeText(service, R.string.webrtc_stream_audio_device_unavailable, Toast.LENGTH_LONG).show() }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun getStateString() =
        "Destroy: $destroyPending, Socket:${signaling?.socketId()}, StreamId:$currentStreamId, Streaming:${isStreaming()}, WFP:$waitingForPermission, networkRecovery:${networkRecovery.value} Clients:${clients.size}"

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun publishState() {
        val state = WebRtcState(
            (signaling?.socketId() == null && !networkRecovery.value) || currentStreamId.isEmpty() || waitingForPermission || currentError.get() != null || destroyPending,
            environment.signalingServerUrl,
            currentStreamId.value,
            currentStreamPassword.value,
            waitingForPermission,
            isStreaming(),
            networkRecovery.value,
            clients.map { it.value.client.toClient() },
            currentError.get()
        )

        mutableWebRtcStateFlow.value = state

        if (previousError != currentError.get()) {
            previousError = currentError.get()
            previousError?.let { service.showErrorNotification(it) } ?: service.hideErrorNotification()
        }
    }
}
