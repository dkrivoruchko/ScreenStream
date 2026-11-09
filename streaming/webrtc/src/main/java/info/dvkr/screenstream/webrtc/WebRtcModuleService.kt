package info.dvkr.screenstream.webrtc

import info.dvkr.screenstream.webrtc.internal.WebRtcStreamingService
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleService
import org.koin.android.ext.android.get
import org.koin.core.parameter.parametersOf

/** The existing Android component hosts one directly managed WebRTC controller. */
public class WebRtcModuleService : StreamingModuleService() {
    override fun createController(managerHandle: StreamingModule.Controller.ManagerHandle): StreamingModule.Controller =
        get<WebRtcStreamingService> { parametersOf(managerHandle, this) }
}
