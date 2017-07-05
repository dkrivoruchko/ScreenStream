package info.dvkr.screenstream.model


import rx.Observable
import java.net.InetSocketAddress

interface HttpServer {
    companion object {
        // Constants in index.html
        const val BACKGROUND_COLOR = "BACKGROUND_COLOR"
        const val SCREEN_STREAM_ADDRESS = "SCREEN_STREAM_ADDRESS"
        const val NO_MJPEG_SUPPORT_MESSAGE = "NO_MJPEG_SUPPORT_MESSAGE"

        // Constants in pinrequest.html
        const val STREAM_REQUIRE_PIN = "STREAM_REQUIRE_PIN"
        const val ENTER_PIN = "ENTER_PIN"
        const val FOUR_DIGITS = "FOUR_DIGITS"
        const val SUBMIT_TEXT = "SUBMIT_TEXT"
        const val WRONG_PIN_MESSAGE = "WRONG_PIN_MESSAGE"

        // Constants for HttpServer
        const val DEFAULT_HTML_ADDRESS = "/"
        const val DEFAULT_STREAM_ADDRESS = "/stream.mjpeg"
        const val DEFAULT_ICON_ADDRESS = "/favicon.ico"
        const val DEFAULT_PIN_ADDRESS = "/?pin="
    }

    sealed class ClientEvent {
        data class ClientConnected(val clientAddress: InetSocketAddress) : ClientEvent()
        data class ClientDisconnected(val clientAddress: InetSocketAddress) : ClientEvent()
    }

    fun stop()

    fun isRunning(): Boolean

    fun onClientStatusChange(): Observable<ClientEvent>
}