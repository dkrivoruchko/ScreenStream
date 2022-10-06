package info.dvkr.screenstream.common

import android.content.Intent

sealed class AppError : Throwable() {
    open class FatalError : AppError()
    open class FixableError : AppError()
}

interface Client
interface TrafficPoint

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
        abstract class PublicState : Effect()
        sealed class Statistic : Effect() {
            class Clients(val clients: List<Client>) : Statistic()
            class Traffic(val traffic: List<TrafficPoint>) : Statistic()
        }
    }

    fun sendEvent(event: Event, timeout: Long = 0)

    fun destroy()
}