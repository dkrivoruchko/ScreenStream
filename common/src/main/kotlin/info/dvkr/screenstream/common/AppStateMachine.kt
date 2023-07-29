package info.dvkr.screenstream.common

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes

sealed class AppError(@StringRes open val id: Int) : Throwable() {
    open class FatalError(@StringRes id: Int=0) : AppError(id)
    open class FixableError(@StringRes id: Int) : AppError(id)

    open fun toString(context: Context): String = if (id != 0) context.getString(id) else toString()
}

interface Client {
    val id: Long
    val clientAddress: String
    override fun equals(other: Any?): Boolean
}

interface TrafficPoint

interface AppStateMachine {

    open class Event {
        object StartStream : Event()
        object CastPermissionsDenied : Event()
        class StartProjection(val intent: Intent) : Event()
        object StopStream : Event()
        object GetNewStreamId : Event()
        object CreateNewStreamPassword : Event()
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