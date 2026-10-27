package io.screenstream.streaming.legacy

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.MainThread
import androidx.core.app.ServiceCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import io.screenstream.streaming.manager.StreamingModuleActionReceiver
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleNotifications
import org.koin.android.ext.android.inject
import java.util.UUID

/** Android host conveniences for the legacy module command and notification protocols. */
public abstract class LegacyStreamingModuleService : Service() {

    protected abstract val notificationIdError: Int

    protected val notifications: StreamingModuleNotifications by inject()

    protected val processedIntents: MutableSet<String> = mutableSetOf()

    @Volatile
    private var moduleRuntime: StreamingModule.Controller.Runtime? = null

    private val foregroundGate: Any = Any()
    private var foregroundClosed: Boolean = false
    private var foregroundRemovalRequired: Boolean = false
    private var foregroundNotification: Notification? = null
    private val notificationHandler: Handler = Handler(Looper.getMainLooper())
    private val refreshNotification: Runnable = Runnable { refreshForegroundNotification() }
    private val notificationPermissionListener: AppOpsManager.OnOpChangedListener = AppOpsManager.OnOpChangedListener { _, changedPackage ->
        if (changedPackage == null || changedPackage == packageName) notificationHandler.post(refreshNotification)
    }

    private val errorNotificationGate: Any = Any()
    private var errorNotificationsClosed: Boolean = false

    @Suppress("RedundantVisibilityModifier")
    protected companion object {
        public const val INTENT_ID: String = "info.dvkr.screenstream.intent.ID"

        public fun Intent.addIntentId(): Intent = putExtra(INTENT_ID, UUID.randomUUID().toString())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        XLog.d(getLog("onCreate"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val op = AppOpsManager.permissionToOp(Manifest.permission.POST_NOTIFICATIONS)
                if (op != null) getSystemService(AppOpsManager::class.java).startWatchingMode(op, packageName, notificationPermissionListener)
            } catch (failure: RuntimeException) {
                XLog.w(getLog("onCreate", "Could not watch notification permission"), failure)
            }
        }
    }

    /** Retain this exact host's admitted runtime before starting its legacy backend. */
    @MainThread
    internal fun attachRuntime(runtime: StreamingModule.Controller.Runtime) {
        check(moduleRuntime == null) { "Legacy Service already has an admitted runtime" }
        moduleRuntime = runtime
        returnToWaiting()
    }

    /** A factory can fail before any controller exists to own cleanup. */
    protected fun stopUnownedHost(startId: Int) {
        closeForegroundAdmission()
        try {
            stopForeground()
        } finally {
            stopSelfResult(startId)
        }
    }

    override fun onDestroy() {
        closeForegroundAdmission()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getSystemService(AppOpsManager::class.java).stopWatchingMode(notificationPermissionListener)
            }
        } catch (failure: RuntimeException) {
            XLog.w(getLog("onDestroy", "Could not stop watching notification permission"), failure)
        } finally {
            notificationHandler.removeCallbacks(refreshNotification)
            try {
                stopForeground()
            } finally {
                try {
                    closeErrorNotifications()
                } finally {
                    super.onDestroy()
                }
            }
        }
    }

    protected fun isDuplicateIntent(intent: Intent): Boolean {
        val id = intent.getStringExtra(INTENT_ID)
        return when {
            id == null -> {
                XLog.w(getLog("isDuplicateIntent", "No intent ID provided"))
                false
            }

            processedIntents.contains(id) -> {
                XLog.w(getLog("isDuplicateIntent", "Duplicate intent ID: $id"))
                true
            }

            else -> {
                processedIntents.add(id)
                false
            }
        }
    }

    @SuppressLint("InlinedApi")
    public fun startCaptureForeground(serviceType: Int) {
        updateForeground(serviceType, exitForegroundAction())
    }

    /** Keep the ownership URI; action identity prevents old PendingIntents being retargeted. */
    protected fun showCaptureForeground(stopIntent: Intent, generation: Long) {
        require(generation > 0)
        stopIntent.action = "io.screenstream.action.STOP_CAPTURE.$generation"
        val stopAction = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        updateForegroundPresentation(StreamingModuleNotifications.ForegroundAction.StopStream(stopAction))
    }

    /** Stop presentation changes immediately; projection/microphone types stay until resource release. */
    public fun captureStopping() {
        updateForegroundPresentation(exitForegroundAction())
    }

    @SuppressLint("InlinedApi")
    public fun returnToWaiting() {
        updateForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, exitForegroundAction())
    }

    public fun closeForegroundAdmission() {
        synchronized(foregroundGate) {
            foregroundClosed = true
            foregroundNotification = null
        }
    }

    private fun exitForegroundAction(): StreamingModuleNotifications.ForegroundAction.Exit {
        val original = checkNotNull(moduleRuntime) { "Legacy Service has no admitted runtime" }
        return StreamingModuleNotifications.ForegroundAction.Exit(StreamingModuleActionReceiver.exitAction(this, original.instanceId))
    }

    private fun updateForeground(serviceType: Int, action: StreamingModuleNotifications.ForegroundAction) {
        synchronized(foregroundGate) {
            val original = checkNotNull(moduleRuntime) { "Legacy Service has no admitted runtime" }
            if (foregroundClosed || !original.isCurrent()) return
            foregroundRemovalRequired = true
            try {
                val notification = notifications.buildForeground(action)
                ServiceCompat.startForeground(this, original.foregroundSlot.id, notification, serviceType)
                foregroundNotification = notification
            } catch (failure: Throwable) {
                foregroundClosed = true
                foregroundNotification = null
                original.reportFailed()
                throw failure
            }
        }
    }

    private fun updateForegroundPresentation(action: StreamingModuleNotifications.ForegroundAction) {
        synchronized(foregroundGate) {
            val original = checkNotNull(moduleRuntime) { "Legacy Service has no admitted runtime" }
            if (foregroundClosed || !foregroundRemovalRequired || !original.isCurrent()) return
            try {
                val notification = notifications.buildForeground(action)
                getSystemService(NotificationManager::class.java).notify(original.foregroundSlot.id, notification)
                foregroundNotification = notification
            } catch (failure: Throwable) {
                foregroundClosed = true
                foregroundNotification = null
                original.reportFailed()
                throw failure
            }
        }
    }

    @MainThread
    private fun refreshForegroundNotification() {
        try {
            synchronized(foregroundGate) {
                val original = moduleRuntime ?: return
                val notification = foregroundNotification ?: return
                if (foregroundClosed || !foregroundRemovalRequired || !original.isCurrent() ||
                    !notifications.areNotificationsEnabled(StreamingModuleNotifications.Channel.Streaming)
                ) return
                getSystemService(NotificationManager::class.java).notify(original.foregroundSlot.id, notification)
            }
        } catch (failure: RuntimeException) {
            XLog.w(getLog("refreshForegroundNotification", "Could not refresh foreground notification"), failure)
        }
    }

    public fun stopForeground() {
        XLog.d(getLog("stopForeground"))
        synchronized(foregroundGate) {
            foregroundClosed = true
            foregroundNotification = null
            if (!foregroundRemovalRequired) return
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundRemovalRequired = false
        }
    }

    protected fun showErrorNotification(message: String, recoverIntent: Intent?) {
        synchronized(errorNotificationGate) {
            if (errorNotificationsClosed) return
            val recoveryAction = recoverIntent?.let {
                PendingIntent.getService(this, 5, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            notifications.updateError(notificationIdError, StreamingModuleNotifications.ErrorContent.Text(message, recoveryAction))
        }
    }

    public fun hideErrorNotification() {
        synchronized(errorNotificationGate) {
            if (errorNotificationsClosed) return
            notifications.updateError(notificationIdError, null)
        }
    }

    /** Close this original host before cancellation so a late backend update cannot resurrect its slot. */
    public fun closeErrorNotifications() {
        synchronized(errorNotificationGate) {
            if (errorNotificationsClosed) return
            errorNotificationsClosed = true
            notifications.updateError(notificationIdError, null)
        }
    }
}
