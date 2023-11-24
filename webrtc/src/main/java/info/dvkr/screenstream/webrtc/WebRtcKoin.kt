package info.dvkr.screenstream.webrtc

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.createScope
import org.koin.core.scope.Scope

@Module
@ComponentScan
public class WebRtcKoinModule

internal class WebRtcKoinScope : KoinScopeComponent {
    override val scope: Scope by lazy(LazyThreadSafetyMode.NONE) { createScope(this) }
}

internal const val WebRtcKoinQualifier: String = "WebRtcStreamingModule"