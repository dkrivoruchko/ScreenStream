package info.dvkr.screenstream.rtsp

import android.app.Service
import android.content.Context
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import io.screenstream.streaming.legacy.LegacyStreamingModuleAdapter
import io.screenstream.streaming.legacy.StreamingModuleLegacy
import io.screenstream.streaming.module.StreamingModule
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton

/** Temporary descriptor for the existing Rtsp pipeline; its exact Service resolves the backend only after controller adoption. */
@Singleton(binds = [StreamingModule::class])
@Named("LegacyRtspStreamingModule")
public class LegacyRtspStreamingModule(
    context: Context,
    @Named("RtspStreamingModule") legacyRtspModule: Lazy<RtspStreamingModuleLegacy>,
) : LegacyStreamingModuleAdapter(context, legacyRtspModule) {
    override val id: StreamingModule.Id = StreamingModule.Id("RTSP")
    override val priority: Int = 10
    override val nameResource: Int = R.string.rtsp_stream_mode
    override val descriptionResource: Int = R.string.rtsp_stream_mode_description
    override val detailsResource: Int = R.string.rtsp_stream_mode_details
    override val serviceClass: Class<out Service> = RtspModuleService::class.java
    override val requiresLocalNetworkPermission: Boolean = true

    override fun startLegacyStream(module: StreamingModuleLegacy) {
        (module as RtspStreamingModuleLegacy).sendEvent(RtspStreamingService.InternalEvent.StartStream(permissionEducationShown = false))
    }
}
