package io.screenstream.streaming.module

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
import io.screenstream.streaming.manager.StreamingModuleActionReceiver
import io.screenstream.streaming.manager.StreamingModuleManagerImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Android Service for one streaming module controller. The concrete Service supplies its inert
 * controller factory; admitted startup provides the runtime and its validated foreground slot.
 * The original controller owns work through full cleanup completion, which may outlive this Service.
 * Foreground calls are synchronous: the module's independent attempt worker serializes them and
 * checks capture admission before calling. This Service has no worker or capture-resource owner.
 */
public abstract class StreamingModuleService : Service() {
    /** Create an inert controller for this Service; the manager adopts it before calling startModule. */
    protected abstract fun createController(runtime: StreamingModule.Controller.Runtime): StreamingModule.Controller

    private val manager: StreamingModuleManagerImpl by inject(mode = LazyThreadSafetyMode.NONE)
    private val notifications: StreamingModuleNotifications by inject(mode = LazyThreadSafetyMode.NONE)
    private val gate: Any = Any()
    private var controller: StreamingModule.Controller? = null
    private var runtime: StreamingModule.Controller.Runtime? = null
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
                manager.onServiceStart(service = this, existingController = controller, intent = intent) { originalRuntime ->
                    synchronized(gate) { runtime = originalRuntime }
                    check(tryEnterWaitingForeground()) { "Initial foreground promotion rejected" }
                    createController(originalRuntime).also { created -> synchronized(gate) { controller = created } }
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
        val originalRuntime = synchronized(gate) { checkNotNull(runtime) }
        val action = StreamingModuleActionReceiver.exitAction(this, originalRuntime.instanceId)
        return notifications.buildForeground(StreamingModuleNotifications.ForegroundAction.Exit(action))
    }

    /** The compat layer filters SPECIAL_USE on older Android versions without leaving foreground. */
    @MainThread
    @SuppressLint("InlinedApi")
    private fun tryEnterWaitingForeground(): Boolean =
        tryPromoteForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, buildExitNotification())

    /** Prepare this original Service for the exact attempt before projection acquisition. */
    @MainThread
    @SuppressLint("InlinedApi")
    public fun enterCaptureForeground(attempt: StreamingModule.CaptureAttemptId, requiresMicrophoneType: Boolean): Boolean {
        synchronized(gate) {
            val original = runtime ?: return false
            if (foregroundAdmissionClosed || original.instanceId != attempt.instanceId || !original.isCurrent()) return false
            check(foregroundAttempt == null) { "Another capture owns foreground" }
            foregroundAttempt = attempt
            showsCaptureStop = false
        }
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (requiresMicrophoneType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        return tryPromoteForeground(types, buildExitNotification())
    }

    /** Presentation follows confirmed capture without revalidating its projection-required mask. */
    public suspend fun updateCaptureNotification(attempt: StreamingModule.CaptureAttemptId, active: Boolean): Unit =
        withContext(Dispatchers.Main.immediate) {
            if (foregroundAttempt != attempt || showsCaptureStop == active) return@withContext
            val notification = if (active) {
                val action = StreamingModuleActionReceiver.stopAction(this@StreamingModuleService, attempt)
                notifications.buildForeground(StreamingModuleNotifications.ForegroundAction.StopStream(action))
            } else buildExitNotification()
            if (updateForegroundNotification(notification)) showsCaptureStop = active
        }

    /** The attempt owner calls after its declared media cleanup; rollback before promotion is safe. */
    public suspend fun leaveCaptureForeground(attempt: StreamingModule.CaptureAttemptId): Unit = withContext(Dispatchers.Main.immediate) {
        if (foregroundAttempt != attempt) return@withContext
        try {
            tryEnterWaitingForeground()
        } finally {
            foregroundAttempt = null
            showsCaptureStop = false
        }
    }

    /** Prevent further promotion and notification updates immediately. */
    @AnyThread
    public fun closeForegroundAdmission() {
        synchronized(gate) {
            foregroundAdmissionClosed = true
            foregroundNotification = null
        }
    }

    /**
     * Promote this exact Service with its fixed notification ID. The caller checks its capture
     * attempt before entering. False means admission was rejected without calling Android; true
     * means API completion, not notification visibility. Platform failures close admission and throw.
     */
    @MainThread
    private fun tryPromoteForeground(serviceTypes: Int, notification: Notification): Boolean {
        val failure = synchronized(gate) {
            val originalRuntime = runtime ?: return false
            if (foregroundAdmissionClosed || serviceTypes <= 0 || !originalRuntime.isCurrent()) return false
            try {
                ServiceCompat.startForeground(this, originalRuntime.foregroundSlot.id, notification, serviceTypes)
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

    /** Content-only update: retain the resource-required mask without revalidating a revoked projection grant. */
    @MainThread
    private fun updateForegroundNotification(notification: Notification): Boolean {
        try {
            synchronized(gate) {
                val original = runtime ?: return false
                if (foregroundAdmissionClosed || foregroundNotification == null || !original.isCurrent()) return false
                getSystemService(NotificationManager::class.java).notify(original.foregroundSlot.id, notification)
                foregroundNotification = notification
                return true // API completion only; notification visibility is not guaranteed.
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
                val original = runtime ?: return
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
     * Stop this exact Service after capture cleanup has settled, then await its destruction.
     * The controller calls this once from its independently owned cleanup operation. Android
     * removes foreground state and its notification when stopping the Service. A failed stop
     * request still waits for destruction and returns false, as do timeout and destruction before
     * this request.
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
                    synchronized(gate) { runtime = null }
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
        val original = synchronized(gate) { runtime to controller }
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
