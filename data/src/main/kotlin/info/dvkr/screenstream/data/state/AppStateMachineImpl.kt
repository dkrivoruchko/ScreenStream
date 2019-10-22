package info.dvkr.screenstream.data.state

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.annotation.AnyThread
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.httpserver.AppHttpServer
import info.dvkr.screenstream.data.httpserver.AppHttpServerImpl
import info.dvkr.screenstream.data.httpserver.HttpServerFiles
import info.dvkr.screenstream.data.image.BitmapCapture
import info.dvkr.screenstream.data.image.BitmapNotification
import info.dvkr.screenstream.data.image.BitmapToJpeg
import info.dvkr.screenstream.data.model.*
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.data.state.helper.BroadcastHelper
import info.dvkr.screenstream.data.state.helper.ConnectivityHelper
import info.dvkr.screenstream.data.state.helper.MediaProjectionHelper
import info.dvkr.screenstream.data.state.helper.NetworkHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.coroutines.CoroutineContext

class AppStateMachineImpl(
    context: Context,
    parentJob: Job,
    private val settingsReadOnly: SettingsReadOnly,
    appIconBitmap: Bitmap,
    onStatistic: (List<HttpClient>, List<TrafficPoint>) -> Unit,
    private val onEffect: (AppStateMachine.Effect) -> Unit
) : AppStateMachine, CoroutineScope {

    private val applicationContext: Context = context.applicationContext
    private val supervisorJob = SupervisorJob(parentJob)
    private val bitmapChannel: Channel<Bitmap> = Channel(Channel.CONFLATED)
    private val jpegChannel: Channel<ByteArray> = Channel(Channel.CONFLATED)
    private val mediaProjectionHelper = MediaProjectionHelper(context) { sendEvent(AppStateMachine.Event.StopStream) }
    private val broadcastHelper = BroadcastHelper.getInstance(context, ::onError)
    private val connectivityHelper: ConnectivityHelper = ConnectivityHelper.getInstance(context)
    private val networkHelper = NetworkHelper(context)
    private val bitmapNotification = BitmapNotification(context, appIconBitmap, bitmapChannel, ::onError)
    private val bitmapToJpeg = BitmapToJpeg(settingsReadOnly, bitmapChannel, jpegChannel, ::onError)
    private val appHttpServer: AppHttpServer

    override val coroutineContext: CoroutineContext
        get() = supervisorJob + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
            onError(FatalError.CoroutineException)
        }

    internal sealed class InternalEvent : AppStateMachine.Event() {
        object DiscoverAddress : InternalEvent()
        object StartServer : InternalEvent()
        data class ComponentError(val appError: AppError) : InternalEvent()
        object StartStopFromWebPage : InternalEvent()
        data class RestartServer(val reason: RestartReason) : InternalEvent()
        object ScreenOff : InternalEvent()
        object Destroy : InternalEvent()

        override fun toString(): String = this::class.java.simpleName
    }

    internal sealed class RestartReason(private val msg: String) {
        class ConnectionChanged(msg: String) : RestartReason(msg)
        class SettingsChanged(msg: String) : RestartReason(msg)
        class NetworkSettingsChanged(msg: String) : RestartReason(msg)

        override fun toString(): String = "${this::class.java.simpleName}[$msg]"
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
                    Settings.Key.ENABLE_LOCAL_HOST, Settings.Key.SERVER_PORT
                )
            )
                sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(key)))
        }
    }

    override fun sendEvent(event: AppStateMachine.Event, timeout: Long) {
        if (timeout > 0) {
            XLog.d(getLog("sendEvent[Timeout: $timeout]", "Event: $event"))
            launch { delay(timeout); sendEvent(event) }
        } else {
            XLog.d(getLog("sendEvent", "Event: $event"))

            if (supervisorJob.isActive.not()) {
                XLog.w(getLog("sendEvent", "JobIsNotActive"))
                return
            }

            try {
                eventChannel.offer(event) || throw IllegalStateException("ChannelIsFull")
            } catch (ignore: ClosedSendChannelException) {
            } catch (ignore: CancellationException) {
            } catch (th: Throwable) {
                XLog.e(getLog("sendEvent"), th)
                onEffect(
                    AppStateMachine.Effect.PublicState(
                        false, true, false, emptyList(), FatalError.ChannelException
                    )
                )
            }
        }
    }

    private val eventChannel: SendChannel<AppStateMachine.Event> = actor(capacity = 32) {
        var streamState = StreamState()
        var previousStreamState: StreamState

        consumeEach { event ->
            try {
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

                    XLog.i(this@AppStateMachineImpl.getLog("actor", "New state:${streamState.state}"))
                }
            } catch (throwable: Throwable) {
                XLog.e(this@AppStateMachineImpl.getLog("actor"), throwable)
                onError(FatalError.ActorException)
            }
        }
    }

    private fun onError(appError: AppError) {
        XLog.e(getLog("onError", "AppError: $appError"))
        sendEvent(InternalEvent.ComponentError(appError))
    }

    init {
        XLog.d(getLog("init", "Invoked"))
        settingsReadOnly.registerChangeListener(settingsListener)
        broadcastHelper.startListening(
            onScreenOff = { sendEvent(InternalEvent.ScreenOff) },
            onConnectionChanged = { sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged(""))) }
        )

        bitmapToJpeg.start()
        appHttpServer = AppHttpServerImpl(
            HttpServerFiles(applicationContext, settingsReadOnly),
            jpegChannel,
            { sendEvent(InternalEvent.StartStopFromWebPage) },
            onStatistic,
            ::onError
        )
        connectivityHelper.startListening {
            sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged("")))
        }
    }

    @AnyThread
    override fun destroy() {
        XLog.d(getLog("destroy", "Invoked"))
        sendEvent(InternalEvent.Destroy)
        settingsReadOnly.unregisterChangeListener(settingsListener)
        broadcastHelper.stopListening()
        connectivityHelper.stopListening()
        supervisorJob.cancelChildren()
        eventChannel.close()
        bitmapToJpeg.stop()
        appHttpServer.stop()
    }

    private fun stopProjection(streamState: StreamState): StreamState {
        XLog.d(getLog("stopProjection", "Invoked"))
        if (streamState.isStreaming()) {
            streamState.bitmapCapture?.stop()
            mediaProjectionHelper.stopMediaProjection(streamState.mediaProjection)
        }

        return streamState.copy(mediaProjection = null, bitmapCapture = null)
    }

    private fun discoverAddress(streamState: StreamState): StreamState {
        XLog.d(getLog("discoverAddress", "Invoked"))

        val netInterfaces = networkHelper.getNetInterfaces(
            settingsReadOnly.useWiFiOnly, settingsReadOnly.enableIPv6, settingsReadOnly.enableLocalHost
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

    private fun startServer(streamState: StreamState): StreamState {
        XLog.d(getLog("startServer", "Invoked"))
        require(streamState.netInterfaces.isNotEmpty())

        appHttpServer.stop()
        appHttpServer.start(streamState.netInterfaces, settingsReadOnly.severPort, settingsReadOnly.useWiFiOnly)
        bitmapNotification.sentBitmapNotification(BitmapNotification.Type.START)

        return streamState.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun startStream(streamState: StreamState): StreamState {
        XLog.d(getLog("startStream", "Invoked"))

        return streamState.copy(state = StreamState.State.PERMISSION_PENDING)
    }

    private fun castPermissionsDenied(streamState: StreamState): StreamState {
        XLog.d(getLog("castPermissionsDenied", "Invoked"))

        return streamState.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun startProjection(streamState: StreamState, intent: Intent): StreamState {
        XLog.d(getLog("startProjection", "Invoked"))

        val mediaProjection = mediaProjectionHelper.getMediaProjection(intent)
        val display = ContextCompat.getSystemService(applicationContext, WindowManager::class.java)!!.defaultDisplay
        val bitmapCapture = BitmapCapture(display, settingsReadOnly, mediaProjection, bitmapChannel, ::onError)
        bitmapCapture.start()

        return streamState.copy(
            state = StreamState.State.STREAMING,
            mediaProjection = mediaProjection,
            bitmapCapture = bitmapCapture
        )
    }

    private fun stopStream(streamState: StreamState): StreamState {
        XLog.d(getLog("stopStream", "Invoked"))

        val state = stopProjection(streamState)
        if (settingsReadOnly.checkAndChangeAutoChangePinOnStop().not())
            bitmapNotification.sentBitmapNotification(BitmapNotification.Type.START)

        return state.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun screenOff(streamState: StreamState): StreamState {
        XLog.d(getLog("screenOff", "Invoked"))

        return if (settingsReadOnly.stopOnSleep && streamState.isStreaming()) stopStream(streamState)
        else streamState
    }

    private fun destroy(streamState: StreamState): StreamState {
        XLog.d(getLog("destroy", "Invoked"))

        return stopProjection(streamState).copy(state = StreamState.State.DESTROYED)
    }

    private fun startStopFromWebPage(streamState: StreamState): StreamState {
        XLog.d(getLog("startStopFromWebPage", "Invoked"))

        if (streamState.isStreaming()) return stopStream(streamState)

        if (streamState.state == StreamState.State.SERVER_STARTED)
            return streamState.copy(state = StreamState.State.PERMISSION_PENDING)

        return streamState
    }

    private fun restartServer(streamState: StreamState, reason: RestartReason): StreamState {
        XLog.d(getLog("restartServer", "Invoked"))

        val state = stopProjection(streamState)

        when (reason) {
            is RestartReason.ConnectionChanged ->
                onEffect(AppStateMachine.Effect.ConnectionChanged)

            is RestartReason.SettingsChanged ->
                bitmapNotification.sentBitmapNotification(BitmapNotification.Type.RELOAD_PAGE)

            is RestartReason.NetworkSettingsChanged ->
                bitmapNotification.sentBitmapNotification(BitmapNotification.Type.NEW_ADDRESS)
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

    private fun componentError(streamState: StreamState, appError: AppError): StreamState {
        XLog.d(getLog("componentError", "Invoked"))

        return stopProjection(streamState).copy(state = StreamState.State.ERROR, appError = appError)
    }

    private fun recoverError(streamState: StreamState): StreamState {
        XLog.d(getLog("recoverError", "Invoked"))

        sendEvent(InternalEvent.DiscoverAddress)
        return streamState.copy(state = StreamState.State.RESTART_PENDING, appError = null)
    }

    private fun requestPublicState(streamState: StreamState): StreamState {
        XLog.d(getLog("requestPublicState", "Invoked"))

        onEffect(streamState.toPublicState())
        return streamState
    }
}