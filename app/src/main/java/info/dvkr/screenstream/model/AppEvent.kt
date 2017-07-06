package info.dvkr.screenstream.model


import android.support.annotation.Keep
import rx.Observable
import rx.subjects.BehaviorSubject
import java.net.InetSocketAddress

interface AppEvent {
    companion object {
        const val APP_STATUS_ERROR_SERVER_PORT_BUSY = "APP_STATUS_ERROR_SERVER_PORT_BUSY"
        const val APP_STATUS_ERROR_WRONG_IMAGE_FORMAT = "APP_STATUS_ERROR_WRONG_IMAGE_FORMAT"
    }

    sealed class Event {
        @Keep class StopStream : Event() // From: StartActivityPresenter
        @Keep class AppExit : Event() // From: StartActivityPresenter
        @Keep data class HttpServerRestart(val restartReason: String) : Event() // From: SettingsActivityPresenter
        @Keep data class AppStatus(val status: String) : Event() // From ImageGeneratorImpl, HttpServerImpl
        @Keep data class AppError(val error: Throwable) : Event()
    }

    fun sendEvent(event: Event)

    fun onEvent(): Observable<Event>

    fun getAppStatus(): Observable<Set<String>>

    fun getStreamRunning(): BehaviorSubject<Boolean>

    fun getJpegBytesStream(): BehaviorSubject<ByteArray>

    // Clients data
    fun clearClients()

    fun sendClientEvent(clientEvent: HttpServer.ClientEvent)

    fun onClientEvent(): Observable<List<InetSocketAddress>>
}