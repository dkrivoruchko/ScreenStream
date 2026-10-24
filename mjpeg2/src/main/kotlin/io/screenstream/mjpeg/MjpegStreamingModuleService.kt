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
 * Creates native MJPEG's inert controller and performs foreground operations.
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
}
