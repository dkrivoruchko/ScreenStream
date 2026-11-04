package info.dvkr.screenstream.rtsp

import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleService
import org.koin.android.ext.android.get
import org.koin.core.parameter.parametersOf

/** The original Android host creates its inert controller; shared foreground policy stays in Core. */
public class RtspModuleService : StreamingModuleService() {
    override fun createController(managerHandle: StreamingModule.Controller.ManagerHandle): StreamingModule.Controller =
        get<RtspStreamingService> { parametersOf(managerHandle, this) }
}
