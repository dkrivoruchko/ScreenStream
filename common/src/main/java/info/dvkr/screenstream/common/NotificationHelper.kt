package info.dvkr.screenstream.common

import android.app.Notification
import android.content.Context
import android.content.Intent

public interface NotificationHelper {

    public fun notificationPermissionGranted(context: Context): Boolean

    public fun areNotificationsEnabled(): Boolean

    public fun getNotificationSettingsIntent(context: Context): Intent

    public fun createForegroundNotification(context: Context, stopIntent: Intent): Notification

    public fun getErrorNotification(context: Context, message: String, recoverIntent: Intent): Notification

    public fun showNotification(notificationId: Int, notification: Notification)

    public fun cancelNotification(notificationId: Int)
}