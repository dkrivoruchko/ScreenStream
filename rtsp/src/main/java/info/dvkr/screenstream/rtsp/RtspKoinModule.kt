package info.dvkr.screenstream.rtsp

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.createScope
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.StringQualifier
import org.koin.core.scope.Scope

public class RtspKoinScope : KoinScopeComponent {
    override val scope: Scope by lazy(LazyThreadSafetyMode.NONE) { createScope(this) }
}

internal val RtspKoinQualifier: Qualifier = StringQualifier("RtspStreamingModule")

@Module
@Configuration
@ComponentScan
public class RtspKoinModule
