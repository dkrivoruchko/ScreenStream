package info.dvkr.screenstream.common.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.MainThread

public interface NotificationHelper {

    public fun canOpenAppNotificationSettings(): Boolean

    public fun notificationPermissionGranted(context: Context): Boolean

    public fun foregroundNotificationsEnabled(): Boolean

    public fun errorNotificationsEnabled(): Boolean

    public fun getNotificationSettingsIntent(): Intent

    public fun getStreamNotificationSettingsIntent(): Intent

    public fun createForegroundNotification(context: Context, stopIntent: Intent): Notification

    /** Builds the streaming notification with a ready Stop action; call on Main before promotion. */
    @MainThread
    public fun createForegroundNotification(context: Context, stopAction: PendingIntent): Notification

    public fun getErrorNotification(context: Context, message: String, recoverIntent: Intent?): Notification

    public fun showNotification(notificationId: Int, notification: Notification)

    public fun cancelNotification(notificationId: Int)
}
