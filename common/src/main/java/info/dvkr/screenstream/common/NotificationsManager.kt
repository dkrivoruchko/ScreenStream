package info.dvkr.screenstream.common

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.annotation.MainThread

public interface NotificationsManager {

    public data object NotificationPermissionRequired : Throwable()

    @MainThread
    @Throws(NotificationPermissionRequired::class)
    public fun showForegroundNotification(service: Service, stopIntent: Intent)

    @MainThread
    public fun hideForegroundNotification(service: Service)

    @MainThread
    public fun showErrorNotification(context: Context, notificationId: Int, message: String, recoverIntent: Intent)

    @MainThread
    public fun hideErrorNotification(notificationId: Int)
}