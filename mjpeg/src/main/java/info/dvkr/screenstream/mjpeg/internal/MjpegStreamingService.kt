package info.dvkr.screenstream.mjpeg.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.view.Display
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.NotificationsManager
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.*
import info.dvkr.screenstream.mjpeg.databinding.ToastMjpegSlowConnectionBinding
import info.dvkr.screenstream.mjpeg.internal.server.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Scope(MjpegKoinScope::class)
@Scoped(binds = [MjpegStreamingService::class])
internal class MjpegStreamingService(
    @InjectedParam override val scope: org.koin.core.scope.Scope,
    @InjectedParam private val service: MjpegService,
    private val mjpegSettings: MjpegSettings,
    private val mjpegStateFlowProvider: MjpegStateFlowProvider,
) : HandlerThread("MJPEG-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback, KoinScopeComponent {

    private val powerManager: PowerManager = service.getSystemService(PowerManager::class.java)
    private val projectionManager = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private val notificationsManager: NotificationsManager by inject()

    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper, this) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("MJPEG-HT_Dispatcher") }
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(SupervisorJob() + coroutineDispatcher) }

    private val broadcastHelper = BroadcastHelper.getInstance(service)
    private val connectivityHelper = ConnectivityHelper.getInstance(service)
    private val networkHelper: NetworkHelper by inject(mode = LazyThreadSafetyMode.NONE) { parametersOf(service) }
    private val notificationBitmap: NotificationBitmap by inject(mode = LazyThreadSafetyMode.NONE) { parametersOf(service) }
    private val bitmapStateFlow = MutableStateFlow(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    private val httpServer by lazy(mode = LazyThreadSafetyMode.NONE) {
        HttpServer(service, mjpegSettings, bitmapStateFlow.asStateFlow(), notificationBitmap, ::sendEvent)
    }

    private val pendingEvents = Collections.synchronizedList(mutableListOf<MjpegEvent>()) // For logging only

    // All Volatiles vars must be write on this (WebRTC-HT) thread
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null
    // All Volatiles vars must be write on this (WebRTC-HT) thread

    // All vars must be read/write on this (WebRTC-HT) thread
    private var pendingServer: Boolean = true
    private var deviceConfiguration: Configuration = Configuration(service.resources.configuration)
    private var netInterfaces: List<MjpegState.NetInterface> = emptyList()
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
        internal data class InitState(@JvmField val clearIntent: Boolean = true) : InternalEvent(Priority.RESTART_IGNORE)
        internal data class DiscoverAddress(@JvmField val attempt: Int) : InternalEvent(Priority.RESTART_IGNORE)
        internal data class StartServer(@JvmField val interfaces: List<MjpegState.NetInterface>) : InternalEvent(Priority.RESTART_IGNORE)
        internal data object StartStream : InternalEvent(Priority.RESTART_IGNORE)
        internal data object StartStopFromWebPage : InternalEvent(Priority.RESTART_IGNORE)
        internal data object ScreenOff : InternalEvent(Priority.RESTART_IGNORE)
        internal data class ConfigurationChange(@JvmField val newConfig: Configuration) : InternalEvent(Priority.RESTART_IGNORE)
        internal data class Clients(@JvmField val clients: List<MjpegState.Client>) : InternalEvent(Priority.RESTART_IGNORE)
        internal data class RestartServer(@JvmField val reason: RestartReason) : InternalEvent(Priority.RESTART_IGNORE)

        internal data class Error(@JvmField val error: MjpegError) : InternalEvent(Priority.RECOVER_IGNORE)

        internal data class Destroy(@JvmField val latch: CountDownLatch) : InternalEvent(Priority.DESTROY_IGNORE)
        internal data class Traffic(@JvmField val traffic: List<MjpegState.TrafficPoint>) : InternalEvent(Priority.DESTROY_IGNORE) {
            override fun toString(): String = Traffic::class.java.simpleName
        }
    }

    internal sealed class RestartReason(private val msg: String) {
        class ConnectionChanged(msg: String) : RestartReason(msg)
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

        sendEvent(InternalEvent.InitState())

        coroutineScope.launch {
            if (mjpegSettings.enablePinFlow.first() && mjpegSettings.newPinOnAppStartFlow.first()) mjpegSettings.setPin(randomPin())
        }

        broadcastHelper.startListening(
            onScreenOff = { sendEvent(InternalEvent.ScreenOff) },
            onConnectionChanged = { sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged("BroadcastHelper"))) }
        )

        connectivityHelper.startListening(coroutineScope) {
            sendEvent(InternalEvent.RestartServer(RestartReason.ConnectionChanged("ConnectivityHelper")))
        }

        mjpegSettings.htmlEnableButtonsFlow.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.HTML_ENABLE_BUTTONS.name)))
        }
        mjpegSettings.htmlBackColorFlow.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.HTML_BACK_COLOR.name)))
        }
        mjpegSettings.enablePinFlow.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.ENABLE_PIN.name)))
        }
        mjpegSettings.pinFlow.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.PIN.name)))
        }
        mjpegSettings.blockAddressFlow.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.SettingsChanged(MjpegSettings.Key.BLOCK_ADDRESS.name)))
        }
        mjpegSettings.useWiFiOnlyFlow.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.USE_WIFI_ONLY.name)))
        }
        mjpegSettings.enableIPv6Flow.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.ENABLE_IPV6.name)))
        }
        mjpegSettings.enableLocalHostFlow.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.ENABLE_LOCAL_HOST.name)))
        }
        mjpegSettings.localHostOnlyFlow.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.LOCAL_HOST_ONLY.name)))
        }
        mjpegSettings.serverPortFlow.listenForChange(coroutineScope, 1) {
            sendEvent(InternalEvent.RestartServer(RestartReason.NetworkSettingsChanged(MjpegSettings.Key.SERVER_PORT.name)))
        }
    }

    @MainThread
    override fun destroy() {
        XLog.d(getLog("destroy"))

        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        broadcastHelper.stopListening()
        connectivityHelper.stopListening()
        coroutineScope.cancel()

        val latch = CountDownLatch(1)
        sendEvent(InternalEvent.Destroy(latch))

        runCatching {
            if (latch.await(500, TimeUnit.MILLISECONDS).not())
                XLog.w(getLog("destroy", "Timeout"), IllegalStateException("destroy: Timeout"))
        }

        handler.removeCallbacksAndMessages(null)
        pendingEvents.clear()

        notificationsManager.hideForegroundNotification(service)
        notificationsManager.hideErrorNotification()

        service.stopSelf()

        quit() // Only after everything else is destroyed
    }

    private var destroyPending: Boolean = false

    @AnyThread
    @Synchronized
    internal fun sendEvent(event: MjpegEvent, timeout: Long = 0) {
        if (destroyPending) {
            val msg = "Pending destroy: Ignoring event => $event"
            XLog.w(getLog("sendEvent", msg))
            XLog.d(getLog("sendEvent", msg), IllegalStateException("allowEvent: $msg"))
            return
        }
        if (event is InternalEvent.Destroy) destroyPending = true

        if (timeout > 0) XLog.d(getLog("sendEvent", "New event [Timeout: $timeout] => $event"))
        else XLog.d(getLog("sendEvent", "New event => $event"))

        if (event is InternalEvent.RestartServer) {
            handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
            pendingEvents.removeAll { it.priority == MjpegEvent.Priority.RESTART_IGNORE }
        }
        if (event is MjpegEvent.Intentable.RecoverError) {
            handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.RECOVER_IGNORE)
            pendingEvents.removeAll { it.priority == MjpegEvent.Priority.RESTART_IGNORE }
            pendingEvents.removeAll { it.priority == MjpegEvent.Priority.RECOVER_IGNORE }
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(MjpegEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(MjpegEvent.Priority.DESTROY_IGNORE)
            pendingEvents.removeAll { it.priority == MjpegEvent.Priority.RESTART_IGNORE }
            pendingEvents.removeAll { it.priority == MjpegEvent.Priority.RECOVER_IGNORE }
            pendingEvents.removeAll { it.priority == MjpegEvent.Priority.DESTROY_IGNORE }
        }

        handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
        pendingEvents.add(event)
        XLog.d(getLog("sendEvent", "Pending events: $pendingEvents"))
    }

    override fun handleMessage(msg: Message): Boolean {
        XLog.v(getLog("handleMessage", "Message: $msg"))

        val event: MjpegEvent = msg.obj as MjpegEvent
        try {
            XLog.d(getLog("processEvent", "Event [$event] Current state: [${getStateString()}]"))
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
                    check(pendingServer) { "MjpegEvent.StartStream: server is already started" }

                    val (useWiFiOnly, enableIPv6) = runBlocking {
                        Pair(mjpegSettings.useWiFiOnlyFlow.first(), mjpegSettings.enableIPv6Flow.first())
                    }
                    val (enableLocalHost, localHostOnly) = runBlocking {
                        Pair(mjpegSettings.enableLocalHostFlow.first(), mjpegSettings.localHostOnlyFlow.first())
                    }

                    val newInterfaces = networkHelper.getNetInterfaces(useWiFiOnly, enableIPv6, enableLocalHost, localHostOnly)

                    if (newInterfaces.isNotEmpty()) {
                        sendEvent(InternalEvent.StartServer(newInterfaces))
                    } else {
                        if (event.attempt < 3) {
                            sendEvent(InternalEvent.DiscoverAddress(event.attempt + 1), 1000)
                        } else {
                            netInterfaces = emptyList()
                            clients = emptyList()
                            slowClients = emptyList()
                            currentError = MjpegError.AddressNotFoundException
                        }
                    }
                }

                is InternalEvent.StartServer -> {
                    check(pendingServer) { "MjpegEvent.StartStream: server is already started" }

                    runBlocking { withTimeoutOrNull(300) { httpServer.stop().await() } }
                    httpServer.start(event.interfaces.toList())

                    if (runBlocking { mjpegSettings.htmlShowPressStartFlow.first() })
                        bitmapStateFlow.value = notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.START)

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

                is MjpegEvent.StartProjection -> {
                    waitingForPermission = false
                    check(pendingServer.not()) { "MjpegEvent.StartProjection: server is not ready" }
                    check(isStreaming.not()) { "MjpegEvent.StartProjection: Already streaming" }

                    notificationsManager.showForegroundNotification(
                        service, MjpegEvent.Intentable.StopStream("User action: Notification").toIntent(service)
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        check(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0) {
                            "MjpegEvent.StartProjection: Service is not FOREGROUND"
                        }

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
                    if (runBlocking { mjpegSettings.keepAwakeFlow.first() }) {
                        val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                        wakeLock = powerManager.newWakeLock(flags, "ScreenStream::MJPEG-Tag").apply { acquire() }
                    }

                    this.isStreaming = true
                    this.mediaProjection = mediaProjection
                    this.bitmapCapture = bitmapCapture
                }

                is MjpegEvent.Intentable.StopStream -> {
                    stopStream()

                    runBlocking {
                        if (mjpegSettings.enablePinFlow.first() && mjpegSettings.autoChangePinFlow.first())
                            mjpegSettings.setPin(randomPin())
                    }

                    if (runBlocking { mjpegSettings.htmlShowPressStartFlow.first() })
                        bitmapStateFlow.value = notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.START)
                }

                is InternalEvent.ScreenOff -> if (isStreaming && runBlocking { mjpegSettings.stopOnSleepFlow.first() })
                    sendEvent(MjpegEvent.Intentable.StopStream("ScreenOff"))

                is InternalEvent.ConfigurationChange -> {
                    if (isStreaming) {
                        val configDiff = deviceConfiguration.diff(event.newConfig)
                        if (
                            configDiff and ActivityInfo.CONFIG_ORIENTATION != 0 || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0 ||
                            configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0 || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                        ) {
                            //TODO Maybe add user settings about stop on config/network/change
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) bitmapCapture?.restart()
                            else sendEvent(MjpegEvent.Intentable.StopStream("ConfigurationChange"))
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
                    if (pendingServer) {
                        XLog.d(getLog("processEvent", "RestartServer: No running server."))
                        if (currentError == MjpegError.AddressNotFoundException) currentError = null
                    } else {
                        runBlocking { withTimeoutOrNull(300) { httpServer.stop().await() } }
                        sendEvent(InternalEvent.InitState(false))
                    }
                    sendEvent(InternalEvent.DiscoverAddress(0))
                }

                is MjpegEvent.Intentable.RecoverError -> {
                    stopStream()
                    runBlocking { withTimeoutOrNull(300) { httpServer.stop().await() } }
                    sendEvent(InternalEvent.InitState(true))
                    sendEvent(InternalEvent.DiscoverAddress(0))
                }

                is InternalEvent.Destroy -> {
                    stopStream()
                    runBlocking { withTimeoutOrNull(300) { httpServer.destroy().await() } }
                    currentError = null
                    event.latch.countDown()
                }

                is InternalEvent.Error -> currentError = event.error

                is InternalEvent.Clients -> {
                    clients = event.clients
                    if (runBlocking { mjpegSettings.notifySlowConnectionsFlow.first() }) {
                        val currentSlowClients = event.clients.filter { it.state == MjpegState.Client.State.SLOW_CONNECTION }.toList()
                        if (slowClients.containsAll(currentSlowClients).not()) showSlowConnectionToast()
                        slowClients = currentSlowClients
                    }
                }

                is InternalEvent.Traffic -> traffic = event.traffic

                else -> throw IllegalArgumentException("Unknown MjpegEvent: ${event::class.java}")
            }
        } catch (cause: Throwable) {
            XLog.e(getLog("processEvent.catch", cause.message))
            XLog.e(getLog("processEvent.catch", cause.message), cause)

            wakeLock?.apply { if (isHeld) release() }
            wakeLock = null

            currentError = MjpegError.UnknownError(cause)
        } finally {
            runCatching { pendingEvents.removeAt(0) }
            XLog.d(getLog("processEvent", "Done [$event] New state: [${getStateString()}] Pending events: $pendingEvents"))
            publishState()
        }

        return true
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream() {
        if (isStreaming) {
            wakeLock?.apply { if (isHeld) release() }
            wakeLock = null

            service.unregisterComponentCallbacks(componentCallback)
            bitmapCapture?.destroy()
            bitmapCapture = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            notificationsManager.hideForegroundNotification(service)

            isStreaming = false
        } else {
            XLog.d(getLog("stopStream", "Not streaming. Ignoring."))
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun getStateString() =
        "Pending Dest/Server: $destroyPending/$pendingServer, Streaming:$isStreaming, WFP:$waitingForPermission, Clients:${clients.size}"

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun publishState() {
        val isBusy = pendingServer || destroyPending || currentError != null
        val state = MjpegState(isBusy, waitingForPermission, isStreaming, netInterfaces, clients.toList(), traffic.toList(), currentError)

        mjpegStateFlowProvider.mutableMjpegStateFlow.value = state
        mjpegStateFlowProvider.mutableAppStateFlow.value = state.toAppState()

        if (previousError != currentError) {
            previousError = currentError
            currentError?.let {
                val message = it.toString(service)
                notificationsManager.showErrorNotification(service, message, MjpegEvent.Intentable.RecoverError.toIntent(service))
            } ?: run {
                notificationsManager.hideErrorNotification()
            }
        }
    }

    @Suppress("DEPRECATION")
    private val windowContext: Context by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            service
        } else {
            val display = ContextCompat.getSystemService(service, DisplayManager::class.java)!!.getDisplay(Display.DEFAULT_DISPLAY)
            service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_TOAST, null)
        }
    }

    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }

    @Suppress("DEPRECATION")
    private fun showSlowConnectionToast() {
        mainHandler.post {
            val layoutInflater = ContextCompat.getSystemService(windowContext, LayoutInflater::class.java)!!
            val binding = ToastMjpegSlowConnectionBinding.inflate(layoutInflater)
            val drawable = AppCompatResources.getDrawable(windowContext, R.drawable.mjpeg_ic_toast_24dp)
            binding.ivToastSlowConnection.setImageDrawable(drawable)
            Toast(windowContext).apply { view = binding.root; duration = Toast.LENGTH_LONG }.show()
        }
    }

    private fun randomPin(): String = Random.nextInt(10).toString() + Random.nextInt(10).toString() +
            Random.nextInt(10).toString() + Random.nextInt(10).toString() +
            Random.nextInt(10).toString() + Random.nextInt(10).toString()
}