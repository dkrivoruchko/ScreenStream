package info.dvkr.screenstream.mjpeg

import info.dvkr.screenstream.common.StreamingModule
import info.dvkr.screenstream.mjpeg.internal.MjpegState
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Scope

@Scope(MjpegKoinScope::class)
internal class MjpegStateFlowProvider(
    @InjectedParam internal val mutableAppStateFlow: MutableStateFlow<StreamingModule.AppState>,
    @InjectedParam internal val mutableMjpegStateFlow: MutableStateFlow<MjpegState>
)