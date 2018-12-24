package info.dvkr.screenstream.data.state

import android.content.Intent
import androidx.annotation.AnyThread
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.NetInterface


interface AppStateMachine {

    open class Event {
        object StartStream : Event()
        class StartProjection(val intent: Intent) : Event()
        object StopStream : Event()
        object RequestPublicState : Event()
        object RecoverError : Event()
    }

    sealed class Effect {
        object RequestCastPermissions : Effect()
        object ConnectionChanged : Effect()
        data class PublicState(
            val isStreaming: Boolean,
            val isBusy: Boolean,
            val netInterfaces: List<NetInterface>,
            val appError: AppError?
        ) : Effect()
    }

    @Throws(FatalError.ChannelException::class)
    fun sendEvent(event: Event, timeout: Long = 0)

    @AnyThread
    fun destroy()
}