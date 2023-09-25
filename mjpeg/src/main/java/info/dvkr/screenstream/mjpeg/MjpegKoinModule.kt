package info.dvkr.screenstream.mjpeg

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.createScope
import org.koin.core.scope.Scope

@Module
@ComponentScan
public class MjpegKoinModule

public class MjpegKoinScope : KoinScopeComponent {
    override val scope: Scope by lazy(LazyThreadSafetyMode.NONE) { createScope(this) }
}

internal const val MjpegKoinQualifier: String = "MjpegStreamingModule"