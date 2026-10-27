package io.screenstream.mjpeg

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import io.screenstream.streaming.manager.StreamingModuleActionReceiver
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleNotifications
import io.screenstream.streaming.module.StreamingModuleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.core.parameter.parametersOf

/**
 * Creates native MJPEG's inert controller and performs foreground operations.
 * The shared base owns exact Service startup, destruction and timeout callbacks.
 */
public class MjpegStreamingModuleService : StreamingModuleService() {
    private val notifications: StreamingModuleNotifications by inject()
    private var foregroundAttempt: StreamingModule.CaptureAttemptId? = null
    private var showsCaptureStop: Boolean = false

    override fun createController(runtime: StreamingModule.Controller.Runtime): StreamingModule.Controller =
        get<MjpegStreamingModuleController> { parametersOf(runtime, this) }

    // ServiceCompat ignores this inlined type mask before API 29.
    @SuppressLint("InlinedApi")
    internal suspend fun enterCaptureForeground(attempt: StreamingModule.CaptureAttemptId): Unit = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        check(foregroundAttempt == null) { "Another capture owns foreground" }
        val notification = buildExitNotification()
        foregroundAttempt = attempt
        showsCaptureStop = false
        check(tryPromoteForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, notification)) { "Foreground promotion rejected" }
    }

    /** Presentation follows confirmed capture, separately from its resource-required type. */
    internal suspend fun updateCaptureNotification(attempt: StreamingModule.CaptureAttemptId, active: Boolean): Unit = withContext(Dispatchers.Main.immediate) {
        if (foregroundAttempt != attempt || showsCaptureStop == active) return@withContext
        val notification = if (active) {
            val stopAction = StreamingModuleActionReceiver.stopAction(this@MjpegStreamingModuleService, attempt)
            notifications.buildForeground(StreamingModuleNotifications.ForegroundAction.StopStream(stopAction))
        } else {
            buildExitNotification()
        }
        if (updateForegroundNotification(notification)) showsCaptureStop = active
    }

    internal suspend fun leaveCaptureForeground(attempt: StreamingModule.CaptureAttemptId): Unit = withContext(Dispatchers.Main.immediate) {
        if (foregroundAttempt != attempt) return@withContext
        try {
            tryEnterWaitingForeground()
        } finally {
            foregroundAttempt = null
            showsCaptureStop = false
        }
    }
}
