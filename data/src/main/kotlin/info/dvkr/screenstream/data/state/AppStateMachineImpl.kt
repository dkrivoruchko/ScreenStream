package info.dvkr.screenstream.data.state

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
import info.dvkr.screenstream.data.httpserver.HttpServer
import info.dvkr.screenstream.data.image.BitmapCapture
import info.dvkr.screenstream.data.image.NotificationBitmap
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.FixableError
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.data.state.helper.BroadcastHelper
import info.dvkr.screenstream.data.state.helper.ConnectivityHelper
import info.dvkr.screenstream.data.state.helper.NetworkHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.LinkedBlockingDeque

class AppStateMachineImpl(
    private val context: Context,
    private val settingsReadOnly: SettingsReadOnly,
    private val onEffect: suspend (AppStateMachine.Effect) -> Unit
) : AppStateMachine {

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        XLog.e(getLog("onCoroutineException"), throwable)
        onError(FatalError.CoroutineException)
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)

    private val bitmapStateFlow = MutableStateFlow(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.i(this@AppStateMachineImpl.getLog("MediaProjection.Callback", "onStop"))
            sendEvent(AppStateMachine.Event.StopStream)
        }
    }

    private val broadcastHelper = BroadcastHelper.getInstance(context)
    private val connectivityHelper: ConnectivityHelper = ConnectivityHelper.getInstance(context)
    private val networkHelper = NetworkHelper(context)
    private val notificationBitmap = NotificationBitmap(context, settingsReadOnly)
    private val httpServer = HttpServer(
        context, coroutineScope, settingsReadOnly, bitmapStateFlow.asStateFlow(),
        notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.ADDRESS_BLOCKED)
    )
    private var mediaProjectionIntent: Intent? = null

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
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

    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) {
            if (key in arrayOf(
                    Settings.Key.HTML_ENABLE_BUTTONS, Settings.Key.HTML_BACK_COLOR,
                    Settings.Key.ENABLE_PIN, Settings.Key.PIN, Settings.Key.BLOCK_ADDRESS
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

    override fun sendEvent(event: AppStateMachine.Event, timeout: Long) {
        if (timeout > 0) {
            XLog.d(getLog("sendEvent[Timeout: $timeout]", "Event: $event"))
            coroutineScope.launch { delay(timeout); sendEvent(event) }
        } else {
            XLog.d(getLog("sendEvent", "Event: $event"))

            try {
                _eventDeque.addLast(event)
                _eventSharedFlow.tryEmit(event) || throw IllegalStateException("_eventSharedFlow IsFull")
                XLog.d(getLog("sendEvent", _eventDeque.toString()))
            } catch (th: Throwable) {
                XLog.e(getLog("sendEvent", _eventDeque.toString()))
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
    private val _eventSharedFlow = MutableSharedFlow<AppStateMachine.Event>(replay = 5, extraBufferCapacity = 8)
    private val _eventDeque = LinkedBlockingDeque<AppStateMachine.Event>()

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

                    if (streamState.isPublicStatePublishRequired(previousStreamState)) onEffect(streamState.toPublicState())

                    XLog.i(this@AppStateMachineImpl.getLog("eventSharedFlow.onEach", "New state:${streamState.state}"))
                }
                _eventDeque.pollFirst()
                XLog.d(this@AppStateMachineImpl.getLog("eventSharedFlow.onEach.done", _eventDeque.toString()))
            }
                .catch { cause ->
                    XLog.e(this@AppStateMachineImpl.getLog("eventSharedFlow.catch"), cause)
                    streamState = componentError(streamState, FatalError.CoroutineException, true)
                    onEffect(streamState.toPublicState())
                }
                .collect()
        }

        settingsReadOnly.registerChangeListener(settingsListener)
        broadcastHelper.startListening(
            onScreenOff = { sendEvent(InternalEvent.ScreenOff) },
            onConnectionChanged = { sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged("BroadcastHelper"))) }
        )

        connectivityHelper.startListening(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged("ConnectivityHelper")))
        }

        coroutineScope.launch(CoroutineName("AppStateMachineImpl.httpServer.eventSharedFlow")) {
            httpServer.eventSharedFlow.onEach { event ->
                if (event !is HttpServer.Event.Statistic)
                    XLog.d(this@AppStateMachineImpl.getLog("httpServer.eventSharedFlow.onEach", "$event"))

                when (event) {
                    is HttpServer.Event.Action ->
                        when (event) {
                            is HttpServer.Event.Action.StartStopRequest -> sendEvent(InternalEvent.StartStopFromWebPage)

                            else -> throw IllegalArgumentException("Unknown HttpServer.Event: $event")
                        }

                    is HttpServer.Event.Statistic ->
                        when (event) {
                            is HttpServer.Event.Statistic.Clients ->
                                onEffect(AppStateMachine.Effect.Statistic.Clients(event.clients))

                            is HttpServer.Event.Statistic.Traffic ->
                                onEffect(AppStateMachine.Effect.Statistic.Traffic(event.traffic))

                            else -> throw IllegalArgumentException("Unknown HttpServer.Event: $event")
                        }

                    is HttpServer.Event.Error -> onError(event.error)

                    else -> throw IllegalArgumentException("Unknown HttpServer.Event: $event")
                }
            }
                .catch { cause ->
                    XLog.e(this@AppStateMachineImpl.getLog("httpServer.eventSharedFlow.catch"), cause)
                    onError(FatalError.CoroutineException)
                }
                .collect()
        }
    }

    override suspend fun destroy() {
        XLog.d(getLog("destroy"))
        sendEvent(InternalEvent.Destroy)
        httpServer.destroy().await()
        settingsReadOnly.unregisterChangeListener(settingsListener)
        broadcastHelper.stopListening()
        connectivityHelper.stopListening()
        coroutineScope.cancel()

        mediaProjectionIntent = null

        wakeLock?.release()
        wakeLock = null
    }

    private fun onError(appError: AppError) {
        XLog.e(getLog("onError", "AppError: $appError"))
        wakeLock?.release()
        wakeLock = null
        sendEvent(InternalEvent.ComponentError(appError))
    }

    private fun stopProjection(streamState: StreamState): StreamState {
        XLog.d(getLog("stopProjection"))
        if (streamState.isStreaming()) {
            streamState.bitmapCapture?.destroy()
            streamState.mediaProjection?.unregisterCallback(projectionCallback)
            streamState.mediaProjection?.stop()
        }

        wakeLock?.release()
        wakeLock = null

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

        withTimeoutOrNull(300) {
            XLog.e(this@AppStateMachineImpl.getLog("startServer", "httpServer.stop().await()...."))
            httpServer.stop().await()
            XLog.e(this@AppStateMachineImpl.getLog("startServer", "httpServer.stop().await()....DONE"))
        }
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
        XLog.d(getLog("startProjection"))

        mediaProjectionIntent = intent
        val mediaProjection = withContext(Dispatchers.Main) {
            delay(500)
            projectionManager.getMediaProjection(Activity.RESULT_OK, intent).apply {
                registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            }
        }
        val bitmapCapture = BitmapCapture(context, settingsReadOnly, mediaProjection, bitmapStateFlow, ::onError)
        bitmapCapture.start()

        if (settingsReadOnly.keepAwake) {
            @Suppress("DEPRECATION")
            @SuppressLint("WakelockTimeout")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "ScreenStream::StreamingTag"
            ).apply { acquire() }
        }

        return streamState.copy(
            state = StreamState.State.STREAMING,
            mediaProjection = mediaProjection,
            bitmapCapture = bitmapCapture
        )
    }

    private fun stopStream(streamState: StreamState): StreamState {
        XLog.d(getLog("stopStream"))

        val state = stopProjection(streamState)
        if (settingsReadOnly.checkAndChangeAutoChangePinOnStop().not())
            bitmapStateFlow.tryEmit(notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.START))

        return state.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun screenOff(streamState: StreamState): StreamState {
        XLog.d(getLog("screenOff"))

        return if (settingsReadOnly.stopOnSleep && streamState.isStreaming()) stopStream(streamState)
        else streamState
    }

    private fun destroy(streamState: StreamState): StreamState {
        XLog.d(getLog("destroy"))

        return stopProjection(streamState).copy(state = StreamState.State.DESTROYED)
    }

    private fun startStopFromWebPage(streamState: StreamState): StreamState {
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
                bitmapStateFlow.tryEmit(notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.RELOAD_PAGE))

            is RestartReason.NetworkSettingsChanged ->
                bitmapStateFlow.tryEmit(notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.NEW_ADDRESS))
        }

        withTimeoutOrNull(300) {
            XLog.e(this@AppStateMachineImpl.getLog("restartServer", " httpServer.stop().await()...."))
            httpServer.stop().await()
            XLog.e(this@AppStateMachineImpl.getLog("restartServer", " httpServer.stop().await()....DONE"))
        }

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

        onEffect(streamState.toPublicState())
        return streamState
    }
}