package info.dvkr.screenstream.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.*
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.HttpClient
import info.dvkr.screenstream.data.model.TrafficPoint
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.data.state.AppStateMachine
import info.dvkr.screenstream.data.state.AppStateMachineImpl
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.service.helper.NotificationHelper
import kotlinx.android.synthetic.main.toast_slow_connection.view.*
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class AppService : Service(), CoroutineScope {

    companion object {
        fun getAppServiceIntent(context: Context): Intent =
            Intent(context.applicationContext, AppService::class.java)

        fun startForeground(context: Context, intent: Intent = getAppServiceIntent(context)) =
            ContextCompat.startForegroundService(context, intent)
    }

    private class ActivityMessagesHandler : Handler() {
        private val activityMessengers = CopyOnWriteArraySet<Messenger>()
        private var lastServiceMessage: ServiceMessage? = null

        private fun registerActivityMessenger(messenger: Messenger) = activityMessengers.add(messenger)

        private fun unRegisterActivityMessenger(messenger: Messenger) = activityMessengers.remove(messenger)

        fun sendMessageToActivities(serviceMessage: ServiceMessage) {
            XLog.v(getLog("sendMessageToActivities", "ServiceMessage: $serviceMessage"))
            lastServiceMessage = serviceMessage
            val iterator = activityMessengers.iterator()
            while (iterator.hasNext()) sendMessage(iterator.next(), serviceMessage)

        }

        private fun sendMessage(activityMessenger: Messenger, serviceMessage: ServiceMessage) {
            XLog.v(getLog("sendMessage", "Messenger: $activityMessenger, ServiceMessage: $serviceMessage"))
            try {
                if (activityMessenger.binder.isBinderAlive)
                    activityMessenger.send(Message.obtain(null, 0).apply { data = serviceMessage.toBundle() })
                else
                    unRegisterActivityMessenger(activityMessenger)
            } catch (ex: RemoteException) {
                XLog.w(getLog("sendMessageToActivities", ex.toString()))
                unRegisterActivityMessenger(activityMessenger)
            }
        }

        override fun handleMessage(msg: Message) {
            val message = ServiceMessage.fromBundle(msg.data)
            XLog.d(getLog("handleMessage", "ServiceMessage: $message"))
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

    private val supervisorJob = SupervisorJob()

    private val isStreaming = AtomicBoolean(false)
    private var errorPrevious: AppError? = null

    override val coroutineContext: CoroutineContext
        get() = supervisorJob + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
            onError(FatalError.CoroutineException)
        }

    override fun onBind(intent: Intent?): IBinder? {
        XLog.d(getLog("onBind", "Invoked"))
        return incomingMessenger.binder
    }

    @AnyThread
    private fun onError(appError: AppError?) {
        errorPrevious != appError || return

        if (appError == null) {
            notificationHelper.hideErrorNotification()
        } else {
            XLog.e(this@AppService.getLog("onError", "AppError: $appError"))
            notificationHelper.showErrorNotification(appError)
        }

        errorPrevious = appError
    }

    private fun onEffect(effect: AppStateMachine.Effect) {
        XLog.d(getLog("onEffect", "Effect: $effect"))

        when (effect) {
            is AppStateMachine.Effect.ConnectionChanged -> Unit  // TODO Notify user about restart reason

            is AppStateMachine.Effect.PublicState -> {
                isStreaming.set(effect.isStreaming)

                activityMessagesHandler.sendMessageToActivities(
                    ServiceMessage.ServiceState(
                        effect.isStreaming, effect.isBusy, effect.isWaitingForPermission,
                        effect.netInterfaces, effect.appError
                    )
                )

                if (effect.isStreaming)
                    notificationHelper.showForegroundNotification(this, NotificationHelper.NotificationType.STOP)
                else
                    notificationHelper.showForegroundNotification(this, NotificationHelper.NotificationType.START)

                onError(effect.appError)
            }
        }
    }

    private val settings: Settings by inject()
    private val notificationHelper: NotificationHelper by inject()
    private lateinit var appStateMachine: AppStateMachine

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createNotificationChannel()
        notificationHelper.showForegroundNotification(this, NotificationHelper.NotificationType.START)

        settings.autoChangePinOnStart()

        appStateMachine = AppStateMachineImpl(
            this,
            supervisorJob,
            settings as SettingsReadOnly,
            BitmapFactory.decodeResource(resources, R.drawable.logo),
            { clients: List<HttpClient>, trafficHistory: List<TrafficPoint> ->
                if (settings.autoStartStop) checkAutoStartStop(clients)
                if (settings.notifySlowConnections) checkForSlowClients(clients)
                activityMessagesHandler.sendMessageToActivities(ServiceMessage.Clients(clients))
                activityMessagesHandler.sendMessageToActivities(ServiceMessage.TrafficHistory(trafficHistory))
            },
            ::onEffect
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intentAction = IntentAction.fromIntent(intent)
        intentAction != null || return START_NOT_STICKY
        XLog.d(getLog("onStartCommand", "IntentAction: $intentAction"))

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
                notificationHelper.hideErrorNotification()
                activityMessagesHandler.sendMessageToActivities(ServiceMessage.FinishActivity)
                stopForeground(true)
                this@AppService.stopSelf()
            }

            is IntentAction.CastIntent -> {
                appStateMachine.sendEvent(AppStateMachine.Event.RequestPublicState)
                appStateMachine.sendEvent(AppStateMachine.Event.StartProjection(intentAction.intent))
            }

            IntentAction.CastPermissionsDenied -> {
                appStateMachine.sendEvent(AppStateMachine.Event.CastPermissionsDenied)
                appStateMachine.sendEvent(AppStateMachine.Event.RequestPublicState)
            }

            IntentAction.StartOnBoot ->
                appStateMachine.sendEvent(AppStateMachine.Event.StartStream, 4500)

            IntentAction.RecoverError -> {
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                notificationHelper.hideErrorNotification()
                appStateMachine.sendEvent(AppStateMachine.Event.RecoverError)
            }

            else -> XLog.e(getLog("onStartCommand", "Unknown action: $intentAction"))
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy", "Invoked"))
        appStateMachine.destroy()
        coroutineContext.cancelChildren()
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

    private fun checkAutoStartStop(clients: List<HttpClient>) {
        if (clients.isNotEmpty() && isStreaming.get().not()) {
            XLog.d(getLog("checkAutoStartStop", "Auto starting"))
            appStateMachine.sendEvent(AppStateMachine.Event.StartStream)
        }

        if (clients.isEmpty() && isStreaming.get()) {
            XLog.d(getLog("checkAutoStartStop", "Auto stopping"))
            appStateMachine.sendEvent(AppStateMachine.Event.StopStream)
        }
    }
}