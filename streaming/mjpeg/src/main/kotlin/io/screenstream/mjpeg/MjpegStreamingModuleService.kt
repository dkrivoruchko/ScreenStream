package io.screenstream.mjpeg

import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleService
import org.koin.android.ext.android.get
import org.koin.core.parameter.parametersOf

/**
 * Creates native MJPEG's inert controller. The shared base owns its original Android lifecycle
 * and exact foreground-attempt bookkeeping; the controller owns capture and delivery work.
 */
public class MjpegStreamingModuleService : StreamingModuleService() {
    override fun createController(managerHandle: StreamingModule.Controller.ManagerHandle): StreamingModule.Controller =
        get<MjpegStreamingModuleController> { parametersOf(managerHandle, this) }
}
