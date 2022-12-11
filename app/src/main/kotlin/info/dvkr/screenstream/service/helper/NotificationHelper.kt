package info.dvkr.screenstream.service.helper

import android.app.*
import android.content.Context
import android.content.Intent
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
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.AddressInUseException
import info.dvkr.screenstream.mjpeg.AddressNotFoundException
import info.dvkr.screenstream.mjpeg.BitmapFormatException
import info.dvkr.screenstream.mjpeg.CastSecurityException
import info.dvkr.screenstream.ui.activity.AppActivity

class NotificationHelper(context: Context) {
    companion object {
        private const val CHANNEL_STREAM = "info.dvkr.screenstream.NOTIFICATION_CHANNEL_START_STOP"
        private const val CHANNEL_ERROR = "info.dvkr.screenstream.NOTIFICATION_CHANNEL_ERROR"
    }

    enum class NotificationType(val id: Int) { START(10), STOP(11), ERROR(50) }

    private val applicationContext: Context = context.applicationContext
    private val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val flagImmutable = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) 0 else PendingIntent.FLAG_IMMUTABLE

    private var currentNotificationType: NotificationType? = null

    fun createNotificationChannel() {
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
    fun getNotificationSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)

    fun showForegroundNotification(service: Service, notificationType: NotificationType) {
        XLog.d(getLog("showForegroundNotification", "Service:${service.hashCode()}, NotificationType: $notificationType"))
        if (currentNotificationType != notificationType) {
            val notification = getForegroundNotification(notificationType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                XLog.d(getLog("showForegroundNotification", "service.startForeground.Q: Service:${service.hashCode()}, NotificationType: $notificationType"))
                service.startForeground(notificationType.id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                XLog.d(getLog("showForegroundNotification", "service.startForeground: Service:${service.hashCode()}, NotificationType: $notificationType"))
                service.startForeground(notificationType.id, notification)
            }
            currentNotificationType = notificationType
            XLog.d(getLog("showForegroundNotification", "service.startForeground: Service:${service.hashCode()}, currentNotificationType: $currentNotificationType"))
        }
    }

    fun showErrorNotification(appError: AppError) {
        notificationManager.cancel(NotificationType.ERROR.id)

        val message: String = when (appError) {
            is AddressInUseException ->
                applicationContext.getString(R.string.error_port_in_use)
            is CastSecurityException ->
                applicationContext.getString(R.string.error_invalid_media_projection)
            is AddressNotFoundException ->
                applicationContext.getString(R.string.error_ip_address_not_found)
            is BitmapFormatException ->
                applicationContext.getString(R.string.error_wrong_image_format)
            else -> appError.toString()
        }

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
                    applicationContext, 0, AppActivity.getStartIntent(applicationContext), flagImmutable
                )
            )

        if (appError is AppError.FixableError)
            builder.addAction(
                NotificationCompat.Action(
                    null,
                    applicationContext.getString(android.R.string.ok),
                    PendingIntent.getService(
                        applicationContext, 5,
                        IntentAction.RecoverError.toAppServiceIntent(applicationContext),
                        flagImmutable or PendingIntent.FLAG_UPDATE_CURRENT
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
                        flagImmutable or PendingIntent.FLAG_UPDATE_CURRENT
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

        notificationManager.notify(NotificationType.ERROR.id, builder.build())
    }

    fun hideErrorNotification() {
        notificationManager.cancel(NotificationType.ERROR.id)
    }

    private fun getForegroundNotification(notificationType: NotificationType): Notification {
        XLog.d(getLog("getForegroundNotification", "NotificationType: $notificationType"))

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_STREAM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setLargeIcon(AppCompatResources.getDrawable(applicationContext, R.drawable.logo)?.toBitmap())
            .setContentIntent(
                PendingIntent.getActivity(applicationContext, 0, AppActivity.getStartIntent(applicationContext), flagImmutable)
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
            NotificationType.START -> builder
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
                            flagImmutable or PendingIntent.FLAG_UPDATE_CURRENT
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
                            flagImmutable or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                )
                .build()

            NotificationType.STOP -> builder
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
                            flagImmutable or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                )
                .build()

            else -> throw IllegalArgumentException("Unexpected notification type: $notificationType")
        }
    }
}