package info.dvkr.screenstream.mjpeg.state

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.MainThread
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.AppStateMachine
import info.dvkr.screenstream.common.NotificationHelper
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
    private val service: Service,
    private val notificationHelper: NotificationHelper,
    private val appSettings: AppSettings,
    private val mjpegSettings: MjpegSettings,
    private val effectSharedFlow: MutableSharedFlow<AppStateMachine.Effect>,
    private val onSlowConnectionDetected: () -> Unit
) : AppStateMachine {

    override val mode: Int = AppSettings.Values.STREAM_MODE_MJPEG

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        XLog.e(getLog("onCoroutineException"), throwable)
        onError(CoroutineException)
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)

    private var deviceConfiguration = Configuration(service.resources.configuration)
    private val bitmapStateFlow = MutableStateFlow(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    private val projectionManager = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.i(this@MjpegStateMachine.getLog("MediaProjection.Callback", "onStop"))
            sendEvent(AppStateMachine.Event.StopStream)
        }
    }

    private val broadcastHelper = BroadcastHelper.getInstance(service)
    private val connectivityHelper: ConnectivityHelper = ConnectivityHelper.getInstance(service)
    private val networkHelper = NetworkHelper(service)
    private val notificationBitmap = NotificationBitmap(service, mjpegSettings)
    private val httpServer = HttpServer(service, coroutineScope, mjpegSettings, bitmapStateFlow.asStateFlow(), notificationBitmap)
    private var mediaProjectionIntent: Intent? = null

    private val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    internal sealed class InternalEvent : AppStateMachine.Event() {
        data object DiscoverAddress : InternalEvent()
        data object StartServer : InternalEvent()
        data class ComponentError(internal val appError: AppError) : InternalEvent()
        data object StartStopFromWebPage : InternalEvent()
        data class RestartServer(internal val reason: RestartReason) : InternalEvent()
        data object ScreenOff : InternalEvent()
        data object Destroy : InternalEvent()
        data class ConfigurationChange(internal val newConfig: Configuration) : InternalEvent()
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

    private val componentCallback = object : ComponentCallbacks {
        @MainThread
        override fun onConfigurationChanged(newConfig: Configuration) {
            sendEvent(InternalEvent.ConfigurationChange(newConfig))
        }

        override fun onLowMemory() = Unit
    }

    init {
        XLog.d(getLog("init"))

        notificationHelper.showNotification(service, NotificationHelper.NotificationType.START)

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
                        is InternalEvent.ConfigurationChange -> configurationChange(streamState, event.newConfig)

                        is AppStateMachine.Event.StartStream -> startStream(streamState)
                        is AppStateMachine.Event.CastPermissionsDenied -> castPermissionsDenied(streamState)
                        is AppStateMachine.Event.StartProjection -> startProjection(streamState, event.intent)
                        is AppStateMachine.Event.StopStream -> stopStream(streamState)
                        is AppStateMachine.Event.RequestPublicState -> requestPublicState(streamState)
                        is AppStateMachine.Event.RecoverError -> recoverError(streamState)
                        is AppStateMachine.Event.UpdateNotification -> updateNotification(streamState)
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

    override fun pauseRequest(): Boolean {
        XLog.d(getLog("pauseRequest", "Ignoring"))
        return false
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
        notificationHelper.clearNotification(service)
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
            notificationHelper.showNotification(service, NotificationHelper.NotificationType.START)
            service.unregisterComponentCallbacks(componentCallback)
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

        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && mediaProjectionIntent != null) {
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
            notificationHelper.showNotification(service, NotificationHelper.NotificationType.STOP)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // waiting for correct service state
                delay(250)
                if (service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION == 0)
                    throw IllegalStateException("Service is not FOREGROUND. Give up.")
            }

            val mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, intent).apply {
                registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) mediaProjectionIntent = intent
            val bitmapCapture = BitmapCapture(service, mjpegSettings, mediaProjection, bitmapStateFlow, ::onError)
            if (bitmapCapture.start()) service.registerComponentCallbacks(componentCallback)

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

            return streamState.copy(state = StreamState.State.STREAMING, mediaProjection = mediaProjection, bitmapCapture = bitmapCapture)
        } catch (cause: Throwable) {
            notificationHelper.showNotification(service, NotificationHelper.NotificationType.START)
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

    private fun configurationChange(streamState: StreamState, newConfig: Configuration): StreamState {
        if (streamState.isStreaming()) {
            val configDiff = deviceConfiguration.diff(newConfig)
            if (
                configDiff and ActivityInfo.CONFIG_ORIENTATION != 0 || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0 ||
                configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0 || configDiff and ActivityInfo.CONFIG_DENSITY != 0
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    streamState.bitmapCapture?.restart()
                } else { //TODO Add auto restart to settings?
                    sendEvent(AppStateMachine.Event.StopStream)
                    sendEvent(AppStateMachine.Event.StartStream, 500)
                }
            } else {
                XLog.d(getLog("configurationChange", "No change relevant for streaming. Ignoring."))
            }
        } else {
            XLog.d(getLog("configurationChange", "Not streaming. Ignoring."))
        }
        deviceConfiguration = Configuration(newConfig)

        return streamState
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

    private fun updateNotification(streamState: StreamState): StreamState {
        XLog.d(getLog("updateNotification"))

        if (streamState.isStreaming().not()) {
            notificationHelper.showNotification(service, NotificationHelper.NotificationType.START)
        }

        return streamState
    }

    private suspend fun requestPublicState(streamState: StreamState): StreamState {
        XLog.d(getLog("requestPublicState"))

        effectSharedFlow.emit(streamState.toPublicState())
        return streamState
    }

    private fun <T> Flow<T>.listenForChange(scope: CoroutineScope, action: suspend (T) -> Unit) =
        distinctUntilChanged().drop(1).onEach { action(it) }.launchIn(scope)
}