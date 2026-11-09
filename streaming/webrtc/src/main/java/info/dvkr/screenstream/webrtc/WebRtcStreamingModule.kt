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
    override val description: StreamingModule.Description = StreamingModule.Description(
        iconRes = R.drawable.webrtc_method_24px,
        titleRes = R.string.webrtc_method_title,
        summaryRes = R.string.webrtc_method_summary,
        introRes = R.string.webrtc_method_intro,
        conditionsRes = R.string.webrtc_method_conditions,
        benefitsRes = R.array.webrtc_method_benefits,
        thingsToKnowRes = R.array.webrtc_method_things_to_know,
    )
    override val serviceClass: Class<out Service> = WebRtcModuleService::class.java
    override val foregroundSlot: StreamingModuleNotifications.ForegroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
        override val id: Int = 200
    }
}
