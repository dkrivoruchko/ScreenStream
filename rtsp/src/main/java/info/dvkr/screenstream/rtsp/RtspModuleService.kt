package info.dvkr.screenstream.rtsp

import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleService
import org.koin.android.ext.android.get
import org.koin.core.parameter.parametersOf

/** The original Android host creates its inert controller; shared foreground policy stays in Core. */
public class RtspModuleService : StreamingModuleService() {
    override fun createController(runtime: StreamingModule.Controller.Runtime): StreamingModule.Controller =
        get<RtspStreamingService> { parametersOf(runtime, this) }
}
