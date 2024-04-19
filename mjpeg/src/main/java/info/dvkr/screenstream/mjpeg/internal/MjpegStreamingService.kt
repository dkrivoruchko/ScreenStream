package info.dvkr.screenstream.mjpeg.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.MjpegKoinScope
import info.dvkr.screenstream.mjpeg.MjpegModuleService
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.MjpegError
import info.dvkr.screenstream.mjpeg.ui.MjpegState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import kotlin.random.Random

@Scope(MjpegKoinScope::class)
@Scoped(binds = [MjpegStreamingService::class])
internal class MjpegStreamingService(
    @InjectedParam private val service: MjpegModuleService,
    @InjectedParam private val mutableMjpegStateFlow: MutableStateFlow<MjpegState>,
    private val networkHelper: NetworkHelper,
    private val mjpegSettings: MjpegSettings
) : HandlerThread("MJPEG-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback {

    private val powerManager: PowerManager = service.getSystemService(PowerManager::class.java)
    private val projectionManager = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper, this) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("MJPEG-HT_Dispatcher") }
    private val supervisorJob = SupervisorJob()
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(supervisorJob + coroutineDispatcher) }
    private val bitmapStateFlow = MutableStateFlow(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    private val httpServer by lazy(mode = LazyThreadSafetyMode.NONE) {
        HttpServer(service, mjpegSettings, bitmapStateFlow.asStateFlow(), ::sendEvent)
    }

    // All Volatiles vars must be write on this (WebRTC-HT) thread
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    // All Volatiles vars must be write on this (WebRTC-HT) thread

    // All vars must be read/write on this (WebRTC-HT) thread
    private var startBitmap: Bitmap? = null
    private var pendingServer: Boolean = true
    private var deviceConfiguration: Configuration = Configuration(service.resources.configuration)
    private var netInterfaces: List<MjpegNetInterface> = emptyList()
    private var clients: List<MjpegState.Client> = emptyList()
    private var slowClients: List<MjpegState.Client> = emptyList()
    private var traffic: List<MjpegState.TrafficPoint> = emptyList()
    private var isStreaming: Boolean = false
    private var waitingForPermission: Boolean = false
    private var mediaProjectionIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var bitmapCapture: BitmapCapture? = null
    private var currentError: MjpegError? = null
    private var previousError: MjpegError? = null
    // All vars must be read/write on this (WebRTC-HT) thread

    internal sealed class InternalEvent(priority: Int) : MjpegEvent(priority) {
        data class InitState(val clearIntent: Boolean = true) : InternalEvent(Priority.RESTART_IGNORE)
        data class DiscoverAddress(val reason: String, val attempt: Int) : InternalEvent(Priority.RESTART_IGNORE)
        data class StartServer(val interfaces: List<MjpegNetInterface>) : InternalEvent(Priority.RESTART_IGNORE)
        data object StartStream : InternalEvent(Priority.RESTART_IGNORE)
        data object StartStopFromWebPage : InternalEvent(Priority.RESTART_IGNORE)
        data object ScreenOff : InternalEvent(Priority.RESTART_IGNORE)
        data class ConfigurationChange(val newConfig: Configuration) : InternalEvent(Priority.RESTART_IGNORE) {
            override fun toString(): String = "ConfigurationChange"
        }
        data class Clients(val clients: List<MjpegState.Client>) : InternalEvent(Priority.RESTART_IGNORE)
        data class RestartServer(val reason: RestartReason) : InternalEvent(Priority.RESTART_IGNORE)
        data object UpdateStartBitmap : InternalEvent(Priority.RESTART_IGNORE)

        data class Error(val error: MjpegError) : InternalEvent(Priority.RECOVER_IGNORE)

        data class Destroy(val destroyJob: CompletableJob) : InternalEvent(Priority.DESTROY_IGNORE)
        data class Traffic(val time: Long, val traffic: List<MjpegState.TrafficPoint>) : InternalEvent(Priority.DESTROY_IGNORE) {
            override fun toString(): String = "Traffic(time=$time)"
        }
    }

    internal sealed class RestartReason(private val msg: String) {
        object ConnectionChanged : RestartReason("")
        class SettingsChanged(msg: String) : RestartReason(msg)
        class NetworkSettingsChanged(msg: String) : RestartReason(msg)

        override fun toString(): String = "${javaClass.simpleName}[$msg]"
    }

    private val componentCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = sendEvent(InternalEvent.ConfigurationChange(newConfig))
        override fun onLowMemory() = Unit
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.i(this@MjpegStreamingService.getLog("MediaProjection.Callback", "onStop"))
            sendEvent(MjpegEvent.Intentable.StopStream("MediaProjection.Callback"))
        }
    }

    init {
        XLog.d(getLog("init"))
    }

    @MainThread
    override fun start() {
        super.start()
        XLog.d(getLog("start"))

        mutableMjpegStateFlow.value = MjpegState()
        sendEvent(InternalEvent.InitState())

        coroutineScope.launch {
            if (mjpegSettings.data.value.enablePin && mjpegSettings.data.value.newPinOnAppStart) {
                mjpegSettings.updateData { copy(pin = randomPin()) }
            }
        }

        service.startListening(
            supervisorJob,
            onScreenOff = { sendEvent(InternalEvent.ScreenOff) },
            onConnectionChanged = { sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged)) }
        )

        mjpegSettings.data.map { it.htmlBackColor }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.UpdateStartBitmap)
        }
        mjpegSettings.data.map { it.enablePin }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.ENABLE_PIN.name)))
        }
        mjpegSettings.data.map { it.pin }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.PIN.name)))
        }
        mjpegSettings.data.map { it.blockAddress }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.BLOCK_ADDRESS.name)))
        }
        mjpegSettings.data.map { it.useWiFiOnly }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.USE_WIFI_ONLY.name)))
        }
        mjpegSettings.data.map { it.enableIPv6 }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.ENABLE_IPV6.name)))
        }
        mjpegSettings.data.map { it.enableLocalHost }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.ENABLE_LOCAL_HOST.name)))
        }
        mjpegSettings.data.map { it.localHostOnly }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.LOCAL_HOST_ONLY.name)))
        }
        mjpegSettings.data.map { it.serverPort }.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.SERVER_PORT.name)))
        }
    }

    @MainThread
    suspend fun destroyService() {
        XLog.d(getLog("destroyService"))

        wakeLock?.apply { if (isHeld) release() }
        supervisorJob.cancel()

        val destroyJob = Job()
        sendEvent(InternalEvent.Destroy(destroyJob))
        withTimeoutOrNull(3000) { destroyJob.join() } ?: run {
            XLog.w(getLog("destroyService", "Timeout"), IllegalStateException("destroy: Timeout 3"))
        }

        handler.removeCallbacksAndMessages(null)

        service.stopSelf()

        quit() // Only after everything else is destroyed
    }

    private var destroyPending: Boolean = false

    @AnyThread
    @Synchronized
    internal fun sendEvent(event: MjpegEvent, timeout: Long = 0) {
        if (destroyPending) {
            XLog.w(getLog("sendEvent", "Pending destroy: Ignoring event => $event"))
            return
        }
        if (event is InternalEvent.Destroy) destroyPending = true

        if (timeout > 0) XLog.d(getLog("sendEvent", "New event [Timeout: $timeout] => $event"))
        else XLog.d(getLog("sendEvent", "New event => $event"))

        if (event is InternalEvent.RestartServer) {
            handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
        }
        if (event is MjpegEvent.Intentable.RecoverError) {
            handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.RECOVER_IGNORE)
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.DESTROY_IGNORE)
        }

        handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking(Dispatchers.Unconfined) {
        XLog.v(this@MjpegStreamingService.getLog("handleMessage", "Message: $msg"))

        val event: MjpegEvent = msg.obj as MjpegEvent
        try {
            XLog.d(this@MjpegStreamingService.getLog("handleMessage", "Event [$event] Current state: [${getStateString()}]"))
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@MjpegStreamingService.getLog("processEvent.catch", cause.toString()))
            XLog.e(this@MjpegStreamingService.getLog("processEvent.catch", cause.toString()), cause)

            mediaProjectionIntent = null
            stopStream()

            currentError = if (cause is MjpegError) cause else MjpegError.UnknownError(cause)
        } finally {
            XLog.d(this@MjpegStreamingService.getLog("processEvent", "Done [$event] New state: [${getStateString()}]"))
            if (event is InternalEvent.Destroy) event.destroyJob.complete()
            publishState()
        }

        true
    }

    // On MJPEG-HT only
    private suspend fun processEvent(event: MjpegEvent) {
        when (event) {
            is InternalEvent.InitState -> {
                pendingServer = true
                deviceConfiguration = Configuration(service.resources.configuration)
                netInterfaces = emptyList()
                clients = emptyList()
                slowClients = emptyList()
                isStreaming = false
                waitingForPermission = false
                if (event.clearIntent) mediaProjectionIntent = null
                mediaProjection = null
                bitmapCapture = null

                currentError = null
            }

            is InternalEvent.DiscoverAddress -> {
                if (pendingServer.not()) httpServer.stop(false)

                val newInterfaces = networkHelper.getNetInterfaces(
                    mjpegSettings.data.value.useWiFiOnly,
                    mjpegSettings.data.value.enableIPv6,
                    mjpegSettings.data.value.enableLocalHost,
                    mjpegSettings.data.value.localHostOnly
                )

                if (newInterfaces.isNotEmpty()) {
                    sendEvent(InternalEvent.StartServer(newInterfaces))
                } else {
                    if (event.attempt < 3) {
                        sendEvent(InternalEvent.DiscoverAddress(event.reason, event.attempt + 1), 1000)
                    } else {
                        netInterfaces = emptyList()
                        clients = emptyList()
                        slowClients = emptyList()
                        currentError = MjpegError.AddressNotFoundException
                    }
                }
            }

            is InternalEvent.StartServer -> {
                if (pendingServer.not()) httpServer.stop(false)
                httpServer.start(event.interfaces.toList())

                if (mjpegSettings.data.value.htmlShowPressStart) bitmapStateFlow.value = getStartBitmap()

                netInterfaces = event.interfaces
                pendingServer = false
            }

            is InternalEvent.StartStopFromWebPage -> when {
                isStreaming -> sendEvent(MjpegEvent.Intentable.StopStream("StartStopFromWebPage"))
                pendingServer.not() && currentError == null -> waitingForPermission = true
            }

            is InternalEvent.StartStream -> {
                check(pendingServer.not()) { "MjpegEvent.StartStream: server is not ready" }

                mediaProjectionIntent?.let {
                    check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "MjpegEvent.StartStream: UPSIDE_DOWN_CAKE" }
                    sendEvent(MjpegEvent.StartProjection(it))
                } ?: run {
                    waitingForPermission = true
                }
            }

            is MjpegEvent.CastPermissionsDenied -> waitingForPermission = false

            is MjpegEvent.StartProjection ->
                if (pendingServer) {
                    waitingForPermission = false
                    XLog.w(getLog("MjpegEvent.StartProjection", "Server is not ready. Ignoring"))
                } else {
                    waitingForPermission = false
                    check(isStreaming.not()) { "MjpegEvent.StartProjection: Already streaming" }

                    service.startForeground()

                    val mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, event.intent).apply {
                        registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                    }

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) mediaProjectionIntent = event.intent
                    val bitmapCapture = BitmapCapture(service, mjpegSettings, mediaProjection, bitmapStateFlow) { error ->
                        sendEvent(InternalEvent.Error(error))
                    }
                    if (bitmapCapture.start()) service.registerComponentCallbacks(componentCallback)

                    @Suppress("DEPRECATION")
                    @SuppressLint("WakelockTimeout")
                    if (Build.MANUFACTURER !in listOf("OnePlus", "OPPO") && mjpegSettings.data.value.keepAwake) {
                        val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                        wakeLock = powerManager.newWakeLock(flags, "ScreenStream::MJPEG-Tag").apply { acquire() }
                    }

                    this@MjpegStreamingService.isStreaming = true
                    this@MjpegStreamingService.mediaProjection = mediaProjection
                    this@MjpegStreamingService.bitmapCapture = bitmapCapture
                }

            is MjpegEvent.Intentable.StopStream -> {
                stopStream()

                if (mjpegSettings.data.value.enablePin && mjpegSettings.data.value.autoChangePin)
                    mjpegSettings.updateData { copy(pin = randomPin()) }

                if (mjpegSettings.data.value.htmlShowPressStart) bitmapStateFlow.value = getStartBitmap()
            }

            is InternalEvent.ScreenOff -> if (isStreaming && mjpegSettings.data.value.stopOnSleep)
                sendEvent(MjpegEvent.Intentable.StopStream("ScreenOff"))

            is InternalEvent.ConfigurationChange -> {
                if (isStreaming) {
                    val configDiff = deviceConfiguration.diff(event.newConfig)
                    if (
                        configDiff and ActivityInfo.CONFIG_ORIENTATION != 0 || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0 ||
                        configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0 || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                    ) {
                        bitmapCapture?.resize()
                    } else {
                        XLog.d(getLog("configurationChange", "No change relevant for streaming. Ignoring."))
                    }
                } else {
                    XLog.d(getLog("configurationChange", "Not streaming. Ignoring."))
                }
                deviceConfiguration = Configuration(event.newConfig)
            }

            is InternalEvent.RestartServer -> {
                stopStream()
                waitingForPermission = false
                if (pendingServer) {
                    XLog.d(getLog("processEvent", "RestartServer: No running server."))
                    if (currentError == MjpegError.AddressNotFoundException) currentError = null
                } else {
                    httpServer.stop(event.reason is RestartReason.SettingsChanged)
                    sendEvent(InternalEvent.InitState(false))
                }
                sendEvent(InternalEvent.DiscoverAddress("RestartServer", 0))
            }

            InternalEvent.UpdateStartBitmap -> {
                startBitmap = null
                if (isStreaming.not() && mjpegSettings.data.value.htmlShowPressStart) bitmapStateFlow.value = getStartBitmap()
            }

            is MjpegEvent.Intentable.RecoverError -> {
                stopStream()
                httpServer.stop(true)

                handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
                handler.removeMessages(MjpegEvent.Priority.RECOVER_IGNORE)

                sendEvent(InternalEvent.InitState(true))
                sendEvent(InternalEvent.DiscoverAddress("RecoverError", 0))
            }

            is InternalEvent.Destroy -> {
                stopStream()
                httpServer.destroy()
                currentError = null
            }

            is InternalEvent.Error -> currentError = event.error

            is InternalEvent.Clients -> {
                clients = event.clients
                if (mjpegSettings.data.value.notifySlowConnections) {
                    val currentSlowClients = event.clients.filter { it.state == MjpegState.Client.State.SLOW_CONNECTION }.toList()
                    if (slowClients.containsAll(currentSlowClients).not()) {
                        mainHandler.post { Toast.makeText(service, R.string.mjpeg_slow_client_connection, Toast.LENGTH_LONG).show() }
                    }
                    slowClients = currentSlowClients
                }
            }

            is InternalEvent.Traffic -> traffic = event.traffic

            is MjpegEvent.CreateNewPin -> when {
                destroyPending -> XLog.i(
                    getLog("CreateNewPin", "DestroyPending. Ignoring"),
                    IllegalStateException("CreateNewPin: DestroyPending")
                )

                isStreaming -> XLog.i(getLog("CreateNewPin", "Streaming. Ignoring."), IllegalStateException("CreateNewPin: Streaming."))
                mjpegSettings.data.value.enablePin -> mjpegSettings.updateData { copy(pin = randomPin()) } // will restart server
            }

            else -> throw IllegalArgumentException("Unknown MjpegEvent: ${event::class.java}")
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream() {
        if (isStreaming) {
            service.unregisterComponentCallbacks(componentCallback)
            bitmapCapture?.destroy()
            bitmapCapture = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null

            isStreaming = false
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
        "Pending Dest/Server: $destroyPending/$pendingServer, Streaming:$isStreaming, WFP:$waitingForPermission, Clients:${clients.size}, Error:${currentError}"

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun publishState() {
        val state = MjpegState(
            isBusy = pendingServer || destroyPending || waitingForPermission || currentError != null,
            serverNetInterfaces = netInterfaces.map {
                MjpegState.ServerNetInterface(it.name, "http://${it.address.asString()}:${mjpegSettings.data.value.serverPort}")
            }.sortedBy { it.fullAddress },
            waitingCastPermission = waitingForPermission,
            isStreaming = isStreaming,
            pin = MjpegState.Pin(mjpegSettings.data.value.enablePin, mjpegSettings.data.value.pin, mjpegSettings.data.value.hidePinOnStart),
            clients = clients.toList(),
            traffic = traffic.toList(),
            error = currentError
        )

        mutableMjpegStateFlow.value = state

        if (previousError != currentError) {
            previousError = currentError
            currentError?.let { service.showErrorNotification(it) } ?: service.hideErrorNotification()
        }
    }

    private fun randomPin(): String = Random.nextInt(10).toString() + Random.nextInt(10).toString() +
            Random.nextInt(10).toString() + Random.nextInt(10).toString() +
            Random.nextInt(10).toString() + Random.nextInt(10).toString()

    private fun getStartBitmap(): Bitmap {
        startBitmap?.let { return it }

        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply {
            drawColor(mjpegSettings.data.value.htmlBackColor)
            val shader = LinearGradient(0F, 0F, 0F, 400F, Color.parseColor("#144A74"), Color.parseColor("#001D34"), Shader.TileMode.CLAMP);
            drawRoundRect(0F, 0F, 600F, 400F, 32F, 32F, Paint().apply { setShader(shader) })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; color = Color.WHITE }
        val logo = service.getFileFromAssets("logo.png")
            .run { BitmapFactory.decodeByteArray(this, 0, size) }
            .let { Bitmap.createScaledBitmap(it, 256, 256, true) }
        canvas.drawBitmap(logo, 172f, 16f, paint)
        val message = service.getString(R.string.mjpeg_start_image_text)
        val bounds = Rect().apply { paint.getTextBounds(message, 0, message.length, this) }
        canvas.drawText(message, (bitmap.width - bounds.width()) / 2f, 324f, paint)
        startBitmap = bitmap
        return bitmap
    }
}