package info.dvkr.screenstream.service


import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.annotation.Keep
import android.support.v4.app.NotificationCompat
import android.support.v7.content.res.AppCompatResources
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.RemoteViews
import android.widget.Toast
import com.jakewharton.rxrelay.BehaviorRelay
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.data.presenter.foreground.FgPresenter
import info.dvkr.screenstream.data.presenter.foreground.FgView
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.httpserver.HttpServer
import info.dvkr.screenstream.domain.settings.Settings
import info.dvkr.screenstream.domain.utils.Utils
import info.dvkr.screenstream.ui.StartActivity
import kotlinx.android.synthetic.main.slow_connection_toast.view.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FgService : Service(), FgView {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "info.dvkr.screenstream.service.NOTIFICATION_CHANNEL_01"
        private const val NOTIFICATION_START_STREAMING = 10
        private const val NOTIFICATION_STOP_STREAMING = 11

        private const val EXTRA_DATA = "EXTRA_DATA"
        private const val ACTION_START_STREAM = "ACTION_START_STREAM"
        const val ACTION_INIT = "ACTION_INIT"
        const val ACTION_START_ON_BOOT = "ACTION_START_ON_BOOT"

        fun getIntent(context: Context, action: String): Intent =
            Intent(context, FgService::class.java).putExtra(EXTRA_DATA, action)

        fun getStartStreamIntent(context: Context, data: Intent): Intent {
            return Intent(context, FgService::class.java)
                .putExtra(EXTRA_DATA, ACTION_START_STREAM)
                .putExtra(Intent.EXTRA_INTENT, data)
        }
    }

    @Keep sealed class LocalEvent : FgView.ToEvent() {
        @Keep object StartService : LocalEvent()
        @Keep class StartStream(val intent: Intent) : FgView.ToEvent()
    }

    private val presenter: FgPresenter by inject()
    private val settings: Settings by inject()
    private val eventBus: EventBus by inject()
    private val globalStatus: GlobalStatus by inject()
    private val imageNotify: ImageNotify by inject()
    private val jpegBytesStream: BehaviorRelay<ByteArray> by inject()

    private var isForegroundServiceInit: Boolean = false
    private val isConnectionEventScheduled = AtomicBoolean(false)
    private val isFirstNetworkEvent = AtomicBoolean(true)

    private lateinit var toEvents: SendChannel<FgView.ToEvent>

    @Volatile private var mediaProjection: MediaProjection? = null
    @Volatile private var projectionCallback: MediaProjection.Callback? = null

    private var counterStartHttpServer: Int = 0

    private val defaultWifiRegexArray: Array<Regex> = arrayOf(
        Regex("wlan\\d"),
        Regex("ap\\d"),
        Regex("wigig\\d"),
        Regex("softap\\.?\\d")
    )

    private val wifiRegexArray: Array<Regex> by lazy {
        val tetherId =
            Resources.getSystem().getIdentifier("config_tether_wifi_regexs", "array", "android")
        resources.getStringArray(tetherId).map { it.toRegex() }.toTypedArray()
    }

    // Registering receiver for screen off messages and network & WiFi changes
    private val intentFilter: IntentFilter by lazy {
        IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
    }

    private val broadCastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Timber.d("[${Utils.getLogPrefix(this)}] action: ${intent?.action}")
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> presenter.offer(FgView.FromEvent.ScreenOff)
                WifiManager.WIFI_STATE_CHANGED_ACTION,
                ConnectivityManager.CONNECTIVITY_ACTION ->
                    if (isConnectionEventScheduled.get().not()) {
                        isConnectionEventScheduled.set(true)
                        toEvent(FgView.ToEvent.ConnectionEvent, 1000)
                    }
            }
        }
    }

    private val wifiManager: WifiManager by lazy {
        getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val projectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val windowManager: WindowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val layoutInflater: LayoutInflater by lazy {
        getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    }

    // Base values
    private lateinit var baseFavicon: ByteArray
    private lateinit var baseLogo: ByteArray
    private lateinit var baseIndexHtml: String
    private lateinit var basePinRequestHtml: String
    private lateinit var pinRequestErrorMsg: String

    override fun toEvent(event: FgView.ToEvent, timeout: Long) {
        Timber.d("[${Utils.getLogPrefix(this)}] toEvent: ${event.javaClass.simpleName}, delay: $timeout")
        if (timeout > 0) {
            async {
                delay(timeout, TimeUnit.MILLISECONDS)
                toEvent(event)
            }
        } else {
            if (toEvents.isFull)
                IllegalStateException("FgService.toEvent: toEvents.isFull")
            if (toEvents.isClosedForSend.not()) toEvents.offer(event)
            else IllegalStateException("FgService.toEvent: toEvents.isClosedForSend")
        }
    }

    @SuppressLint("NewApi")
    override fun onCreate() {
        Timber.i("[${Utils.getLogPrefix(this)}] onCreate")

        presenter.attach(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "Screen Stream Channel", NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        toEvents = actor(CommonPool, Channel.UNLIMITED) {
            for (event in this) try {
                when (event) {
                    FgService.LocalEvent.StartService -> {
                        baseFavicon = getFavicon(applicationContext)
                        baseLogo = getLogo(applicationContext)
                        baseIndexHtml = getBaseIndexHtml(applicationContext)
                        basePinRequestHtml = getBasePinRequestHtml(applicationContext)
                        pinRequestErrorMsg = applicationContext.getString(R.string.html_wrong_pin)
                    }

                    FgView.ToEvent.StartHttpServer -> {
                        presenter.offer(FgView.FromEvent.StopHttpServer)

                        val interfaces = getInterfaces()
                        presenter.offer(FgView.FromEvent.CurrentInterfaces(interfaces))
                        if (settings.useWiFiOnly) {
                            if (interfaces.isEmpty()) {
                                if (counterStartHttpServer < 5) { // Scheduling one more try in 1 second
                                    counterStartHttpServer++
                                    toEvent(FgView.ToEvent.StartHttpServer, 1000)
                                } else {
                                    toEvent(FgView.ToEvent.Error(NoSuchElementException()))
                                }
                            } else {
                                counterStartHttpServer = 0
                                presenter.offer(
                                    FgView.FromEvent.StartHttpServer(
                                        InetSocketAddress(interfaces.first().address, settings.severPort),
                                        baseFavicon, baseLogo, baseIndexHtml, basePinRequestHtml, pinRequestErrorMsg
                                    )
                                )
                            }
                        } else {
                            presenter.offer(
                                FgView.FromEvent.StartHttpServer(
                                    InetSocketAddress(settings.severPort),
                                    baseFavicon, baseLogo, baseIndexHtml, basePinRequestHtml, pinRequestErrorMsg
                                )
                            )
                        }
                    }

                    FgView.ToEvent.ConnectionEvent -> {
                        isConnectionEventScheduled.set(false)
                        if (isFirstNetworkEvent.get()) {
                            isFirstNetworkEvent.set(false)
                        } else if (settings.useWiFiOnly) {
                            counterStartHttpServer = 0
                            presenter.offer(FgView.FromEvent.HttpServerRestartRequest)
                        } else {
                            presenter.offer(FgView.FromEvent.CurrentInterfaces(getInterfaces()))
                        }
                    }

                    is FgView.ToEvent.NotifyImage -> {
                        jpegBytesStream.call(imageNotify.getImage(event.notifyType))
                        jpegBytesStream.call(imageNotify.getImage(event.notifyType))
                        jpegBytesStream.call(imageNotify.getImage(event.notifyType))
                    }

                    is FgService.LocalEvent.StartStream -> {
                        val projection = projectionManager.getMediaProjection(Activity.RESULT_OK, event.intent)
                        mediaProjection = projection
                        presenter.offer(
                            FgView.FromEvent.StartImageGenerator(windowManager.defaultDisplay, projection)
                        )
                        stopForeground(true)
                        startForeground(NOTIFICATION_STOP_STREAMING, getCustomNotification(NOTIFICATION_STOP_STREAMING))

                        projectionCallback = object : MediaProjection.Callback() {
                            override fun onStop() {
                                Timber.w("[${Utils.getLogPrefix(this)}] ProjectionCallback: onStop")
                                launch(CommonPool) { eventBus.send(EventBus.GlobalEvent.StopStream) }
                            }
                        }
                        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                    }

                    is FgView.ToEvent.StopStream -> {
                        stopForeground(true)
                        stopMediaProjection()
                        startForeground(
                            NOTIFICATION_START_STREAMING, getCustomNotification(NOTIFICATION_START_STREAMING)
                        )
                        if (event.isNotifyOnComplete) presenter.offer(FgView.FromEvent.StopStreamComplete)
                    }

                    FgView.ToEvent.AppExit -> {
                        stopSelf()
                    }

                    FgView.ToEvent.CurrentInterfacesRequest -> {
                        presenter.offer(FgView.FromEvent.CurrentInterfaces(getInterfaces()))
                    }

                    is FgView.ToEvent.Error -> {
                        when (event.error) {
                            is NoSuchElementException, is BindException, is UnsupportedOperationException ->
                                Timber.d(event.error)
                            else -> Timber.w(event.error)
                        }
                        globalStatus.error.set(event.error)
                        startActivity(
                            StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_ERROR)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }

                    FgView.ToEvent.SlowConnectionDetected -> runBlocking(UI) {
                        val toastView = layoutInflater.inflate(R.layout.slow_connection_toast, null)
                        val drawable =
                            AppCompatResources.getDrawable(applicationContext, R.drawable.ic_service_notification_24dp)
                        toastView.slowConnectionToastIcon.setImageDrawable(drawable)
                        Toast(applicationContext).apply {
                            view = toastView
                            duration = Toast.LENGTH_LONG
                        }.show()
                    }
                }
            } catch (ex: RuntimeException) {
                Timber.e(ex)
            }
        }

        try {
            toEvent(FgService.LocalEvent.StartService, 0)
        } catch (t: Throwable) {
            Timber.e(t, "FgService.LocalEvent.StartService")
        }

        registerReceiver(broadCastReceiver, intentFilter)

        startForeground(NOTIFICATION_START_STREAMING, getCustomNotification(NOTIFICATION_START_STREAMING))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_DATA)
        if (action == null) {
            Timber.e(IllegalStateException("FgService:onStartCommand: action == null"))
        } else {
            Timber.i("[${Utils.getLogPrefix(this)}] onStartCommand.action: $action")
            when (action) {
                ACTION_INIT -> if (!isForegroundServiceInit) {
                    presenter.offer(FgView.FromEvent.Init)
                    isForegroundServiceInit = true
                }

                ACTION_START_ON_BOOT ->
                    startActivity(
                        StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_START_STREAM)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )

                ACTION_START_STREAM ->
                    toEvent(FgService.LocalEvent.StartStream(intent.getParcelableExtra(Intent.EXTRA_INTENT)))
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        Timber.i("[${Utils.getLogPrefix(this)}] onDestroy")

        unregisterReceiver(broadCastReceiver)

        presenter.offer(FgView.FromEvent.StopHttpServer)

        stopForeground(true)
        stopMediaProjection()
        presenter.detach()

        toEvents.close()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID)
        }

        super.onDestroy()
        System.exit(0)
    }

    private fun stopMediaProjection() {
        Timber.i("[${Utils.getLogPrefix(this)}] stopMediaProjection")

        mediaProjection?.apply { projectionCallback?.let { unregisterCallback(it) } }
        projectionCallback = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun getCustomNotification(notificationType: Int): Notification {
        Timber.i("[${Utils.getLogPrefix(this)}] getCustomNotification: $notificationType")

        val pendingMainActivityIntent = PendingIntent.getActivity(
            applicationContext, 0,
            StartActivity.getStartIntent(applicationContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            0
        )

        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID).apply {
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setCategory(Notification.CATEGORY_SERVICE)
            priority = NotificationCompat.PRIORITY_MAX
            setSmallIcon(R.drawable.ic_service_notification_24dp)
            setWhen(0)
        }

        when (notificationType) {
            NOTIFICATION_START_STREAMING -> {
                val startIntent = PendingIntent.getActivity(
                    applicationContext, 1,
                    StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_START_STREAM),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                builder.setCustomContentView(
                    RemoteViews(packageName, R.layout.start_notification_small).apply {
                        setOnClickPendingIntent(R.id.linearLayoutStartNotificationSmall, pendingMainActivityIntent)
                        setImageViewResource(R.id.imageViewStartNotificationSmallIconMain, R.drawable.ic_app_icon)
                        setImageViewResource(
                            R.id.imageViewStartNotificationSmallIconStart, R.drawable.ic_service_start_24dp
                        )
                        setOnClickPendingIntent(R.id.imageViewStartNotificationSmallIconStart, startIntent)
                    })

                val exitIntent = PendingIntent.getActivity(
                    applicationContext, 3,
                    StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_EXIT),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                builder.setCustomBigContentView(
                    RemoteViews(packageName, R.layout.start_notification_big).apply {
                        setOnClickPendingIntent(R.id.linearLayoutStartNotificationBig, pendingMainActivityIntent)
                        setImageViewResource(R.id.imageViewStartNotificationBigIconMain, R.drawable.ic_app_icon)
                        setImageViewResource(
                            R.id.imageViewStartNotificationBigIconStart, R.drawable.ic_service_start_24dp
                        )
                        setImageViewResource(
                            R.id.imageViewStartNotificationBigIconExit, R.drawable.ic_service_exit_24dp
                        )
                        setOnClickPendingIntent(R.id.linearLayoutStartNotificationBigStart, startIntent)
                        setOnClickPendingIntent(R.id.linearLayoutStartNotificationBigExit, exitIntent)
                    })
            }

            NOTIFICATION_STOP_STREAMING -> {
                val stopIntent = PendingIntent.getActivity(
                    applicationContext, 2,
                    StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_STOP_STREAM),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                builder.setCustomContentView(
                    RemoteViews(packageName, R.layout.stop_notification_small).apply {
                        setOnClickPendingIntent(R.id.linearLayoutStopNotificationSmall, pendingMainActivityIntent)
                        setImageViewResource(R.id.imageViewStopNotificationSmallIconMain, R.drawable.ic_app_icon)
                        setImageViewResource(
                            R.id.imageViewStopNotificationSmallIconStop, R.drawable.ic_service_stop_24dp
                        )
                        setOnClickPendingIntent(R.id.imageViewStopNotificationSmallIconStop, stopIntent)
                    })

                builder.setCustomBigContentView(
                    RemoteViews(packageName, R.layout.stop_notification_big).apply {
                        setOnClickPendingIntent(R.id.linearLayoutStopNotificationBig, pendingMainActivityIntent)
                        setImageViewResource(R.id.imageViewStopNotificationBigIconMain, R.drawable.ic_app_icon)
                        setImageViewResource(R.id.imageViewStopNotificationBigIconStop, R.drawable.ic_service_stop_24dp)
                        setOnClickPendingIntent(R.id.linearLayoutStopNotificationBigStop, stopIntent)
                    })
            }
        }

        return builder.build()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun getFileFromAssets(context: Context, fileName: String): ByteArray {
        Timber.i("[${Utils.getLogPrefix(this)}] getFileFromAssets: $fileName")
        context.assets.open(fileName).use { inputStream ->
            val fileBytes = ByteArray(inputStream.available())
            inputStream.read(fileBytes)
            return fileBytes
        }
    }

    private fun getFavicon(context: Context): ByteArray {
        val iconBytes = getFileFromAssets(context, "favicon.ico")
        iconBytes.isNotEmpty() || throw IllegalStateException("baseFavicon.ico is empty")
        return iconBytes
    }

    private fun getLogo(context: Context): ByteArray {
        val logoBytes = getFileFromAssets(context, "logo_big.png")
        logoBytes.isNotEmpty() || throw IllegalStateException("logo_big.png is empty")
        return logoBytes
    }

    private fun getBaseIndexHtml(context: Context): String {
        val htmlBytes = getFileFromAssets(context, "index.html")
        htmlBytes.isNotEmpty() || throw IllegalStateException("index.html is empty")
        return String(htmlBytes, Charset.defaultCharset())
            .replaceFirst(
                HttpServer.NO_MJPEG_SUPPORT_MESSAGE.toRegex(),
                context.getString(R.string.html_no_mjpeg_support)
            )
    }

    private fun getBasePinRequestHtml(context: Context): String {
        val htmlBytes = getFileFromAssets(context, "pinrequest.html")
        htmlBytes.isNotEmpty() || throw IllegalStateException("pinrequest.html is empty")
        return String(htmlBytes, Charset.defaultCharset())
            .replaceFirst(
                HttpServer.STREAM_REQUIRE_PIN.toRegex(), context.getString(R.string.html_stream_require_pin)
            )
            .replaceFirst(
                HttpServer.ENTER_PIN.toRegex(), context.getString(R.string.html_enter_pin)
            )
            .replaceFirst(
                HttpServer.FOUR_DIGITS.toRegex(), context.getString(R.string.html_four_digits)
            )
            .replaceFirst(
                HttpServer.SUBMIT_TEXT.toRegex(), context.getString(R.string.html_submit_text)
            )
    }

    private fun getInterfaces(): List<EventBus.Interface> {
        Timber.i("[${Utils.getLogPrefix(this)}] getInterfaces")

        val interfaceList = ArrayList<EventBus.Interface>()
        try {
            for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
                for (inetAddress in networkInterface.inetAddresses) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address)
                        if (settings.useWiFiOnly) {
                            if (defaultWifiRegexArray.any { it.matches(networkInterface.displayName) } ||
                                wifiRegexArray.any { it.matches(networkInterface.displayName) }) {
                                interfaceList.add(EventBus.Interface(networkInterface.displayName, inetAddress))
                                return interfaceList
                            }
                        } else {
                            interfaceList.add(EventBus.Interface(networkInterface.displayName, inetAddress))
                        }
                }
            }
        } catch (ex: Throwable) {
            Timber.i(ex)
            if (wifiConnected()) interfaceList.add(EventBus.Interface("wlan0", getWiFiIpAddress()))
        }
        return interfaceList
    }

    private fun wifiConnected() = wifiManager.connectionInfo.ipAddress != 0

    private fun getWiFiIpAddress(): Inet4Address {
        Timber.w("[${Utils.getLogPrefix(this)}] getWiFiIpAddress")

        val ipInt = wifiManager.connectionInfo.ipAddress
        return InetAddress.getByAddress(
            ByteArray(4, { i -> (ipInt.shr(i * 8).and(255)).toByte() })
        ) as Inet4Address
    }
}