package info.dvkr.screenstream.common.module

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.NotificationHelper
import info.dvkr.screenstream.common.getLog
import org.koin.android.ext.android.inject

public abstract class AbstractModuleService : Service() {

    protected abstract val notificationIdForeground: Int
    protected abstract val notificationIdError: Int

    protected val streamingModulesManager: StreamingModulesManager by inject(mode = LazyThreadSafetyMode.NONE)
    protected val notificationHelper: NotificationHelper by inject(mode = LazyThreadSafetyMode.NONE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        XLog.d(getLog("onCreate"))
    }

    override fun onDestroy() {
        stopForeground()
        hideErrorNotification()
        super.onDestroy()
    }

    protected fun startForeground(stopIntent: Intent) {
        val notification = notificationHelper.createForegroundNotification(this, stopIntent)

        val audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        val serviceType = if (audioPermission == PackageManager.PERMISSION_GRANTED) ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

        ServiceCompat.startForeground(this, notificationIdForeground, notification, serviceType)
    }

    public fun stopForeground() {
        XLog.d(getLog("stopForeground"))

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    protected fun showErrorNotification(message: String, recoverIntent: Intent) {
        hideErrorNotification()

        if (notificationHelper.notificationPermissionGranted(this).not()) {
            XLog.e(getLog("showErrorNotification", "No permission granted. Ignoring."))
            return
        }

        val notification = notificationHelper.getErrorNotification(this, message, recoverIntent)
        notificationHelper.showNotification(notificationIdError, notification)
    }

    public fun hideErrorNotification() {
        XLog.d(getLog("hideErrorNotification"))

        notificationHelper.cancelNotification(notificationIdError)
    }
}

