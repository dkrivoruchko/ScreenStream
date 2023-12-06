package info.dvkr.screenstream.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.activity.AppActivity
import info.dvkr.screenstream.common.NotificationHelper
import info.dvkr.screenstream.common.getLog
import org.koin.core.annotation.Single

@Single
internal class NotificationHelperImpl(context: Context) : NotificationHelper {
    private companion object {
        private const val CHANNEL_STREAMING = "info.dvkr.screenstream.NOTIFICATION_CHANNEL_STREAMING"
        private const val CHANNEL_ERROR = "info.dvkr.screenstream.NOTIFICATION_CHANNEL_ERROR"
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    init {
        createNotificationChannel(context)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        notificationManager.deleteNotificationChannel("info.dvkr.screenstream.service.NOTIFICATION_CHANNEL_01")
        notificationManager.deleteNotificationChannel("info.dvkr.screenstream.NOTIFICATION_CHANNEL_START_STOP")

        val streamingName = context.getString(R.string.app_notification_channel_streaming)
        NotificationChannel(CHANNEL_STREAMING, streamingName, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
        }.let { notificationManager.createNotificationChannel(it) }

        val errorName = context.getString(R.string.app_notification_channel_error)
        NotificationChannel(CHANNEL_ERROR, errorName, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
        }.let { notificationManager.createNotificationChannel(it) }
    }

    override fun notificationPermissionGranted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            .also { XLog.d(getLog("notificationPermissionGranted", "$it")) }

    override fun areNotificationsEnabled(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || notificationManager.areNotificationsEnabled())
            .also { XLog.d(getLog("areNotificationsEnabled", "$it")) }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    override fun createForegroundNotification(context: Context, stopIntent: Intent): Notification {
        XLog.d(getLog("createForegroundNotification", "context: ${context::class.java.simpleName}#${context.hashCode()}"))

        return NotificationCompat.Builder(context, CHANNEL_STREAMING)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setLargeIcon(AppCompatResources.getDrawable(context, R.drawable.app_logo)?.toBitmap())
            .setOngoing(true)
            .setContentTitle(context.getString(R.string.app_notification_streaming_title))
            .setContentText(context.getString(R.string.app_notification_streaming_content))
            .setSmallIcon(R.drawable.ic_notification_small_anim_24dp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(context, 0, AppActivity.getIntent(context), PendingIntent.FLAG_IMMUTABLE))
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_notification_stop_24dp,
                    context.getString(R.string.app_notification_stop),
                    PendingIntent.getService(context, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE)
                )
            ).also { builder ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    notificationManager.getNotificationChannel(CHANNEL_STREAMING)?.let { notificationChannel ->
                        builder.setSound(notificationChannel.sound)
                            .setPriority(notificationChannel.importance)
                            .setVibrate(notificationChannel.vibrationPattern)
                    }
                }
            }.build()
    }

    override fun getErrorNotification(context: Context, message: String, recoverIntent: Intent): Notification {
        XLog.d(getLog("getErrorNotification", "context: ${context::class.java.simpleName}#${context.hashCode()}, message: $message"))

        return NotificationCompat.Builder(context, CHANNEL_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setLargeIcon(AppCompatResources.getDrawable(context, R.drawable.app_logo)?.toBitmap())
            .setSmallIcon(R.drawable.ic_notification_small_24dp)
            .setContentTitle(context.getString(R.string.app_error_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setColor(ContextCompat.getColor(context, R.color.colorError))
            .setContentIntent(PendingIntent.getActivity(context, 0, AppActivity.getIntent(context), PendingIntent.FLAG_IMMUTABLE))
            .addAction(
                NotificationCompat.Action(
                    null,
                    context.getString(R.string.app_error_recover),
                    PendingIntent.getService(context, 5, recoverIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            ).also { builder ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    notificationManager.getNotificationChannel(CHANNEL_ERROR)?.let { notificationChannel ->
                        builder
                            .setSound(notificationChannel.sound)
                            .setPriority(notificationChannel.importance)
                            .setVibrate(notificationChannel.vibrationPattern)
                    }
                }
            }
            .build()
    }

    override fun showNotification(notificationId: Int, notification: Notification) {
        XLog.d(getLog("showNotification", "$notificationId"))
        notificationManager.notify(notificationId, notification)
    }

    override fun cancelNotification(notificationId: Int) {
        XLog.d(getLog("cancelNotification", "$notificationId"))
        notificationManager.cancel(notificationId)
    }
}