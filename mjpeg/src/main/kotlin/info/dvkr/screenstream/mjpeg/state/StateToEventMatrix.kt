package info.dvkr.screenstream.mjpeg.state

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.AppStateMachine
import info.dvkr.screenstream.common.getLog
import java.util.*

object StateToEventMatrix {

    fun skippEvent(state: StreamState.State, event: AppStateMachine.Event): Boolean {
        val action = matrix[state]?.get(event::class.java)
        require(action != null) { getLog("skipEvent", "Unknown Sate-Event pair [$state, $event]") }

        return when (action) {
            Action.Process -> {
                XLog.i(getLog("skipEvent", "Accepting: [State:$state, Event:$event]"))
                false
            }

            Action.Skipp -> {
                XLog.w(getLog("skipEvent", "Skipping: [State:$state, Event:$event]"))
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
            MjpegStateMachine.InternalEvent.DiscoverAddress::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartServer::class.java to Action.Error,
            MjpegStateMachine.InternalEvent.ComponentError::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartStopFromWebPage::class.java to Action.Error,
            MjpegStateMachine.InternalEvent.RestartServer::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.ScreenOff::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Error,
            AppStateMachine.Event.StopStream::class.java to Action.Error,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Skipp
        )

        matrix[StreamState.State.ADDRESS_DISCOVERED] = mapOf(
            MjpegStateMachine.InternalEvent.DiscoverAddress::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartServer::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.ComponentError::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartStopFromWebPage::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.RestartServer::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.ScreenOff::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Skipp,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.SERVER_STARTED] = mapOf(
            MjpegStateMachine.InternalEvent.DiscoverAddress::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartServer::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.ComponentError::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartStopFromWebPage::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.RestartServer::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.ScreenOff::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Process,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Process,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.PERMISSION_PENDING] = mapOf(
            MjpegStateMachine.InternalEvent.DiscoverAddress::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartServer::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.ComponentError::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartStopFromWebPage::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.RestartServer::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.ScreenOff::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Process,
            AppStateMachine.Event.StartProjection::class.java to Action.Process,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.STREAMING] = mapOf(
            MjpegStateMachine.InternalEvent.DiscoverAddress::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.StartServer::class.java to Action.Error,
            MjpegStateMachine.InternalEvent.ComponentError::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartStopFromWebPage::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.RestartServer::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.ScreenOff::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Skipp,
            AppStateMachine.Event.StopStream::class.java to Action.Process,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.RESTART_PENDING] = mapOf(
            MjpegStateMachine.InternalEvent.DiscoverAddress::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartServer::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.ComponentError::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartStopFromWebPage::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.RestartServer::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.ScreenOff::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Skipp,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.ERROR] = mapOf(
            MjpegStateMachine.InternalEvent.DiscoverAddress::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.StartServer::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.ComponentError::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.StartStopFromWebPage::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.RestartServer::class.java to Action.Process,
            MjpegStateMachine.InternalEvent.ScreenOff::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.Destroy::class.java to Action.Process,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Skipp,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Process,
            AppStateMachine.Event.RecoverError::class.java to Action.Process
        )

        matrix[StreamState.State.DESTROYED] = mapOf(
            MjpegStateMachine.InternalEvent.DiscoverAddress::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.StartServer::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.ComponentError::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.StartStopFromWebPage::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.RestartServer::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.ScreenOff::class.java to Action.Skipp,
            MjpegStateMachine.InternalEvent.Destroy::class.java to Action.Skipp,

            AppStateMachine.Event.StartStream::class.java to Action.Skipp,
            AppStateMachine.Event.CastPermissionsDenied::class.java to Action.Skipp,
            AppStateMachine.Event.StartProjection::class.java to Action.Skipp,
            AppStateMachine.Event.StopStream::class.java to Action.Skipp,
            AppStateMachine.Event.RequestPublicState::class.java to Action.Skipp,
            AppStateMachine.Event.RecoverError::class.java to Action.Skipp
        )
    }
}