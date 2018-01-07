package info.dvkr.screenstream.domain.httpserver


import android.support.annotation.Keep
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
        const val DEFAULT_LOGO_ADDRESS = "/logo_big.png"
    }

    // Clients. Open for HttpServerImpl
    @Keep open class Client(val clientAddress: InetSocketAddress,
                            var hasBackpressure: Boolean = false,
                            var disconnected: Boolean = false)

    // Traffic
    @Keep class TrafficPoint(val time: Long, val bytes: Long)

    fun stop()
}