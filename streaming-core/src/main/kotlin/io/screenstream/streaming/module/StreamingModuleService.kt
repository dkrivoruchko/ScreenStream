package io.screenstream.streaming.module

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import io.screenstream.streaming.StreamingModuleManager
import kotlinx.coroutines.CompletableDeferred
import org.koin.android.ext.android.inject

/**
 * Android Service for one streaming module controller. The concrete Service supplies its notification
 * ID and inert controller factory; startup is admitted by the manager before that factory is called.
 * The original controller owns work through full cleanup completion, which may outlive this Service.
 * Foreground calls are synchronous: the module's independent attempt worker serializes them and
 * checks capture admission before calling. This Service has no worker or capture-resource owner.
 */
public abstract class StreamingModuleService : Service() {
    /** Positive notification ID reserved by the concrete streaming Service. */
    protected abstract val foregroundNotificationId: Int

    /** Create an inert controller for this Service; the manager adopts it before calling startModule. */
    protected abstract fun createController(runtime: StreamingModule.Controller.Runtime): StreamingModule.Controller

    private val manager: StreamingModuleManager by inject(mode = LazyThreadSafetyMode.NONE)
    private val gate: Any = Any()
    private var controller: StreamingModule.Controller? = null
    private var runtime: StreamingModule.Controller.Runtime? = null
    private var destroyed: Boolean = false
    private var foregroundAdmissionClosed: Boolean = false
    private var foregroundRemovalRequired: Boolean = false
    private var removalFailure: ReleaseResult? = null
    private val destruction: CompletableDeferred<Unit> = CompletableDeferred()

    final override fun onBind(intent: Intent?): IBinder? = null

    final override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == StreamingModuleManager.ACTION_START_MODULE) {
            manager.onServiceStart(service = this, existingController = controller, intent = intent) { originalRuntime ->
                createController(originalRuntime).also { created ->
                    synchronized(gate) {
                        runtime = originalRuntime
                        controller = created
                    }
                }
            }
        }
        if (controller == null) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    /** Prevent further promotion immediately; required foreground removal remains permitted. */
    public fun closeForegroundAdmission() {
        synchronized(gate) { foregroundAdmissionClosed = true }
    }

    /** Wait for this Service's destruction; cancelling the caller cancels only this wait. */
    public suspend fun awaitDestroyed() {
        destruction.await()
    }

    /**
     * Promote this exact Service with its fixed notification ID. The caller must serialize platform
     * calls and check its capture attempt before entering. A cleanup obligation is recorded before
     * Android is called, including when Android throws. Return describes that API call, not visibility.
     */
    public fun promoteForeground(serviceTypes: Int, notification: Notification): PromotionResult {
        val admitted = synchronized(gate) {
            if (destroyed || foregroundAdmissionClosed || foregroundRemovalRequired || removalFailure != null ||
                serviceTypes <= 0 || foregroundNotificationId <= 0 || runtime?.isCurrent() != true
            ) false else {
                foregroundRemovalRequired = true
                true
            }
        }
        if (!admitted) return PromotionResult.Rejected
        return try {
            ServiceCompat.startForeground(this, foregroundNotificationId, notification, serviceTypes)
            PromotionResult.ApiCompleted
        } catch (cause: Throwable) {
            PromotionResult.Failed(cause)
        }
    }

    /**
     * Remove foreground after the caller's capture work permits it. Serialize this call with
     * promotion. No promotion gives NotRequired even after host loss; a required call that fails
     * or cannot run closes future promotion and shuts down the original controller, without retry.
     */
    public fun removeForeground(): ReleaseResult {
        val immediate = synchronized(gate) {
            removalFailure ?: when {
                !foregroundRemovalRequired -> ReleaseResult.NotRequired
                destroyed -> ReleaseResult.Unconfirmed.also {
                    removalFailure = it
                    foregroundAdmissionClosed = true
                }

                else -> null
            }
        }
        if (immediate != null) {
            if (immediate == ReleaseResult.Unconfirmed) reportFailure()
            return immediate
        }
        val result = try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            synchronized(gate) { foregroundRemovalRequired = false }
            ReleaseResult.ApiCompleted
        } catch (cause: Throwable) {
            ReleaseResult.Failed(cause).also {
                synchronized(gate) {
                    foregroundAdmissionClosed = true
                    removalFailure = it
                }
            }
        }
        if (result is ReleaseResult.Failed) reportFailure()
        return result
    }

    final override fun onDestroy() {
        val original = synchronized(gate) {
            destroyed = true
            foregroundAdmissionClosed = true
            controller.also { controller = null }
        }
        try {
            super.onDestroy()
        } finally {
            try {
                original?.onServiceDestroyed()
            } finally {
                synchronized(gate) { runtime = null }
                destruction.complete(Unit)
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
        closeForegroundAdmission()
        try {
            stopSelf()
        } catch (cause: Throwable) {
            Log.e(TAG, "Android Service timeout stop failed", cause)
        } finally {
            reportFailure()
        }
    }

    private fun reportFailure() {
        val original = synchronized(gate) { runtime to controller }
        try {
            original.first?.reportFailed()
        } catch (cause: Throwable) {
            Log.e(TAG, "Failed to report Service loss", cause)
        } finally {
            try {
                original.second?.requestModuleShutdown()
            } catch (cause: Throwable) {
                Log.e(TAG, "Failed to request module shutdown", cause)
            }
        }
    }

    /** Outcome of the synchronous Android promotion call. */
    public sealed interface PromotionResult {
        /** Android accepted the promotion API call; notification visibility is not guaranteed. */
        public data object ApiCompleted : PromotionResult

        /** This Service cannot admit promotion; Android was not called. */
        public data object Rejected : PromotionResult

        /** Android threw during promotion; foreground removal is still required. */
        public class Failed(public val cause: Throwable) : PromotionResult
    }

    /** Outcome of foreground removal; these are API results, not proof of physical cleanup. */
    public sealed interface ReleaseResult {
        /** No removal obligation remains, including after a successful removal or host loss. */
        public data object NotRequired : ReleaseResult

        /** Android accepted removal; this promotion obligation is complete. */
        public data object ApiCompleted : ReleaseResult

        /** The Service was destroyed before required removal could be admitted. */
        public data object Unconfirmed : ReleaseResult

        /** Android threw during removal; later promotion is permanently blocked. */
        public class Failed(public val cause: Throwable) : ReleaseResult
    }

    private companion object {
        const val TAG: String = "StreamingModuleService"
    }
}
