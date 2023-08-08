package info.dvkr.screenstream.mjpeg.state

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.AppStateMachine
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.mjpeg.*
import info.dvkr.screenstream.mjpeg.httpserver.HttpServer
import info.dvkr.screenstream.mjpeg.image.BitmapCapture
import info.dvkr.screenstream.mjpeg.image.NotificationBitmap
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.state.helper.BroadcastHelper
import info.dvkr.screenstream.mjpeg.state.helper.ConnectivityHelper
import info.dvkr.screenstream.mjpeg.state.helper.NetworkHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.LinkedBlockingDeque

class MjpegStateMachine(
    private val serviceContext: Context,
    private val appSettings: AppSettings,
    private val mjpegSettings: MjpegSettings,
    private val effectSharedFlow: MutableSharedFlow<AppStateMachine.Effect>,
    private val onSlowConnectionDetected: () -> Unit
) : AppStateMachine {

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        XLog.e(getLog("onCoroutineException"), throwable)
        onError(CoroutineException)
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)

    private val bitmapStateFlow = MutableStateFlow(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    private val projectionManager = serviceContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.i(this@MjpegStateMachine.getLog("MediaProjection.Callback", "onStop"))
            sendEvent(AppStateMachine.Event.StopStream)
        }
    }

    private val broadcastHelper = BroadcastHelper.getInstance(serviceContext)
    private val connectivityHelper: ConnectivityHelper = ConnectivityHelper.getInstance(serviceContext)
    private val networkHelper = NetworkHelper(serviceContext)
    private val notificationBitmap = NotificationBitmap(serviceContext, mjpegSettings)
    private val httpServer = HttpServer(serviceContext, coroutineScope, mjpegSettings, bitmapStateFlow.asStateFlow(), notificationBitmap)
    private var mediaProjectionIntent: Intent? = null

    private val powerManager = serviceContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    internal sealed class InternalEvent : AppStateMachine.Event() {
        object DiscoverAddress : InternalEvent()
        object StartServer : InternalEvent()
        data class ComponentError(val appError: AppError) : InternalEvent()
        object StartStopFromWebPage : InternalEvent()
        data class RestartServer(val reason: RestartReason) : InternalEvent()
        object ScreenOff : InternalEvent()
        object Destroy : InternalEvent()

        override fun toString(): String = javaClass.simpleName
    }

    internal sealed class RestartReason(private val msg: String) {
        class ConnectionChanged(msg: String) : RestartReason(msg)
        class SettingsChanged(msg: String) : RestartReason(msg)
        class NetworkSettingsChanged(msg: String) : RestartReason(msg)

        override fun toString(): String = "${javaClass.simpleName}[$msg]"
    }

    override fun sendEvent(event: AppStateMachine.Event, timeout: Long) {
        if (timeout > 0) {
            XLog.d(getLog("sendEvent[Timeout: $timeout]", "Event: $event"))
            coroutineScope.launch { delay(timeout); sendEvent(event) }
            return
        }

        XLog.d(getLog("sendEvent", "Event: $event"))

        runCatching {
            eventDeque.addLast(event)
            eventSharedFlow.tryEmit(event) || throw IllegalStateException("eventSharedFlow IsFull")
            XLog.d(getLog("sendEvent", "Pending events => $eventDeque"))
        }.onFailure { cause ->
            XLog.e(getLog("sendEvent", "Pending events => $eventDeque"), cause)
            coroutineScope.launch(NonCancellable) {
                streamState = componentError(streamState, ChannelException, true)
                effectSharedFlow.emit(streamState.toPublicState())
            }
        }
    }

    private var streamState = StreamState()
    private var previousStreamState = StreamState()
    private val eventSharedFlow = MutableSharedFlow<AppStateMachine.Event>(replay = 5, extraBufferCapacity = 8)
    private val eventDeque = LinkedBlockingDeque<AppStateMachine.Event>()

    init {
        XLog.d(getLog("init"))

        coroutineScope.launch {
            if (mjpegSettings.enablePinFlow.first() && mjpegSettings.newPinOnAppStartFlow.first())
                mjpegSettings.setPin(randomPin())
        }

        coroutineScope.launch(CoroutineName("MjpegAppStateMachine.eventSharedFlow")) {
            eventSharedFlow.onEach { event ->
                XLog.d(this@MjpegStateMachine.getLog("eventSharedFlow.onEach", "$event"))
                if (StateToEventMatrix.skippEvent(streamState.state, event).not()) {
                    previousStreamState = streamState
                    streamState = when (event) {
                        is InternalEvent.DiscoverAddress -> discoverAddress(streamState)
                        is InternalEvent.StartServer -> startServer(streamState)
                        is InternalEvent.ComponentError -> componentError(streamState, event.appError, false)
                        is InternalEvent.StartStopFromWebPage -> startStopFromWebPage(streamState)
                        is InternalEvent.RestartServer -> restartServer(streamState, event.reason)
                        is InternalEvent.ScreenOff -> screenOff(streamState)
                        is InternalEvent.Destroy -> destroy(streamState)

                        is AppStateMachine.Event.StartStream -> startStream(streamState)
                        is AppStateMachine.Event.CastPermissionsDenied -> castPermissionsDenied(streamState)
                        is AppStateMachine.Event.StartProjection -> startProjection(streamState, event.intent)
                        is AppStateMachine.Event.StopStream -> stopStream(streamState)
                        is AppStateMachine.Event.RequestPublicState -> requestPublicState(streamState)
                        is AppStateMachine.Event.RecoverError -> recoverError(streamState)
                        else -> throw IllegalArgumentException("Unknown AppStateMachine.Event: $event")
                    }

                    if (streamState.isPublicStatePublishRequired(previousStreamState)) effectSharedFlow.emit(streamState.toPublicState())

                    XLog.i(this@MjpegStateMachine.getLog("eventSharedFlow.onEach", "New state:${streamState.state}"))
                }
                eventDeque.pollFirst()
                XLog.d(this@MjpegStateMachine.getLog("eventSharedFlow.onEach.done", eventDeque.toString()))
            }
                .catch { cause ->
                    XLog.e(this@MjpegStateMachine.getLog("eventSharedFlow.catch"), cause)
                    streamState = componentError(streamState, CoroutineException, true)
                    effectSharedFlow.emit(streamState.toPublicState())
                }
                .collect()
        }

        mjpegSettings.htmlEnableButtonsFlow.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.HTML_ENABLE_BUTTONS.name)))
        }
        mjpegSettings.htmlBackColorFlow.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.HTML_BACK_COLOR.name)))
        }
        mjpegSettings.enablePinFlow.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.ENABLE_PIN.name)))
        }
        mjpegSettings.pinFlow.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.PIN.name)))
        }
        mjpegSettings.blockAddressFlow.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.BLOCK_ADDRESS.name)))
        }
        mjpegSettings.useWiFiOnlyFlow.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.USE_WIFI_ONLY.name)))
        }
        mjpegSettings.enableIPv6Flow.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.ENABLE_IPV6.name)))
        }
        mjpegSettings.enableLocalHostFlow.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.ENABLE_LOCAL_HOST.name)))
        }
        mjpegSettings.localHostOnlyFlow.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.LOCAL_HOST_ONLY.name)))
        }
        mjpegSettings.serverPortFlow.listenForChange(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.SERVER_PORT.name)))
        }

        broadcastHelper.startListening(
            onScreenOff = { sendEvent(InternalEvent.ScreenOff) },
            onConnectionChanged = { sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged("BroadcastHelper"))) }
        )

        connectivityHelper.startListening(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged("ConnectivityHelper")))
        }

        coroutineScope.launch(CoroutineName("MjpegStateMachine.httpServer.eventSharedFlow")) {
            httpServer.eventSharedFlow.onEach { event ->
                if (event !is HttpServer.Event.Statistic)
                    XLog.d(this@MjpegStateMachine.getLog("httpServer.eventSharedFlow.onEach", "$event"))

                when (event) {
                    is HttpServer.Event.Action ->
                        when (event) {
                            is HttpServer.Event.Action.StartStopRequest -> sendEvent(InternalEvent.StartStopFromWebPage)
                        }

                    is HttpServer.Event.Statistic ->
                        when (event) {
                            is HttpServer.Event.Statistic.Clients -> {
                                effectSharedFlow.emit(AppStateMachine.Effect.Statistic.Clients(event.clients))
                                if (appSettings.autoStartStopFlow.first()) checkAutoStartStop(event.clients)
                                if (mjpegSettings.notifySlowConnectionsFlow.first()) checkForSlowClients(event.clients)
                            }

                            is HttpServer.Event.Statistic.Traffic ->
                                effectSharedFlow.emit(AppStateMachine.Effect.Statistic.Traffic(event.traffic))
                        }

                    is HttpServer.Event.Error -> onError(event.error)
                }
            }
                .catch { cause ->
                    XLog.e(this@MjpegStateMachine.getLog("httpServer.eventSharedFlow.catch"), cause)
                    onError(CoroutineException)
                }
                .collect()
        }
    }

    private var slowClients: List<MjpegClient> = emptyList()

    @Synchronized
    private fun checkForSlowClients(clients: List<MjpegClient>) {
        val currentSlowConnections = clients.filter { it.isSlowConnection }.toList()
        if (slowClients.containsAll(currentSlowConnections).not()) onSlowConnectionDetected()
        slowClients = currentSlowConnections
    }

    private fun checkAutoStartStop(clients: List<MjpegClient>) {
        if (clients.isNotEmpty() && streamState.isStreaming().not()) {
            XLog.d(getLog("checkAutoStartStop", "Auto starting"))
            sendEvent(AppStateMachine.Event.StartStream)
        }

        if (clients.isEmpty() && streamState.isStreaming()) {
            XLog.d(getLog("checkAutoStartStop", "Auto stopping"))
            sendEvent(AppStateMachine.Event.StopStream)
        }
    }

    private fun releaseWakeLock() {
        synchronized(this) {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
        }
    }

    override fun destroy() {
        XLog.d(getLog("destroy"))
        releaseWakeLock()

        sendEvent(InternalEvent.Destroy)
        try {
            runBlocking(coroutineScope.coroutineContext) { withTimeout(1000) { httpServer.destroy().await() } }
        } catch (cause: Throwable) {
            XLog.e(getLog("destroy", cause.toString()))
        }
        broadcastHelper.stopListening()
        connectivityHelper.stopListening()
        coroutineScope.cancel()

        mediaProjectionIntent = null
    }

    private fun onError(appError: AppError) {
        XLog.e(getLog("onError", "AppError: $appError"))
        releaseWakeLock()
        sendEvent(InternalEvent.ComponentError(appError))
    }

    private fun stopProjection(streamState: StreamState): StreamState {
        XLog.d(getLog("stopProjection"))
        if (streamState.isStreaming()) {
            streamState.bitmapCapture?.destroy()
            streamState.mediaProjection?.unregisterCallback(projectionCallback)
            streamState.mediaProjection?.stop()
        }

        releaseWakeLock()

        return streamState.copy(mediaProjection = null, bitmapCapture = null)
    }

    private suspend fun discoverAddress(streamState: StreamState): StreamState {
        XLog.d(getLog("discoverAddress"))

        val netInterfaces = networkHelper.getNetInterfaces(
            mjpegSettings.useWiFiOnlyFlow.first(), mjpegSettings.enableIPv6Flow.first(),
            mjpegSettings.enableLocalHostFlow.first(), mjpegSettings.localHostOnlyFlow.first()
        )
        if (netInterfaces.isEmpty())
            return if (streamState.httpServerAddressAttempt < 3) {
                sendEvent(InternalEvent.DiscoverAddress, 1000)
                streamState.copy(httpServerAddressAttempt = streamState.httpServerAddressAttempt + 1)
            } else {
                XLog.w(getLog("discoverAddress", "No address found"))
                streamState.copy(
                    state = StreamState.State.ERROR,
                    netInterfaces = emptyList(),
                    httpServerAddressAttempt = 0,
                    appError = AddressNotFoundException
                )
            }

        sendEvent(InternalEvent.StartServer)
        return streamState.copy(
            state = StreamState.State.ADDRESS_DISCOVERED,
            netInterfaces = netInterfaces,
            httpServerAddressAttempt = 0
        )
    }

    private suspend fun startServer(streamState: StreamState): StreamState {
        XLog.d(getLog("startServer"))
        require(streamState.netInterfaces.isNotEmpty())

        withTimeoutOrNull(300) { httpServer.stop().await() }
        httpServer.start(streamState.netInterfaces)
        bitmapStateFlow.tryEmit(notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.START))

        return streamState.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun startStream(streamState: StreamState): StreamState {
        XLog.d(getLog("startStream"))

        return if (mediaProjectionIntent != null) {
            sendEvent(AppStateMachine.Event.StartProjection(mediaProjectionIntent!!))
            streamState
        } else {
            streamState.copy(state = StreamState.State.PERMISSION_PENDING)
        }
    }

    private fun castPermissionsDenied(streamState: StreamState): StreamState {
        XLog.d(getLog("castPermissionsDenied"))

        return streamState.copy(state = StreamState.State.SERVER_STARTED)
    }

    private suspend fun startProjection(streamState: StreamState, intent: Intent): StreamState {
        XLog.d(getLog("startProjection", "Intent: $intent"))

        try {
            val mediaProjection = withContext(Dispatchers.Main) {
                delay(500)
                projectionManager.getMediaProjection(Activity.RESULT_OK, intent).apply {
                    registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                }
            }
            mediaProjectionIntent = intent
            val bitmapCapture = BitmapCapture(serviceContext, mjpegSettings, mediaProjection, bitmapStateFlow, ::onError)
            bitmapCapture.start()

            if (appSettings.keepAwakeFlow.first()) {
                synchronized(this) {
                    @Suppress("DEPRECATION")
                    @SuppressLint("WakelockTimeout")
                    wakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "ScreenStream::StreamingTag"
                    )
                        .apply { acquire() }
                }
            }

            return streamState.copy(
                state = StreamState.State.STREAMING,
                mediaProjection = mediaProjection,
                bitmapCapture = bitmapCapture
            )
        } catch (cause: Throwable) {
            XLog.e(getLog("startProjection"), cause)
        }
        mediaProjectionIntent = null
        return streamState.copy(state = StreamState.State.ERROR, appError = CastSecurityException)
    }

    private suspend fun stopStream(streamState: StreamState): StreamState {
        XLog.d(getLog("stopStream"))

        val state = stopProjection(streamState)
        if (mjpegSettings.enablePinFlow.first() && mjpegSettings.autoChangePinFlow.first()) {
            mjpegSettings.setPin(randomPin())
        } else {
            bitmapStateFlow.tryEmit(notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.START))
        }

        return state.copy(state = StreamState.State.SERVER_STARTED)
    }

    private suspend fun screenOff(streamState: StreamState): StreamState {
        XLog.d(getLog("screenOff"))

        return if (appSettings.stopOnSleepFlow.first() && streamState.isStreaming()) stopStream(streamState)
        else streamState
    }

    private fun destroy(streamState: StreamState): StreamState {
        XLog.d(getLog("destroy"))

        return stopProjection(streamState).copy(state = StreamState.State.DESTROYED)
    }

    private suspend fun startStopFromWebPage(streamState: StreamState): StreamState {
        XLog.d(getLog("startStopFromWebPage"))

        if (streamState.isStreaming()) return stopStream(streamState)

        if (streamState.state == StreamState.State.SERVER_STARTED)
            return streamState.copy(state = StreamState.State.PERMISSION_PENDING)

        return streamState
    }

    private suspend fun restartServer(streamState: StreamState, reason: RestartReason): StreamState {
        XLog.d(getLog("restartServer"))

        val state = stopProjection(streamState)

        when (reason) {
            is RestartReason.ConnectionChanged ->
                effectSharedFlow.emit(AppStateMachine.Effect.ConnectionChanged)

            is RestartReason.SettingsChanged ->
                bitmapStateFlow.emit(notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.RELOAD_PAGE))

            is RestartReason.NetworkSettingsChanged ->
                bitmapStateFlow.emit(notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.NEW_ADDRESS))
        }

        withTimeoutOrNull(300) { httpServer.stop().await() }

        if (state.state == StreamState.State.ERROR)
            sendEvent(AppStateMachine.Event.RecoverError)
        else
            sendEvent(InternalEvent.DiscoverAddress, 1000)

        return state.copy(
            state = StreamState.State.RESTART_PENDING,
            netInterfaces = emptyList(),
            httpServerAddressAttempt = 0
        )
    }

    private fun componentError(streamState: StreamState, appError: AppError, report: Boolean): StreamState {
        XLog.d(getLog("componentError"))
        if (report) XLog.e(getLog("componentError"), appError)

        return stopProjection(streamState).copy(state = StreamState.State.ERROR, appError = appError)
    }

    private fun recoverError(streamState: StreamState): StreamState {
        XLog.d(getLog("recoverError"))

        sendEvent(InternalEvent.DiscoverAddress)
        return streamState.copy(state = StreamState.State.RESTART_PENDING, appError = null)
    }

    private suspend fun requestPublicState(streamState: StreamState): StreamState {
        XLog.d(getLog("requestPublicState"))

        effectSharedFlow.emit(streamState.toPublicState())
        return streamState
    }

    private fun <T> Flow<T>.listenForChange(scope: CoroutineScope, action: suspend (T) -> Unit) =
        distinctUntilChanged().drop(1).onEach { action(it) }.launchIn(scope)
}