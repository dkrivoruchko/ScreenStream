package info.dvkr.screenstream.webrtc

import android.app.Service
import android.content.Context
import info.dvkr.screenstream.webrtc.internal.WebRtcStreamingService
import io.screenstream.streaming.legacy.LegacyStreamingModuleAdapter
import io.screenstream.streaming.legacy.StreamingModuleLegacy
import io.screenstream.streaming.module.StreamingModule
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton

/** Temporary descriptor for the existing WebRtc pipeline; its exact Service resolves the backend only after controller adoption. */
@Singleton(binds = [StreamingModule::class])
@Named("LegacyWebRtcStreamingModule")
public class LegacyWebRtcStreamingModule(
    context: Context,
    @Named("WebRtcStreamingModule") webRtcStreamingModule: Lazy<WebRtcStreamingModuleLegacy>,
) : LegacyStreamingModuleAdapter(context, webRtcStreamingModule) {
    override val id: StreamingModule.Id = StreamingModule.Id("WEBRTC")
    override val priority: Int = 20
    override val nameResource: Int = R.string.webrtc_stream_mode
    override val descriptionResource: Int = R.string.webrtc_stream_mode_description
    override val detailsResource: Int = R.string.webrtc_stream_mode_details
    override val serviceClass: Class<out Service> = WebRtcModuleService::class.java

    override fun startLegacyStream(module: StreamingModuleLegacy) {
        (module as WebRtcStreamingModuleLegacy).sendEvent(WebRtcStreamingService.InternalEvent.StartStream(permissionEducationShown = false))
    }
}
