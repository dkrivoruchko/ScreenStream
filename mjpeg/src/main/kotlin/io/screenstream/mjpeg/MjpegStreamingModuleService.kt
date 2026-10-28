package io.screenstream.mjpeg

import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleService
import org.koin.android.ext.android.get
import org.koin.core.parameter.parametersOf

/**
 * Creates native MJPEG's inert controller. The shared base owns its original Android lifecycle
 * and exact foreground-attempt bookkeeping; the controller owns capture and delivery work.
 */
public class MjpegStreamingModuleService : StreamingModuleService() {
    override fun createController(runtime: StreamingModule.Controller.Runtime): StreamingModule.Controller =
        get<MjpegStreamingModuleController> { parametersOf(runtime, this) }
}
