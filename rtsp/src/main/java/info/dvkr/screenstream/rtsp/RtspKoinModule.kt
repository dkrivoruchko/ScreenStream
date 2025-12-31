package info.dvkr.screenstream.rtsp

import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.internal.rtsp.server.NetworkHelper
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.settings.RtspSettingsImpl
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.createScope
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.StringQualifier
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module

public class RtspKoinScope : KoinScopeComponent {
    override val scope: Scope by lazy(LazyThreadSafetyMode.NONE) { createScope(this) }
}

internal val RtspKoinQualifier: Qualifier = StringQualifier("RtspStreamingModule")

public val RtspKoinModule: org.koin.core.module.Module = module {
    single(RtspKoinQualifier) { RtspStreamingModule() } bind (StreamingModule::class)
    single { RtspSettingsImpl(context = get()) } bind (RtspSettings::class)
    single { NetworkHelper(context = get()) } bind (NetworkHelper::class)
    scope<RtspKoinScope> {
        scoped { params -> RtspStreamingService(params.get(), params.get(), get(), get()) } bind (RtspStreamingService::class)
    }
}