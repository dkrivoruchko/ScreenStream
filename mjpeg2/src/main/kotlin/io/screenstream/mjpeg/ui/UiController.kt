package io.screenstream.mjpeg.ui

import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.flow.StateFlow

/** Capture controls for one installed controller; UI collection owns no streaming work. */
internal interface UiController {
    val state: StateFlow<State>

    /** Stop carries the exact capture identity shown by this instance's controls. */
    data class State(val instanceId: StreamingModule.InstanceId, val action: Action)

    sealed interface Action {
        data class Start(val enabled: Boolean) : Action
        data object Busy : Action
        data class Stop(val attempt: StreamingModule.CaptureAttemptId) : Action
    }
}
