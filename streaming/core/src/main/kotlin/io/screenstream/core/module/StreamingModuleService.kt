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
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.app.ServiceCompat
import io.screenstream.core.logE
import io.screenstream.core.logW
import io.screenstream.core.manager.StreamingModuleActionReceiver
import io.screenstream.core.manager.StreamingModuleManagerImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Base Android Service that hosts one module instance's controller and foreground notification.
 *
 * The manager starts it with ordinary `startService`. For an admitted start, the Service enters waiting
 * foreground (`specialUse`, Exit action) and then calls [createController]; otherwise it stops unless a
 * newer start is pending. Stale and duplicate starts are ignored; a new admitted instance whose
 * start arrives while this Service still hosts a previous controller fails. The controller owns module
 * work and cleanup, which can outlive this Service, and ends it through [stopAndAwaitDestruction]. A
 * foreground failure or Android Service timeout reports instance failure and requests controller shutdown.
 * Capture-foreground ownership covers only this Service's presentation, not media. Destruction ends it
 * before the controller is notified, so later or reentrant capture-foreground calls do nothing and
 * [enterCaptureForeground] returns false.
 *
 * Subclasses declare the manifest entry with its foreground types, `specialUse` subtype, and permissions.
 * Binding is unsupported, and starts are not sticky.
 */
public abstract class StreamingModuleService : Service() {
    /**
     * Creates the inert controller for [managerHandle]'s instance, on Main after waiting foreground is
     * entered. Defer module work and resource acquisition to [StreamingModule.Controller.startModule]. The
     * manager verifies the controller's identity and starts it only if the instance is still admitted;
     * otherwise it requests shutdown. A thrown exception fails the instance.
     */
    protected abstract fun createController(managerHandle: StreamingModule.Controller.ManagerHandle): StreamingModule.Controller

    private val manager: StreamingModuleManagerImpl by inject(mode = LazyThreadSafetyMode.NONE)
    private val notifications: StreamingModuleNotifications by inject(mode = LazyThreadSafetyMode.NONE)
    private val gate: Any = Any()
    private var controller: StreamingModule.Controller? = null
    private var managerHandle: StreamingModule.Controller.ManagerHandle? = null
    private var foregroundAdmissionClosed: Boolean = false
    private var foregroundNotification: Notification? = null

    // Main only: the attempt that owns capture foreground, and whether its notification shows Stop.
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
                logW("StreamingModuleService", "Could not watch notification permission", failure)
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
            // stopSelfResult keeps an unowned host alive when a newer start is pending.
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
     * Promotes this Service to `mediaProjection` foreground, adding `microphone` on API 30+ when
     * [requiresMicrophoneType], after consent and before projection acquisition. Call on Main from the
     * attempt's serialized foreground work after rechecking the attempt, admission, and permissions.
     *
     * Returns false without calling Android when the instance is not current or foreground admission is
     * closed. True means `startForeground` returned; notification visibility follows delivery settings.
     * Throws if another attempt owns capture foreground. A platform failure closes foreground admission,
     * reports instance failure, and rethrows. Once [attempt] has called this, its rollback or cleanup must
     * call [leaveCaptureForeground].
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
     * Switches [attempt]'s foreground notification on Main: true shows Stop after capture is confirmed;
     * false restores Exit. Returns early when [attempt] does not own capture foreground or the presentation
     * is unchanged. Otherwise it builds the content first, then posts it under the same ID, keeping the
     * foreground types, unless the instance is closed or no longer current. Construction failures propagate
     * even when posting would be skipped; a posting failure also closes foreground admission and reports
     * instance failure.
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
     * Releases [attempt]'s capture foreground on Main after its media cleanup or rollback settles, and
     * restores waiting foreground while the instance is admitted. Ignored for an attempt that does not own
     * capture foreground, so it is safe in rollback before promotion. Ownership is cleared even when
     * restoration fails; a platform failure is handled as in [enterCaptureForeground].
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

    /**
     * Permanently rejects later foreground promotions and notification updates. The current foreground
     * state remains until the Service stops.
     */
    @AnyThread
    public fun closeForegroundAdmission() {
        synchronized(gate) {
            foregroundAdmissionClosed = true
            foregroundNotification = null
        }
    }

    /**
     * Calls `startForeground` with the instance's slot ID while admission is open and the instance is
     * current; false means Android was not called. A platform failure closes admission, reports instance
     * failure, and rethrows.
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

    /** Re-posts the current foreground notification after a notification-permission change; failures are logged. */
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
            logW("StreamingModuleService", "Could not refresh foreground notification", failure)
        }
    }

    /**
     * Closes foreground admission, calls `stopSelf` on Main, and suspends until `onDestroy` completes. The
     * wait has no timeout; only caller cancellation ends it early. Call once from the controller's cleanup,
     * after capture cleanup settles. Returns true only when this call requested the stop and Android then
     * destroyed the Service without a timeout; a failed stop request still waits, then returns false.
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
            logE("StreamingModuleService", "Android Service stop failed", cause)
            false
        }
        val expectedStop = destruction.await()
        return stopSucceeded && expectedStop
    }

    final override fun onDestroy() {
        val (original, expectedStop) = synchronized(gate) {
            foregroundAdmissionClosed = true
            foregroundNotification = null
            foregroundAttempt = null
            showsCaptureStop = false
            val original = controller
            controller = null
            original to (terminalStopRequested && !timedOut)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getSystemService(AppOpsManager::class.java).stopWatchingMode(notificationPermissionListener)
            }
        } catch (failure: RuntimeException) {
            logW("StreamingModuleService", "Could not stop watching notification permission", failure)
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
            logE("StreamingModuleService", "Android Service timeout stop failed", cause)
        } finally {
            reportServiceFailure()
        }
    }

    private fun reportServiceFailure() {
        val original = synchronized(gate) { managerHandle to controller }
        try {
            original.first?.reportFailed()
        } catch (cause: Throwable) {
            logE("StreamingModuleService", "Failed to report Service loss", cause)
        } finally {
            try {
                original.second?.requestModuleShutdown()
            } catch (cause: Throwable) {
                logE("StreamingModuleService", "Failed to request module shutdown", cause)
            }
        }
    }
}
