package info.dvkr.screenstream.data.httpserver

internal interface HttpServerFiles {
    companion object {
        const val FAVICON_ICO = "favicon.ico"
        const val LOGO_PNG = "logo.png"

        const val INDEX_HTML = "index.html"
        const val INDEX_HTML_BACKGROUND_COLOR = "BACKGROUND_COLOR"
        const val INDEX_HTML_SCREEN_STREAM_ADDRESS = "SCREEN_STREAM_ADDRESS"

        const val PINREQUEST_HTML = "pinrequest.html"
        const val PINREQUEST_HTML_STREAM_REQUIRE_PIN = "STREAM_REQUIRE_PIN"
        const val PINREQUEST_HTML_ENTER_PIN = "ENTER_PIN"
        const val PINREQUEST_HTML_FOUR_DIGITS = "FOUR_DIGITS"
        const val PINREQUEST_HTML_SUBMIT_TEXT = "SUBMIT_TEXT"
        const val PINREQUEST_HTML_WRONG_PIN_MESSAGE = "WRONG_PIN_MESSAGE"

        const val DEFAULT_HTML_ADDRESS = "/"
        const val DEFAULT_STREAM_ADDRESS = "/stream.mjpeg"
        const val DEFAULT_ICON_ADDRESS = "/favicon.ico"
        const val DEFAULT_PIN_ADDRESS = "/?pin="
        const val DEFAULT_LOGO_ADDRESS = "/logo.png"
    }
}