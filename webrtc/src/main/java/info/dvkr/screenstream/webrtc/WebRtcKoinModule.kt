package info.dvkr.screenstream.webrtc

import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.webrtc.internal.WebRtcEnvironment
import info.dvkr.screenstream.webrtc.internal.WebRtcStreamingService
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings
import info.dvkr.screenstream.webrtc.settings.WebRtcSettingsImpl
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.createScope
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.StringQualifier
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module

internal class WebRtcKoinScope : KoinScopeComponent {
    override val scope: Scope by lazy(LazyThreadSafetyMode.NONE) { createScope(this) }
}

internal val WebRtcKoinQualifier: Qualifier = StringQualifier("WebRtcStreamingModule")

public val WebRtcKoinModule: Module = module {
    single(WebRtcKoinQualifier) { WebRtcStreamingModule() } bind (StreamingModule::class)
    single { WebRtcSettingsImpl(get()) } bind (WebRtcSettings::class)
    scope<WebRtcKoinScope> {
        scoped { WebRtcEnvironment(get()) }
        scoped { params ->
            WebRtcStreamingService(
                service = params.get(),
                mutableWebRtcStateFlow = params.get(),
                environment = get(),
                webRtcSettings = get(),
                streamingAnalytics = get()
            )
        } bind (WebRtcStreamingService::class)
    }
}
