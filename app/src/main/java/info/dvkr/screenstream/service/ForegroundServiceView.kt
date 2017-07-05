package info.dvkr.screenstream.service


import android.content.Intent
import rx.Observable

interface ForegroundServiceView {

    sealed class Event {
        class Init : Event() // To ForegroundServicePresenter
        class StartHttpServerRequest : Event() // To ForegroundService
        data class StartHttpServer(val favicon: ByteArray,
                                   val baseIndexHtml: String,
                                   val basePinRequestHtml: String,
                                   val pinRequestErrorMsg: String) : Event() // To ForegroundServicePresenter

        data class Notify(val notifyType: String) : Event() // To ForegroundService
        class StopHttpServer : Event() // To ForegroundServicePresenter
        data class StartStream(val intent: Intent) : Event() // To ForegroundService
        data class StopStream(val isNotifyOnComplete: Boolean) : Event() // To ForegroundService
        class StopStreamComplete : Event() // To ForegroundServicePresenter
        class ScreenOff : Event() // To ForegroundServicePresenter
        class AppExit : Event() // To ForegroundService
        data class AppError(val error: Throwable) : Event() // To ForegroundService
    }

    fun onEvent(): Observable<Event> // Events from ForegroundService

    fun sendEvent(event: Event) // Events to ForegroundService

    fun sendEvent(event: Event, timeout: Long) // Events to ForegroundService
}