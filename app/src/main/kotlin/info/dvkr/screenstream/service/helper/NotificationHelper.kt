package info.dvkr.screenstream.service.helper

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.FixableError
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.ui.activity.AppActivity

class NotificationHelper(context: Context) {
    companion object {
        private const val CHANNEL_START_STOP = "info.dvkr.screenstream.NOTIFICATION_CHANNEL_START_STOP"
        private const val CHANNEL_ERROR = "info.dvkr.screenstream.NOTIFICATION_CHANNEL_ERROR"
    }

    enum class NotificationType(val id: Int) { START(10), STOP(11), ERROR(50) }

    private val applicationContext: Context = context.applicationContext
    private val packageName: String = applicationContext.packageName
    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var currentNotificationType: NotificationType? = null

    fun createNotificationChannel() {
        currentNotificationType = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel("info.dvkr.screenstream.service.NOTIFICATION_CHANNEL_01")
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_START_STOP, "Start/Stop notifications", NotificationManager.IMPORTANCE_DEFAULT
                )
                    .apply {
                        setSound(null, null)
                        enableLights(false)
                        enableVibration(false)
                        setShowBadge(false)
                    }
            )

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ERROR, "Error notifications", NotificationManager.IMPORTANCE_HIGH
                )
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
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)

    fun showForegroundNotification(service: Service, notificationType: NotificationType) {
        if (currentNotificationType != notificationType) {
            service.startForeground(notificationType.id, getForegroundNotification(notificationType))
            currentNotificationType = notificationType
        }
    }

    fun showErrorNotification(appError: AppError) {
        notificationManager.cancel(NotificationType.ERROR.id)

        val pendingStartAppActivityIntent = PendingIntent.getActivity(
            applicationContext, 0, AppActivity.getStartIntent(applicationContext), 0
        )

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ERROR).apply {
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setCategory(Notification.CATEGORY_ERROR)
            priority = NotificationCompat.PRIORITY_HIGH
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setSound(notificationManager.getNotificationChannel(CHANNEL_ERROR).sound)
                priority = notificationManager.getNotificationChannel(CHANNEL_ERROR).importance
                setVibrate(notificationManager.getNotificationChannel(CHANNEL_ERROR).vibrationPattern)
            }
        }

        builder.setSmallIcon(R.drawable.ic_notification_small_24dp)

        val actionIntent = PendingIntent.getService(
            applicationContext, 5,
            if (appError is FixableError) IntentAction.RecoverError.toAppServiceIntent(applicationContext)
            else IntentAction.Exit.toAppServiceIntent(applicationContext),
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val message: String = when (appError) {
            is FixableError.AddressInUseException ->
                applicationContext.getString(R.string.error_port_in_use)
            is FixableError.CastSecurityException ->
                applicationContext.getString(R.string.error_invalid_media_projection)
            is FixableError.AddressNotFoundException ->
                applicationContext.getString(R.string.error_ip_address_not_found)
            is FatalError.BitmapFormatException ->
                applicationContext.getString(R.string.error_wrong_image_format)
            else -> appError.toString()
        }

        val actionText = applicationContext.getString(
            if (appError is FixableError) android.R.string.ok else R.string.error_exit
        )

        builder.setCustomContentView(
            RemoteViews(packageName, R.layout.notification_error_small).apply {
                setOnClickPendingIntent(R.id.ll_notification_error_small, pendingStartAppActivityIntent)
                setImageViewResource(R.id.iv_notification_error_small_main, R.drawable.logo)
                setTextViewText(R.id.tv_notification_error_small_message, message)
                setTextViewText(R.id.tv_notification_error_small_action, actionText)
                setOnClickPendingIntent(R.id.tv_notification_error_small_action, actionIntent)
            })

        notificationManager.notify(NotificationType.ERROR.id, builder.build())
    }

    fun hideErrorNotification() {
        notificationManager.cancel(NotificationType.ERROR.id)
    }

    private fun getForegroundNotification(notificationType: NotificationType): Notification {
        XLog.d(getLog("getForegroundNotification", "NotificationType: $notificationType"))

        val pendingStartAppActivityIntent = PendingIntent.getActivity(
            applicationContext, 0, AppActivity.getStartIntent(applicationContext), 0
        )

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_START_STOP).apply {
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setCategory(Notification.CATEGORY_SERVICE)
            priority = NotificationCompat.PRIORITY_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setSound(notificationManager.getNotificationChannel(CHANNEL_START_STOP).sound)
                priority = notificationManager.getNotificationChannel(CHANNEL_START_STOP).importance
                setVibrate(notificationManager.getNotificationChannel(CHANNEL_START_STOP).vibrationPattern)
            }
        }

        when (notificationType) {
            NotificationType.START -> {
                builder.setSmallIcon(R.drawable.ic_notification_small_24dp)

                val startIntent = PendingIntent.getActivity(
                    applicationContext, 1,
                    IntentAction.StartStream.toAppActivityIntent(applicationContext),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                builder.setCustomContentView(
                    RemoteViews(packageName, R.layout.notification_start_small).apply {
                        setOnClickPendingIntent(R.id.ll_notification_start_small, pendingStartAppActivityIntent)
                        setImageViewResource(R.id.iv_notification_start_small_main, R.drawable.logo)
                        setImageViewResource(
                            R.id.iv_notification_start_small_start, R.drawable.ic_notification_start_24dp
                        )
                        setOnClickPendingIntent(R.id.iv_notification_start_small_start, startIntent)
                    })

                val exitIntent = PendingIntent.getService(
                    applicationContext, 3,
                    IntentAction.Exit.toAppServiceIntent(applicationContext),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                builder.setCustomBigContentView(
                    RemoteViews(packageName, R.layout.notification_start_big).apply {
                        setOnClickPendingIntent(R.id.ll_notification_start_big, pendingStartAppActivityIntent)
                        setImageViewResource(R.id.iv_notification_start_big_main, R.drawable.logo)
                        setTextViewCompoundDrawables(
                            R.id.tv_notification_start_big_start,
                            R.drawable.ic_notification_start_24dp, 0, 0, 0
                        )
                        setTextViewCompoundDrawables(
                            R.id.tv_notification_start_big_exit,
                            R.drawable.ic_notification_exit_24dp, 0, 0, 0
                        )
                        setOnClickPendingIntent(R.id.ll_notification_start_big_start, startIntent)
                        setOnClickPendingIntent(R.id.ll_notification_start_big_exit, exitIntent)
                    })
            }

            NotificationType.STOP -> {
                builder.setSmallIcon(R.drawable.ic_notification_small_anim_24dp)

                val stopIntent = PendingIntent.getService(
                    applicationContext, 2,
                    IntentAction.StopStream.toAppServiceIntent(applicationContext),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                builder.setCustomContentView(
                    RemoteViews(packageName, R.layout.notification_stop_small).apply {
                        setOnClickPendingIntent(R.id.ll_notification_stop_small, pendingStartAppActivityIntent)
                        setImageViewResource(R.id.iv_notification_stop_small_main, R.drawable.logo)
                        setImageViewResource(R.id.iv_notification_stop_small_stop, R.drawable.ic_notification_stop_24dp)
                        setOnClickPendingIntent(R.id.iv_notification_stop_small_stop, stopIntent)
                    })

                builder.setCustomBigContentView(
                    RemoteViews(packageName, R.layout.notification_stop_big).apply {
                        setOnClickPendingIntent(R.id.ll_notification_stop_big, pendingStartAppActivityIntent)
                        setImageViewResource(R.id.iv_notification_stop_big_main, R.drawable.logo)
                        setTextViewCompoundDrawables(
                            R.id.tv_notification_stop_big_stop,
                            R.drawable.ic_notification_stop_24dp, 0, 0, 0
                        )
                        setOnClickPendingIntent(R.id.ll_notification_stop_big_stop, stopIntent)
                    })
            }
        }

        return builder.build()
    }
}