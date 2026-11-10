package info.dvkr.screenstream.vnc

import android.app.Service
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import org.koin.core.annotation.Singleton

/** Metadata only; the admitted Service creates each native controller. */
@Singleton(binds = [StreamingModule::class])
internal class VncStreamingModule : StreamingModule {
    override val id: StreamingModule.Id = StreamingModule.Id("VNC")
    override val priority: Int = 5
    override val description: StreamingModule.Description = StreamingModule.Description(
        iconRes = R.drawable.vnc_method_24px,
        titleRes = R.string.vnc_method_title,
        summaryRes = R.string.vnc_method_summary,
        introRes = R.string.vnc_method_intro,
        conditionsRes = R.string.vnc_method_conditions,
        benefitsRes = R.array.vnc_method_benefits,
        thingsToKnowRes = R.array.vnc_method_things_to_know,
    )
    override val serviceClass: Class<out Service> = VncModuleService::class.java
    override val foregroundSlot: StreamingModuleNotifications.ForegroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
        override val id: Int = 500
    }
}
