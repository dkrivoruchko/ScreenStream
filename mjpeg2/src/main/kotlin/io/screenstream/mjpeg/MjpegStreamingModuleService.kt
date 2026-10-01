package io.screenstream.mjpeg

import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleService
import org.koin.android.ext.android.get
import org.koin.core.parameter.parametersOf

/**
 * Configures native MJPEG's foreground notification and creates its inert business controller.
 * The shared base owns exact Service startup, destruction and timeout callbacks.
 */
public class MjpegStreamingModuleService : StreamingModuleService() {
    override val foregroundNotificationId: Int = 400

    override fun createController(runtime: StreamingModule.Controller.Runtime): StreamingModule.Controller =
        get<MjpegStreamingModuleController> { parametersOf(runtime, this) }
}
