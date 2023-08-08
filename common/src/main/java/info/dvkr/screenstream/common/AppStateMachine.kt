package info.dvkr.screenstream.common

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes

sealed class AppError(@StringRes open val id: Int, override val message: String? = null) : Throwable() {
    open class FatalError(@StringRes id: Int = 0) : AppError(id)
    open class FixableError(@StringRes id: Int, override val message: String? = null) : AppError(id)

    open fun toString(context: Context): String = if (id != 0) context.getString(id) else message ?: toString()
}

interface Client {
    val id: Long
    val clientAddress: String
    override fun equals(other: Any?): Boolean
}

interface TrafficPoint

interface AppStateMachine {

    open class Event {
        data object StartStream : Event()
        data object CastPermissionsDenied : Event()
        class StartProjection(val intent: Intent) : Event() {
            override fun toString(): String = javaClass.simpleName
        }

        data object StopStream : Event()
        data object GetNewStreamId : Event()
        data object CreateNewStreamPassword : Event()
        data object RequestPublicState : Event()
        data object RecoverError : Event()
    }

    sealed class Effect {
        data object ConnectionChanged : Effect()
        abstract class PublicState : Effect()
        sealed class Statistic : Effect() {
            class Clients(val clients: List<Client>) : Statistic()
            class Traffic(val traffic: List<TrafficPoint>) : Statistic()
        }
    }

    fun sendEvent(event: Event, timeout: Long = 0)

    fun destroy()
}