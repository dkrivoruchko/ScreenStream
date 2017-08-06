package info.dvkr.screenstream.service


import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.support.annotation.Keep
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.LayoutInflater
import android.widget.RemoteViews
import android.widget.Toast
import com.cantrowitz.rxbroadcast.RxBroadcast
import com.crashlytics.android.Crashlytics
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ScreenStreamApp
import info.dvkr.screenstream.model.*
import info.dvkr.screenstream.model.image.ImageGeneratorImpl
import info.dvkr.screenstream.presenter.ForegroundServicePresenter
import info.dvkr.screenstream.ui.StartActivity
import kotlinx.android.synthetic.main.slow_connection_toast.view.*
import rx.Observable
import rx.Scheduler
import rx.functions.Action1
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ForegroundService : Service(), ForegroundServiceView {
    companion object {
        private const val TAG = "ForegroundService"
        private const val NOTIFICATION_CHANNEL_ID = "info.dvkr.screenstream.service.NOTIFICATION_CHANNEL_01"
        private const val NOTIFICATION_START_STREAMING = 10
        private const val NOTIFICATION_STOP_STREAMING = 11

        private const val EXTRA_DATA = "EXTRA_DATA"
        private const val ACTION_START_STREAM = "ACTION_START_STREAM"
        const val ACTION_INIT = "ACTION_INIT"
        const val ACTION_START_ON_BOOT = "ACTION_START_ON_BOOT"

        fun getIntent(context: Context, action: String): Intent {
            return Intent(context, ForegroundService::class.java).putExtra(EXTRA_DATA, action)
        }

        fun getStartStreamIntent(context: Context, data: Intent): Intent {
            return Intent(context, ForegroundService::class.java)
                    .putExtra(EXTRA_DATA, ACTION_START_STREAM)
                    .putExtra(Intent.EXTRA_INTENT, data)
        }
    }

    sealed class LocalEvent : ForegroundServiceView.ToEvent() {
        @Keep class StartService : LocalEvent()
        @Keep data class StartStream(val intent: Intent) : ForegroundServiceView.ToEvent()
    }

    @Inject internal lateinit var presenter: ForegroundServicePresenter
    @Inject internal lateinit var settings: Settings
    @Inject internal lateinit var eventScheduler: Scheduler
    @Inject internal lateinit var eventBus: EventBus
    @Inject internal lateinit var globalStatus: GlobalStatus
    @Inject internal lateinit var imageNotify: ImageNotify

    private var isForegroundServiceInit: Boolean = false
    private val subscriptions = CompositeSubscription()
    private val fromEvents = PublishSubject.create<ForegroundServiceView.FromEvent>()
    private val toEvents = PublishSubject.create<ForegroundServiceView.ToEvent>()

    private val jpegBytesStream = BehaviorSubject.create<ByteArray>()
    private @Volatile var mediaProjection: MediaProjection? = null
    private @Volatile var projectionCallback: MediaProjection.Callback? = null
    private @Volatile var imageGenerator: ImageGenerator? = null
    private val connectionEvents = PublishSubject.create<String>()

    // Base values
    private lateinit var baseFavicon: ByteArray
    private lateinit var baseIndexHtml: String
    private lateinit var basePinRequestHtml: String
    private lateinit var pinRequestErrorMsg: String

    override fun fromEvent(): Observable<ForegroundServiceView.FromEvent> = fromEvents.asObservable()

    override fun toEvent(event: ForegroundServiceView.ToEvent) = toEvents.onNext(event)

    override fun toEvent(event: ForegroundServiceView.ToEvent, timeout: Long) {
        Observable.just<ForegroundServiceView.ToEvent>(event)
                .delay(timeout, TimeUnit.MILLISECONDS, eventScheduler)
                .subscribe { toEvent(it) }
    }

    override fun onCreate() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")

        (application as ScreenStreamApp).appComponent().plusActivityComponent().inject(this)
        presenter.attach(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Screen Stream Channel", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)?.createNotificationChannel(channel)
        }

        subscriptions.add(toEvents.startWith(ForegroundService.LocalEvent.StartService())
                .observeOn(eventScheduler).subscribe { event ->

            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] toEvent: " + event.javaClass.simpleName)

            when (event) {
                is ForegroundService.LocalEvent.StartService -> {
                    baseFavicon = getFavicon(applicationContext)
                    baseIndexHtml = getBaseIndexHtml(applicationContext)
                    basePinRequestHtml = getBasePinRequestHtml(applicationContext)
                    pinRequestErrorMsg = applicationContext.getString(R.string.html_wrong_pin)
                }

                is ForegroundServiceView.ToEvent.StartHttpServer -> {
                    fromEvents.onNext(ForegroundServiceView.FromEvent.StartHttpServer(
                            baseFavicon,
                            baseIndexHtml,
                            basePinRequestHtml,
                            pinRequestErrorMsg,
                            jpegBytesStream.asObservable())
                    )
                }

                is ForegroundServiceView.ToEvent.NotifyImage -> {
                    Observable.just(event.notifyType).observeOn(Schedulers.computation())
                            .map { notifyType -> imageNotify.getImage(notifyType) }
                            .subscribe { byteArray -> jpegBytesStream.onNext(byteArray) }
                }

                is ForegroundService.LocalEvent.StartStream -> {
                    val data = event.intent
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = projectionManager.getMediaProjection(Activity.RESULT_OK, data)
                    if (null == projection) IllegalStateException(TAG + ": mediaProjection == null")
                    mediaProjection = projection
                    imageGenerator = ImageGeneratorImpl(
                            applicationContext,
                            projection,
                            eventScheduler,
                            eventBus,
                            globalStatus,
                            settings.resizeFactor,
                            settings.jpegQuality,
                            Action1 { imageByteArray -> jpegBytesStream.onNext(imageByteArray) })

                    stopForeground(true)
                    startForeground(NOTIFICATION_STOP_STREAMING, getCustomNotification(NOTIFICATION_STOP_STREAMING))

                    projectionCallback = object : MediaProjection.Callback() {
                        override fun onStop() {
                            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] ProjectionCallback")
                            eventBus.sendEvent(EventBus.GlobalEvent.StopStream())
                        }
                    }
                    projection.registerCallback(projectionCallback, null)
                }

                is ForegroundServiceView.ToEvent.StopStream -> {
                    stopForeground(true)
                    stopMediaProjection()
                    startForeground(NOTIFICATION_START_STREAMING, getCustomNotification(NOTIFICATION_START_STREAMING))
                    if (event.isNotifyOnComplete)
                        fromEvents.onNext(ForegroundServiceView.FromEvent.StopStreamComplete())
                }

                is ForegroundServiceView.ToEvent.AppExit -> {
                    stopSelf()
                }

                is ForegroundServiceView.ToEvent.CurrentInterfacesRequest -> {
                    fromEvents.onNext(ForegroundServiceView.FromEvent.CurrentInterfaces(getInterfaces()))
                }

                is ForegroundServiceView.ToEvent.Error -> {
                    if (BuildConfig.DEBUG_MODE) Log.e(TAG, event.error.toString())
                    Crashlytics.logException(event.error)
                    globalStatus.error = event.error
                    startActivity(StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_ERROR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }

                is ForegroundServiceView.ToEvent.SlowConnectionDetected -> {
                    val toast = Toast(applicationContext)
                    val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                    val toastView = inflater.inflate(R.layout.slow_connection_toast, null)
                    toastView.slowConnectionToastIcon.setImageDrawable(ContextCompat.getDrawable(applicationContext, R.drawable.ic_service_notification_24dp))
                    toast.view = toastView
                    toast.duration = Toast.LENGTH_LONG
                    toast.show()
                }
            }
        })

        // Registering receiver for screen off messages and WiFi changes
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
        intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)

        subscriptions.add(RxBroadcast.fromBroadcast(applicationContext, intentFilter)
                .map<String>
                { it.action }
                .observeOn(eventScheduler)
                .subscribe
                {
                    action ->
                    if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Action: " + action)
                    when (action) {
                        Intent.ACTION_SCREEN_OFF -> fromEvents.onNext(ForegroundServiceView.FromEvent.ScreenOff())
                        WifiManager.WIFI_STATE_CHANGED_ACTION -> connectionEvents.onNext(WifiManager.WIFI_STATE_CHANGED_ACTION)
                        ConnectivityManager.CONNECTIVITY_ACTION -> connectionEvents.onNext(ConnectivityManager.CONNECTIVITY_ACTION)
                    }
                })

        subscriptions.add(connectionEvents
                .throttleWithTimeout(500, TimeUnit.MILLISECONDS, eventScheduler)
                .subscribe
                { _ -> fromEvents.onNext(ForegroundServiceView.FromEvent.CurrentInterfaces(getInterfaces())) }
        )

        startForeground(NOTIFICATION_START_STREAMING, getCustomNotification(NOTIFICATION_START_STREAMING))
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Done")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onStartCommand")

        if (null == intent) {
            if (BuildConfig.DEBUG_MODE) Log.e(TAG, "onStartCommand: Intent == null")
            Crashlytics.logException(IllegalStateException(TAG + " onStartCommand: Intent == null"))
            return Service.START_NOT_STICKY
        }

        val action = intent.getStringExtra(EXTRA_DATA)
        if (null == action) {
            if (BuildConfig.DEBUG_MODE) Log.e(TAG, "onStartCommand: action == null")
            Crashlytics.logException(IllegalStateException(TAG + " onStartCommand: action == null"))
            return Service.START_NOT_STICKY
        }

        when (action) {
            ACTION_INIT -> if (!isForegroundServiceInit) {
                fromEvents.onNext(ForegroundServiceView.FromEvent.Init())
                isForegroundServiceInit = true
            }

            ACTION_START_ON_BOOT -> {
                startActivity(StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_START_STREAM))
            }

            ACTION_START_STREAM ->
                toEvents.onNext(ForegroundService.LocalEvent.StartStream(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)))
        }

        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: StartService")
        fromEvents.onNext(ForegroundServiceView.FromEvent.StopHttpServer())
        subscriptions.clear()
        stopForeground(true)
        stopMediaProjection()
        presenter.detach()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)?.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID)
        }

        super.onDestroy()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: Done")
        System.exit(0)
    }

// ======================================================================================================

    private fun stopMediaProjection() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] stopMediaProjection")
        mediaProjection?.apply {
            projectionCallback?.let { unregisterCallback(it) }
            stop()
        }
        projectionCallback = null
        mediaProjection = null
        imageGenerator?.stop()
        imageGenerator = null
    }

    private fun getCustomNotification(notificationType: Int): Notification {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] getCustomNotification:$notificationType")

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
                smallView.setImageViewResource(R.id.imageViewStartNotificationSmallIconMain, R.drawable.ic_app)
                smallView.setImageViewResource(R.id.imageViewStartNotificationSmallIconStart, R.drawable.ic_service_start_24dp)
                smallView.setOnClickPendingIntent(R.id.imageViewStartNotificationSmallIconStart, startIntent)
                builder.setCustomContentView(smallView)

                val bigView = RemoteViews(packageName, R.layout.start_notification_big)
                bigView.setOnClickPendingIntent(R.id.linearLayoutStartNotificationBig, pendingMainActivityIntent)
                bigView.setImageViewResource(R.id.imageViewStartNotificationBigIconMain, R.drawable.ic_app)
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
                smallView.setImageViewResource(R.id.imageViewStopNotificationSmallIconMain, R.drawable.ic_app)
                smallView.setImageViewResource(R.id.imageViewStopNotificationSmallIconStop, R.drawable.ic_service_stop_24dp)
                smallView.setOnClickPendingIntent(R.id.imageViewStopNotificationSmallIconStop, stopIntent)
                builder.setCustomContentView(smallView)

                val bigView = RemoteViews(packageName, R.layout.stop_notification_big)
                bigView.setOnClickPendingIntent(R.id.linearLayoutStopNotificationBig, pendingMainActivityIntent)
                bigView.setImageViewResource(R.id.imageViewStopNotificationBigIconMain, R.drawable.ic_app)
                bigView.setImageViewResource(R.id.imageViewStopNotificationBigIconStop, R.drawable.ic_service_stop_24dp)
                bigView.setOnClickPendingIntent(R.id.linearLayoutStopNotificationBigStop, stopIntent)
                builder.setCustomBigContentView(bigView)
            }
        }

        return builder.build()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun getFileFromAssets(context: Context, fileName: String): ByteArray {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] getFileFromAssets: $fileName")
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

    fun getInterfaces(): List<ForegroundServiceView.Interface> {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] getInterfaces")
        val interfaceList = ArrayList<ForegroundServiceView.Interface>()
        try {
            val enumeration = NetworkInterface.getNetworkInterfaces()
            while (enumeration.hasMoreElements()) {
                val networkInterface = enumeration.nextElement()
                val enumIpAddr = networkInterface.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address)
                        interfaceList.add(ForegroundServiceView.Interface(networkInterface.displayName, inetAddress.hostAddress))
                }
            }
        } catch (ex: Throwable) {
            if (BuildConfig.DEBUG_MODE) Log.e(TAG, ex.toString())
            Crashlytics.logException(ex)
        }
        return interfaceList
    }
}