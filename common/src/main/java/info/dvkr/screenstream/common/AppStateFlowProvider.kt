package info.dvkr.screenstream.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single

@Single
public class AppStateFlowProvider(
    public val mutableAppStateFlow: MutableStateFlow<StreamingModule.AppState> = MutableStateFlow(StreamingModule.AppState()),
    public val appStateFlow: StateFlow<StreamingModule.AppState> = mutableAppStateFlow.asStateFlow()
)