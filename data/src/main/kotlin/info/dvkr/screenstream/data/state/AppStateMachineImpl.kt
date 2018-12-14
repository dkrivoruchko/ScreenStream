package info.dvkr.screenstream.data.state

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.annotation.AnyThread
import androidx.annotation.Keep
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import info.dvkr.screenstream.data.httpserver.HttpServer
import info.dvkr.screenstream.data.httpserver.HttpServerImpl
import info.dvkr.screenstream.data.image.BitmapCapture
import info.dvkr.screenstream.data.image.BitmapNotification
import info.dvkr.screenstream.data.image.BitmapToJpeg
import info.dvkr.screenstream.data.model.*
import info.dvkr.screenstream.data.other.getTag
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.data.state.helper.BroadcastHelper
import info.dvkr.screenstream.data.state.helper.NetworkHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import timber.log.Timber
import java.net.InetSocketAddress
import kotlin.coroutines.CoroutineContext

class AppStateMachineImpl(
    context: Context,
    private val parentJob: Job,
    private val settingsReadOnly: SettingsReadOnly,
    private val projectionManager: MediaProjectionManager,
    appIconBitmap: Bitmap,
    onStatistic: (List<HttpClient>, List<TrafficPoint>) -> Unit,
    private val onEffect: (AppStateMachine.Effect) -> Unit
) : AppStateMachine, CoroutineScope {


    override val coroutineContext: CoroutineContext
        get() = parentJob + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Timber.tag(getTag("onCoroutineException")).e(throwable)
            onError(FatalError.CoroutineException)
        }

    private val applicationContext: Context = context.applicationContext
    private val bitmapChannel: Channel<Bitmap> = Channel(Channel.CONFLATED)
    private val jpegChannel: Channel<ByteArray> = Channel(Channel.CONFLATED)
    private val broadcastHelper = BroadcastHelper(applicationContext, ::onError)
    private val networkHelper = NetworkHelper(applicationContext, ::onError)
    private val bitmapNotification = BitmapNotification(applicationContext, appIconBitmap, bitmapChannel, ::onError)
    private val bitmapToJpeg = BitmapToJpeg(settingsReadOnly, bitmapChannel, jpegChannel, ::onError)
    private val httpServer: HttpServer

    @Keep private sealed class InternalEvent : AppStateMachine.Event() {
        @Keep object DiscoverServerAddress : InternalEvent()
        @Keep object StartHttpServer : InternalEvent()
        @Keep object ScreenOff : InternalEvent()
        @Keep object Destroy : InternalEvent()
        @Keep data class RestartHttpServer(val reason: RestartReason) : InternalEvent()
        @Keep data class ComponentError(val appError: AppError) : InternalEvent()

        override fun toString(): String = this::class.java.simpleName
    }

    @Keep private sealed class RestartReason(val msg: String) {
        @Keep class ConnectionChanged(msg: String) : RestartReason(msg)
        @Keep class SettingsChanged(msg: String) : RestartReason(msg)
        @Keep class NetworkSettingsChanged(msg: String) : RestartReason(msg)

        override fun toString(): String = "${this::class.java.simpleName}[$msg]"
    }

    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) {
            when (key) {
                Settings.Key.MJPEG_CHECK ->
                    RestartReason.SettingsChanged("disableMJPEGCheck: ${settingsReadOnly.disableMJPEGCheck}")

                Settings.Key.HTML_BACK_COLOR ->
                    RestartReason.SettingsChanged("htmlBackColor: ${settingsReadOnly.htmlBackColor}")

                Settings.Key.ENABLE_PIN ->
                    RestartReason.SettingsChanged("enablePin: ${settingsReadOnly.enablePin}")

                Settings.Key.PIN ->
                    RestartReason.SettingsChanged("pin: ${settingsReadOnly.pin}")

                Settings.Key.USE_WIFI_ONLY ->
                    RestartReason.NetworkSettingsChanged("useWiFiOnly: ${settingsReadOnly.useWiFiOnly}")

                Settings.Key.SERVER_PORT ->
                    RestartReason.NetworkSettingsChanged("severPort: ${settingsReadOnly.severPort}")

                else -> null
            }?.let { restartReason -> sendEvent(InternalEvent.RestartHttpServer(restartReason)) }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Timber.tag(getTag("MediaProjection.Callback")).d("onStop")
            sendEvent(AppStateMachine.Event.StopStream)
        }
    }

    @Throws(FatalError.ChannelException::class)
    override fun sendEvent(event: AppStateMachine.Event, timeout: Long) {
        if (timeout > 0) {
            Timber.tag(getTag("sendEvent [Timeout: $timeout]")).d("Event: $event")
            launch { delay(timeout); sendEvent(event) }
        } else {
            Timber.tag(getTag("sendEvent")).d("Event: $event")
            parentJob.isActive || return

            if (eventChannel.isClosedForSend) {
                Timber.tag(getTag("sendEvent")).e(IllegalStateException("Channel is ClosedForSend"))
            } else if (eventChannel.offer(event).not()) {
                Timber.tag(getTag("sendEvent")).e(IllegalStateException("Channel is full"))
                throw FatalError.ChannelException
            }
        }
    }

    private val eventChannel: SendChannel<AppStateMachine.Event> = actor(capacity = 8) {
        var streamState = StreamState()
        var previousStreamState: StreamState

        for (event in this) try {
            if (streamState.state == StreamState.State.ERROR &&
                event !in listOf(
                    InternalEvent.Destroy, AppStateMachine.Event.RequestPublicState, AppStateMachine.Event.RecoverError
                )
            ) {
                Timber.tag(this@AppStateMachineImpl.getTag("actor")).w("[State.ERROR] Skipping event: $event")
                continue
            }

            Timber.tag(this@AppStateMachineImpl.getTag("actor")).d("$streamState. Event: $event")
            previousStreamState = streamState
            streamState = when (event) {
                is InternalEvent.DiscoverServerAddress -> discoverServerAddress(streamState)
                is InternalEvent.StartHttpServer -> startHttpServer(streamState)

                is AppStateMachine.Event.StartStream ->
                    streamState.requireState(StreamState.State.SERVER_STARTED).also {
                        onEffect(AppStateMachine.Effect.RequestCastPermissions)
                    }.copy(state = StreamState.State.PERMISSION_REQUESTED)

                is AppStateMachine.Event.CastPermissionsDenied ->
                    streamState.requireState(StreamState.State.PERMISSION_REQUESTED)
                        .copy(state = StreamState.State.SERVER_STARTED)

                is AppStateMachine.Event.StartProjection -> startProjection(streamState, event.intent)
                is AppStateMachine.Event.StopStream -> stopStream(streamState)

                is InternalEvent.ScreenOff ->
                    if (settingsReadOnly.stopOnSleep && streamState.isStreaming()) stopStream(streamState) else streamState

                is InternalEvent.Destroy ->
                    streamState.stopProjectionIfStreaming(projectionCallback)
                        .copy(state = StreamState.State.DESTROYED)

                is InternalEvent.RestartHttpServer ->
                    restartHttpServer(streamState.stopProjectionIfStreaming(projectionCallback), event.reason)

                is InternalEvent.ComponentError ->
                    streamState.stopProjectionIfStreaming(projectionCallback)
                        .copy(state = StreamState.State.ERROR, appError = event.appError)

                is AppStateMachine.Event.RecoverError -> {
                    sendEvent(InternalEvent.DiscoverServerAddress)
                    streamState.copy(state = StreamState.State.CREATED, appError = null)
                }

                is AppStateMachine.Event.RequestPublicState -> {
                    onEffect(streamState.toPublicState())
                    streamState
                }

                else -> throw IllegalArgumentException("Unknown AppStateMachine.Event: $event")
            }

            Timber.tag(this@AppStateMachineImpl.getTag("actor")).d("New $streamState")
            if (streamState.isPublicStatePublishRequired(previousStreamState)) onEffect(streamState.toPublicState())

        } catch (throwable: Throwable) {
            Timber.tag(this@AppStateMachineImpl.getTag("actor")).e(throwable)
            onError(FatalError.ActorException)
        }
    }

    @WorkerThread
    private fun onError(appError: AppError) {
        Timber.tag(getTag("onError")).e("AppError: $appError")
        sendEvent(InternalEvent.ComponentError(appError))
    }

    init {
        Timber.tag(getTag("init")).d("Invoked")
        settingsReadOnly.registerChangeListener(settingsListener)
        broadcastHelper.registerReceiver(
            onScreenOff = { sendEvent(InternalEvent.ScreenOff) },
            onConnectionChanged = { sendEvent(InternalEvent.RestartHttpServer(RestartReason.ConnectionChanged(""))) }
        )
        bitmapToJpeg.start()
        httpServer = HttpServerImpl(applicationContext, jpegChannel, onStatistic, ::onError)
        sendEvent(InternalEvent.DiscoverServerAddress)
    }

    @AnyThread
    override fun destroy() {
        Timber.tag(getTag("destroy")).d("Invoked")
        sendEvent(InternalEvent.Destroy)
        settingsReadOnly.unregisterChangeListener(settingsListener)
        broadcastHelper.unregisterReceiver()
        eventChannel.close()
        bitmapToJpeg.stop()
        httpServer.stop()
        httpServer.destroy()
    }

    private fun discoverServerAddress(streamState: StreamState): StreamState {
        Timber.tag(getTag("discoverServerAddress")).d("Invoked")
        streamState.requireState(StreamState.State.CREATED, StreamState.State.SERVER_STARTED)

        val netInterfaces = networkHelper.getNetInterfaces(settingsReadOnly.useWiFiOnly)
        if (netInterfaces.isEmpty())
            return if (streamState.httpServerAddressAttempt < 3) {
                sendEvent(InternalEvent.DiscoverServerAddress, 1000)
                streamState.copy(httpServerAddressAttempt = streamState.httpServerAddressAttempt + 1)
            } else {
                Timber.tag(getTag("discoverServerAddress")).w("No address found")
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
        Timber.tag(getTag("startHttpServer")).d("Invoked")
        streamState.requireState(StreamState.State.SERVER_ADDRESS_DISCOVERED)
        require(streamState.httpServerAddress != null)

        httpServer.stop()
        httpServer.configure(settingsReadOnly)
        httpServer.start(streamState.httpServerAddress)
        bitmapNotification.sentBitmapNotification(BitmapNotification.Type.START)

        return streamState.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun startProjection(streamState: StreamState, intent: Intent): StreamState {
        Timber.tag(getTag("startProjection")).d("Invoked")
        streamState.requireState(StreamState.State.PERMISSION_REQUESTED)

        val mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, intent)
        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
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
        Timber.tag(getTag("stopStream")).d("Invoked")
        streamState.requireState(StreamState.State.STREAMING)

        return streamState.stopProjectionIfStreaming(projectionCallback).also {
            if (settingsReadOnly.autoChangePinOnStop().not())
                bitmapNotification.sentBitmapNotification(BitmapNotification.Type.START)
        }.copy(state = StreamState.State.SERVER_STARTED)
    }

    private fun restartHttpServer(streamState: StreamState, reason: RestartReason): StreamState {
        Timber.tag(getTag("restartHttpServer")).d("Invoked")
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
            state = StreamState.State.CREATED,
            netInterfaces = emptyList(),
            httpServerAddress = null,
            httpServerAddressAttempt = 0
        )
    }
}