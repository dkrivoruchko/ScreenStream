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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.PowerManager
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.WebRtcKoinScope
import info.dvkr.screenstream.webrtc.WebRtcModuleService
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings
import info.dvkr.screenstream.webrtc.ui.WebRtcError
import info.dvkr.screenstream.webrtc.ui.WebRtcState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import org.webrtc.IceCandidate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

@Scope(WebRtcKoinScope::class)
@Scoped(binds = [WebRtcStreamingService::class])
internal class WebRtcStreamingService(
    @InjectedParam private val service: WebRtcModuleService,
    @InjectedParam private val mutableWebRtcStateFlow: MutableStateFlow<WebRtcState>,
    private val environment: WebRtcEnvironment,
    private val webRtcSettings: WebRtcSettings
) : HandlerThread("WebRTC-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback {

    private val versionName = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.packageManager.getPackageInfo("com.google.android.gms", PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            service.packageManager.getPackageInfo("com.google.android.gms", 0).versionName
        }
    }.getOrDefault("-")

    private val powerManager: PowerManager = service.application.getSystemService(PowerManager::class.java)
    private val connectivityManager: ConnectivityManager = service.application.getSystemService(ConnectivityManager::class.java)
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

    // All Volatile vars must be write on this (WebRTC-HT) thread
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var currentStreamId: StreamId = StreamId.EMPTY
    @Volatile private var currentStreamPassword: StreamPassword = StreamPassword.EMPTY
    // All Volatile vars must be write on this (WebRTC-HT) thread

    // All vars must be read/write on this (WebRTC-HT) thread
    private var deviceConfiguration: Configuration = Configuration(service.resources.configuration)
    private var socketErrorRetryAttempts: Int = 0
    private var signaling: SocketSignaling? = null
    private var waitingForPermission: Boolean = false
    private var mediaProjectionIntent: Intent? = null
    private var projection: WebRtcProjection? = null
    private var isAudioPermissionGrantedOnStart: Boolean = false
    private var clients: MutableMap<ClientId, WebRtcClient> = HashMap()
    private var previousError: WebRtcError? = null
    // All vars must be read/write on this (WebRTC-HT) thread

    internal sealed class InternalEvent(priority: Int) : WebRtcEvent(priority) {
        data object InitState : InternalEvent(Priority.RECOVER_IGNORE)
        data class GetNonce(val attempt: Int, val forceTokenUpdate: Boolean) : InternalEvent(Priority.RECOVER_IGNORE)
        data class GetToken(val nonce: String, val attempt: Int, val forceUpdate: Boolean) : InternalEvent(Priority.RECOVER_IGNORE)
        data class OpenSocket(val token: PlayIntegrityToken) : InternalEvent(Priority.RECOVER_IGNORE)
        data object StreamCreate : InternalEvent(Priority.RECOVER_IGNORE)
        data class StreamCreated(val streamId: StreamId) : InternalEvent(Priority.RECOVER_IGNORE)
        data class ClientJoin(val clientId: ClientId) : InternalEvent(Priority.RECOVER_IGNORE)
        data class SocketSignalingError(val error: SocketSignaling.Error) : InternalEvent(Priority.RECOVER_IGNORE)

        data object StartStream : InternalEvent(Priority.STOP_IGNORE)
        data class SendHostOffer(val clientId: ClientId, val offer: Offer) : InternalEvent(Priority.STOP_IGNORE)
        data class HostOfferConfirmed(val clientId: ClientId) : InternalEvent(Priority.STOP_IGNORE)
        data class SetClientAnswer(val clientId: ClientId, val answer: Answer) : InternalEvent(Priority.STOP_IGNORE)
        data class SendHostCandidates(val clientId: ClientId, val candidates: List<IceCandidate>) : InternalEvent(Priority.STOP_IGNORE) {
            override fun toString(): String = "SendHostCandidates(clientId=$clientId)"
        }
        data class SetClientCandidate(val clientId: ClientId, val candidate: IceCandidate) : InternalEvent(Priority.STOP_IGNORE) {
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

        override fun onClientJoin(clientId: ClientId) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientJoin", "$clientId"))
            sendEvent(InternalEvent.ClientJoin(clientId))
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
            sendEvent(InternalEvent.SetClientAnswer(clientId, answer))
        }

        override fun onClientCandidate(clientId: ClientId, candidate: IceCandidate) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onClientCandidate", "$clientId"))
            sendEvent(InternalEvent.SetClientCandidate(clientId, candidate))
        }

        override fun onHostOfferConfirmed(clientId: ClientId) {
            XLog.v(this@WebRtcStreamingService.getLog("SocketSignaling.onHostOfferConfirmed", "$clientId"))
            sendEvent(InternalEvent.HostOfferConfirmed(clientId))
        }

        override fun onError(cause: SocketSignaling.Error) {
            if (cause.log) XLog.e(this@WebRtcStreamingService.getLog("SocketSignaling.onError", cause.message), cause)
            else XLog.e(this@WebRtcStreamingService.getLog("SocketSignaling.onError", cause.message))
            sendEvent(InternalEvent.SocketSignalingError(cause))
        }
    }

    private val webRtcClientEventListener = object : WebRtcClient.EventListener {
        override fun onHostOffer(clientId: ClientId, offer: Offer) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onHostOffer", "Client: $clientId"))
            sendEvent(InternalEvent.SendHostOffer(clientId, offer))
        }

        override fun onHostCandidates(clientId: ClientId, candidates: List<IceCandidate>) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onHostCandidates", "Client: $clientId"))
            sendEvent(InternalEvent.SendHostCandidates(clientId, candidates))
        }

        override fun onClientAddress(clientId: ClientId) {
            XLog.v(this@WebRtcStreamingService.getLog("WebRTCClient.onClientAddress", "Client: $clientId"))
            sendEvent(WebRtcEvent.UpdateState)
        }

        override fun onError(clientId: ClientId, cause: Throwable) {
            if (cause.message?.startsWith("onPeerDisconnected") == true)
                XLog.e(this@WebRtcStreamingService.getLog("WebRTCClient.onError", "Client: $clientId: ${cause.message}"))
            else
                XLog.e(this@WebRtcStreamingService.getLog("WebRTCClient.onError", "Client: $clientId"), cause)
            sendEvent(WebRtcEvent.RemoveClient(clientId, true, "onError:${cause.message}"))
        }
    }

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            XLog.d(this@WebRtcStreamingService.getLog("onReceive", "Action: ${intent?.action}"))
            if (intent?.action == Intent.ACTION_SCREEN_OFF) sendEvent(InternalEvent.ScreenOff)
        }
    }

    private val networkAvailable = MutableStateFlow(true)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            XLog.d(this@WebRtcStreamingService.getLog("onAvailable"))
            networkAvailable.value = true
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            XLog.d(this@WebRtcStreamingService.getLog("onLost"))
        }
    }

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
            else sendEvent(WebRtcEvent.Intentable.StopStream("networkAvailableFlow: false"))
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

    override fun handleMessage(msg: Message): Boolean = runBlocking(Dispatchers.Unconfined) {
        XLog.v(this@WebRtcStreamingService.getLog("handleMessage", "Message: $msg"))

        val event: WebRtcEvent = msg.obj as WebRtcEvent
        try {
            XLog.d(this@WebRtcStreamingService.getLog("handleMessage", "Event [$event] Current state: [${getStateString()}]"))
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@WebRtcStreamingService.getLog("handleMessage.catch", cause.toString()))
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
                isAudioPermissionGrantedOnStart = false
                wakeLock = null
                clients = HashMap()

                currentError.set(null)
            }

            is InternalEvent.GetNonce -> {
                if (destroyPending) {
                    XLog.i(getLog("GetNonce", "DestroyPending. Ignoring"))
                    return
                }

                currentError.get()?.let { error ->
                    if (error !is WebRtcError.NetworkError) {
                        XLog.w(getLog("GetNonce", "Error present. Ignoring. [$error]"))
                        XLog.w(getLog("GetNonce", "Error present. Ignoring. [$error]"), RuntimeException("GetNonce: Error present. Ignoring. [$error]", error))
                        return
                    }
                }
                currentError.set(null)

                playIntegrity.getNonce {
                    // OkHttp thread
                    if (destroyPending) return@getNonce
                    onSuccess { nonce -> sendEvent(InternalEvent.GetToken(nonce, 0, event.forceTokenUpdate)) }
                    onFailure { cause ->
                        if (cause !is WebRtcError.NetworkError) {
                            currentError.set(WebRtcError.UnknownError(cause))
                            sendEvent(WebRtcEvent.UpdateState)
                        } else if (cause.isNonRetryable() || event.attempt >= 3) {
                            networkAvailable.value = false
                            currentError.set(cause)
                            sendEvent(WebRtcEvent.UpdateState)
                        } else {
                            val attempt = event.attempt + 1
                            val delay = (2000L * (1.5).pow(attempt - 1)).toLong()
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
                                sendEvent(WebRtcEvent.UpdateState)
                            }

                            cause.isAutoRetryable.not() -> {
                                XLog.i(this@WebRtcStreamingService.getLog("getToken", "Got error. Stopping: ${cause.message}"))
                                currentError.set(cause)
                                sendEvent(WebRtcEvent.UpdateState)
                            }

                            event.attempt >= 3 -> {
                                XLog.i(this@WebRtcStreamingService.getLog("getToken", "Got error. Max attempts. Stopping: ${cause.message}"))
                                networkAvailable.value = false
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
                        .sendRemoveClients(clients.map { it.value.clientId }, "StreamCreated: New StreamID")
                    clients = HashMap()
                    currentStreamPassword = StreamPassword.EMPTY
                }

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
                clients[event.clientId]?.stop()
                clients[event.clientId] =
                    WebRtcClient(event.clientId, prj.peerConnectionFactory, prj.videoCodecs, prj.audioCodecs, webRtcClientEventListener)

                if (isStreaming()) {
                    clients[event.clientId]?.start(prj.localMediaSteam!!)
                    requireNotNull(signaling) { "signaling==null" }.sendStreamStart(event.clientId)
                }
            }

            is WebRtcEvent.RemoveClient -> {
                if (destroyPending) {
                    XLog.i(getLog("RemoveClient", "DestroyPending. Ignoring"), IllegalStateException("RemoveClient: DestroyPending"))
                    return
                }

                clients[event.clientId]?.stop()
                clients.remove(event.clientId)
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

                currentError.set(WebRtcError.SocketError(event.error.message!!, event.error.cause))
            }

            is WebRtcEvent.CastPermissionsDenied -> waitingForPermission = false

            is WebRtcEvent.StartProjection -> {
                if (destroyPending) {
                    XLog.i(getLog("StartProjection", "DestroyPending. Ignoring"), IllegalStateException("StartProjection: DestroyPending"))
                    return
                }

                waitingForPermission = false
                check(isStreaming().not()) { "WebRtcEvent.StartProjection: Already streaming" }

                service.startForeground()

                isAudioPermissionGrantedOnStart =
                    ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

                val prj = requireNotNull(projection)
                prj.start(currentStreamId, event.intent) {
                    XLog.i(this@WebRtcStreamingService.getLog("StartProjection", "MediaProjectionCallback.onStop"))
                    sendEvent(WebRtcEvent.Intentable.StopStream("MediaProjectionCallback.onStop"))
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    mediaProjectionIntent = event.intent
                    service.registerComponentCallbacks(componentCallback)
                }

                requireNotNull(signaling).sendStreamStart()
                clients.forEach { it.value.start(prj.localMediaSteam!!) }

                @Suppress("DEPRECATION")
                @SuppressLint("WakelockTimeout")
                if (Build.MANUFACTURER !in listOf("OnePlus", "OPPO") && webRtcSettings.data.value.keepAwake) {
                    val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    wakeLock = powerManager.newWakeLock(flags, "ScreenStream::WebRTC-Tag").apply { acquire() }
                }
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

                requireNotNull(signaling).sendHostOffer(event.clientId, event.offer)
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

                clients[event.clientId]?.onHostOfferConfirmed() ?: run {
                    XLog.i(getLog("HostOfferConfirmed", "Client ${event.clientId} not found"))
                    sendEvent(WebRtcEvent.RemoveClient(event.clientId, true, "HostOfferConfirmed"))
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

                clients[event.clientId]?.setClientAnswer(projection!!.localMediaSteam!!.id, event.answer) ?: run {
                    XLog.i(getLog("SetClientAnswer", "Client ${event.clientId} not found"))
                    sendEvent(WebRtcEvent.RemoveClient(event.clientId, true, "SetClientAnswer"))
                }
            }

            is InternalEvent.SendHostCandidates -> {
                if (destroyPending) {
                    XLog.i(getLog("SendHostCandidates", "DestroyPending. Ignoring"), IllegalStateException("SendHostCandidates: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("SendHostCandidates", "Not streaming. Ignoring."))
                    return
                }

                requireNotNull(signaling).sendHostCandidates(event.clientId, event.candidates)
            }

            is InternalEvent.SetClientCandidate -> {
                if (destroyPending) {
                    XLog.i(getLog("SetClientCandidate", "DestroyPending. Ignoring"), IllegalStateException("SetClientCandidate: DestroyPending"))
                    return
                }

                if (isStreaming().not()) {
                    XLog.d(getLog("SetClientCandidate", "Not streaming. Ignoring."))
                    return
                }

                clients[event.clientId]?.setClientCandidate(projection!!.localMediaSteam!!.id, event.candidate) ?: run {
                    XLog.i(getLog("SetClientCandidates", "Client ${event.clientId} not found"))
                    sendEvent(WebRtcEvent.RemoveClient(event.clientId, true, "SetClientCandidate"))
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

                projection?.setMicrophoneMute(event.enableMic.not())

                if (isStreaming() && isAudioPermissionGrantedOnStart.not() && event.enableMic) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        XLog.i(this@WebRtcStreamingService.getLog("EnableMic", "StopStream & StartStream"))
                        sendEvent(WebRtcEvent.Intentable.StopStream("EnableMic"))
                        sendEvent(InternalEvent.StartStream, 500)
                    } else {
                        // Ignore. Button disabled on UI
                    }
                }
            }

            is InternalEvent.EnableDeviceAudio -> {
                if (destroyPending) {
                    XLog.i(getLog("EnableDeviceAudio", "DestroyPending. Ignoring"))
                    return
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    projection?.setDeviceAudioMute(event.enableDeviceAudio.not())

                    if (isStreaming() && isAudioPermissionGrantedOnStart.not() && event.enableDeviceAudio) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            XLog.i(this@WebRtcStreamingService.getLog("EnableDeviceAudio", "StopStream & StartStream"))
                            sendEvent(WebRtcEvent.Intentable.StopStream("EnableDeviceAudio"))
                            sendEvent(InternalEvent.StartStream, 500)
                        } else {
                            // Ignore. Button disabled on UI
                        }
                    }
                }
            }

            is InternalEvent.ConfigurationChange -> {
                if (destroyPending) {
                    XLog.i(getLog("ConfigurationChange", "DestroyPending. Ignoring"), IllegalStateException("ConfigurationChange: DestroyPending"))
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
                    projection!!.changeCaptureFormat()
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
                currentStreamId = StreamId.EMPTY
                currentStreamPassword = StreamPassword.EMPTY
                webRtcSettings.updateData { copy(lastStreamId = StreamId.EMPTY.value) }
            }

            is WebRtcEvent.CreateNewPassword -> {
                if (destroyPending) {
                    XLog.i(getLog("CreateNewPassword", "DestroyPending. Ignoring"), IllegalStateException("CreateNewPassword: DestroyPending"))
                    return
                }

                if (isStreaming()) {
                    XLog.i(getLog("CreateNewPassword", "Streaming. Ignoring."))
                    return
                }

                requireNotNull(signaling).sendRemoveClients(clients.map { it.value.clientId }, "CreateNewStreamPassword")
                clients = HashMap()
                currentStreamPassword = StreamPassword.generateNew()
            }

            is WebRtcEvent.UpdateState -> Unit // Expected

            else -> throw IllegalArgumentException("Unknown WebRtcEvent: ${event::class.java}")
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun isStreaming(): Boolean = projection?.isRunning ?: false

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream() {
        if (isStreaming()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.unregisterComponentCallbacks(componentCallback)
            }
            requireNotNull(signaling).sendStreamStop()
            clients.forEach { it.value.stop() }
            requireNotNull(projection).stop()
        } else {
            XLog.d(getLog("stopStream", "Not streaming. Ignoring."))
        }

        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        service.stopForeground()
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun getStateString() =
        "Destroy: $destroyPending, Socket:${signaling?.socketId()}, StreamId:$currentStreamId, Streaming:${isStreaming()}, WFP:$waitingForPermission, Clients:${clients.size}"

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun publishState() {
        val state = WebRtcState(
            signaling?.socketId() == null || currentStreamId.isEmpty() || waitingForPermission || currentError.get() != null || destroyPending,
            environment.signalingServerUrl,
            currentStreamId.value,
            currentStreamPassword.value,
            waitingForPermission,
            isStreaming(),
            clients.map { it.value.toClient() },
            currentError.get()
        )

        mutableWebRtcStateFlow.value = state

        if (previousError != currentError.get()) {
            previousError = currentError.get()
            previousError?.let { service.showErrorNotification(it) } ?: service.hideErrorNotification()
        }
    }
}