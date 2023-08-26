package info.dvkr.screenstream.webrtc

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.AppStateMachine
import info.dvkr.screenstream.common.ForegroundService
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.listenForChange
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.webrtc.internal.Answer
import info.dvkr.screenstream.webrtc.internal.ClientId
import info.dvkr.screenstream.webrtc.internal.Offer
import info.dvkr.screenstream.webrtc.internal.PlayIntegrity
import info.dvkr.screenstream.webrtc.internal.PlayIntegrityToken
import info.dvkr.screenstream.webrtc.internal.SocketSignaling
import info.dvkr.screenstream.webrtc.internal.StreamId
import info.dvkr.screenstream.webrtc.internal.StreamPassword
import info.dvkr.screenstream.webrtc.internal.WebRtcClient
import info.dvkr.screenstream.webrtc.internal.WebRtcProjection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import org.webrtc.IceCandidate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.pow

public class WebRtcHandlerThread(
    private val service: ForegroundService,
    private val appSettings: AppSettings,
    private val environment: WebRtcEnvironment,
    private val webRtcSettings: WebRtcSettings,
    private val effectSharedFlow: MutableSharedFlow<AppStateMachine.Effect>
) : AppStateMachine, HandlerThread("WebRTC-HT", android.os.Process.THREAD_PRIORITY_DISPLAY) {

    override val mode: Int = AppSettings.Values.STREAM_MODE_WEBRTC

    private val versionName = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.packageManager.getPackageInfo("com.google.android.gms", PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            service.packageManager.getPackageInfo("com.google.android.gms", 0).versionName
        }
    }.getOrDefault("-")

    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("WebRTC-HTDispatcher") }
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(SupervisorJob() + coroutineDispatcher) }

    private val okHttpClient = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val playIntegrity = PlayIntegrity(service, environment, okHttpClient)

    private val connectivityManager: ConnectivityManager = service.getSystemService(ConnectivityManager::class.java)
    private val powerManager: PowerManager = service.getSystemService(PowerManager::class.java)

    private val pendingEventDeque = LinkedBlockingDeque<AppStateMachine.Event>()

    // All vars must be read/write on this (WebRTC-HT) thread
    private var deviceConfiguration = Configuration(service.resources.configuration)
    private var lastPublicStreamState: WebRtcPublicState? = null
    private var socketErrorRetryAttempts = 0
    private var signaling: SocketSignaling? = null
    private var streamId: StreamId = StreamId.EMPTY
    private var streamPassword: StreamPassword = StreamPassword.EMPTY
    private var waitingForPermission: Boolean = false
    private var waitingForForegroundService: Boolean = false
    private var mediaProjectionIntent: Intent? = null
    private var projection: WebRtcProjection? = null
    private var isAudioPermissionGrantedOnStart: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var clients: MutableMap<ClientId, WebRtcClient> = HashMap()
    private var appError: AppError? = null

    private fun isStreaming(): Boolean = projection?.isRunning ?: false
    private fun isNotStreaming(): Boolean = isStreaming().not()
    private fun isBusy(): Boolean =
        signaling?.socketId() == null || streamId.isEmpty() || waitingForPermission || waitingForForegroundService || appError != null

    private fun createAndSendPublicState(force: Boolean) {
        val publicState = WebRtcPublicState(isStreaming(), isBusy(), waitingForPermission, streamId.value, streamPassword.value, appError)
        if (force || lastPublicStreamState != publicState) effectSharedFlow.tryEmit(publicState) //todo block UI if not ready
        lastPublicStreamState = publicState
    }

    private sealed class InternalEvent(val neverIgnore: Boolean = false) : AppStateMachine.Event() {
        data class GetNonce(internal val attempt: Int, internal val forceUpdate: Boolean) : InternalEvent(true)
        data class GetToken(internal val nonce: String, internal val attempt: Int, internal val forceUpdate: Boolean) : InternalEvent(true)
        data class OpenSocket(internal val token: PlayIntegrityToken) : InternalEvent(true)
        data object StreamCreate : InternalEvent(true)
        data class StreamCreated(internal val streamId: StreamId) : InternalEvent(true)
        data class WaitForForegroundService(internal val counter: Int, internal val intent: Intent) : InternalEvent()
        data class StartProjection(internal val intent: Intent) : InternalEvent()
        data class ClientJoin(internal val clientId: ClientId) : InternalEvent()
        data class ClientLeave(internal val clientId: ClientId) : InternalEvent()
        data class SendHostOffer(internal val clientId: ClientId, internal val offer: Offer) : InternalEvent()
        data class SetClientAnswer(internal val clientId: ClientId, internal val answer: Answer) : InternalEvent()
        data class SendHostCandidate(internal val clientId: ClientId, internal val candidate: IceCandidate) : InternalEvent() {
            override fun toString(): String = "SendHostCandidate(clientId=$clientId)"
        }

        data class SetClientCandidate(internal val clientId: ClientId, internal val candidate: IceCandidate) : InternalEvent() {
            override fun toString(): String = "SetClientCandidate(clientId=$clientId)"
        }

        data class RemoveClient(internal val clientId: ClientId, internal val notifyServer: Boolean, internal val reason: String) : InternalEvent()
        data object Restart : InternalEvent(true)
        data class Destroy(internal val latch: CountDownLatch) : InternalEvent(true)
        data class SocketSignalingError(internal val error: SocketSignaling.Errors) : InternalEvent(true)
        data object PublishClients : InternalEvent(true)
        data object ScreenOff : InternalEvent(true)
        data class ConfigurationChange(internal val newConfig: Configuration) : InternalEvent(true)
    }

    private val streamPasswordValidator = object : SocketSignaling.StreamPasswordValidator {
        @Synchronized
        override fun isPasswordValid(clientId: ClientId, passwordHash: String): Boolean {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.verifyPassword"))
            return streamPassword.isValid(clientId, streamId, passwordHash)
        }
    }

    private val ssEventListener = object : SocketSignaling.EventListener {
        override fun onSocketConnected() {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.onSocketConnected"))
            sendEvent(InternalEvent.StreamCreate)
        }

        override fun onTokenExpired() {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.onTokenExpired"))
            sendEvent(InternalEvent.GetNonce(0, true))
        }

        override fun onSocketDisconnected(reason: String) {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.onSocketDisconnected", reason))
            sendEvent(InternalEvent.GetNonce(0, false))
        }

        override fun onStreamCreated(streamId: StreamId) {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.onStreamCreated", "$streamId"))
            sendEvent(InternalEvent.StreamCreated(streamId))
        }

        override fun onStreamRemoved() {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.onStreamRemoved"))
            sendEvent(InternalEvent.StreamCreate)
        }

        override fun onClientJoin(clientId: ClientId) {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.onClientJoin", "$clientId"))
            sendEvent(InternalEvent.ClientJoin(clientId))
            sendEvent(InternalEvent.PublishClients)
        }

        override fun onClientLeave(clientId: ClientId) {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.onClientLeave", "$clientId"))
            sendEvent(InternalEvent.ClientLeave(clientId))
            sendEvent(InternalEvent.PublishClients)
        }

        override fun onClientNotFound(clientId: ClientId, reason: String) {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.onClientNotFound", "$clientId"))
            sendEvent(InternalEvent.RemoveClient(clientId, false, reason))
            sendEvent(InternalEvent.PublishClients)
        }

        override fun onClientAnswer(clientId: ClientId, answer: Answer) {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.onClientAnswer", "$clientId"))
            sendEvent(InternalEvent.SetClientAnswer(clientId, answer))
        }

        override fun onClientCandidate(clientId: ClientId, candidate: IceCandidate) {
            XLog.d(this@WebRtcHandlerThread.getLog("SocketSignaling.onClientCandidate", "$clientId"))
            sendEvent(InternalEvent.SetClientCandidate(clientId, candidate))
        }

        override fun onError(cause: SocketSignaling.Errors) {
            XLog.e(this@WebRtcHandlerThread.getLog("SocketSignaling.onError", cause.message), cause)
            sendEvent(InternalEvent.SocketSignalingError(cause))
        }
    }

    private val webRtcClientEventListener = object : WebRtcClient.EventListener {
        override fun onHostOffer(clientId: ClientId, offer: Offer) {
            XLog.d(this@WebRtcHandlerThread.getLog("WebRTCClient.onHostOffer", "Client: $clientId"))
            sendEvent(InternalEvent.SendHostOffer(clientId, offer))
        }

        override fun onHostCandidate(clientId: ClientId, candidate: IceCandidate) {
            XLog.d(this@WebRtcHandlerThread.getLog("WebRTCClient.onHostCandidate", "Client: $clientId"))
            sendEvent(InternalEvent.SendHostCandidate(clientId, candidate))
        }

        override fun onClientAddress(clientId: ClientId) {
            XLog.d(this@WebRtcHandlerThread.getLog("WebRTCClient.onClientAddress", "Client: $clientId"))
            sendEvent(InternalEvent.PublishClients)
        }

        override fun onClientDisconnected(clientId: ClientId) {
            XLog.d(this@WebRtcHandlerThread.getLog("WebRTCClient.onClientDisconnected", "Client: $clientId"))
            sendEvent(InternalEvent.RemoveClient(clientId, true, "onClientDisconnected"))
            sendEvent(InternalEvent.PublishClients)
        }

        override fun onError(clientId: ClientId, cause: Throwable) {
            XLog.e(this@WebRtcHandlerThread.getLog("WebRTCClient.onError", "Client: $clientId"), cause)
            sendEvent(InternalEvent.RemoveClient(clientId, true, "onError:${cause.message}"))
            sendEvent(InternalEvent.PublishClients)
        }
    }

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            XLog.d(this@WebRtcHandlerThread.getLog("onReceive", "Action: ${intent?.action}"))
            if (intent?.action == Intent.ACTION_SCREEN_OFF) sendEvent(InternalEvent.ScreenOff)
        }
    }

    private val networkAvailable = MutableStateFlow(true)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            XLog.d(this@WebRtcHandlerThread.getLog("onAvailable"))
            networkAvailable.value = true
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            XLog.d(this@WebRtcHandlerThread.getLog("onLost"))
            networkAvailable.value = false
        }
    }

    private val componentCallback = object : ComponentCallbacks {
        @MainThread
        override fun onConfigurationChanged(newConfig: Configuration) {
            sendEvent(InternalEvent.ConfigurationChange(newConfig))
        }

        override fun onLowMemory() = Unit
    }

    init {
        XLog.d(getLog("init"))
    }

    override fun start() {
        super.start()
        XLog.d(getLog("start"))

        val intentFilter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            service.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        else
            service.registerReceiver(broadcastReceiver, intentFilter)

        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            networkCallback
        )

        networkAvailable.onEach {
            XLog.d(getLog("init", "networkAvailable: $it"))
            if (it) sendEvent(InternalEvent.GetNonce(0, false))
            else sendEvent(AppStateMachine.Event.StopStream)
        }.launchIn(coroutineScope)

        webRtcSettings.enableMicFlow
            .onStart {
                val micPermission = ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO)
                if (micPermission == PackageManager.PERMISSION_DENIED) webRtcSettings.setEnableMic(false)
            }
            .listenForChange(coroutineScope, 0) { enableMic ->
                XLog.d(this@WebRtcHandlerThread.getLog("enableMicFlow", "$enableMic"))
                if (isStreaming() && isAudioPermissionGrantedOnStart.not() && enableMic) {
                    XLog.i(this@WebRtcHandlerThread.getLog("enableMicFlow", "StopStream & StartStream"))
                    sendEvent(AppStateMachine.Event.StopStream)
                    sendEvent(AppStateMachine.Event.StartStream, 500)
                }
            }
    }

    @Volatile
    private var pendingDestroy: Boolean = false
    private var pendingSocket: Boolean = true
    private var pendingStreamId: Boolean = true

    private val pendingSocketEvents = listOf(
        InternalEvent.GetNonce::class,
        InternalEvent.GetToken::class,
        InternalEvent.OpenSocket::class,
        InternalEvent.Restart::class,
        AppStateMachine.Event.RecoverError::class
    )

    private val pendingStreamIdEvents = pendingSocketEvents +
            listOf(InternalEvent.StreamCreate::class, AppStateMachine.Event.GetNewStreamId::class)

    private fun allowEvent(event: AppStateMachine.Event): Boolean {
        if (pendingDestroy) {
            XLog.w(getLog("allowEvent", "Pending destroy: Ignoring event => $event"))
            return false
        }
        if (event is InternalEvent.Destroy) pendingDestroy = true

        if (event is AppStateMachine.Event.StopStream || event is AppStateMachine.Event.RequestPublicState || event is AppStateMachine.Event.RecoverError) return true

        if (appError != null && appError !is WebRtcError.NetworkError) {
            XLog.w(getLog("allowEvent", "App error present: Ignoring event => $event"))
            return false
        }

        if (pendingSocket) {
            if (event is InternalEvent.StreamCreate) pendingSocket = false
            if (event is InternalEvent && event.neverIgnore) return true

            XLog.w(getLog("allowEvent", "Pending socket: Ignoring event => $event"))
            return false
        }
        if (event::class in pendingSocketEvents) pendingSocket = true

        if (pendingStreamId) {
            if (event is InternalEvent.StreamCreated) pendingStreamId = false
            if (event is InternalEvent && event.neverIgnore) return true

            XLog.w(getLog("allowEvent", "Pending StreamId: Ignoring event => $event"))
            return false
        }
        if (event::class in pendingStreamIdEvents) pendingStreamId = true

        return true
    }

    @Synchronized
    override fun sendEvent(event: AppStateMachine.Event, timeout: Long) {
        if (timeout > 0) XLog.d(getLog("sendEvent", "New event [Timeout: $timeout] => $event"))
        else XLog.d(getLog("sendEvent", "New event => $event"))

        if (allowEvent(event).not()) return

        pendingEventDeque.add(event)
        handler.postDelayed({ processEvent(event) }, timeout)

        XLog.d(getLog("sendEvent", "Pending events: $pendingEventDeque"))
    }

    override fun pauseRequest(): Boolean = if (isStreaming()) {
        XLog.d(getLog("pauseRequest", "isStreaming = true. Ignoring"))
        false
    } else {
        XLog.d(getLog("pauseRequest", "isStreaming = false. Destroying"))
        destroy()
        true
    }

    override fun destroy() {
        XLog.d(getLog("destroy"))

        service.unregisterReceiver(broadcastReceiver)
        connectivityManager.unregisterNetworkCallback(networkCallback)
        coroutineScope.cancel()

        val latch = CountDownLatch(1)
        sendEvent(InternalEvent.Destroy(latch))

        runCatching {
            val releasedBeforeTimeout = latch.await(1500, TimeUnit.MILLISECONDS)
            if (releasedBeforeTimeout.not()) XLog.w(getLog("destroy", "Timeout"))
        }

        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()

        quit() // Only after everything else is destroyed

        signaling = null
        mediaProjectionIntent = null
        projection = null
        clients = HashMap()
        appError = null
        lastPublicStreamState = null
        pendingEventDeque.clear()
        service.hideForegroundNotification()
    }

    private fun processEvent(event: AppStateMachine.Event) = runCatching {
        XLog.d(getLog("processEvent", "Event [$event] Current state: [${getStateString()}]"))

        when (event) {
            is InternalEvent.GetNonce -> {
                appError = null
                playIntegrity.getNonce {
                    // OkHttp thread
                    if (pendingDestroy) return@getNonce
                    onSuccess { nonce -> sendEvent(InternalEvent.GetToken(nonce, 0, event.forceUpdate)) }
                    onFailure { cause ->
                        if (cause is WebRtcError.NetworkError && cause.isNonRetryable()) {
                            networkAvailable.value = false
                            appError = cause
                            sendEvent(AppStateMachine.Event.RequestPublicState)
                        } else if (event.attempt >= 3) {
                            networkAvailable.value = false
                            if (cause is WebRtcError) appError = cause else throw cause
                            sendEvent(AppStateMachine.Event.RequestPublicState)
                        } else {
                            val attempt = event.attempt + 1
                            val delay = (2000L * (1.5).pow(attempt - 1)).toLong()
                            sendEvent(InternalEvent.GetNonce(attempt, event.forceUpdate), delay)
                        }
                    }
                }
            }

            is InternalEvent.GetToken -> {
                appError = null
                playIntegrity.getToken(event.nonce, event.forceUpdate) {
                    // MainThread
                    if (pendingDestroy) return@getToken
                    onSuccess { token -> sendEvent(InternalEvent.OpenSocket(token)) }
                    onFailure { cause ->
                        if (cause is WebRtcError.PlayIntegrityError && cause.isAutoRetryable.not()) {
                            appError = cause
                            sendEvent(AppStateMachine.Event.RequestPublicState)
                        } else if (event.attempt >= 3) {
                            networkAvailable.value = false
                            if (cause is WebRtcError) appError = cause else throw cause
                            sendEvent(AppStateMachine.Event.RequestPublicState)
                        } else {
                            val attempt = event.attempt + 1
                            val delay = (5000L * (2.00).pow(attempt - 1)).toLong()
                            sendEvent(InternalEvent.GetToken(event.nonce, attempt, event.forceUpdate), delay)
                        }
                    }
                }
            }

            is InternalEvent.OpenSocket -> {
                signaling?.destroy()
                signaling = SocketSignaling(environment, okHttpClient, ssEventListener, streamPasswordValidator)
                    .apply { openSocket(event.token, versionName) }
            }

            is InternalEvent.StreamCreate -> {
                if (streamId.isEmpty()) streamPassword = StreamPassword.generateNew()
                coroutineScope.launch { signaling!!.sendStreamCreate(StreamId(webRtcSettings.lastStreamIdFlow.first())) }
            }

            is InternalEvent.StreamCreated -> {
                coroutineScope.launch { webRtcSettings.setLastStreamId(event.streamId.value) }

                if (streamId.isEmpty().not() && event.streamId != streamId) {// We got new streamId while we have another one
                    if (isStreaming()) {
                        signaling!!.sendStreamStop()
                        clients.values.forEach { it.stop() }
                        service.unregisterComponentCallbacks(componentCallback)
                        projection!!.stop()
                        service.hideForegroundNotification()
                    }

                    signaling!!.sendRemoveClients(clients.values.map { it.clientId }, "StreamCreated")
                    clients = HashMap()
                    streamPassword = StreamPassword.generateNew()
                }

                streamId = event.streamId
                projection = projection ?: WebRtcProjection(service, webRtcSettings, coroutineDispatcher)
            }

            is InternalEvent.ClientJoin -> {
                val existingClient = clients[event.clientId]
                val prj = requireNotNull(projection)

                if (existingClient != null) {
                    existingClient.stopIfMismatch(prj.localMediaSteam)
                    if (isStreaming() && existingClient.start(prj.localMediaSteam!!)) signaling!!.sendStreamStart(event.clientId)
                } else {
                    val client =
                        WebRtcClient(event.clientId, prj.peerConnectionFactory, prj.videoCodecs, prj.audioCodecs, webRtcClientEventListener)
                    clients[client.clientId] = client

                    if (isStreaming()) {
                        client.start(prj.localMediaSteam!!)
                        signaling!!.sendStreamStart(event.clientId)
                    }
                }
            }

            is InternalEvent.ClientLeave -> {
                clients[event.clientId]?.stop()
                clients.remove(event.clientId)
            }

            is AppStateMachine.Event.StartStream ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && mediaProjectionIntent != null)
                    sendEvent(AppStateMachine.Event.StartProjection(mediaProjectionIntent!!))
                else
                    waitingForPermission = true

            is AppStateMachine.Event.CastPermissionsDenied -> waitingForPermission = false

            is AppStateMachine.Event.StartProjection -> if (isStreaming()) {
                waitingForPermission = false
                XLog.w(getLog("StartProjection", "Already streaming. Ignoring."))
            } else {
                waitingForPermission = false

                service.showForegroundNotification()

                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                        sendEvent(InternalEvent.StartProjection(event.intent))

                    service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0 ->
                        sendEvent(InternalEvent.StartProjection(event.intent))

                    else -> {
                        waitingForForegroundService = true
                        sendEvent(InternalEvent.WaitForForegroundService(10, event.intent))
                    }
                }
            }

            is InternalEvent.WaitForForegroundService -> @SuppressLint("NewApi") {
                if (service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0) {
                    waitingForForegroundService = false
                    sendEvent(InternalEvent.StartProjection(event.intent))
                } else if (event.counter > 0)
                    sendEvent(InternalEvent.WaitForForegroundService(event.counter - 1, event.intent), 500)
                else {
                    service.hideForegroundNotification()
                    waitingForForegroundService = false
                    throw IllegalStateException("Service is not FOREGROUND. Give up.")
                }
            }

            is InternalEvent.StartProjection -> {
                isAudioPermissionGrantedOnStart =
                    ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                projection!!.start(streamId, event.intent) {
                    XLog.i(this@WebRtcHandlerThread.getLog("StartProjection", "MediaProjectionCallback.onStop"))
                    sendEvent(AppStateMachine.Event.StopStream)
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) mediaProjectionIntent = event.intent
                service.registerComponentCallbacks(componentCallback)
                takeWakeLock()
                signaling!!.sendStreamStart()
                clients.values.forEach { it.start(projection!!.localMediaSteam!!) }
            }

            is InternalEvent.SendHostOffer ->
                if (isStreaming()) signaling!!.sendHostOffer(event.clientId, event.offer)
                else XLog.w(getLog("SendHostOffer", "Not streaming. Ignoring."))

            is InternalEvent.SetClientAnswer ->
                if (isStreaming()) {
                    clients[event.clientId]?.setClientAnswer(projection!!.localMediaSteam!!.id, event.answer) ?: run {
                        XLog.w(getLog("SetClientAnswer", "Client ${event.clientId} not found"))
                        sendEvent(InternalEvent.RemoveClient(event.clientId, true, "SetClientAnswer"))
                    }
                } else {
                    XLog.w(getLog("SetClientAnswer", "Not streaming. Ignoring."))
                }

            is InternalEvent.SendHostCandidate ->
                if (isStreaming()) {
                    signaling!!.sendHostCandidates(event.clientId, event.candidate)
                } else {
                    XLog.w(getLog("SendHostCandidate", "Not streaming. Ignoring."))
                }

            is InternalEvent.SetClientCandidate ->
                if (isStreaming()) {
                    clients[event.clientId]?.setClientCandidate(projection!!.localMediaSteam!!.id, event.candidate) ?: run {
                        XLog.w(getLog("SetClientCandidates", "Client ${event.clientId} not found"))
                        sendEvent(InternalEvent.RemoveClient(event.clientId, true, "SetClientCandidate"))
                    }
                } else {
                    XLog.w(getLog("SetClientCandidates", "Not streaming. Ignoring."))
                }

            is InternalEvent.RemoveClient -> {
                clients[event.clientId]?.stop()
                clients.remove(event.clientId)
                if (event.notifyServer) signaling?.sendRemoveClients(listOf(event.clientId), "RemoveClient:${event.reason}")
            }

            is AppStateMachine.Event.StopStream ->
                if (isStreaming()) {
                    releaseWakeLock()
                    signaling!!.sendStreamStop()
                    clients.values.forEach { it.stop() }
                    service.unregisterComponentCallbacks(componentCallback)
                    projection!!.stop()
                    service.hideForegroundNotification()
                } else {
                    XLog.w(getLog("StopStream", "Not streaming. Ignoring."))
                }

            is AppStateMachine.Event.RecoverError,
            is InternalEvent.Restart,
            is InternalEvent.Destroy -> {
                releaseWakeLock()

                if (isStreaming()) {
                    signaling!!.sendStreamStop()
                    clients.values.forEach { it.stop() }
                    service.unregisterComponentCallbacks(componentCallback)
                    projection!!.stop()
                }

                signaling?.destroy()
                projection?.destroy()

                pendingSocket = true
                pendingStreamId = true
                lastPublicStreamState = null

                signaling = null
                streamId = StreamId.EMPTY
                streamPassword = StreamPassword.EMPTY
                waitingForPermission = false
                mediaProjectionIntent = null
                projection = null
                clients = HashMap()
                appError = null
                service.hideForegroundNotification()
                if (event is AppStateMachine.Event.RecoverError) socketErrorRetryAttempts = 0

                when (event) {
                    is AppStateMachine.Event.RecoverError,
                    is InternalEvent.Restart -> sendEvent(InternalEvent.GetNonce(0, true))

                    is InternalEvent.Destroy -> event.latch.countDown()
                }
            }

            is AppStateMachine.Event.GetNewStreamId ->
                if (isNotStreaming()) {
                    signaling!!.sendStreamRemove()
                    clients = HashMap()
                    streamId = StreamId.EMPTY
                    coroutineScope.launch { webRtcSettings.setLastStreamId(StreamId.EMPTY.value) }
                } else {
                    XLog.w(getLog("GetNewStreamId", "Streaming. Ignoring."))
                }

            is AppStateMachine.Event.CreateNewStreamPassword ->
                if (isNotStreaming()) {
                    signaling!!.sendRemoveClients(clients.values.map { it.clientId }, "CreateNewStreamPassword")
                    clients = HashMap()
                    streamPassword = StreamPassword.generateNew()
                } else {
                    XLog.w(getLog("CreateNewStreamPassword", "Streaming. Ignoring."))
                }

            is InternalEvent.SocketSignalingError ->
                if (event.error is SocketSignaling.Errors.StreamStartError && socketErrorRetryAttempts < 2) {
                    socketErrorRetryAttempts++
                    sendEvent(AppStateMachine.Event.StopStream)
                } else if (event.error.retry && socketErrorRetryAttempts < 2) {
                    socketErrorRetryAttempts++
                    sendEvent(InternalEvent.Restart, 2000L * socketErrorRetryAttempts)
                } else {
                    appError = WebRtcError.SocketError(event.error.message!!, event.error.cause)
                }

            is InternalEvent.PublishClients ->
                effectSharedFlow.tryEmit(AppStateMachine.Effect.Statistic.Clients(clients.values.map { it.toPublic() }))

            is InternalEvent.ScreenOff -> coroutineScope.launch {
                if (appSettings.stopOnSleepFlow.first() && isStreaming()) sendEvent(AppStateMachine.Event.StopStream)
            }

            is InternalEvent.ConfigurationChange -> {
                if (isStreaming()) {
                    val configDiff = deviceConfiguration.diff(event.newConfig)
                    if (
                        configDiff and ActivityInfo.CONFIG_ORIENTATION != 0 || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0 ||
                        configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0 || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                    ) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            projection!!.changeCaptureFormat()
                        } else { //TODO Add auto restart to settings?
                            sendEvent(AppStateMachine.Event.StopStream)
                            sendEvent(AppStateMachine.Event.StartStream, 500)
                        }
                    } else {
                        XLog.d(getLog("ConfigurationChange", "No change relevant for streaming. Ignoring."))
                    }
                } else {
                    XLog.d(getLog("ConfigurationChange", "Not streaming. Ignoring."))
                }
                deviceConfiguration = Configuration(event.newConfig)
            }

            is AppStateMachine.Event.RequestPublicState -> {
                createAndSendPublicState(true)
                effectSharedFlow.tryEmit(AppStateMachine.Effect.Statistic.Clients(clients.values.map { it.toPublic() }))
            }

            else -> throw IllegalArgumentException("Unknown WebRTCHandlerThread.Event: $event")
        }

        createAndSendPublicState(false)
        pendingEventDeque.pollFirst()
        XLog.d(getLog("processEvent", "Done [$event] New state: [${getStateString()}] Pending events: $pendingEventDeque"))
    }.onFailure { cause ->
        XLog.e(getLog("processEvent.onFailure", cause.message)) //todo
        XLog.e(getLog("processEvent.onFailure", cause.message), cause)
        releaseWakeLock()
        appError = WebRtcError.UnknownError(cause)
        createAndSendPublicState(true)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    private fun takeWakeLock() {
        coroutineScope.launch {
            if (appSettings.keepAwakeFlow.first()) {
                val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                wakeLock = powerManager.newWakeLock(flags, "ScreenStream::StreamingTag").apply { acquire() }
            }
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun getStateString(): String =
        "Pending Dest/Sock/ID: $pendingDestroy/$pendingSocket/$pendingStreamId, Socket:${signaling?.socketId()}, StreamId:$streamId, Streaming:${isStreaming()}, WFP:$waitingForPermission, WFFS: $waitingForForegroundService, Clients:${clients.size}"
}