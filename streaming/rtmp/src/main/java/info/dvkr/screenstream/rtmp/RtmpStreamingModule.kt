package info.dvkr.screenstream.rtmp

import android.app.Service
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import org.koin.core.annotation.Singleton

/** Metadata only; the admitted Service creates each native controller. */
@Singleton(binds = [StreamingModule::class])
internal class RtmpStreamingModule : StreamingModule {
    override val id: StreamingModule.Id = StreamingModule.Id("RTMP")
    override val priority: Int = 30
    override val description: StreamingModule.Description = StreamingModule.Description(
        iconRes = R.drawable.rtmp_method_24px,
        titleRes = R.string.rtmp_method_title,
        summaryRes = R.string.rtmp_method_summary,
        introRes = R.string.rtmp_method_intro,
        conditionsRes = R.string.rtmp_method_conditions,
        benefitsRes = R.array.rtmp_method_benefits,
        thingsToKnowRes = R.array.rtmp_method_things_to_know,
    )
    override val serviceClass: Class<out Service> = RtmpModuleService::class.java
    override val foregroundSlot: StreamingModuleNotifications.ForegroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
        override val id: Int = 500
    }
}
