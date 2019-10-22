package info.dvkr.screenstream.data.state

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog
import java.util.*

object StateToEventMatrix {

    fun skippEvent(state: StreamState.State, event: AppStateMachine.Event): Boolean {
        val action = matrix[state]?.get(event::class.java)
        require(action != null) { getLog("skippEvent", "Unknown Sate-Event pair [$state, $event]") }

        return when (action) {
            Action.Process -> {
                XLog.i(getLog("skippEvent", "Accepting: [State:$state, Event:$event]"))
                false
            }

            Action.Skipp -> {
                XLog.w(getLog("skippEvent", "Skipping: [State:$state, Event:$event]"))
                true
            }

            Action.Error ->
                throw IllegalStateException("AppStateMachine in state [$state] event: $event")
        }
    }

    private sealed class Action {
        object Process : Action()
        object Skipp : Action()
        object Error : Action()
    }

    private val matrix: EnumMap<StreamState.State, Map<Class<out AppStateMachine.Event>, Action>> =
        EnumMap(StreamState.State::class.java)

    init {
        matrix[StreamState.State.CREATED] = mapOf(
            AppStateMachineImpl.InternalEvent.DiscoverAddress::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartServer::class.java to Action.Error,
            AppStateMachineImpl.InternalEvent.ComponentError::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartStopFromWebPage::class.java to Action.Error,
            AppStateMachineImpl.InternalEvent.RestartServer::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.ScreenOff::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Error,
            AppStateMachine.Event.StopStream::class.java to Action.Error,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Skipp
        )

        matrix[StreamState.State.ADDRESS_DISCOVERED] = mapOf(
            AppStateMachineImpl.InternalEvent.DiscoverAddress::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartServer::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.ComponentError::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartStopFromWebPage::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.RestartServer::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.ScreenOff::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Skipp,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.SERVER_STARTED] = mapOf(
            AppStateMachineImpl.InternalEvent.DiscoverAddress::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartServer::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.ComponentError::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartStopFromWebPage::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.RestartServer::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.ScreenOff::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Process,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Process,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.PERMISSION_PENDING] = mapOf(
            AppStateMachineImpl.InternalEvent.DiscoverAddress::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartServer::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.ComponentError::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartStopFromWebPage::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.RestartServer::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.ScreenOff::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Process,
            AppStateMachine.Event.StartProjection::class.java to Action.Process,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.STREAMING] = mapOf(
            AppStateMachineImpl.InternalEvent.DiscoverAddress::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.StartServer::class.java to Action.Error,
            AppStateMachineImpl.InternalEvent.ComponentError::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartStopFromWebPage::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.RestartServer::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.ScreenOff::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Skipp,
            AppStateMachine.Event.StopStream::class.java to Action.Process,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.RESTART_PENDING] = mapOf(
            AppStateMachineImpl.InternalEvent.DiscoverAddress::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartServer::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.ComponentError::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartStopFromWebPage::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.RestartServer::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.ScreenOff::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Skipp,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.ERROR] = mapOf(
            AppStateMachineImpl.InternalEvent.DiscoverAddress::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.StartServer::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.ComponentError::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.StartStopFromWebPage::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.RestartServer::class.java to Action.Process,
            AppStateMachineImpl.InternalEvent.ScreenOff::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Skipp,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.DESTROYED] = mapOf(
            AppStateMachineImpl.InternalEvent.DiscoverAddress::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.StartServer::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.ComponentError::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.StartStopFromWebPage::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.RestartServer::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.ScreenOff::class.java to Action.Skipp,
            AppStateMachineImpl.InternalEvent.Destroy::class.java to Action.Skipp,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Skipp,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Skipp,
            AppStateMachine.Event.RecoverError::class.java to Action.Skipp
        )
    }
}