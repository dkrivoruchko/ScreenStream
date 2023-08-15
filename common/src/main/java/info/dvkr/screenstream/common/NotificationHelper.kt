package info.dvkr.screenstream.common

import android.app.Service
import android.content.Context
import android.content.Intent

public interface NotificationHelper {
    enum class NotificationType(val id: Int) { START(10), STOP(11), ERROR(50) }

    fun createNotificationChannel()
    fun getNotificationSettingsIntent(): Intent
    fun isNotificationPermissionGranted(context: Context): Boolean
    fun showNotification(service: Service, notificationType: NotificationType)
    fun clearNotification(service: Service)
    fun showErrorNotification(appError: AppError)
    fun hideErrorNotification()
}