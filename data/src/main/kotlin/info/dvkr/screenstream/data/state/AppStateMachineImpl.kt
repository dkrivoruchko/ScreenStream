package info.dvkr.screenstream.data.state

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
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
import info.dvkr.screenstream.data.state.helper.MediaProjectionHelper
import info.dvkr.screenstream.data.state.helper.NetworkHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import java.net.InetSocketAddress
import kotlin.coroutines.CoroutineContext

class AppStateMachineImpl(
    context: Context,
    private val parentJob: Job,
    private val settingsReadOnly: SettingsReadOnly,
    appIconBitmap: Bitmap,
    onStatistic: (List<HttpClient>, List<TrafficPoint>) -> Unit,
    private val onEffect: (AppStateMachine.Effect) -> Unit
) : AppStateMachine, CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = parentJob + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
            onError(FatalError.CoroutineException)
        }

    private val applicationContext: Context = context.applicationContext
    private val bitmapChannel: Channel<Bitmap> = Channel(Channel.CONFLATED)
    private val jpegChannel: Channel<ByteArray> = Channel(Channel.CONFLATED)
    private val mediaProjectionHelper = MediaProjectionHelper(context) { sendEvent(AppStateMachine.Event.StopStream) }
    private val broadcastHelper = BroadcastHelper(context, ::onError)
    private val networkHelper = NetworkHelper(context, ::onError)
    private val bitmapNotification = BitmapNotification(context, appIconBitmap, bitmapChannel, ::onError)
    private val bitmapToJpeg = BitmapToJpeg(settingsReadOnly, bitmapChannel, jpegChannel, ::onError)
    private val appHttpServer: AppHttpServer

    private sealed class InternalEvent : AppStateMachine.Event() {
        object DiscoverServerAddress : InternalEvent()
        object StartHttpServer : InternalEvent()
        object ScreenOff : InternalEvent()
        object Destroy : InternalEvent()
        object StartStopFromWebPage : InternalEvent()
        data class RestartHttpServer(val reason: RestartReason) : InternalEvent()
        data class ComponentError(val appError: AppError) : InternalEvent()

        override fun toString(): String = this::class.java.simpleName
    }

    private sealed class RestartReason(val msg: String) {
        class ConnectionChanged(msg: String) : RestartReason(msg)
        class SettingsChanged(msg: String) : RestartReason(msg)
        class NetworkSettingsChanged(msg: String) : RestartReason(msg)

        override fun toString(): String = "${this::class.java.simpleName}[$msg]"
    }

    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) {
            if (key in arrayOf(Settings.Key.HTML_BACK_COLOR, Settings.Key.ENABLE_PIN, Settings.Key.PIN))
                sendEvent(InternalEvent.RestartHttpServer(RestartReason.SettingsChanged(key)))

            if (key in arrayOf(Settings.Key.USE_WIFI_ONLY, Settings.Key.SERVER_PORT))
                sendEvent(InternalEvent.RestartHttpServer(RestartReason.NetworkSettingsChanged(key)))
        }
    }

    @Throws(FatalError.ChannelException::class)
    override fun sendEvent(event: AppStateMachine.Event, timeout: Long) {
        if (timeout > 0) {
            XLog.d(getLog("sendEvent[Timeout: $timeout]", "Event: $event"))
            launch { delay(timeout); sendEvent(event) }
        } else {
            XLog.d(getLog("sendEvent", "Event: $event"))
            parentJob.isActive || return

            if (eventChannel.isClosedForSend) {
                XLog.e(getLog("sendEvent"), IllegalStateException("Channel is ClosedForSend"))
            } else if (eventChannel.offer(event).not()) {
                XLog.e(getLog("sendEvent"), IllegalStateException("Channel is full"))
                throw FatalError.ChannelException
            }
        }
    }

    private fun skipEvent(state: StreamState.State, newEvent: AppStateMachine.Event): Boolean =
        (state == StreamState.State.ERROR && newEvent !in listOf(
            AppStateMachineImpl.InternalEvent.Destroy,
            AppStateMachine.Event.RequestPublicState,
            AppStateMachine.Event.RecoverError
        ))
                ||
                (state == StreamState.State.RESTART_PENDING && newEvent !in listOf(
                    AppStateMachineImpl.InternalEvent.DiscoverServerAddress, AppStateMachine.Event.RecoverError
                ))
                ||
                (state == StreamState.State.SERVER_STARTED && newEvent in listOf(AppStateMachine.Event.StopStream))
                ||
                (state == StreamState.State.STREAMING && (
                        newEvent is AppStateMachine.Event.StartProjection || newEvent is AppStateMachine.Event.StartStream
                        )
                        )

    private val eventChannel: SendChannel<AppStateMachine.Event> = actor(capacity = 8) {
        var streamState = StreamState()
        var previousStreamState: StreamState

        for (event in this) try {
            if (skipEvent(streamState.state, event)) {
                XLog.w(this@AppStateMachineImpl.getLog("actor", "[${streamState.state}] Skipping event: $event"))
                continue
            }

            XLog.i(this@AppStateMachineImpl.getLog("actor", "$streamState. Event: $event"))
            previousStreamState = streamState
            streamState = when (event) {
                is InternalEvent.DiscoverServerAddress -> discoverServerAddress(streamState)
                is InternalEvent.StartHttpServer -> startHttpServer(streamState)

                is AppStateMachine.Event.StartStream -> {
                    streamState.requireState(StreamState.State.SERVER_STARTED)
                    onEffect(AppStateMachine.Effect.RequestCastPermissions)
                    streamState
                }

                is AppStateMachine.Event.StartProjection -> startProjection(streamState, event.intent)
                is AppStateMachine.Event.StopStream -> stopStream(streamState)

                is InternalEvent.ScreenOff ->
                    if (settingsReadOnly.stopOnSleep && streamState.isStreaming()) stopStream(streamState) else streamState

                is InternalEvent.Destroy -> stopProjection(streamState).copy(state = StreamState.State.DESTROYED)

                is InternalEvent.StartStopFromWebPage -> when {
                    streamState.isStreaming() -> stopStream(streamState)
                    streamState.state == StreamState.State.SERVER_STARTED -> {
                        onEffect(AppStateMachine.Effect.RequestCastPermissions)
                        streamState
                    }
                    else -> streamState
                }

                is InternalEvent.RestartHttpServer -> restartHttpServer(stopProjection(streamState), event.reason)

                is InternalEvent.ComponentError ->
                    stopProjection(streamState).copy(state = StreamState.State.ERROR, appError = event.appError)

                is AppStateMachine.Event.RecoverError -> {
                    sendEvent(InternalEvent.DiscoverServerAddress)
                    streamState.copy(state = StreamState.State.RESTART_PENDING, appError = null)
                }

                is AppStateMachine.Event.RequestPublicState -> {
                    onEffect(streamState.toPublicState())
                    streamState
                }

                else -> throw IllegalArgumentException("Unknown AppStateMachine.Event: $event")
            }

            XLog.i(this@AppStateMachineImpl.getLog("actor", "New $streamState"))
            if (streamState.isPublicStatePublishRequired(previousStreamState)) onEffect(streamState.toPublicState())

        } catch (throwable: Throwable) {
            XLog.e(this@AppStateMachineImpl.getLog("actor"), throwable)
            onError(FatalError.ActorException)
        }
    }

    @WorkerThread
    private fun onError(appError: AppError) {
        XLog.e(getLog("onError", "AppError: $appError"))
        sendEvent(InternalEvent.ComponentError(appError))
    }

    init {
        XLog.d(getLog("init", "Invoked"))
        settingsReadOnly.registerChangeListener(settingsListener)
        broadcastHelper.registerReceiver(
            onScreenOff = { sendEvent(InternalEvent.ScreenOff) },
            onConnectionChanged = { sendEvent(InternalEvent.RestartHttpServer(RestartReason.ConnectionChanged(""))) }
        )
        bitmapToJpeg.start()
        appHttpServer = AppHttpServerImpl(
            HttpServerFiles(applicationContext, settingsReadOnly),
            jpegChannel,
            { sendEvent(InternalEvent.StartStopFromWebPage) },
            onStatistic,
            ::onError
        )
        sendEvent(InternalEvent.DiscoverServerAddress)
    }

    @AnyThread
    override fun destroy() {
        XLog.d(getLog("destroy", "Invoked"))
        sendEvent(InternalEvent.Destroy)
        settingsReadOnly.unregisterChangeListener(settingsListener)
        broadcastHelper.unregisterReceiver()
        eventChannel.close()
        bitmapToJpeg.stop()
        appHttpServer.stop()
    }

    private fun discoverServerAddress(streamState: StreamState): StreamState {
        XLog.d(getLog("discoverServerAddress", "Invoked"))
        streamState.requireState(
            StreamState.State.CREATED, StreamState.State.SERVER_STARTED, StreamState.State.RESTART_PENDING
        )

        val netInterfaces = networkHelper.getNetInterfaces(settingsReadOnly.useWiFiOnly)
        if (netInterfaces.isEmpty())
            return if (streamState.httpServerAddressAttempt < 3) {
                sendEvent(InternalEvent.DiscoverServerAddress, 1000)
                streamState.copy(httpServerAddressAttempt = streamState.httpServerAddressAttempt + 1)
            } else {
                XLog.w(getLog("discoverServerAddress", "No address found"))
                streamState.copy(
                    state = StreamState.State.ERROR,
                    netInterfaces = emptyList(),
                    httpServerAddress = null,
                    httpServerAddressAttempt = 0,
                    appError = FixableError.AddressNotFoundException
                )
            }

        sendEvent(InternalEvent.StartHttpServer)
        val httpServerAddress =
            if (settingsReadOnly.useWiFiOnly) {
                InetSocketAddress(netInterfaces.first().address, settingsReadOnly.severPort)
            } else {
                InetSocketAddress(settingsReadOnly.severPort)
            }

        return streamState.copy(
            state = StreamState.State.SERVER_ADDRESS_DISCOVERED,
            netInterfaces = netInterfaces,
            httpServerAddress = httpServerAddress,
            httpServerAddressAttempt = 0
        )
    }

    private fun startHttpServer(streamState: StreamState): StreamState {
        XLog.d(getLog("startHttpServer", "Invoked"))
        streamState.requireState(StreamState.State.SERVER_ADDRESS_DISCOVERED)
        require(streamState.httpServerAddress != null)

        appHttpServer.stop()
        appHttpServer.start(streamState.httpServerAddress)
        bitmapNotification.sentBitmapNotification(BitmapNotification.Type.START)

        return streamState.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun startProjection(streamState: StreamState, intent: Intent): StreamState {
        XLog.d(getLog("startProjection", "Invoked"))
        streamState.requireState(StreamState.State.SERVER_STARTED)

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
        streamState.requireState(StreamState.State.STREAMING)

        return stopProjection(streamState).also {
            if (settingsReadOnly.autoChangePinOnStop().not())
                bitmapNotification.sentBitmapNotification(BitmapNotification.Type.START)
        }.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun restartHttpServer(streamState: StreamState, reason: RestartReason): StreamState {
        XLog.d(getLog("restartHttpServer", "Invoked"))
        when (reason) {
            is RestartReason.ConnectionChanged ->
                onEffect(AppStateMachine.Effect.ConnectionChanged)

            is RestartReason.SettingsChanged ->
                bitmapNotification.sentBitmapNotification(BitmapNotification.Type.RELOAD_PAGE)

            is RestartReason.NetworkSettingsChanged ->
                bitmapNotification.sentBitmapNotification(BitmapNotification.Type.NEW_ADDRESS)
        }

        if (streamState.state == StreamState.State.ERROR) sendEvent(AppStateMachine.Event.RecoverError)
        else sendEvent(InternalEvent.DiscoverServerAddress, 1000)

        return streamState.copy(
            state = StreamState.State.RESTART_PENDING,
            netInterfaces = emptyList(),
            httpServerAddress = null,
            httpServerAddressAttempt = 0
        )
    }

    private fun stopProjection(streamState: StreamState): StreamState {
        XLog.d(getLog("stopProjection", "Invoked"))
        if (streamState.isStreaming()) {
            streamState.bitmapCapture?.stop()
            mediaProjectionHelper.stopMediaProjection(streamState.mediaProjection)
        }

        return streamState.copy(mediaProjection = null, bitmapCapture = null)
    }

}