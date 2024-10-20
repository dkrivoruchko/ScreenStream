package info.dvkr.screenstream.mjpeg

import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.mjpeg.internal.MjpegStreamingService
import info.dvkr.screenstream.mjpeg.internal.NetworkHelper
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.settings.MjpegSettingsImpl
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.createScope
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.StringQualifier
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module

public class MjpegKoinScope : KoinScopeComponent {
    override val scope: Scope by lazy(LazyThreadSafetyMode.NONE) { createScope(this) }
}

internal val MjpegKoinQualifier: Qualifier = StringQualifier("MjpegStreamingModule")

public val MjpegKoinModule: org.koin.core.module.Module = module {
    single(MjpegKoinQualifier) { MjpegStreamingModule() } bind (StreamingModule::class)
    single { MjpegSettingsImpl(context = get()) } bind (MjpegSettings::class)
    scope<MjpegKoinScope> {
        scoped { NetworkHelper(get()) }
        scoped { params -> MjpegStreamingService(params.get(), params.get(), get(), get()) } bind (MjpegStreamingService::class)
    }
}