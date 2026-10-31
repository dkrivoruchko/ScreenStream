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
    override val nameResource: Int = R.string.mjpeg_mode_name
    override val descriptionResource: Int = R.string.mjpeg_mode_description
    override val detailsResource: Int = R.string.mjpeg_mode_details
    override val serviceClass: Class<out Service> = MjpegStreamingModuleService::class.java
    override val foregroundSlot: StreamingModuleNotifications.ForegroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
        override val id: Int = 400
    }
}
