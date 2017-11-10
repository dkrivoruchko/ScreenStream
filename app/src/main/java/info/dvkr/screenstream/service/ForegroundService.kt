package info.dvkr.screenstream.service


import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.support.annotation.Keep
import android.support.v4.app.NotificationCompat
import android.support.v7.content.res.AppCompatResources
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.RemoteViews
import android.widget.Toast
import com.cantrowitz.rxbroadcast.RxBroadcast
import com.jakewharton.rxrelay.BehaviorRelay
import com.jakewharton.rxrelay.PublishRelay
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.data.presenter.foreground.ForegroundPresenter
import info.dvkr.screenstream.data.presenter.foreground.ForegroundView
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.httpserver.HttpServer
import info.dvkr.screenstream.domain.settings.Settings
import info.dvkr.screenstream.ui.StartActivity
import kotlinx.android.synthetic.main.slow_connection_toast.view.*
import org.koin.android.ext.android.inject
import rx.BackpressureOverflow
import rx.Observable
import rx.Scheduler
import rx.functions.Action0
import rx.subscriptions.CompositeSubscription
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class ForegroundService : Service(), ForegroundView {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "info.dvkr.screenstream.service.NOTIFICATION_CHANNEL_01"
        private const val NOTIFICATION_START_STREAMING = 10
        private const val NOTIFICATION_STOP_STREAMING = 11

        private const val EXTRA_DATA = "EXTRA_DATA"
        private const val ACTION_START_STREAM = "ACTION_START_STREAM"
        const val ACTION_INIT = "ACTION_INIT"
        const val ACTION_START_ON_BOOT = "ACTION_START_ON_BOOT"

        fun getIntent(context: Context, action: String): Intent =
                Intent(context, ForegroundService::class.java).putExtra(EXTRA_DATA, action)

        fun getStartStreamIntent(context: Context, data: Intent): Intent {
            return Intent(context, ForegroundService::class.java)
                    .putExtra(EXTRA_DATA, ACTION_START_STREAM)
                    .putExtra(Intent.EXTRA_INTENT, data)
        }
    }

    sealed class LocalEvent : ForegroundView.ToEvent() {
        @Keep object StartService : LocalEvent()
        @Keep data class StartStream(val intent: Intent) : ForegroundView.ToEvent()
    }

    private val presenter: ForegroundPresenter by inject()
    private val settings: Settings by inject()
    private val eventScheduler: Scheduler by inject()
    private val eventBus: EventBus by inject()
    private val globalStatus: GlobalStatus by inject()
    private val imageNotify: ImageNotify by inject()
    private val jpegBytesStream: BehaviorRelay<ByteArray> by inject()

    private var isForegroundServiceInit: Boolean = false
    private val subscriptions = CompositeSubscription()
    private val fromEvents = PublishRelay.create<ForegroundView.FromEvent>()
    private val toEvents = PublishRelay.create<ForegroundView.ToEvent>()

    @Volatile private var mediaProjection: MediaProjection? = null
    @Volatile private var projectionCallback: MediaProjection.Callback? = null

    private val connectionEvents = PublishRelay.create<String>()
    private var counterStartHttpServer: Int = 0

    private val defaultWifiRegexArray: Array<Regex> = arrayOf(Regex("wlan\\d"), Regex("ap\\d"), Regex("wigig\\d"), Regex("softap\\.?\\d"))
    private val wifiRegexArray: Array<Regex> by lazy {
        val tetherId = Resources.getSystem().getIdentifier("config_tether_wifi_regexs", "array", "android")
        resources.getStringArray(tetherId).map { it.toRegex() }.toTypedArray()
    }

    private val wifiManager: WifiManager by lazy {
        getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // Base values
    private lateinit var baseFavicon: ByteArray
    private lateinit var baseLogo: ByteArray
    private lateinit var baseIndexHtml: String
    private lateinit var basePinRequestHtml: String
    private lateinit var pinRequestErrorMsg: String

    override fun fromEvent(): Observable<ForegroundView.FromEvent> = fromEvents.asObservable()

    override fun toEvent(event: ForegroundView.ToEvent) = toEvents.call(event)

    override fun toEvent(event: ForegroundView.ToEvent, timeout: Long) {
        Observable.just<ForegroundView.ToEvent>(event)
                .delay(timeout, TimeUnit.MILLISECONDS, eventScheduler)
                .subscribe { toEvent(it) }
                .also { subscriptions.add(it) }
    }

    override fun onCreate() {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] onCreate")

        presenter.attach(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Screen Stream Channel", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        toEvents.startWith(ForegroundService.LocalEvent.StartService)
                .observeOn(eventScheduler).subscribe { event ->
            Timber.d("[${Thread.currentThread().name} @${this.hashCode()}] toEvent: $event")

            when (event) {
                is ForegroundService.LocalEvent.StartService -> {
                    baseFavicon = getFavicon(applicationContext)
                    baseLogo = getLogo(applicationContext)
                    baseIndexHtml = getBaseIndexHtml(applicationContext)
                    basePinRequestHtml = getBasePinRequestHtml(applicationContext)
                    pinRequestErrorMsg = applicationContext.getString(R.string.html_wrong_pin)
                }

                is ForegroundView.ToEvent.StartHttpServer -> {
                    fromEvents.call(ForegroundView.FromEvent.StopHttpServer)

                    val interfaces = getInterfaces()
                    val serverAddress: InetSocketAddress
                    fromEvents.call(ForegroundView.FromEvent.CurrentInterfaces(interfaces))
                    if (settings.useWiFiOnly) {
                        if (interfaces.isEmpty()) {
                            if (counterStartHttpServer < 5) { // Scheduling one more try in 1 second
                                counterStartHttpServer++
                                toEvent(ForegroundView.ToEvent.StartHttpServer, 1000)
                            }
                            return@subscribe
                        }
                        counterStartHttpServer = 0
                        serverAddress = InetSocketAddress(interfaces.first().address, settings.severPort)
                    } else {
                        serverAddress = InetSocketAddress(settings.severPort)
                    }

                    fromEvents.call(ForegroundView.FromEvent.StartHttpServer(
                            serverAddress,
                            baseFavicon,
                            baseLogo,
                            baseIndexHtml,
                            basePinRequestHtml,
                            pinRequestErrorMsg,
                            jpegBytesStream.onBackpressureBuffer(2,
                                    Action0 { Timber.e("jpegBytesStream.onBackpressureBuffer - ON_OVERFLOW_DROP_OLDEST") },
                                    BackpressureOverflow.ON_OVERFLOW_DROP_OLDEST))
                    )
                }

                is ForegroundView.ToEvent.NotifyImage -> {
                    Observable.just(event.notifyType)
                            .map { notifyType -> imageNotify.getImage(notifyType) }
                            .subscribe { byteArray -> jpegBytesStream.call(byteArray) }
                }

                is ForegroundService.LocalEvent.StartStream -> {
                    val data = event.intent
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = projectionManager.getMediaProjection(Activity.RESULT_OK, data)
                    mediaProjection = projection
                    val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    fromEvents.call(ForegroundView.FromEvent.StartImageGenerator(windowManager.defaultDisplay, projection))
                    stopForeground(true)
                    startForeground(NOTIFICATION_STOP_STREAMING, getCustomNotification(NOTIFICATION_STOP_STREAMING))

                    projectionCallback = object : MediaProjection.Callback() {
                        override fun onStop() {
                            Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] ProjectionCallback: onStop")
                            eventBus.sendEvent(EventBus.GlobalEvent.StopStream())
                        }
                    }
                    projection.registerCallback(projectionCallback, null)
                }

                is ForegroundView.ToEvent.StopStream -> {
                    stopForeground(true)
                    stopMediaProjection()
                    startForeground(NOTIFICATION_START_STREAMING, getCustomNotification(NOTIFICATION_START_STREAMING))
                    if (event.isNotifyOnComplete)
                        fromEvents.call(ForegroundView.FromEvent.StopStreamComplete)
                }

                is ForegroundView.ToEvent.AppExit -> {
                    stopSelf()
                }

                is ForegroundView.ToEvent.CurrentInterfacesRequest -> {
                    fromEvents.call(ForegroundView.FromEvent.CurrentInterfaces(getInterfaces()))
                }

                is ForegroundView.ToEvent.Error -> {
                    Timber.e(event.error)
                    globalStatus.error.set(event.error)
                    startActivity(StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_ERROR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }

                is ForegroundView.ToEvent.SlowConnectionDetected -> {
                    val toast = Toast(applicationContext)
                    val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                    val toastView = inflater.inflate(R.layout.slow_connection_toast, null)
                    toastView.slowConnectionToastIcon.setImageDrawable(AppCompatResources.getDrawable(applicationContext, R.drawable.ic_service_notification_24dp))
                    toast.view = toastView
                    toast.duration = Toast.LENGTH_LONG
                    toast.show()
                }
            }
        }.also { subscriptions.add(it) }

        // Registering receiver for screen off messages and network & WiFi changes
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
        intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)

        RxBroadcast.fromBroadcast(applicationContext, intentFilter)
                .observeOn(eventScheduler)
                .map { it.action }
                .subscribe { action ->
                    Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] action: $action")
                    when (action) {
                        Intent.ACTION_SCREEN_OFF -> fromEvents.call(ForegroundView.FromEvent.ScreenOff)
                        WifiManager.WIFI_STATE_CHANGED_ACTION -> connectionEvents.call(WifiManager.WIFI_STATE_CHANGED_ACTION)
                        ConnectivityManager.CONNECTIVITY_ACTION -> connectionEvents.call(ConnectivityManager.CONNECTIVITY_ACTION)
                    }
                }.also { subscriptions.add(it) }

        connectionEvents.throttleWithTimeout(500, TimeUnit.MILLISECONDS, eventScheduler)
                .skip(1)
                .subscribe { _ ->
                    Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] connectionEvent")
                    if (settings.useWiFiOnly) {
                        counterStartHttpServer = 0
                        fromEvents.call(ForegroundView.FromEvent.HttpServerRestartRequest)
                    } else {
                        fromEvents.call(ForegroundView.FromEvent.CurrentInterfaces(getInterfaces()))
                    }
                }.also { subscriptions.add(it) }

        startForeground(NOTIFICATION_START_STREAMING, getCustomNotification(NOTIFICATION_START_STREAMING))
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val action = intent.getStringExtra(EXTRA_DATA)
        if (action == null) {
            Timber.e(IllegalStateException("ForegroundService:onStartCommand: action == null"))
        } else {
            Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] onStartCommand.action:  $action")
            when (action) {
                ACTION_INIT -> if (!isForegroundServiceInit) {
                    fromEvents.call(ForegroundView.FromEvent.Init)
                    isForegroundServiceInit = true
                }

                ACTION_START_ON_BOOT -> {
                    startActivity(StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_START_STREAM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }

                ACTION_START_STREAM ->
                    toEvents.call(ForegroundService.LocalEvent.StartStream(intent.getParcelableExtra(Intent.EXTRA_INTENT)))
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] onDestroy")

        fromEvents.call(ForegroundView.FromEvent.StopHttpServer)
        subscriptions.clear()
        stopForeground(true)
        stopMediaProjection()
        presenter.detach()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID)
        }

        super.onDestroy()
        System.exit(0)
    }

    private fun stopMediaProjection() {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] stopMediaProjection")

        mediaProjection?.apply { projectionCallback?.let { unregisterCallback(it) } }
        projectionCallback = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun getCustomNotification(notificationType: Int): Notification {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] getCustomNotification: $notificationType")

        val pendingMainActivityIntent = PendingIntent.getActivity(applicationContext, 0,
                StartActivity.getStartIntent(applicationContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                0)

        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.setCategory(Notification.CATEGORY_SERVICE)
        builder.priority = NotificationCompat.PRIORITY_MAX
        builder.setSmallIcon(R.drawable.ic_service_notification_24dp)
        builder.setWhen(0)

        when (notificationType) {
            NOTIFICATION_START_STREAMING -> {
                val startIntent = PendingIntent.getActivity(applicationContext, 1,
                        StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_START_STREAM),
                        PendingIntent.FLAG_UPDATE_CURRENT)

                val exitIntent = PendingIntent.getActivity(applicationContext, 3,
                        StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_EXIT),
                        PendingIntent.FLAG_UPDATE_CURRENT)

                val smallView = RemoteViews(packageName, R.layout.start_notification_small)
                smallView.setOnClickPendingIntent(R.id.linearLayoutStartNotificationSmall, pendingMainActivityIntent)
                smallView.setImageViewResource(R.id.imageViewStartNotificationSmallIconMain, R.drawable.ic_app_icon)
                smallView.setImageViewResource(R.id.imageViewStartNotificationSmallIconStart, R.drawable.ic_service_start_24dp)
                smallView.setOnClickPendingIntent(R.id.imageViewStartNotificationSmallIconStart, startIntent)
                builder.setCustomContentView(smallView)

                val bigView = RemoteViews(packageName, R.layout.start_notification_big)
                bigView.setOnClickPendingIntent(R.id.linearLayoutStartNotificationBig, pendingMainActivityIntent)
                bigView.setImageViewResource(R.id.imageViewStartNotificationBigIconMain, R.drawable.ic_app_icon)
                bigView.setImageViewResource(R.id.imageViewStartNotificationBigIconStart, R.drawable.ic_service_start_24dp)
                bigView.setImageViewResource(R.id.imageViewStartNotificationBigIconExit, R.drawable.ic_service_exit_24dp)
                bigView.setOnClickPendingIntent(R.id.linearLayoutStartNotificationBigStart, startIntent)
                bigView.setOnClickPendingIntent(R.id.linearLayoutStartNotificationBigExit, exitIntent)
                builder.setCustomBigContentView(bigView)
            }

            NOTIFICATION_STOP_STREAMING -> {
                val stopIntent = PendingIntent.getActivity(applicationContext, 2,
                        StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_STOP_STREAM),
                        PendingIntent.FLAG_UPDATE_CURRENT)

                val smallView = RemoteViews(packageName, R.layout.stop_notification_small)
                smallView.setOnClickPendingIntent(R.id.linearLayoutStopNotificationSmall, pendingMainActivityIntent)
                smallView.setImageViewResource(R.id.imageViewStopNotificationSmallIconMain, R.drawable.ic_app_icon)
                smallView.setImageViewResource(R.id.imageViewStopNotificationSmallIconStop, R.drawable.ic_service_stop_24dp)
                smallView.setOnClickPendingIntent(R.id.imageViewStopNotificationSmallIconStop, stopIntent)
                builder.setCustomContentView(smallView)

                val bigView = RemoteViews(packageName, R.layout.stop_notification_big)
                bigView.setOnClickPendingIntent(R.id.linearLayoutStopNotificationBig, pendingMainActivityIntent)
                bigView.setImageViewResource(R.id.imageViewStopNotificationBigIconMain, R.drawable.ic_app_icon)
                bigView.setImageViewResource(R.id.imageViewStopNotificationBigIconStop, R.drawable.ic_service_stop_24dp)
                bigView.setOnClickPendingIntent(R.id.linearLayoutStopNotificationBigStop, stopIntent)
                builder.setCustomBigContentView(bigView)
            }
        }

        return builder.build()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun getFileFromAssets(context: Context, fileName: String): ByteArray {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] getFileFromAssets: $fileName")
        context.assets.open(fileName).use { inputStream ->
            val fileBytes = ByteArray(inputStream.available())
            inputStream.read(fileBytes)
            return fileBytes
        }
    }

    private fun getFavicon(context: Context): ByteArray {
        val iconBytes = getFileFromAssets(context, "favicon.ico")
        if (iconBytes.isEmpty()) throw IllegalStateException("baseFavicon.ico is empty")
        return iconBytes
    }

    private fun getLogo(context: Context): ByteArray {
        val logoBytes = getFileFromAssets(context, "logo_big.png")
        if (logoBytes.isEmpty()) throw IllegalStateException("logo_big.png is empty")
        return logoBytes
    }

    private fun getBaseIndexHtml(context: Context): String {
        val htmlBytes = getFileFromAssets(context, "index.html")
        if (htmlBytes.isEmpty()) throw IllegalStateException("index.html is empty")
        return String(htmlBytes, Charset.defaultCharset())
                .replaceFirst(HttpServer.NO_MJPEG_SUPPORT_MESSAGE.toRegex(), context.getString(R.string.html_no_mjpeg_support))
    }

    private fun getBasePinRequestHtml(context: Context): String {
        val htmlBytes = getFileFromAssets(context, "pinrequest.html")
        if (htmlBytes.isEmpty()) throw IllegalStateException("pinrequest.html is empty")
        return String(htmlBytes, Charset.defaultCharset())
                .replaceFirst(HttpServer.STREAM_REQUIRE_PIN.toRegex(), context.getString(R.string.html_stream_require_pin))
                .replaceFirst(HttpServer.ENTER_PIN.toRegex(), context.getString(R.string.html_enter_pin))
                .replaceFirst(HttpServer.FOUR_DIGITS.toRegex(), context.getString(R.string.html_four_digits))
                .replaceFirst(HttpServer.SUBMIT_TEXT.toRegex(), context.getString(R.string.html_submit_text))
    }

    private fun getInterfaces(): List<EventBus.Interface> {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] getInterfaces")

        val interfaceList = ArrayList<EventBus.Interface>()
        try {
            val enumeration = NetworkInterface.getNetworkInterfaces()
            while (enumeration.hasMoreElements()) {
                val networkInterface = enumeration.nextElement()
                val enumIpAddr = networkInterface.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
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
            Timber.e(ex)
            if (wifiConnected()) interfaceList.add(EventBus.Interface("wlan0", getWiFiIpAddress()))
        }
        return interfaceList
    }

    private fun wifiConnected() = wifiManager.connectionInfo.ipAddress != 0

    private fun getWiFiIpAddress(): Inet4Address {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] getWiFiIpAddress")

        val ipInt = wifiManager.connectionInfo.ipAddress
        return InetAddress.getByAddress(ByteArray(4, { i -> (ipInt.shr(i * 8).and(255)).toByte() })) as Inet4Address
    }
}