package info.dvkr.screenstream.data.state

import android.content.Intent
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.HttpClient
import info.dvkr.screenstream.data.model.NetInterface
import info.dvkr.screenstream.data.model.TrafficPoint


interface AppStateMachine {

    open class Event {
        object StartStream : Event()
        object CastPermissionsDenied : Event()
        class StartProjection(val intent: Intent) : Event()
        object StopStream : Event()
        object RequestPublicState : Event()
        object RecoverError : Event()

        override fun toString(): String = javaClass.simpleName
    }

    sealed class Effect {
        object ConnectionChanged : Effect()

        data class PublicState(
            val isStreaming: Boolean,
            val isBusy: Boolean,
            val isWaitingForPermission: Boolean,
            val netInterfaces: List<NetInterface>,
            val appError: AppError?
        ) : Effect()

        sealed class Statistic : Effect() {
            class Clients(val clients: List<HttpClient>) : Statistic()
            class Traffic(val traffic: List<TrafficPoint>) : Statistic()
        }
    }

    fun sendEvent(event: Event, timeout: Long = 0)

    suspend fun destroy()
}