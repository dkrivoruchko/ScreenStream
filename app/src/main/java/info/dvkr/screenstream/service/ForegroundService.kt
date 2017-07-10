package info.dvkr.screenstream.service


import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.support.annotation.Keep
import android.support.v4.content.ContextCompat
import android.support.v7.app.NotificationCompat
import android.util.Log
import com.cantrowitz.rxbroadcast.RxBroadcast
import com.crashlytics.android.Crashlytics
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ScreenStreamApp
import info.dvkr.screenstream.model.*
import info.dvkr.screenstream.model.image.ImageGeneratorImpl
import info.dvkr.screenstream.presenter.ForegroundServicePresenter
import info.dvkr.screenstream.ui.StartActivity
import rx.Observable
import rx.Scheduler
import rx.functions.Action1
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

class ForegroundService : Service(), ForegroundServiceView {
    companion object {
        private const val TAG = "ForegroundService"
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

    @Inject internal lateinit var mPresenter: ForegroundServicePresenter
    @Inject internal lateinit var mSettings: Settings
    @Inject internal lateinit var mEventScheduler: Scheduler
    @Inject internal lateinit var mEventBus: EventBus
    @Inject internal lateinit var mImageNotify: ImageNotify

    private val isForegroundServiceInit = AtomicBoolean(false)
    private val mSubscriptions = CompositeSubscription()
    private val mFromEvents = PublishSubject.create<ForegroundServiceView.FromEvent>()
    private val mToEvents = PublishSubject.create<ForegroundServiceView.ToEvent>()

    private val mJpegBytesStream = BehaviorSubject.create<ByteArray>()
    private val mMediaProjection = AtomicReference<MediaProjection?>()
    private val mProjectionCallback = AtomicReference<MediaProjection.Callback?>()
    private val mImageGenerator = AtomicReference<ImageGenerator?>()
    private val mConnectionEvents = PublishSubject.create<String>()

    // Base values
    private lateinit var mFavicon: ByteArray
    private lateinit var mBaseIndexHtml: String
    private lateinit var mBasePinRequestHtml: String
    private lateinit var mPinRequestErrorMsg: String

    override fun fromEvent(): Observable<ForegroundServiceView.FromEvent> {
        return mFromEvents
                .observeOn(mEventScheduler) // TODO
                .asObservable()
    }

    override fun toEvent(event: ForegroundServiceView.ToEvent) {
        mToEvents.onNext(event)
    }

    override fun toEvent(event: ForegroundServiceView.ToEvent, timeout: Long) {
        Observable.just<ForegroundServiceView.ToEvent>(event)
                .delay(timeout, TimeUnit.MILLISECONDS, mEventScheduler)
                .subscribe({ toEvent(it) })
    }


    override fun onCreate() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")

        (application as ScreenStreamApp).appComponent().plusActivityComponent().inject(this)
        mPresenter.attach(this)

        mSubscriptions.add(mToEvents.startWith(ForegroundService.LocalEvent.StartService())
                .observeOn(mEventScheduler).subscribe({ event ->

            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] toEvent: " + event.javaClass.simpleName)

            when (event) {
                is ForegroundService.LocalEvent.StartService -> {
                    mFavicon = getFavicon(applicationContext)
                    mBaseIndexHtml = getBaseIndexHtml(applicationContext)
                    mBasePinRequestHtml = getBasePinRequestHtml(applicationContext)
                    mPinRequestErrorMsg = applicationContext.getString(R.string.html_wrong_pin)
                }

                is ForegroundServiceView.ToEvent.StartHttpServer -> {
                    mFromEvents.onNext(ForegroundServiceView.FromEvent.StartHttpServer(
                            mFavicon,
                            mBaseIndexHtml,
                            mBasePinRequestHtml,
                            mPinRequestErrorMsg,
                            mJpegBytesStream.asObservable())
                    )
                }

                is ForegroundServiceView.ToEvent.NotifyImage -> {
                    val notifyType = event.notifyType
                    mJpegBytesStream.onNext(mImageNotify.getImage(notifyType))
                    mJpegBytesStream.onNext(mImageNotify.getImage(notifyType)) // TODO need better solution
                }

                is ForegroundService.LocalEvent.StartStream -> {
                    val data = event.intent
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, data)
                    if (null == mediaProjection) IllegalStateException(TAG + ": mediaProjection == null")
                    mMediaProjection.set(mediaProjection)
                    mImageGenerator.set(ImageGeneratorImpl(
                            applicationContext,
                            mediaProjection,
                            mEventScheduler,
                            mEventBus,
                            mSettings.resizeFactor,
                            mSettings.jpegQuality,
                            Action1 { imageByteArray -> mJpegBytesStream.onNext(imageByteArray) })
                    )
                    stopForeground(true)
                    startForeground(NOTIFICATION_STOP_STREAMING, getNotification(NOTIFICATION_STOP_STREAMING))
                    mProjectionCallback.set(object : MediaProjection.Callback() {
                        override fun onStop() {
                            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] ProjectionCallback")
                            mEventBus.sendEvent(EventBus.GlobalEvent.StopStream())
                        }
                    })
                    mediaProjection.registerCallback(mProjectionCallback.get(), null)
                }

                is ForegroundServiceView.ToEvent.StopStream -> {
                    stopForeground(true)
                    stopMediaProjection()
                    startForeground(NOTIFICATION_START_STREAMING, getNotification(NOTIFICATION_START_STREAMING))
                    if (event.isNotifyOnComplete)
                        mFromEvents.onNext(ForegroundServiceView.FromEvent.StopStreamComplete())
                }

                is ForegroundServiceView.ToEvent.AppExit -> {
                    stopSelf()
                }

                is ForegroundServiceView.ToEvent.CurrentInterfacesRequest -> {
                    mFromEvents.onNext(ForegroundServiceView.FromEvent.CurrentInterfaces(getInterfaces()))
                }

//                is ForegroundServiceView.ToEvent.AppStatus -> {
//                    startActivity(StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_APP_STATUS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
//                }
            }
        })
        { onError ->
            if (BuildConfig.DEBUG_MODE) Log.e(TAG, onError.toString())
            Crashlytics.logException(onError)
            startActivity(StartActivity.getErrorIntent(applicationContext, onError.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })

        // Registering receiver for screen off messages and WiFi changes
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
        intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)

        mSubscriptions.add(RxBroadcast.fromBroadcast(applicationContext, intentFilter)
                .map<String>({ it.action })
                .observeOn(mEventScheduler)
                .subscribe { action ->
                    if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Action: " + action)
                    when (action) {
                        Intent.ACTION_SCREEN_OFF -> mFromEvents.onNext(ForegroundServiceView.FromEvent.ScreenOff())
                        WifiManager.WIFI_STATE_CHANGED_ACTION -> mConnectionEvents.onNext(WifiManager.WIFI_STATE_CHANGED_ACTION)
                        ConnectivityManager.CONNECTIVITY_ACTION -> mConnectionEvents.onNext(ConnectivityManager.CONNECTIVITY_ACTION)
                    }
                })

        mSubscriptions.add(mConnectionEvents
                .throttleWithTimeout(500, TimeUnit.MILLISECONDS, mEventScheduler)
                .subscribe { _ -> mFromEvents.onNext(ForegroundServiceView.FromEvent.CurrentInterfaces(getInterfaces())) }
        )

        startForeground(NOTIFICATION_START_STREAMING, getNotification(NOTIFICATION_START_STREAMING))
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
            ACTION_INIT -> if (!isForegroundServiceInit.get()) {
                mFromEvents.onNext(ForegroundServiceView.FromEvent.Init())
                isForegroundServiceInit.set(true)
            }

            ACTION_START_ON_BOOT -> {
                startActivity(StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_START_STREAM))
            }

            ACTION_START_STREAM ->
                mToEvents.onNext(ForegroundService.LocalEvent.StartStream(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)))
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: StartService")
        mSubscriptions.clear()
        stopForeground(true)
        stopMediaProjection()
        mFromEvents.onNext(ForegroundServiceView.FromEvent.StopHttpServer())
        mPresenter.detach()
        super.onDestroy()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: Done")
        System.exit(0)
    }

// ======================================================================================================

    private fun stopMediaProjection() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] stopMediaProjection")

//        ScreenStreamApp.getRefWatcher().watch(mMediaProjection)
//        ScreenStreamApp.getRefWatcher().watch(mProjectionCallback)
//        ScreenStreamApp.getRefWatcher().watch(mImageGenerator)

        val mediaProjection = mMediaProjection.get()
        if (null != mediaProjection) {
            val callback = mProjectionCallback.get()
            if (null != callback) {
                mediaProjection.unregisterCallback(callback)
                mProjectionCallback.set(null)
            }
            mediaProjection.stop()
            mMediaProjection.set(null)
        }
        val imageGenerator = mImageGenerator.get()
        if (null != imageGenerator) {
            imageGenerator.stop()
            mImageGenerator.set(null)
        }
    }

    private fun getNotification(notificationType: Int): Notification {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] getNotification:$notificationType")

        val pendingMainActivityIntent = PendingIntent.getActivity(applicationContext, 0,
                StartActivity.getStartIntent(applicationContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                0)

        val builder = NotificationCompat.Builder(applicationContext)
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.setSmallIcon(R.drawable.ic_service_notification_24dp)
        builder.setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_notification))
        builder.color = ContextCompat.getColor(applicationContext, R.color.colorPrimaryDark)
        builder.setContentIntent(pendingMainActivityIntent)

        when (notificationType) {
            NOTIFICATION_START_STREAMING -> {
                builder.setContentTitle(getString(R.string.service_ready_to_stream))
                builder.setContentText(getString(R.string.service_press_start))

                val startIntent = PendingIntent.getActivity(applicationContext, 1,
                        StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_START_STREAM),
                        PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(R.drawable.ic_service_start_24dp, getString(R.string.service_start).toUpperCase(), startIntent)

                val exitIntent = PendingIntent.getActivity(applicationContext, 3,
                        StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_EXIT), PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(R.drawable.ic_service_exit_24dp, getString(R.string.service_exit).toUpperCase(), exitIntent)
            }

            NOTIFICATION_STOP_STREAMING -> {
                builder.setContentTitle(getString(R.string.service_stream))
                builder.setContentText(getServerAddresses())

                val stopIntent = PendingIntent.getActivity(applicationContext, 2,
                        StartActivity.getStartIntent(applicationContext, StartActivity.ACTION_STOP_STREAM),
                        PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(R.drawable.ic_service_stop_24dp, getString(R.string.service_stop).toUpperCase(), stopIntent)
            }
        }

        builder.setStyle(NotificationCompat.MediaStyle().setShowActionsInCompactView(0))
        builder.setCategory(Notification.CATEGORY_SERVICE)
        builder.priority = NotificationCompat.PRIORITY_MAX
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
        if (iconBytes.isEmpty()) throw IllegalStateException("favicon.ico is empty")
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

    //TODO UPDATE THIS
    fun getServerAddresses(): String {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] getServerAddresses")
        val addresses = StringBuilder(getString(R.string.service_go_to))
        try {
            val enumeration = NetworkInterface.getNetworkInterfaces()
            while (enumeration.hasMoreElements()) {
                val networkInterface = enumeration.nextElement()
                val enumIpAddr = networkInterface.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address)
                        addresses.append("http://").append(inetAddress.hostAddress).append(":").append(mSettings.severPort).append("\n")
                }
            }
        } catch (ex: Throwable) {
            if (BuildConfig.DEBUG_MODE) Log.e(TAG, ex.toString())
            Crashlytics.logException(ex)
        }
        return addresses.toString()
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