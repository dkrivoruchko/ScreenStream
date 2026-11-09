package info.dvkr.screenstream.rtsp

import android.app.Service
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import org.koin.core.annotation.Singleton

/** Metadata only; the admitted Service creates each native controller. */
@Singleton(binds = [StreamingModule::class])
internal class RtspStreamingModule : StreamingModule {
    override val id: StreamingModule.Id = StreamingModule.Id("RTSP")
    override val priority: Int = 10
    override val description: StreamingModule.Description = StreamingModule.Description(
        iconRes = R.drawable.rtsp_method_24px,
        titleRes = R.string.rtsp_method_title,
        summaryRes = R.string.rtsp_method_summary,
        introRes = R.string.rtsp_method_intro,
        conditionsRes = R.string.rtsp_method_conditions,
        benefitsRes = R.array.rtsp_method_benefits,
        thingsToKnowRes = R.array.rtsp_method_things_to_know,
    )
    override val serviceClass: Class<out Service> = RtspModuleService::class.java
    override val foregroundSlot: StreamingModuleNotifications.ForegroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
        override val id: Int = 300
    }
}
