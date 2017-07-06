package info.dvkr.screenstream.service


import android.content.Intent
import android.support.annotation.Keep
import rx.Observable

interface ForegroundServiceView {

    sealed class Event {
        @Keep class Start : Event() // Local ForegroundService
        @Keep class Init : Event() // To ForegroundServicePresenter
        @Keep class StartHttpServerRequest : Event() // To ForegroundService
        @Keep data class StartHttpServer(val favicon: ByteArray,
                                         val baseIndexHtml: String,
                                         val basePinRequestHtml: String,
                                         val pinRequestErrorMsg: String) : Event() // To ForegroundServicePresenter

        @Keep data class Notify(val notifyType: String) : Event() // To ForegroundService
        @Keep class StopHttpServer : Event() // To ForegroundServicePresenter
        @Keep data class StartStream(val intent: Intent) : Event() // To ForegroundService
        @Keep data class StopStream(val isNotifyOnComplete: Boolean) : Event() // To ForegroundService
        @Keep class StopStreamComplete : Event() // To ForegroundServicePresenter
        @Keep class ScreenOff : Event() // To ForegroundServicePresenter
        @Keep class AppExit : Event() // To ForegroundService
        @Keep class AppStatus() : Event() // To ForegroundService
        @Keep data class AppError(val error: Throwable) : Event() // To ForegroundService
    }

    fun onEvent(): Observable<Event> // Events from ForegroundService

    fun sendEvent(event: Event) // Events to ForegroundService

    fun sendEvent(event: Event, timeout: Long) // Events to ForegroundService
}