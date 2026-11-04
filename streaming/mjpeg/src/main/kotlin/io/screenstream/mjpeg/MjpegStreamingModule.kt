package io.screenstream.mjpeg

import android.app.Service
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import org.koin.core.annotation.Singleton

/**
 * Lists native MJPEG metadata and its Android Service component.
 * Selection starts that Service; this process singleton creates no controller or resources.
 */
@Suppress("unused")
@Singleton(binds = [StreamingModule::class])
internal class MjpegStreamingModule : StreamingModule {
    internal companion object {
        internal val Id: StreamingModule.Id = StreamingModule.Id("MJPEG")
    }

    override val id: StreamingModule.Id = Id
    override val priority: Int = 40
    override val description: StreamingModule.Description = StreamingModule.Description(
        iconRes = R.drawable.mjpeg_method_24px,
        titleRes = R.string.mjpeg_method_title,
        summaryRes = R.string.mjpeg_method_summary,
        introRes = R.string.mjpeg_method_intro,
        conditionsRes = R.string.mjpeg_method_conditions,
        benefitsRes = R.array.mjpeg_method_benefits,
        thingsToKnowRes = R.array.mjpeg_method_things_to_know,
    )
    override val serviceClass: Class<out Service> = MjpegStreamingModuleService::class.java
    override val foregroundSlot: StreamingModuleNotifications.ForegroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
        override val id: Int = 400
    }
}
