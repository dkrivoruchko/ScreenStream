package info.dvkr.screenstream.vnc

import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.vnc.internal.VncNetworkHelper
import info.dvkr.screenstream.vnc.internal.VncStreamingService
import info.dvkr.screenstream.vnc.settings.VncSettings
import info.dvkr.screenstream.vnc.settings.VncSettingsImpl
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.createScope
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.StringQualifier
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module

public class VncKoinScope : KoinScopeComponent {
    override val scope: Scope by lazy(LazyThreadSafetyMode.NONE) { createScope(this) }
}

internal val VncKoinQualifier: Qualifier = StringQualifier("VncStreamingModule")

public val VncKoinModule: org.koin.core.module.Module = module {
    single(VncKoinQualifier) { VncStreamingModule() } bind (StreamingModule::class)
    single { VncSettingsImpl(context = get()) } bind (VncSettings::class)
    scope<VncKoinScope> {
        scoped { VncNetworkHelper(context = get()) }
        scoped { params ->
            VncStreamingService(
                service = params.get(),
                mutableVncStateFlow = params.get(),
                vncSettings = get(),
                networkHelper = get(),
                streamingAnalytics = get()
            )
        } bind (VncStreamingService::class)
    }
}
