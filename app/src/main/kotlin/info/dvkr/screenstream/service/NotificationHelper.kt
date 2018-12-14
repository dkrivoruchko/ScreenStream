package info.dvkr.screenstream.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getTag
import info.dvkr.screenstream.ui.activity.AppActivity
import timber.log.Timber

class NotificationHelper(context: Context) {

    enum class NotificationType(val id: Int) { START(10), STOP(11) }
    private val applicationContext: Context = context.applicationContext
    private val packageName: String = applicationContext.packageName
    private val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java)!!
    private val CHANNEL_ID = "info.dvkr.screenstream.service.NOTIFICATION_CHANNEL_01"
    private var currentNotificationType: NotificationType? = null

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen Stream Channel", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableLights(false)
                    enableVibration(false)
                }
            )
        }
    }

    fun showForegroundNotification(service: Service, notificationType: NotificationType) {
        if (currentNotificationType != notificationType) {
            service.stopForeground(true)
            service.startForeground(notificationType.id, getNotification(notificationType))
            currentNotificationType = notificationType
        }
    }

    private fun getNotification(notificationType: NotificationType): Notification {
        Timber.tag(getTag("getNotification")).d("NotificationType: $notificationType")

        val pendingAppActivityIntent = PendingIntent.getActivity(
            applicationContext, 0,
            AppActivity.getStartIntent(applicationContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            0
        )

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID).apply {
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setCategory(Notification.CATEGORY_SERVICE)
            priority = NotificationCompat.PRIORITY_HIGH
            setSmallIcon(R.drawable.ic_notification_small_24dp)
        }

        when (notificationType) {
            NotificationType.START -> {
                val startIntent = PendingIntent.getService(
                    applicationContext, 1,
                    AppService.getStartIntent(applicationContext, AppService.IntentAction.StartStream),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                builder.setCustomContentView(
                    RemoteViews(packageName, R.layout.notification_start_small).apply {
                        setOnClickPendingIntent(R.id.ll_notification_start_small, pendingAppActivityIntent)
                        setImageViewResource(R.id.iv_notification_start_small_main, R.drawable.ic_app_icon)
                        setImageViewResource(
                            R.id.iv_notification_start_small_start, R.drawable.ic_notification_start_24dp
                        )
                        setOnClickPendingIntent(R.id.iv_notification_start_small_start, startIntent)
                    })

                val exitIntent = PendingIntent.getService(
                    applicationContext, 3,
                    AppService.getStartIntent(applicationContext, AppService.IntentAction.Exit),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                builder.setCustomBigContentView(
                    RemoteViews(packageName, R.layout.notification_start_big).apply {
                        setOnClickPendingIntent(R.id.ll_notification_start_big, pendingAppActivityIntent)
                        setImageViewResource(R.id.iv_notification_start_big_main, R.drawable.ic_app_icon)
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
                val stopIntent = PendingIntent.getService(
                    applicationContext, 2,
                    AppService.getStartIntent(applicationContext, AppService.IntentAction.StopStream),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                builder.setCustomContentView(
                    RemoteViews(packageName, R.layout.notification_stop_small).apply {
                        setOnClickPendingIntent(R.id.ll_notification_stop_small, pendingAppActivityIntent)
                        setImageViewResource(R.id.iv_notification_stop_small_main, R.drawable.ic_app_icon)
                        setImageViewResource(R.id.iv_notification_stop_small_stop, R.drawable.ic_notification_stop_24dp)
                        setOnClickPendingIntent(R.id.iv_notification_stop_small_stop, stopIntent)
                    })

                builder.setCustomBigContentView(
                    RemoteViews(packageName, R.layout.notification_stop_big).apply {
                        setOnClickPendingIntent(R.id.ll_notification_stop_big, pendingAppActivityIntent)
                        setImageViewResource(R.id.iv_notification_stop_big_main, R.drawable.ic_app_icon)
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