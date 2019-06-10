package info.dvkr.screenstream.data.state

import android.content.Intent
import androidx.annotation.AnyThread
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.NetInterface


interface AppStateMachine {

    open class Event {
        object StartStream : Event()
        object CastPermissionsDenied : Event()
        class StartProjection(val intent: Intent) : Event()
        object StopStream : Event()
        object RequestPublicState : Event()
        object RecoverError : Event()

        override fun toString(): String = this::class.java.simpleName
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
    }

    fun sendEvent(event: Event, timeout: Long = 0)

    @AnyThread
    fun destroy()
}