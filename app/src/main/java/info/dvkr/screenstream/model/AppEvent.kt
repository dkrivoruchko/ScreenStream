package info.dvkr.screenstream.model


import rx.Observable
import rx.subjects.BehaviorSubject
import java.net.InetSocketAddress

interface AppEvent {
    companion object {
        const val APP_ERROR_WRONG_IMAGE_FORMAT = "APP_ERROR_WRONG_IMAGE_FORMAT"
        const val APP_ERROR_SERVER_PORT_BUSY = "APP_ERROR_SERVER_PORT_BUSY"
        const val APP_ERROR_UNKNOWN_ERROR = "APP_ERROR_UNKNOWN_ERROR"
    }

    sealed class Event {
        class StopStream : Event() // From: StartActivityPresenter
        class AppExit : Event() // From: StartActivityPresenter
        data class HttpServerRestart(val restartReason: String) : Event() // From: SettingsActivityPresenter
        data class AppError(val error: Throwable) : Event() // From: ImageGeneratorImpl, HttpServerImpl
    }

    fun sendEvent(event: Event)

    fun onEvent(): Observable<Event>

    // TODO Maybe Change
    fun getStreamRunning(): BehaviorSubject<Boolean>

    // TODO Maybe Change
    fun getJpegBytesStream(): BehaviorSubject<ByteArray>

    // Clients data
    fun clearClients()

    fun sendClientEvent(clientEvent: HttpServer.ClientEvent)

    fun onClientEvent(): Observable<List<InetSocketAddress>>
}