package info.dvkr.screenstream.webrtc

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.createScope
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.StringQualifier
import org.koin.core.scope.Scope

internal class WebRtcKoinScope : KoinScopeComponent {
    override val scope: Scope by lazy(LazyThreadSafetyMode.NONE) { createScope(this) }
}

internal val WebRtcKoinQualifier: Qualifier = StringQualifier("WebRtcStreamingModule")

@Module
@Configuration
@ComponentScan
public class WebRtcKoinModule
