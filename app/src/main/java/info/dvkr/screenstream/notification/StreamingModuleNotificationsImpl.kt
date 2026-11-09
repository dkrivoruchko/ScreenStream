package info.dvkr.screenstream.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.SingleActivity
import info.dvkr.screenstream.common.getLog
import io.screenstream.core.module.StreamingModuleNotifications
import org.koin.core.annotation.Singleton

@Singleton(binds = [StreamingModuleNotifications::class])
internal class StreamingModuleNotificationsImpl(context: Context) : StreamingModuleNotifications {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val largeIcon by lazy { AppCompatResources.getDrawable(appContext, R.drawable.logo)?.toBitmap() }
    private val localizedContext: Context
        get() = ContextCompat.getContextForLanguage(appContext)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel("info.dvkr.screenstream.service.NOTIFICATION_CHANNEL_01")
            notificationManager.deleteNotificationChannel("info.dvkr.screenstream.NOTIFICATION_CHANNEL_START_STOP")

            with(localizedContext) {
                for (channel in StreamingModuleNotifications.Channel.entries) {
                    val name = getString(
                        when (channel) {
                            StreamingModuleNotifications.Channel.Streaming -> R.string.app_notification_channel_streaming
                            StreamingModuleNotifications.Channel.Error -> R.string.app_notification_channel_error
                        }
                    )
                    val notificationChannel = NotificationChannel(channel.id, name, channel.defaultImportance).apply {
                        setSound(null, null)
                        enableLights(false)
                        enableVibration(false)
                        setShowBadge(false)
                    }
                    notificationManager.createNotificationChannel(notificationChannel)
                }
            }
        }
    }

    override fun buildForeground(action: StreamingModuleNotifications.ForegroundAction): Notification = with(localizedContext) {
        val builder = NotificationCompat.Builder(this, StreamingModuleNotifications.Channel.Streaming.id)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setLargeIcon(largeIcon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent())

        when (action) {
            is StreamingModuleNotifications.ForegroundAction.Exit -> builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.app_notification_waiting_content))
                .setSmallIcon(R.drawable.ic_notification_small_24dp)
                .addAction(NotificationCompat.Action(null, getString(R.string.app_tab_exit), action.pendingIntent))

            is StreamingModuleNotifications.ForegroundAction.StopStream -> builder
                .setContentTitle(getString(R.string.app_notification_streaming_title))
                .setContentText(getString(R.string.app_notification_streaming_content))
                .setSmallIcon(R.drawable.ic_notification_small_anim_24dp)
                .addAction(NotificationCompat.Action(null, getString(R.string.app_notification_stop), action.pendingIntent))
        }.build()
    }

    override fun updateError(notificationId: Int, content: StreamingModuleNotifications.ErrorContent?) {
        require(notificationId > 0) { "Notification ID must be positive" }
        try {
            if (content == null) {
                notificationManager.cancel(notificationId)
            } else if (areNotificationsEnabled(StreamingModuleNotifications.Channel.Error)) {
                notificationManager.notify(notificationId, buildError(content))
            }
        } catch (failure: Exception) {
            XLog.e(getLog("updateError", "Error notification delivery failed: id=$notificationId, cancel=${content == null}"), failure)
        }
    }

    override fun areNotificationsEnabled(channel: StreamingModuleNotifications.Channel): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!notificationManager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val importance = notificationManager.getNotificationChannel(channel.id)?.importance ?: NotificationManager.IMPORTANCE_NONE
        return importance > NotificationManager.IMPORTANCE_NONE
    }

    override fun channelSettingsIntent(channel: StreamingModuleNotifications.Channel): Intent? = resolveSettingsIntent(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channel.id)
        } else {
            appDetailsIntent()
        }
    )

    private fun buildError(content: StreamingModuleNotifications.ErrorContent): Notification = with(localizedContext) {
        val message = when (content) {
            is StreamingModuleNotifications.ErrorContent.Text -> content.message
            is StreamingModuleNotifications.ErrorContent.ModuleProblem -> getString(content.messageResource ?: R.string.app_module_error_title)
        }
        val builder = NotificationCompat.Builder(this, StreamingModuleNotifications.Channel.Error.id)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setLargeIcon(largeIcon)
            .setSmallIcon(R.drawable.ic_notification_small_24dp)
            .setContentTitle(getString(R.string.app_error_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent())
        if (content is StreamingModuleNotifications.ErrorContent.Text) {
            content.recoveryAction?.let { recoveryAction ->
                builder.addAction(NotificationCompat.Action(null, getString(R.string.app_error_recover), recoveryAction))
            }
        }
        builder.build()
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            0,
            SingleActivity.getIntent(appContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appContext.packageName, null))

    private fun resolveSettingsIntent(intent: Intent): Intent? =
        intent.takeIf { it.resolveActivity(appContext.packageManager) != null }
}
