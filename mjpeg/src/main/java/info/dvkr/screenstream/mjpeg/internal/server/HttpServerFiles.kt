package info.dvkr.screenstream.mjpeg.internal.server

import android.content.Context
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.randomString
import info.dvkr.screenstream.mjpeg.MjpegSettings
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.internal.getFileFromAssets
import kotlinx.coroutines.flow.first
import java.nio.charset.StandardCharsets

internal class HttpServerFiles(context: Context, private val mjpegSettings: MjpegSettings) {
    internal companion object {
        internal const val FAVICON_PNG = "favicon.png"
        internal const val LOGO_PNG = "logo.png"
        internal const val FULLSCREEN_ON_PNG = "fullscreen-on.png"
        internal const val FULLSCREEN_OFF_PNG = "fullscreen-off.png"
        internal const val START_STOP_PNG = "start-stop.png"

        private const val INDEX_HTML = "index.html"
        private const val INDEX_HTML_BACKGROUND_COLOR = "BACKGROUND_COLOR"
        private const val INDEX_HTML_SCREEN_STREAM_ADDRESS = "SCREEN_STREAM_ADDRESS"
        private const val INDEX_HTML_ENABLE_BUTTONS = "ENABLE_BUTTONS"

        private const val PINREQUEST_HTML = "pinrequest.html"
        private const val PINREQUEST_HTML_STREAM_REQUIRE_PIN = "STREAM_REQUIRE_PIN"
        private const val PINREQUEST_HTML_ENTER_PIN = "ENTER_PIN"
        private const val PINREQUEST_HTML_SUBMIT_TEXT = "SUBMIT_TEXT"
        private const val PINREQUEST_HTML_WRONG_PIN_MESSAGE = "WRONG_PIN_MESSAGE"

        private const val ADDRESS_BLOCKED_HTML = "blocked.html"
        private const val ADDRESS_BLOCKED_HTML_ADDRESS_BLOCKED = "ADDRESS_BLOCKED"

        internal const val ROOT_ADDRESS = "/"
        internal const val PIN_REQUEST_ADDRESS = "pinrequest"
        internal const val CLIENT_BLOCKED_ADDRESS = "blocked"
        internal const val START_STOP_ADDRESS = "start-stop"
        internal const val PIN_PARAMETER = "pin"
    }

    private val applicationContext: Context = context.applicationContext

    internal val faviconPng: ByteArray = applicationContext.getFileFromAssets(FAVICON_PNG)
    internal val logoPng: ByteArray = applicationContext.getFileFromAssets(LOGO_PNG)
    internal val fullscreenOnPng: ByteArray = applicationContext.getFileFromAssets(FULLSCREEN_ON_PNG)
    internal val fullscreenOffPng: ByteArray = applicationContext.getFileFromAssets(FULLSCREEN_OFF_PNG)
    internal val startStopPng: ByteArray = applicationContext.getFileFromAssets(START_STOP_PNG)

    private val baseIndexHtml = String(applicationContext.getFileFromAssets(INDEX_HTML), StandardCharsets.UTF_8)

    private val basePinRequestHtml = String(applicationContext.getFileFromAssets(PINREQUEST_HTML), StandardCharsets.UTF_8)
        .replaceFirst(PINREQUEST_HTML_STREAM_REQUIRE_PIN.toRegex(), applicationContext.getString(R.string.mjpeg_html_stream_require_pin))
        .replaceFirst(PINREQUEST_HTML_ENTER_PIN.toRegex(), applicationContext.getString(R.string.mjpeg_html_enter_pin))
        .replaceFirst(PINREQUEST_HTML_SUBMIT_TEXT.toRegex(), applicationContext.getString(R.string.mjpeg_html_submit_text))

    private val baseAddressBlockedHtml: String = String(applicationContext.getFileFromAssets(ADDRESS_BLOCKED_HTML), StandardCharsets.UTF_8)

    internal var htmlEnableButtons: Boolean = false
    internal var htmlBackColor: Int = 0
    internal lateinit var pin: String
    internal lateinit var streamAddress: String
    internal lateinit var jpegFallbackAddress: String
    internal lateinit var indexHtml: String
    internal lateinit var pinRequestHtml: String
    internal lateinit var pinRequestErrorHtml: String
    internal lateinit var addressBlockedHtml: String
    private var enablePin: Boolean = false

    internal suspend fun configure() {
        XLog.d(getLog("configure"))

        htmlEnableButtons = mjpegSettings.htmlEnableButtonsFlow.first()
        htmlBackColor = mjpegSettings.htmlBackColorFlow.first()
        enablePin = mjpegSettings.enablePinFlow.first()
        pin = mjpegSettings.pinFlow.first()
        streamAddress = configureStreamAddress()
        jpegFallbackAddress = configureJpegFallbackAddress()
        indexHtml = configureIndexHtml(streamAddress)

        pinRequestHtml = configurePinRequestHtml()
        pinRequestErrorHtml = configurePinRequestErrorHtml()
        addressBlockedHtml = configureAddressBlockedHtml()
    }

    private fun configureStreamAddress(): String = (if (enablePin) "${randomString(16)}.mjpeg" else "stream.mjpeg")

    private fun configureJpegFallbackAddress(): String = streamAddress.dropLast(5) + "jpeg"

    private fun configureIndexHtml(streamAddress: String): String =
        baseIndexHtml
            .replaceFirst(INDEX_HTML_ENABLE_BUTTONS.toRegex(), (htmlEnableButtons && enablePin.not()).toString())
            .replaceFirst(INDEX_HTML_BACKGROUND_COLOR.toRegex(), "#%06X".format(0xFFFFFF and htmlBackColor))
            .replace(INDEX_HTML_SCREEN_STREAM_ADDRESS.toRegex(), streamAddress)

    private fun configurePinRequestHtml(): String =
        if (enablePin) basePinRequestHtml.replaceFirst(PINREQUEST_HTML_WRONG_PIN_MESSAGE.toRegex(), "&nbsp") else ""

    private fun configurePinRequestErrorHtml(): String =
        if (enablePin)
            basePinRequestHtml.replaceFirst(
                PINREQUEST_HTML_WRONG_PIN_MESSAGE.toRegex(), applicationContext.getString(R.string.mjpeg_html_wrong_pin)
            )
        else ""

    private fun configureAddressBlockedHtml(): String =
        if (enablePin)
            baseAddressBlockedHtml.replaceFirst(
                ADDRESS_BLOCKED_HTML_ADDRESS_BLOCKED.toRegex(), applicationContext.getString(R.string.mjpeg_html_address_blocked)
            )
        else ""
}