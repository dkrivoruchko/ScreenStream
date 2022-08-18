package info.dvkr.screenstream.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.state.AppStateMachine
import info.dvkr.screenstream.data.state.AppStateMachineImpl
import info.dvkr.screenstream.databinding.ToastSlowConnectionBinding
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.service.helper.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject

class ForegroundService : Service() {

    internal companion object {
        @JvmStatic
        @Volatile
        var isRunning: Boolean = false

        fun getForegroundServiceIntent(context: Context): Intent = Intent(context, ForegroundService::class.java)

        fun startForeground(context: Context, intent: Intent) {
            XLog.e(getLog("startForeground"))
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { XLog.e(getLog("startForeground", "Failed to start Foreground Service"), it) }
        }
    }

    internal object ForegroundServiceBinder : Binder() {
        private val serviceMessageStateFlow = MutableStateFlow<ServiceMessage?>(null)

        internal val serviceMessageFlow: StateFlow<ServiceMessage?> = serviceMessageStateFlow.asStateFlow()

        internal fun sendMessage(serviceMessage: ServiceMessage) = try {
            serviceMessageStateFlow.tryEmit(serviceMessage)
        } catch (cause: RemoteException) {
            XLog.d(getLog("sendMessage", "Failed to send message: $serviceMessage: $cause"))
            XLog.e(getLog("sendMessage", "Failed to send message: $serviceMessage"), cause)
        }
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val effectFlow = MutableStateFlow<AppStateMachine.Effect?>(null)

    private val settings: Settings by inject()
    private val notificationHelper: NotificationHelper by inject()

    private var appStateMachine: AppStateMachine? = null
    private var appErrorPrevious: AppError? = null

    override fun onBind(intent: Intent?): IBinder {
        XLog.e(getLog("onBind"))
        return ForegroundServiceBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        XLog.e(getLog("onUnbind"))
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        XLog.d(getLog("onCreate"))

        notificationHelper.createNotificationChannel()
        notificationHelper.showForegroundNotification(this, NotificationHelper.NotificationType.START)

        effectFlow.filterNotNull().onEach { effect ->
            if (effect !is AppStateMachine.Effect.Statistic)
                XLog.d(this@ForegroundService.getLog("onEffect", "Effect: $effect"))

            when (effect) {
                is AppStateMachine.Effect.ConnectionChanged -> Unit  // TODO Notify user about restart reason

                is AppStateMachine.Effect.PublicState -> {
                    ForegroundServiceBinder.sendMessage(
                        ServiceMessage.ServiceState(
                            effect.isStreaming, effect.isBusy, effect.waitingForPermission,
                            effect.netInterfaces, effect.appError
                        )
                    )

                    val notificationType = if (effect.isStreaming) NotificationHelper.NotificationType.STOP
                    else NotificationHelper.NotificationType.START
                    notificationHelper.showForegroundNotification(this@ForegroundService, notificationType)

                    onError(effect.appError)
                }

                is AppStateMachine.Effect.Statistic -> {
                    if (effect is AppStateMachine.Effect.Statistic.Clients)
                        ForegroundServiceBinder.sendMessage(ServiceMessage.Clients(effect.clients))

                    if (effect is AppStateMachine.Effect.Statistic.Traffic)
                        ForegroundServiceBinder.sendMessage(ServiceMessage.TrafficHistory(effect.traffic))
                }
            }
        }
            .launchIn(coroutineScope)

        appStateMachine = AppStateMachineImpl(this, settings, effectFlow, ::showSlowConnectionToast)

        isRunning = true
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intentAction = IntentAction.fromIntent(intent) ?: return START_NOT_STICKY
        XLog.d(getLog("onStartCommand", "IntentAction: $intentAction"))

        when (intentAction) {
            IntentAction.GetServiceState -> {
                appStateMachine?.sendEvent(AppStateMachine.Event.RequestPublicState)
            }

            IntentAction.StartStream -> {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R)
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))

                appStateMachine?.sendEvent(AppStateMachine.Event.StartStream)
            }

            IntentAction.StopStream -> {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R)
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))

                appStateMachine?.sendEvent(AppStateMachine.Event.StopStream)
            }

            IntentAction.Exit -> {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R)
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))

                notificationHelper.hideErrorNotification()
                stopForeground(true)
                ForegroundServiceBinder.sendMessage(ServiceMessage.FinishActivity)
                this@ForegroundService.stopSelf()
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
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R)
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))

                notificationHelper.hideErrorNotification()
                appStateMachine?.sendEvent(AppStateMachine.Event.RecoverError)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))

        isRunning = false

        coroutineScope.cancel()

        appStateMachine?.destroy()
        appStateMachine = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        XLog.d(getLog("onDestroy", "Done"))
        super.onDestroy()
    }

    private fun onError(appError: AppError?) {
        appErrorPrevious != appError || return
        appErrorPrevious = appError

        if (appError == null) {
            notificationHelper.hideErrorNotification()
        } else {
            XLog.e(this@ForegroundService.getLog("onError", "AppError: $appError"))
            notificationHelper.showErrorNotification(appError)
        }
    }

    @Suppress("DEPRECATION")
    private fun showSlowConnectionToast() {
        coroutineScope.launch {
            val layoutInflater = ContextCompat.getSystemService(this@ForegroundService, LayoutInflater::class.java)!!
            val binding = ToastSlowConnectionBinding.inflate(layoutInflater)
            val drawable = AppCompatResources.getDrawable(this@ForegroundService, R.drawable.ic_notification_small_24dp)
            binding.ivToastSlowConnection.setImageDrawable(drawable)
            Toast(this@ForegroundService).apply { view = binding.root; duration = Toast.LENGTH_LONG }.show()
        }
    }
}