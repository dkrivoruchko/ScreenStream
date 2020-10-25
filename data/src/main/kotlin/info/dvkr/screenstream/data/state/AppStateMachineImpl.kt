package info.dvkr.screenstream.data.state

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.httpserver.AppHttpServer
import info.dvkr.screenstream.data.httpserver.ClientStatistic
import info.dvkr.screenstream.data.httpserver.HttpServerFiles
import info.dvkr.screenstream.data.image.BitmapCapture
import info.dvkr.screenstream.data.image.NotificationBitmap
import info.dvkr.screenstream.data.model.*
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.data.state.helper.BroadcastHelper
import info.dvkr.screenstream.data.state.helper.ConnectivityHelper
import info.dvkr.screenstream.data.state.helper.MediaProjectionHelper
import info.dvkr.screenstream.data.state.helper.NetworkHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AppStateMachineImpl(
    context: Context,
    private val settingsReadOnly: SettingsReadOnly,
    private val onEffect: suspend (AppStateMachine.Effect) -> Unit
) : AppStateMachine {

    private val applicationContext = context.applicationContext
    private val bitmapStateFlow = MutableStateFlow(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    private val mediaProjectionHelper = MediaProjectionHelper(context) { sendEvent(AppStateMachine.Event.StopStream) }
    private val broadcastHelper = BroadcastHelper.getInstance(context)
    private val connectivityHelper: ConnectivityHelper = ConnectivityHelper.getInstance(context)
    private val networkHelper = NetworkHelper(context)
    private val notificationBitmap = NotificationBitmap(context)
    private val clientStatistic: ClientStatistic = ClientStatistic(::onError)
    private val httpServerFiles = HttpServerFiles(applicationContext, settingsReadOnly)
    private val httpServer = AppHttpServer(
        settingsReadOnly, httpServerFiles, clientStatistic, bitmapStateFlow.asStateFlow(),
        { sendEvent(InternalEvent.StartStopFromWebPage) },
        ::onError
    )

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        XLog.e(getLog("onCoroutineException"), throwable)
        onError(FatalError.CoroutineException)
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)

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

    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) {
            if (key in arrayOf(
                    Settings.Key.HTML_ENABLE_BUTTONS, Settings.Key.HTML_BACK_COLOR,
                    Settings.Key.ENABLE_PIN, Settings.Key.PIN
                )
            ) sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(key)))

            if (key in arrayOf(
                    Settings.Key.USE_WIFI_ONLY, Settings.Key.ENABLE_IPV6,
                    Settings.Key.ENABLE_LOCAL_HOST, Settings.Key.LOCAL_HOST_ONLY, Settings.Key.SERVER_PORT
                )
            )
                sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(key)))
        }
    }

    override val statisticFlow: StateFlow<Pair<List<HttpClient>, List<TrafficPoint>>> = clientStatistic.statisticFlow

    override fun sendEvent(event: AppStateMachine.Event, timeout: Long) {
        if (timeout > 0) {
            XLog.d(getLog("sendEvent[Timeout: $timeout]", "Event: $event"))
            coroutineScope.launch { delay(timeout); sendEvent(event) }
        } else {
            XLog.d(getLog("sendEvent", "Event: $event"))

            try {
                _eventSharedFlow.tryEmit(event) || throw IllegalStateException("_eventSharedFlow IsFull")
            } catch (th: Throwable) {
                XLog.e(getLog("sendEvent"), th)
                coroutineScope.launch(NonCancellable) {
                    onEffect(
                        AppStateMachine.Effect.PublicState(false, true, false, emptyList(), FatalError.ChannelException)
                    )
                }
            }
        }
    }

    private var streamState = StreamState()
    private var previousStreamState = StreamState()
    private val _eventSharedFlow = MutableSharedFlow<AppStateMachine.Event>(extraBufferCapacity = 32)

    init {
        XLog.d(getLog("init"))

        coroutineScope.launch(CoroutineName("AppStateMachineImpl.eventSharedFlow")) {
            _eventSharedFlow.onEach { event ->
                XLog.d(this@AppStateMachineImpl.getLog("eventSharedFlow.onEach", "$event"))
                if (StateToEventMatrix.skippEvent(streamState.state, event).not()) {
                    previousStreamState = streamState
                    streamState = when (event) {
                        is InternalEvent.DiscoverAddress -> discoverAddress(streamState)
                        is InternalEvent.StartServer -> startServer(streamState)
                        is InternalEvent.ComponentError -> componentError(streamState, event.appError)
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

                    if (streamState.isPublicStatePublishRequired(previousStreamState)) onEffect(streamState.toPublicState())

                    XLog.i(this@AppStateMachineImpl.getLog("eventSharedFlow.onEach", "New state:${streamState.state}"))
                }
            }
                .catch { cause ->
                    XLog.e(this@AppStateMachineImpl.getLog("eventSharedFlow.catch"), cause)
                    streamState = componentError(streamState, FatalError.CoroutineException)
                    onEffect(streamState.toPublicState())
                }
                .collect()
        }

        settingsReadOnly.registerChangeListener(settingsListener)
        broadcastHelper.startListening(
            onScreenOff = { sendEvent(InternalEvent.ScreenOff) },
            onConnectionChanged = { sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged(""))) }
        )

        connectivityHelper.startListening {
            sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged("")))
        }
    }

    override suspend fun destroy() {
        XLog.d(getLog("destroy"))
        sendEvent(InternalEvent.Destroy)
        httpServer.stop()
        clientStatistic.destroy()
        settingsReadOnly.unregisterChangeListener(settingsListener)
        broadcastHelper.stopListening()
        connectivityHelper.stopListening()
        coroutineScope.cancel()
    }

    private fun onError(appError: AppError) {
        XLog.e(getLog("onError", "AppError: $appError"))
        sendEvent(InternalEvent.ComponentError(appError))
    }

    private fun stopProjection(streamState: StreamState): StreamState {
        XLog.d(getLog("stopProjection"))
        if (streamState.isStreaming()) {
            streamState.bitmapCapture?.destroy()
            mediaProjectionHelper.stopMediaProjection(streamState.mediaProjection)
        }

        return streamState.copy(mediaProjection = null, bitmapCapture = null)
    }

    private fun discoverAddress(streamState: StreamState): StreamState {
        XLog.d(getLog("discoverAddress"))

        val netInterfaces = networkHelper.getNetInterfaces(
            settingsReadOnly.useWiFiOnly, settingsReadOnly.enableIPv6,
            settingsReadOnly.enableLocalHost, settingsReadOnly.localHostOnly
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
                    appError = FixableError.AddressNotFoundException
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

        httpServer.stop()
        httpServer.start(streamState.netInterfaces)
        notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.START).let { bitmap ->
            repeat(3) { bitmapStateFlow.tryEmit(bitmap); delay(150) }
        }

        return streamState.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun startStream(streamState: StreamState): StreamState {
        XLog.d(getLog("startStream"))

        return streamState.copy(state = StreamState.State.PERMISSION_PENDING)
    }

    private fun castPermissionsDenied(streamState: StreamState): StreamState {
        XLog.d(getLog("castPermissionsDenied"))

        return streamState.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun startProjection(streamState: StreamState, intent: Intent): StreamState {
        XLog.d(getLog("startProjection"))

        val mediaProjection = mediaProjectionHelper.getMediaProjection(intent)
        val display = ContextCompat.getSystemService(applicationContext, WindowManager::class.java)!!.defaultDisplay
        val bitmapCapture = BitmapCapture(display, settingsReadOnly, mediaProjection, bitmapStateFlow, ::onError)
        bitmapCapture.start()

        return streamState.copy(
            state = StreamState.State.STREAMING,
            mediaProjection = mediaProjection,
            bitmapCapture = bitmapCapture
        )
    }

    private suspend fun stopStream(streamState: StreamState): StreamState {
        XLog.d(getLog("stopStream"))

        val state = stopProjection(streamState)
        if (settingsReadOnly.checkAndChangeAutoChangePinOnStop().not())
            notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.START).let { bitmap ->
                repeat(3) { bitmapStateFlow.tryEmit(bitmap); delay(150) }
            }

        return state.copy(state = StreamState.State.SERVER_STARTED)
    }

    private suspend fun screenOff(streamState: StreamState): StreamState {
        XLog.d(getLog("screenOff"))

        return if (settingsReadOnly.stopOnSleep && streamState.isStreaming()) stopStream(streamState)
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
                onEffect(AppStateMachine.Effect.ConnectionChanged)

            is RestartReason.SettingsChanged ->
                notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.RELOAD_PAGE).let { bitmap ->
                    repeat(3) { bitmapStateFlow.tryEmit(bitmap); delay(150) }
                }

            is RestartReason.NetworkSettingsChanged ->
                notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.NEW_ADDRESS).let { bitmap ->
                    repeat(3) { bitmapStateFlow.tryEmit(bitmap); delay(150) }
                }
        }

        httpServer.stop()

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

    private fun componentError(streamState: StreamState, appError: AppError): StreamState {
        XLog.d(getLog("componentError"))

        return stopProjection(streamState).copy(state = StreamState.State.ERROR, appError = appError)
    }

    private fun recoverError(streamState: StreamState): StreamState {
        XLog.d(getLog("recoverError"))

        sendEvent(InternalEvent.DiscoverAddress)
        return streamState.copy(state = StreamState.State.RESTART_PENDING, appError = null)
    }

    private suspend fun requestPublicState(streamState: StreamState): StreamState {
        XLog.d(getLog("requestPublicState"))

        onEffect(streamState.toPublicState())
        return streamState
    }
}