package info.dvkr.screenstream.webrtc

import android.app.Service
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import org.koin.core.annotation.Singleton

/** Selection metadata only; the admitted Service creates each independent controller. */
@Singleton(binds = [StreamingModule::class])
internal class WebRtcStreamingModule : StreamingModule {
    override val id: StreamingModule.Id = StreamingModule.Id("WEBRTC")
    override val priority: Int = 20
    override val nameResource: Int = R.string.webrtc_stream_mode
    override val descriptionResource: Int = R.string.webrtc_stream_mode_description
    override val detailsResource: Int = R.string.webrtc_stream_mode_details
    override val serviceClass: Class<out Service> = WebRtcModuleService::class.java
    override val foregroundSlot: StreamingModuleNotifications.ForegroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
        override val id: Int = 200
    }
}
