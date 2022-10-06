package info.dvkr.screenstream.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.AppStateMachine
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.databinding.ToastSlowConnectionBinding
import info.dvkr.screenstream.mjpeg.MjpegPublicState
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.state.MjpegStateMachine
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

        @JvmStatic
        fun getForegroundServiceIntent(context: Context): Intent = Intent(context, ForegroundService::class.java)

        @JvmStatic
        fun startForeground(context: Context, intent: Intent) {
            XLog.d(getLog("startForeground", intent.extras?.toString()))
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { XLog.e(getLog("startForeground", "Failed to start Foreground Service"), it) }
        }

        @JvmStatic
        fun startService(context: Context, intent: Intent) {
            XLog.d(getLog("startService", intent.extras?.toString()))
            runCatching { context.startService(intent) }
                .onFailure { XLog.e(getLog("startService", "Failed to start Service"), it) }
        }

        @JvmStatic
        private val serviceMessageSharedFlow = MutableSharedFlow<ServiceMessage>()

        @JvmStatic
        internal val serviceMessageFlow: SharedFlow<ServiceMessage> = serviceMessageSharedFlow.asSharedFlow()

        @JvmStatic
        internal suspend fun sendMessage(serviceMessage: ServiceMessage) = serviceMessageSharedFlow.emit(serviceMessage)
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val effectFlow = MutableSharedFlow<AppStateMachine.Effect>(extraBufferCapacity = 8)

    private val appSettings: AppSettings by inject()
    private val mjpegSettings: MjpegSettings by inject()
    private val notificationHelper: NotificationHelper by inject()

    private var appStateMachine: AppStateMachine? = null
    private var appErrorPrevious: AppError? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        XLog.d(getLog("onCreate"))

        notificationHelper.createNotificationChannel()
        notificationHelper.showForegroundNotification(this, NotificationHelper.NotificationType.START)

        effectFlow.onEach { effect ->
            if (effect !is AppStateMachine.Effect.Statistic)
                XLog.d(this@ForegroundService.getLog("onEffect", "Effect: $effect"))

            when (effect) {
                is AppStateMachine.Effect.ConnectionChanged -> Unit  // TODO Notify user about restart reason

                is AppStateMachine.Effect.PublicState -> {
                    if (effect is MjpegPublicState) {
                        sendMessage(
                            ServiceMessage.ServiceState(
                                effect.isStreaming, effect.isBusy, effect.waitingForCastPermission, effect.netInterfaces, effect.appError
                            )
                        )

                        val notificationType = if (effect.isStreaming) NotificationHelper.NotificationType.STOP
                        else NotificationHelper.NotificationType.START
                        notificationHelper.showForegroundNotification(this@ForegroundService, notificationType)

                        onError(effect.appError)
                    }
                }

                is AppStateMachine.Effect.Statistic -> {
                    if (effect is AppStateMachine.Effect.Statistic.Clients) sendMessage(ServiceMessage.Clients(effect.clients))

                    if (effect is AppStateMachine.Effect.Statistic.Traffic) sendMessage(ServiceMessage.TrafficHistory(effect.traffic))
                }
            }
        }
            .launchIn(coroutineScope)

        appStateMachine = MjpegStateMachine(this, appSettings, mjpegSettings, effectFlow, ::showSlowConnectionToast)

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
                coroutineScope.launch { sendMessage(ServiceMessage.FinishActivity) }
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

        appStateMachine?.destroy()
        appStateMachine = null

        coroutineScope.cancel()

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
    private val windowContext: Context by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            this
        } else {
            val display = ContextCompat.getSystemService(this, DisplayManager::class.java)!!
                .getDisplay(Display.DEFAULT_DISPLAY)

            this.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_TOAST, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun showSlowConnectionToast() {
        coroutineScope.launch {
            val layoutInflater = ContextCompat.getSystemService(windowContext, LayoutInflater::class.java)!!
            val binding = ToastSlowConnectionBinding.inflate(layoutInflater)
            val drawable = AppCompatResources.getDrawable(windowContext, R.drawable.ic_notification_small_24dp)
            binding.ivToastSlowConnection.setImageDrawable(drawable)
            Toast(windowContext).apply { view = binding.root; duration = Toast.LENGTH_LONG }.show()
        }
    }
}