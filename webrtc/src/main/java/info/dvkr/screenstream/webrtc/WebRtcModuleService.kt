package info.dvkr.screenstream.webrtc

import info.dvkr.screenstream.webrtc.internal.WebRtcStreamingService
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleService
import org.koin.android.ext.android.get
import org.koin.core.parameter.parametersOf

/** The existing Android component hosts one directly managed WebRTC controller. */
public class WebRtcModuleService : StreamingModuleService() {
    override fun createController(runtime: StreamingModule.Controller.Runtime): StreamingModule.Controller =
        get<WebRtcStreamingService> { parametersOf(runtime, this) }
}
