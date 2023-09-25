package info.dvkr.screenstream.webrtc

import info.dvkr.screenstream.common.StreamingModule
import info.dvkr.screenstream.webrtc.internal.WebRtcState
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Scope

@Scope(WebRtcKoinScope::class)
internal class WebRtcStateFlowProvider(
    @InjectedParam internal val mutableAppStateFlow: MutableStateFlow<StreamingModule.AppState>,
    @InjectedParam internal val mutableWebRtcStateFlow: MutableStateFlow<WebRtcState>
)