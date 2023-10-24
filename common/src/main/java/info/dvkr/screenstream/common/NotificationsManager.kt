package info.dvkr.screenstream.common

import android.app.Service
import android.content.Context
import android.content.Intent

public interface NotificationsManager {

    public data object NotificationPermissionRequired : Throwable()

    @Throws(NotificationPermissionRequired::class)
    public fun showForegroundNotification(service: Service, stopIntent: Intent)
    public fun hideForegroundNotification(service: Service)
    public fun showErrorNotification(context: Context, message: String, recoverIntent: Intent)
    public fun hideErrorNotification()
}