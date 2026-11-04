package io.screenstream.core.module

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.app.ServiceCompat
import io.screenstream.core.manager.StreamingModuleActionReceiver
import io.screenstream.core.manager.StreamingModuleManagerImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Android Service hosting one module controller and its foreground notification.
 *
 * For an admitted start, the Service enters waiting foreground before invoking [createController].
 * The controller owns module work and cleanup, which may outlive this Service. The attempt owner
 * serializes foreground operations and validates capture admission; capture resources belong to the
 * module. Concrete Services declare their manifest permissions, foreground types, and specialUse subtype.
 * Binding returns null, and the Service returns START_NOT_STICKY.
 */
public abstract class StreamingModuleService : Service() {
    /**
     * Module-supplied factory for an inert [StreamingModule.Controller] bound to [managerHandle].
     * [StreamingModuleService] enters waiting foreground before invoking this factory. Return the
     * controller with module work and instance-resource acquisition still pending. The manager adopts
     * the returned controller and validates its identity, then calls [StreamingModule.Controller.startModule]
     * if the instance is still admitted; otherwise it requests shutdown.
     */
    protected abstract fun createController(managerHandle: StreamingModule.Controller.ManagerHandle): StreamingModule.Controller

    private val manager: StreamingModuleManagerImpl by inject(mode = LazyThreadSafetyMode.NONE)
    private val notifications: StreamingModuleNotifications by inject(mode = LazyThreadSafetyMode.NONE)
    private val gate: Any = Any()
    private var controller: StreamingModule.Controller? = null
    private var managerHandle: StreamingModule.Controller.ManagerHandle? = null
    private var foregroundAdmissionClosed: Boolean = false
    private var foregroundNotification: Notification? = null

    // Main owns this attempt and its presentation until rollback or media cleanup settles.
    private var foregroundAttempt: StreamingModule.CaptureAttemptId? = null
    private var showsCaptureStop: Boolean = false
    private var terminalStopRequested: Boolean = false
    private var timedOut: Boolean = false
    private val destruction: CompletableDeferred<Boolean> = CompletableDeferred()
    private val notificationHandler: Handler = Handler(Looper.getMainLooper())
    private val refreshNotification: Runnable = Runnable { refreshForegroundNotification() }
    private val notificationPermissionListener: AppOpsManager.OnOpChangedListener = AppOpsManager.OnOpChangedListener { _, changedPackage ->
        if (changedPackage == null || changedPackage == packageName) notificationHandler.post(refreshNotification)
    }

    final override fun onBind(intent: Intent?): IBinder? = null

    final override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val op = AppOpsManager.permissionToOp(Manifest.permission.POST_NOTIFICATIONS)
                if (op != null) getSystemService(AppOpsManager::class.java).startWatchingMode(op, packageName, notificationPermissionListener)
            } catch (failure: RuntimeException) {
                Log.w("StreamingModuleService", "Could not watch notification permission", failure)
            }
        }
    }

    final override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent?.action == StreamingModuleManagerImpl.ACTION_START_MODULE) {
                manager.onServiceStart(service = this, existingController = controller, intent = intent) { originalManagerHandle ->
                    synchronized(gate) { managerHandle = originalManagerHandle }
                    check(tryEnterWaitingForeground()) { "Initial foreground promotion rejected" }
                    createController(originalManagerHandle).also { created -> synchronized(gate) { controller = created } }
                }
            }
        } finally {
            // A newer pending start can retain this unowned host for its own admission.
            if (controller == null && stopSelfResult(startId)) {
                closeForegroundAdmission()
            }
        }
        return START_NOT_STICKY
    }

    /** Build an Exit presentation even when notification delivery is blocked. */
    @MainThread
    private fun buildExitNotification(): Notification {
        val originalManagerHandle = synchronized(gate) { checkNotNull(managerHandle) }
        val action = StreamingModuleActionReceiver.exitAction(this, originalManagerHandle.instanceId)
        return notifications.buildForeground(StreamingModuleNotifications.ForegroundAction.Exit(action))
    }

    /** The compat layer filters SPECIAL_USE on older Android versions while preserving foreground. */
    @MainThread
    @SuppressLint("InlinedApi")
    private fun tryEnterWaitingForeground(): Boolean =
        tryPromoteForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, buildExitNotification())

    /**
     * Prepare this original Service after consent and before projection acquisition, using
     * mediaProjection and, when requested and supported, microphone foreground types. The attempt
     * owner serializes foreground calls and rechecks its exact attempt, module admission and current
     * permissions before calling on Main.
     *
     * False indicates admission was rejected before startForeground; true confirms foreground API
     * completion. Notification visibility has separate delivery conditions. An already-owned foreground
     * attempt causes an exception. Platform promotion failures close admission and throw.
     */
    @MainThread
    @SuppressLint("InlinedApi")
    public fun enterCaptureForeground(attempt: StreamingModule.CaptureAttemptId, requiresMicrophoneType: Boolean): Boolean {
        synchronized(gate) {
            val original = managerHandle ?: return false
            if (foregroundAdmissionClosed || original.instanceId != attempt.instanceId || !original.isCurrent()) return false
            check(foregroundAttempt == null) { "Another capture owns foreground" }
            foregroundAttempt = attempt
            showsCaptureStop = false
        }
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (requiresMicrophoneType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        return tryPromoteForeground(types, buildExitNotification())
    }

    /**
     * Update this exact attempt's presentation on Main. Set [active] after confirming capture to
     * show Stop; clear it to restore Exit presentation. Updates apply to the matching attempt. The
     * content update retains the existing foreground type mask; capture authorization remains with
     * the attempt and media owner. Construction and delivery failures throw.
     */
    public suspend fun updateCaptureNotification(attempt: StreamingModule.CaptureAttemptId, active: Boolean): Unit =
        withContext(Dispatchers.Main.immediate) {
            if (foregroundAttempt != attempt || showsCaptureStop == active) return@withContext
            val notification = if (active) {
                val action = StreamingModuleActionReceiver.stopAction(this@StreamingModuleService, attempt)
                notifications.buildForeground(StreamingModuleNotifications.ForegroundAction.StopStream(action))
            } else buildExitNotification()
            if (updateForegroundNotification(notification)) showsCaptureStop = active
        }

    /**
     * After declared media cleanup or rollback settles, attempt to restore waiting foreground for
     * this surviving instance on Main. Rollback before promotion is safe. Foreground ownership is
     * cleared only for the matching attempt, and restoration requires an admitted instance.
     */
    public suspend fun leaveCaptureForeground(attempt: StreamingModule.CaptureAttemptId): Unit = withContext(Dispatchers.Main.immediate) {
        if (foregroundAttempt != attempt) return@withContext
        try {
            tryEnterWaitingForeground()
        } finally {
            foregroundAttempt = null
            showsCaptureStop = false
        }
    }

    /** Close admission for further promotion and notification updates immediately. */
    @AnyThread
    public fun closeForegroundAdmission() {
        synchronized(gate) {
            foregroundAdmissionClosed = true
            foregroundNotification = null
        }
    }

    /**
     * Promote this exact Service with its fixed notification ID. The caller checks its capture
     * attempt before entering. False indicates admission was rejected before the Android call; true
     * means API completion. Notification visibility has separate delivery conditions. Platform failures
     * close admission and throw.
     */
    @MainThread
    private fun tryPromoteForeground(serviceTypes: Int, notification: Notification): Boolean {
        val failure = synchronized(gate) {
            val originalManagerHandle = managerHandle ?: return false
            if (foregroundAdmissionClosed || serviceTypes <= 0 || !originalManagerHandle.isCurrent()) return false
            try {
                ServiceCompat.startForeground(this, originalManagerHandle.foregroundSlot.id, notification, serviceTypes)
                foregroundNotification = notification
                null
            } catch (cause: Throwable) {
                foregroundAdmissionClosed = true
                foregroundNotification = null
                cause
            }
        }
        if (failure != null) {
            reportServiceFailure()
            throw failure
        }
        return true
    }

    /** Content-only update using the existing foreground type mask. */
    @MainThread
    private fun updateForegroundNotification(notification: Notification): Boolean {
        try {
            synchronized(gate) {
                val original = managerHandle ?: return false
                if (foregroundAdmissionClosed || foregroundNotification == null || !original.isCurrent()) return false
                getSystemService(NotificationManager::class.java).notify(original.foregroundSlot.id, notification)
                foregroundNotification = notification
                return true // API completion only; visibility follows delivery settings.
            }
        } catch (failure: Throwable) {
            closeForegroundAdmission()
            reportServiceFailure()
            throw failure
        }
    }

    @MainThread
    private fun refreshForegroundNotification() {
        try {
            synchronized(gate) {
                val original = managerHandle ?: return
                val notification = foregroundNotification ?: return
                if (foregroundAdmissionClosed || !original.isCurrent() ||
                    !notifications.areNotificationsEnabled(StreamingModuleNotifications.Channel.Streaming)
                ) return
                getSystemService(NotificationManager::class.java).notify(original.foregroundSlot.id, notification)
            }
        } catch (failure: RuntimeException) {
            Log.w("StreamingModuleService", "Could not refresh foreground notification", failure)
        }
    }

    /**
     * Close foreground admission, stop this Service after capture cleanup, and await its destruction.
     * The controller calls this once from its independently owned cleanup operation. A failed stop
     * request still waits for destruction while the caller remains active. Return false if that request
     * failed, destruction preceded it, or Android timed out the Service; otherwise return true.
     *
     * This wait has an open-ended lifetime. Service destruction completes on its own schedule, including
     * after the manager transition's bounded wait.
     */
    @AnyThread
    public suspend fun stopAndAwaitDestruction(): Boolean {
        closeForegroundAdmission()
        val stopSucceeded = try {
            withContext(Dispatchers.Main.immediate) {
                if (!destruction.isCompleted) {
                    synchronized(gate) { terminalStopRequested = true }
                    stopSelf()
                }
            }
            true
        } catch (cause: Throwable) {
            Log.e("StreamingModuleService", "Android Service stop failed", cause)
            false
        }
        val expectedStop = destruction.await()
        return stopSucceeded && expectedStop
    }

    final override fun onDestroy() {
        val (original, expectedStop) = synchronized(gate) {
            foregroundAdmissionClosed = true
            foregroundNotification = null
            val original = controller
            controller = null
            original to (terminalStopRequested && !timedOut)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getSystemService(AppOpsManager::class.java).stopWatchingMode(notificationPermissionListener)
            }
        } catch (failure: RuntimeException) {
            Log.w("StreamingModuleService", "Could not stop watching notification permission", failure)
        } finally {
            notificationHandler.removeCallbacks(refreshNotification)
            try {
                super.onDestroy()
            } finally {
                try {
                    original?.onServiceDestroyed()
                } finally {
                    synchronized(gate) { managerHandle = null }
                    destruction.complete(expectedStop)
                }
            }
        }
    }

    final override fun onTimeout(startId: Int) {
        stopTimedOutService()
    }

    final override fun onTimeout(startId: Int, fgsType: Int) {
        stopTimedOutService()
    }

    /** Stop this exact platform Service before asking its controller to close ordinary work. */
    private fun stopTimedOutService() {
        synchronized(gate) { timedOut = true }
        closeForegroundAdmission()
        try {
            stopSelf()
        } catch (cause: Throwable) {
            Log.e("StreamingModuleService", "Android Service timeout stop failed", cause)
        } finally {
            reportServiceFailure()
        }
    }

    private fun reportServiceFailure() {
        val original = synchronized(gate) { managerHandle to controller }
        try {
            original.first?.reportFailed()
        } catch (cause: Throwable) {
            Log.e("StreamingModuleService", "Failed to report Service loss", cause)
        } finally {
            try {
                original.second?.requestModuleShutdown()
            } catch (cause: Throwable) {
                Log.e("StreamingModuleService", "Failed to request module shutdown", cause)
            }
        }
    }
}
