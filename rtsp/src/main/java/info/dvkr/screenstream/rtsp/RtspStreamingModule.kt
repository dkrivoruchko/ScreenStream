package info.dvkr.screenstream.rtsp

import android.app.Service
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleNotifications
import org.koin.core.annotation.Singleton

/** Metadata only; the admitted Service creates each native controller. */
@Singleton(binds = [StreamingModule::class])
internal class RtspStreamingModule : StreamingModule {
    override val id: StreamingModule.Id = StreamingModule.Id("RTSP")
    override val priority: Int = 10
    override val nameResource: Int = R.string.rtsp_stream_mode
    override val descriptionResource: Int = R.string.rtsp_stream_mode_description
    override val detailsResource: Int = R.string.rtsp_stream_mode_details
    override val serviceClass: Class<out Service> = RtspModuleService::class.java
    override val foregroundSlot: StreamingModuleNotifications.ForegroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
        override val id: Int = 300
    }
}
