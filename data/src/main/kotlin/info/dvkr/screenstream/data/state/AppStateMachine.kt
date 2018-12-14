package info.dvkr.screenstream.data.state

import android.content.Intent
import androidx.annotation.AnyThread
import androidx.annotation.Keep
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.NetInterface


interface AppStateMachine {

    @Keep open class Event {
        @Keep object StartStream : Event()
        @Keep object CastPermissionsDenied : Event()
        @Keep class StartProjection(val intent: Intent) : Event()
        @Keep object StopStream : Event()
        @Keep object RequestPublicState : Event()
        @Keep object RecoverError : Event()
    }

    @Keep sealed class Effect {
        @Keep object RequestCastPermissions : Effect()
        @Keep object ConnectionChanged : Effect()
        @Keep data class PublicState(
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