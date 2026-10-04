package io.screenstream.mjpeg

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import info.dvkr.screenstream.common.notification.NotificationHelper
import io.screenstream.streaming.foreground.createForegroundNotification
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.core.parameter.parametersOf

/**
 * Configures native MJPEG's foreground notification and creates its inert business controller.
 * The shared base owns exact Service startup, destruction and timeout callbacks.
 */
public class MjpegStreamingModuleService : StreamingModuleService() {
    override val foregroundNotificationId: Int = 400
    private val notifications: NotificationHelper by inject()

    override fun createController(runtime: StreamingModule.Controller.Runtime): StreamingModule.Controller =
        get<MjpegStreamingModuleController> { parametersOf(runtime, this) }

    // ServiceCompat ignores this inlined type mask before API 29.
    @SuppressLint("InlinedApi")
    internal suspend fun enterCaptureForeground(attempt: StreamingModule.CaptureAttemptId) {
        val notification = withContext(Dispatchers.Main.immediate) {
            notifications.createForegroundNotification(this@MjpegStreamingModuleService, attempt)
        }
        currentCoroutineContext().ensureActive()
        when (val promotion = promoteForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, notification)) {
            PromotionResult.ApiCompleted -> Unit
            is PromotionResult.Failed -> throw promotion.cause
            PromotionResult.Rejected -> error("Foreground promotion rejected")
        }
    }

    internal fun leaveCaptureForeground() {
        when (val release = removeForeground()) {
            ReleaseResult.NotRequired, ReleaseResult.ApiCompleted -> Unit
            is ReleaseResult.Failed -> throw release.cause
            ReleaseResult.Unconfirmed -> error("Foreground release unconfirmed")
        }
    }

    internal suspend fun showCaptureError() {
        withContext(Dispatchers.Main.immediate) {
            notifications.showNotification(
                ERROR_NOTIFICATION_ID,
                notifications.getErrorNotification(this@MjpegStreamingModuleService, getString(R.string.mjpeg_capture_error), null),
            )
        }
    }

    private companion object {
        const val ERROR_NOTIFICATION_ID = 410
    }
}
