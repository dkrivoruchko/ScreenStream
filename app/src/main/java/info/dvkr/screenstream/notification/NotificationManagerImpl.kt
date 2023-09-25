package info.dvkr.screenstream.notification

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import info.dvkr.screenstream.common.NotificationsManager
import info.dvkr.screenstream.common.getLog
import org.koin.core.annotation.Single

@Single
internal class NotificationManagerImpl(context: Context) : NotificationsManager {
    private companion object {
        private const val CHANNEL_STREAMING = "info.dvkr.screenstream.NOTIFICATION_CHANNEL_STREAMING"
        private const val CHANNEL_ERROR = "info.dvkr.screenstream.NOTIFICATION_CHANNEL_ERROR"
    }

    private val notificationManager = context.applicationContext.getSystemService(NotificationManager::class.java)

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

    @RequiresApi(Build.VERSION_CODES.O)
    internal fun getNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    internal fun areNotificationsEnabled(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || notificationManager.areNotificationsEnabled()).also {
            XLog.d(getLog("areNotificationsEnabled", "$it"))
        }

    internal fun isNotificationPermissionGranted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            .also { XLog.d(getLog("isNotificationPermissionGranted", "$it")) }

    override fun showForegroundNotification(service: Service, stopIntent: Intent) {
        val serviceName = service::class.java.simpleName + "#" + service.hashCode()

        if (isNotificationPermissionGranted(service).not()) {
            XLog.e(
                getLog("showStreamingNotification", "Service: $serviceName. No permission granted. Ignoring."),
                IllegalStateException("showStreamingNotification: Service: $serviceName. No permission granted. Ignoring.")
            )
            return //TODO Need prod logs
        }

        XLog.d(getLog("showForegroundNotification", "Service: $serviceName"))

        hideForegroundNotification(service)

        val appContext = service.applicationContext

        val notification = NotificationCompat.Builder(appContext, CHANNEL_STREAMING)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setLargeIcon(AppCompatResources.getDrawable(appContext, R.drawable.app_logo)?.toBitmap())
            .setOngoing(true)
            .setContentTitle(service.getString(R.string.app_notification_streaming_title))
            .setContentText(service.getString(R.string.app_notification_streaming_content))
            .setSmallIcon(R.drawable.ic_notification_small_anim_24dp)
            .setContentIntent(PendingIntent.getActivity(appContext, 0, AppActivity.getIntent(appContext), PendingIntent.FLAG_IMMUTABLE))
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_notification_stop_24dp,
                    service.getString(R.string.app_notification_stop),
                    PendingIntent.getService(appContext, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            XLog.d(getLog("showForegroundNotification", "Service: $serviceName. StartForeground on Q"))
            if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                service.startForeground(11, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            else
                service.startForeground(11, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            XLog.d(getLog("showForegroundNotification", "Service: $serviceName}. StartForeground"))
            service.startForeground(11, notification)
        }
    }

    @Suppress("DEPRECATION")
    override fun hideForegroundNotification(service: Service) {
        val serviceName = service::class.java.simpleName + "#" + service.hashCode()
        XLog.d(getLog("hideForegroundNotification", "Service: $serviceName"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        else service.stopForeground(true)
    }

    override fun showErrorNotification(context: Context, message: String, recoverIntent: Intent) {
        if (isNotificationPermissionGranted(context).not()) {
            XLog.e(
                getLog("showErrorNotification", "No permission granted. Ignoring."),
                IllegalStateException("showErrorNotification:  No permission granted. Ignoring.")
            )
            return //TODO Need prod logs
        }

        XLog.d(getLog("showErrorNotification"))

        hideErrorNotification()

        val appContext = context.applicationContext

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setLargeIcon(AppCompatResources.getDrawable(context, R.drawable.app_logo)?.toBitmap())
            .setSmallIcon(R.drawable.ic_notification_small_24dp)
            .setContentTitle(context.getString(R.string.app_error_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setColor(ContextCompat.getColor(context, R.color.colorError))
            .setContentIntent(PendingIntent.getActivity(appContext, 0, AppActivity.getIntent(appContext), PendingIntent.FLAG_IMMUTABLE))
            .addAction(
                NotificationCompat.Action(
                    null,
                    context.getString(R.string.app_error_recover),
                    PendingIntent.getService(appContext, 5, recoverIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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

        notificationManager.notify(50, builder.build())
    }

    override fun hideErrorNotification() {
        XLog.d(getLog("hideErrorNotification"))
        notificationManager.cancel(50)
    }
}