package io.screenstream.mjpeg

import android.content.Context
import io.screenstream.streaming.module.StreamingModuleHost
import org.koin.core.annotation.Singleton

/** One process singleton for MJPEG launches, shared by the module API and each Service lifetime. */
@Singleton(binds = [])
internal class MjpegStreamingModuleHost(context: Context) : StreamingModuleHost<MjpegStreamingModuleController>(
    applicationContext = context.applicationContext,
    moduleId = MjpegStreamingModule.Id,
    serviceClass = MjpegStreamingModuleService::class.java,
    foregroundNotificationId = MjpegStreamingModule.FOREGROUND_NOTIFICATION_ID,
    controllerFactory = ::MjpegStreamingModuleController,
)
