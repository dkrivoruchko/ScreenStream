package info.dvkr.screenstream.service.helper

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.NotificationHelper
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.ui.activity.AppActivity

class NotificationHelperImpl(context: Context) : NotificationHelper {
    companion object {
        private const val CHANNEL_STREAM = "info.dvkr.screenstream.NOTIFICATION_CHANNEL_START_STOP"
        private const val CHANNEL_ERROR = "info.dvkr.screenstream.NOTIFICATION_CHANNEL_ERROR"
    }

    private val applicationContext: Context = context.applicationContext
    private val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var currentNotificationType: NotificationHelper.NotificationType? = null

    //todo val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    //todo notificationManager.areNotificationsEnabled()

    init {
        createNotificationChannel()
    }

    override fun createNotificationChannel() {
        currentNotificationType = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel("info.dvkr.screenstream.service.NOTIFICATION_CHANNEL_01")

            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_STREAM, "Start/Stop notifications", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        setSound(null, null)
                        enableLights(false)
                        enableVibration(false)
                        setShowBadge(false)
                    }
            )

            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ERROR, "Error notifications", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        setSound(null, null)
                        enableLights(false)
                        enableVibration(false)
                        setShowBadge(false)
                    }
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getNotificationSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)

    override fun isNotificationPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun showNotification(service: Service, notificationType: NotificationHelper.NotificationType) {
        val message = "Service:${service.hashCode()}, NotificationType: $notificationType."

        if (ContextCompat.checkSelfPermission(service, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            XLog.d(getLog("showNotification", "$message No permission granted. Ignoring."))
            return
        }

        if (currentNotificationType == notificationType) {
            XLog.d(getLog("showNotification", "$message Same as current. Ignoring."))
            return
        }
        XLog.d(getLog("showNotification", "Service:${service.hashCode()}, NotificationType: $notificationType"))

        clearNotification(service)

        val notification = getNotification(notificationType)

        if (notificationType == NotificationHelper.NotificationType.START) {
            XLog.d(getLog("showNotification", "$message Show"))
            notificationManager.notify(notificationType.id, notification)
        }

        if (notificationType == NotificationHelper.NotificationType.STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                XLog.d(getLog("showNotification", "$message startForeground on Q"))
                if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    service.startForeground(notificationType.id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
                else
                    service.startForeground(notificationType.id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                XLog.d(getLog("showNotification", "$message startForeground"))
                service.startForeground(notificationType.id, notification)
            }
        }

        currentNotificationType = notificationType
    }

    override fun clearNotification(service: Service) {
        XLog.d(getLog("clearNotification", "Service:${service.hashCode()}, NotificationType: $currentNotificationType"))

        currentNotificationType?.let {
            if (it == NotificationHelper.NotificationType.START) {
                XLog.d(getLog("clearNotification", "Service:${service.hashCode()}, NotificationType: $it. Cancel it"))
                notificationManager.cancel(it.id)
            }

            if (it == NotificationHelper.NotificationType.STOP) {
                XLog.d(getLog("clearNotification", "Service:${service.hashCode()}, NotificationType: $it. StopForeground"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                else service.stopForeground(true)
            }
        }
    }

    override fun showErrorNotification(appError: AppError) {
        notificationManager.cancel(NotificationHelper.NotificationType.ERROR.id)

        val message = appError.toString(applicationContext)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setLargeIcon(AppCompatResources.getDrawable(applicationContext, R.drawable.logo)?.toBitmap())
            .setSmallIcon(R.drawable.ic_notification_small_24dp)
            .setContentTitle(applicationContext.getString(R.string.error_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setColor(ContextCompat.getColor(applicationContext, R.color.colorError))
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext, 0, AppActivity.getStartIntent(applicationContext), PendingIntent.FLAG_IMMUTABLE
                )
            )

        if (appError is AppError.FixableError)
            builder.addAction(
                NotificationCompat.Action(
                    null,
                    applicationContext.getString(R.string.error_retry),
                    PendingIntent.getService(
                        applicationContext, 5,
                        IntentAction.RecoverError.toAppServiceIntent(applicationContext),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            )
        else
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_notification_exit_24dp,
                    applicationContext.getString(R.string.error_exit),
                    PendingIntent.getService(
                        applicationContext, 5,
                        IntentAction.Exit.toAppServiceIntent(applicationContext),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.getNotificationChannel(CHANNEL_ERROR)?.let { notificationChannel ->
                builder
                    .setSound(notificationChannel.sound)
                    .setPriority(notificationChannel.importance)
                    .setVibrate(notificationChannel.vibrationPattern)
            }
        }

        notificationManager.notify(NotificationHelper.NotificationType.ERROR.id, builder.build())
    }

    override fun hideErrorNotification() {
        notificationManager.cancel(NotificationHelper.NotificationType.ERROR.id)
    }

    private fun getNotification(notificationType: NotificationHelper.NotificationType): Notification {
        XLog.d(getLog("getNotification", "NotificationType: $notificationType"))

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_STREAM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setLargeIcon(AppCompatResources.getDrawable(applicationContext, R.drawable.logo)?.toBitmap())
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext, 0, AppActivity.getStartIntent(applicationContext), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.getNotificationChannel(CHANNEL_STREAM)?.let { notificationChannel ->
                builder
                    .setSound(notificationChannel.sound)
                    .setPriority(notificationChannel.importance)
                    .setVibrate(notificationChannel.vibrationPattern)
            }
        }

        return when (notificationType) {
            NotificationHelper.NotificationType.START -> builder
                .setContentTitle(applicationContext.getString(R.string.notification_ready_to_stream))
                .setContentText(applicationContext.getString(R.string.notification_press_start))
                .setSmallIcon(R.drawable.ic_notification_small_24dp)
                .addAction(
                    NotificationCompat.Action(
                        R.drawable.ic_notification_start_24dp,
                        applicationContext.getString(R.string.notification_start),
                        PendingIntent.getActivity(
                            applicationContext, 1,
                            IntentAction.StartStream.toAppActivityIntent(applicationContext),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                )
                .addAction(
                    NotificationCompat.Action(
                        R.drawable.ic_notification_exit_24dp,
                        applicationContext.getString(R.string.notification_exit),
                        PendingIntent.getService(
                            applicationContext, 3,
                            IntentAction.Exit.toAppServiceIntent(applicationContext),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                )
                .build()

            NotificationHelper.NotificationType.STOP -> builder
                .setContentTitle(applicationContext.getString(R.string.notification_stream))
                .setContentText(applicationContext.getString(R.string.notification_press_stop))
                .setSmallIcon(R.drawable.ic_notification_small_anim_24dp)
                .addAction(
                    NotificationCompat.Action(
                        R.drawable.ic_notification_stop_24dp,
                        applicationContext.getString(R.string.notification_stop),
                        PendingIntent.getService(
                            applicationContext, 2,
                            IntentAction.StopStream.toAppServiceIntent(applicationContext),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                )
                .build()

            else -> throw IllegalArgumentException("Unexpected notification type: $notificationType")
        }
    }
}