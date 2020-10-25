package info.dvkr.screenstream.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.HttpClient
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.data.state.AppStateMachine
import info.dvkr.screenstream.data.state.AppStateMachineImpl
import info.dvkr.screenstream.databinding.ToastSlowConnectionBinding
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.service.helper.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AppService : Service() {

    companion object {
        var isRunning: Boolean = false

        fun getAppServiceIntent(context: Context): Intent =
            Intent(context.applicationContext, AppService::class.java)

        fun startForeground(context: Context, intent: Intent = getAppServiceIntent(context)) =
            ContextCompat.startForegroundService(context, intent)
    }

    inner class AppServiceBinder : Binder() {
        fun getServiceMessageFlow(): SharedFlow<ServiceMessage> = _serviceMessageSharedFlow.asSharedFlow()
    }

    private val appServiceBinder = AppServiceBinder()
    private val _serviceMessageSharedFlow =
        MutableSharedFlow<ServiceMessage>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun sendMessageToActivities(serviceMessage: ServiceMessage) {
        XLog.v(getLog("sendMessageToActivities", "ServiceMessage: $serviceMessage"))
        _serviceMessageSharedFlow.tryEmit(serviceMessage)
    }

    private val coroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
            onError(FatalError.CoroutineException)
        }
    )

    private val isStreaming = AtomicBoolean(false)
    private val errorPrevious = AtomicReference<AppError?>(null)

    override fun onBind(intent: Intent?): IBinder? {
        XLog.d(getLog("onBind", "Invoked"))
        return appServiceBinder
    }

    private fun onError(appError: AppError?) {
        val oldError = errorPrevious.getAndSet(appError)
        oldError != appError || return

        if (appError == null) {
            notificationHelper.hideErrorNotification()
        } else {
            XLog.e(this@AppService.getLog("onError", "AppError: $appError"))
            notificationHelper.showErrorNotification(appError)
        }
    }

    private suspend fun onEffect(effect: AppStateMachine.Effect) = coroutineScope.launch {
        XLog.d(this@AppService.getLog("onEffect", "Effect: $effect"))

        when (effect) {
            is AppStateMachine.Effect.ConnectionChanged -> Unit  // TODO Notify user about restart reason

            is AppStateMachine.Effect.PublicState -> {
                isStreaming.set(effect.isStreaming)

                sendMessageToActivities(
                    ServiceMessage.ServiceState(
                        effect.isStreaming, effect.isBusy, effect.isWaitingForPermission,
                        effect.netInterfaces, effect.appError
                    )
                )

                if (effect.isStreaming)
                    notificationHelper.showForegroundNotification(
                        this@AppService, NotificationHelper.NotificationType.STOP
                    )
                else
                    notificationHelper.showForegroundNotification(
                        this@AppService, NotificationHelper.NotificationType.START
                    )
                onError(effect.appError)
            }
        }
    }.join()

    private val settings: Settings by inject()
    private val notificationHelper: NotificationHelper by inject()
    private var appStateMachine: AppStateMachine? = null

    override fun onCreate() {
        super.onCreate()
        XLog.d(getLog("onCreate"))
        notificationHelper.createNotificationChannel()
        notificationHelper.showForegroundNotification(this, NotificationHelper.NotificationType.START)

        settings.autoChangePinOnStart()

        appStateMachine = AppStateMachineImpl(this, settings as SettingsReadOnly, ::onEffect)

        coroutineScope.launch(CoroutineName("AppService.statisticFlow")) {
            appStateMachine!!.statisticFlow.onEach { (clients, trafficHistory) ->
                XLog.v(this@AppService.getLog("onStatistic"))
                if (settings.autoStartStop) checkAutoStartStop(clients)
                if (settings.notifySlowConnections) checkForSlowClients(clients)
                sendMessageToActivities(ServiceMessage.Clients(clients))
                sendMessageToActivities(ServiceMessage.TrafficHistory(trafficHistory))
            }.collect()
        }

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intentAction = IntentAction.fromIntent(intent)
        intentAction != null || return START_NOT_STICKY
        XLog.d(getLog("onStartCommand", "IntentAction: $intentAction"))

        when (intentAction) {
            IntentAction.GetServiceState -> {
                appStateMachine?.sendEvent(AppStateMachine.Event.RequestPublicState)
            }

            IntentAction.StartStream -> {
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                appStateMachine?.sendEvent(AppStateMachine.Event.StartStream)
            }

            IntentAction.StopStream -> {
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                appStateMachine?.sendEvent(AppStateMachine.Event.StopStream)
            }

            IntentAction.Exit -> {
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                notificationHelper.hideErrorNotification()
                sendMessageToActivities(ServiceMessage.FinishActivity)
                stopForeground(true)
                this@AppService.stopSelf()
            }

            is IntentAction.CastIntent -> {
                appStateMachine?.sendEvent(AppStateMachine.Event.RequestPublicState)
                appStateMachine?.sendEvent(AppStateMachine.Event.StartProjection(intentAction.intent))
            }

            IntentAction.CastPermissionsDenied -> {
                appStateMachine?.sendEvent(AppStateMachine.Event.CastPermissionsDenied)
                appStateMachine?.sendEvent(AppStateMachine.Event.RequestPublicState)
            }

            IntentAction.StartOnBoot ->
                appStateMachine?.sendEvent(AppStateMachine.Event.StartStream, 4500)

            IntentAction.RecoverError -> {
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                notificationHelper.hideErrorNotification()
                appStateMachine?.sendEvent(AppStateMachine.Event.RecoverError)
            }

            else -> XLog.e(getLog("onStartCommand", "Unknown action: $intentAction"))
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        isRunning = false
        runBlocking(coroutineScope.coroutineContext) { appStateMachine?.destroy() }
        appStateMachine = null
        coroutineScope.cancel(CancellationException("AppService.destroy"))
        stopForeground(true)
        XLog.d(getLog("onDestroy", "Done"))
        super.onDestroy()
    }

    private var slowClients: List<HttpClient> = emptyList()

    private fun checkForSlowClients(clients: List<HttpClient>) {
        val currentSlowConnections = clients.filter { it.isSlowConnection }.toList()
        if (slowClients.containsAll(currentSlowConnections).not()) {
            val layoutInflater = ContextCompat.getSystemService(this@AppService, LayoutInflater::class.java)!!
            val binding = ToastSlowConnectionBinding.inflate(layoutInflater)
            val drawable = AppCompatResources.getDrawable(applicationContext, R.drawable.ic_notification_small_24dp)
            binding.ivToastSlowConnection.setImageDrawable(drawable)
            Toast(applicationContext).apply { view = binding.root; duration = Toast.LENGTH_LONG }.show()
        }
        slowClients = currentSlowConnections
    }

    private fun checkAutoStartStop(clients: List<HttpClient>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return

        if (clients.isNotEmpty() && isStreaming.get().not()) {
            XLog.d(getLog("checkAutoStartStop", "Auto starting"))
            appStateMachine?.sendEvent(AppStateMachine.Event.StartStream)
        }

        if (clients.isEmpty() && isStreaming.get()) {
            XLog.d(getLog("checkAutoStartStop", "Auto stopping"))
            appStateMachine?.sendEvent(AppStateMachine.Event.StopStream)
        }
    }
}