package info.dvkr.screenstream.app

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.screenstream.streaming.StreamingModuleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Named

/** One initial module selection and Exit wait across Activity configuration changes. */
@KoinViewModel
@Named("SingleActivityViewModel")
internal class SingleActivityViewModel(
    private val streamingManager: StreamingModuleManager,
) : ViewModel() {

    sealed interface ExitState {
        data object Idle : ExitState
        data object Waiting : ExitState
        data object Finished : ExitState
    }

    val exitState: StateFlow<ExitState> field = MutableStateFlow<ExitState>(ExitState.Idle)
    private var bootstrapConsumed: Boolean = false

    /** Dispatch selection before observing Exit, once per Activity-scoped ViewModel. */
    @MainThread
    fun onActivityCreated() {
        if (bootstrapConsumed) return
        bootstrapConsumed = true
        if (exitState.value == ExitState.Idle) streamingManager.selectModule()

        viewModelScope.launch {
            streamingManager.state.first { it == StreamingModuleManager.State.Exiting }
            if (beginExitWait()) finishExitWait()
        }
    }

    /** Join the process Exit once, retaining the wait across configuration changes. */
    @MainThread
    fun requestExit() {
        if (beginExitWait()) viewModelScope.launch { finishExitWait() }
    }

    private fun beginExitWait(): Boolean {
        if (exitState.value != ExitState.Idle) return false
        exitState.value = ExitState.Waiting
        return true
    }

    private suspend fun finishExitWait() {
        streamingManager.exit()
        exitState.value = ExitState.Finished
    }
}
