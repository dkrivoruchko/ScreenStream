package info.dvkr.screenstream.mjpeg

import android.app.Service
import android.content.Context
import info.dvkr.screenstream.mjpeg.internal.MjpegStreamingService
import io.screenstream.streaming.legacy.LegacyStreamingModuleAdapter
import io.screenstream.streaming.legacy.StreamingModuleLegacy
import io.screenstream.streaming.module.StreamingModule
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton

/** Temporary descriptor for the existing Mjpeg pipeline; its exact Service resolves the backend only after controller adoption. */
@Singleton(binds = [StreamingModule::class])
@Named("LegacyMjpegStreamingModule")
public class LegacyMjpegStreamingModule(
    context: Context,
    @Named("MjpegStreamingModule") mjpegStreamingModule: Lazy<MjpegStreamingModuleLegacy>,
) : LegacyStreamingModuleAdapter(context, mjpegStreamingModule) {
    override val id: StreamingModule.Id = StreamingModule.Id("MJPEG_LEGACY")
    override val priority: Int = 30
    override val nameResource: Int = R.string.mjpeg_stream_mode
    override val descriptionResource: Int = R.string.mjpeg_stream_mode_description
    override val detailsResource: Int = R.string.mjpeg_stream_mode_details
    override val serviceClass: Class<out Service> = MjpegModuleService::class.java
    override val requiresLocalNetworkPermission: Boolean = true

    override fun startLegacyStream(module: StreamingModuleLegacy) {
        (module as MjpegStreamingModuleLegacy).sendEvent(MjpegStreamingService.InternalEvent.StartStream(permissionEducationShown = false))
    }
}
