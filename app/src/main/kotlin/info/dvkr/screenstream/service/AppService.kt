package info.dvkr.screenstream.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.Keep
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.HttpClient
import info.dvkr.screenstream.data.model.TrafficPoint
import info.dvkr.screenstream.data.other.getTag
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.data.state.AppStateMachine
import info.dvkr.screenstream.data.state.AppStateMachineImpl
import info.dvkr.screenstream.ui.activity.AppActivity
import info.dvkr.screenstream.ui.activity.PermissionActivity
import kotlinx.android.parcel.Parcelize
import kotlinx.android.synthetic.main.toast_slow_connection.view.*
import kotlinx.coroutines.*
import org.koin.android.ext.android.get
import timber.log.Timber
import java.util.*
import kotlin.coroutines.CoroutineContext

class AppService : Service(), CoroutineScope {

    @Keep sealed class IntentAction : Parcelable {
        internal companion object {
            private const val EXTRA_PARCELABLE = "EXTRA_PARCELABLE"
            fun fromIntent(intent: Intent?): IntentAction? = intent?.getParcelableExtra(EXTRA_PARCELABLE)
        }

        fun addToIntent(intent: Intent): Intent = intent.putExtra(EXTRA_PARCELABLE, this)

        @Keep @Parcelize object GetServiceState : IntentAction()
        @Keep @Parcelize object StartStream : IntentAction()
        @Keep @Parcelize object StopStream : IntentAction()
        @Keep @Parcelize object Exit : IntentAction()
        @Keep @Parcelize data class CastIntent(val intent: Intent) : IntentAction()
        @Keep @Parcelize object CastPermissionsDenied : IntentAction()
        @Keep @Parcelize object StartOnBoot : IntentAction()
        @Keep @Parcelize object RecoverError : IntentAction()

        override fun toString(): String = this::class.java.simpleName
    }

    companion object {
        fun getIntent(context: Context): Intent = Intent(context, AppService::class.java)

        fun getStartIntent(context: Context, intentAction: IntentAction? = null): Intent =
            getIntent(context).also { intentAction?.addToIntent(it) }

        fun startForegroundService(context: Context, intentAction: IntentAction? = null) {
            val intent = getStartIntent(context, intentAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private class ActivityMessagesHandler : Handler() {
        private val activityMessengers = Collections.synchronizedSet(HashSet<Messenger>())
        private var lastServiceMessage: ServiceMessage? = null

        private fun registerActivityMessenger(messenger: Messenger) = activityMessengers.add(messenger)

        private fun unRegisterActivityMessenger(messenger: Messenger) = activityMessengers.remove(messenger)

        fun sendMessageToActivities(serviceMessage: ServiceMessage) {
            Timber.tag(getTag("sendMessageToActivities")).v("ServiceMessage: $serviceMessage")
            lastServiceMessage = serviceMessage
            synchronized(activityMessengers) {
                activityMessengers.forEach { activityMessenger -> sendMessage(activityMessenger, serviceMessage) }
            }
        }

        private fun sendMessage(activityMessenger: Messenger, serviceMessage: ServiceMessage) {
            Timber.tag(getTag("sendMessage")).v("Messenger: $activityMessenger, ServiceMessage: $serviceMessage")
            try {
                activityMessenger.send(Message.obtain(null, 0).apply { data = serviceMessage.toBundle() })
            } catch (ex: RemoteException) {
                Timber.tag(getTag("sendMessageToActivities")).e(ex)
                unRegisterActivityMessenger(activityMessenger)
            }
        }

        override fun handleMessage(msg: Message?) {
            val message = ServiceMessage.fromBundle(msg?.data)
            Timber.tag(getTag("handleMessage")).d("ServiceMessage: $message")

            when (message) {
                is ServiceMessage.RegisterActivity -> {
                    lastServiceMessage?.let { sendMessage(message.relyTo, it) }
                    registerActivityMessenger(message.relyTo)
                }
                is ServiceMessage.UnRegisterActivity -> unRegisterActivityMessenger(message.relyTo)
                else -> throw IllegalStateException("Unknown ServiceMessage message: $message")
            }
        }
    }

    private val activityMessagesHandler = ActivityMessagesHandler()
    private val incomingMessenger = Messenger(activityMessagesHandler)

    private val parentJob: Job = Job()

    override val coroutineContext: CoroutineContext
        get() = parentJob + Dispatchers.Main + CoroutineExceptionHandler { _, throwable ->
            Timber.tag(getTag("onCoroutineException")).e(throwable)
            onError(FatalError.CoroutineException)
        }

    override fun onBind(intent: Intent?): IBinder? {
        Timber.tag(getTag("onBind")).d("Invoked")
        return incomingMessenger.binder
    }

    @AnyThread
    private fun onError(appError: AppError) {
        Timber.tag(this@AppService.getTag("onError")).e("AppError: $appError")

        startActivity(AppActivity.getStartIntent(applicationContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun onEffect(effect: AppStateMachine.Effect) {
        Timber.tag(this.getTag("onEffect")).d("Effect: $effect")

        when (effect) {
            is AppStateMachine.Effect.RequestCastPermissions -> startActivity(PermissionActivity.getStartIntent(this))
            is AppStateMachine.Effect.ConnectionChanged -> { //todo
                         }
            is AppStateMachine.Effect.PublicState -> {
                activityMessagesHandler.sendMessageToActivities(
                    ServiceMessage.ServiceState(
                        effect.isStreaming, effect.isBusy, effect.netInterfaces, effect.appError
                    )
                )

                if (effect.isStreaming)
                    notificationHelper.showForegroundNotification(this, NotificationHelper.NotificationType.STOP)
                else
                    notificationHelper.showForegroundNotification(this, NotificationHelper.NotificationType.START)

                effect.appError?.let { onError(it) }
            }
        }
    }

    private val notificationHelper by lazy { NotificationHelper(this) }
    private lateinit var appStateMachine: AppStateMachine

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createNotificationChannel()

        appStateMachine = AppStateMachineImpl(
            this,
            parentJob,
            get<SettingsReadOnly>(),
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager, // TODO
            BitmapFactory.decodeResource(resources, R.drawable.ic_app_icon),
            { clients: List<HttpClient>, trafficHistory: List<TrafficPoint> ->
                checkForSlowClients(clients)
                activityMessagesHandler.sendMessageToActivities(ServiceMessage.Clients(clients))
                activityMessagesHandler.sendMessageToActivities(ServiceMessage.TrafficHistory(trafficHistory))
            },
            ::onEffect
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intentAction = IntentAction.fromIntent(intent)
        intentAction != null || return Service.START_NOT_STICKY
        Timber.tag(getTag("onStartCommand")).d("IntentAction: $intentAction")

        when (intentAction) {
            IntentAction.GetServiceState -> {
                appStateMachine.sendEvent(AppStateMachine.Event.RequestPublicState)
            }

            IntentAction.StartStream -> {
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                appStateMachine.sendEvent(AppStateMachine.Event.StartStream)
            }

            IntentAction.StopStream -> {
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                appStateMachine.sendEvent(AppStateMachine.Event.StopStream)
            }

            IntentAction.Exit -> {
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                activityMessagesHandler.sendMessageToActivities(ServiceMessage.FinishActivity)
                this@AppService.stopSelf()
            }

            is IntentAction.CastIntent ->
                appStateMachine.sendEvent(AppStateMachine.Event.StartProjection(intentAction.intent))

            IntentAction.CastPermissionsDenied ->
                appStateMachine.sendEvent(AppStateMachine.Event.CastPermissionsDenied)

            IntentAction.StartOnBoot ->
                appStateMachine.sendEvent(AppStateMachine.Event.StartStream, 3500)

            IntentAction.RecoverError ->
                appStateMachine.sendEvent(AppStateMachine.Event.RecoverError)

            else -> Timber.tag(getTag("onStartCommand")).e("Unknown action: $intentAction")
        }

        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        Timber.tag(getTag("onDestroy")).d("Invoked")
        appStateMachine.destroy()
        parentJob.cancel()
        stopForeground(true)
        Runtime.getRuntime().exit(0)
        super.onDestroy()
    }

    private var slowClients: List<HttpClient> = emptyList()

    private fun checkForSlowClients(clients: List<HttpClient>) = launch {
        val currentSlowConnections = clients.filter { it.isSlowConnection }.toList()
        if (slowClients.containsAll(currentSlowConnections).not()) {
            val layoutInflater = ContextCompat.getSystemService(this@AppService, LayoutInflater::class.java)!!
            val toastView = layoutInflater.inflate(R.layout.toast_slow_connection, null)
            val drawable = AppCompatResources.getDrawable(applicationContext, R.drawable.ic_notification_small_24dp)
            toastView.iv_toast_slow_connection.setImageDrawable(drawable)
            Toast(applicationContext).apply { view = toastView; duration = Toast.LENGTH_LONG }.show()
        }
        slowClients = currentSlowConnections
    }
}


