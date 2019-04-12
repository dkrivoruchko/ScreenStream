package info.dvkr.screenstream.data.httpserver

import android.content.Context
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.other.randomString
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import java.nio.charset.StandardCharsets

class HttpServerFiles(context: Context, private val settingsReadOnly: SettingsReadOnly) {
    companion object {
        private const val FAVICON_PNG = "favicon.png"
        private const val LOGO_PNG = "logo.png"
        private const val FULLSCREEN_ON_PNG = "fullscreen-on.png"
        private const val FULLSCREEN_OFF_PNG = "fullscreen-off.png"
        private const val START_STOP_PNG = "start-stop.png"

        private const val INDEX_HTML = "index.html"
        private const val INDEX_HTML_BACKGROUND_COLOR = "BACKGROUND_COLOR"
        private const val INDEX_HTML_SCREEN_STREAM_ADDRESS = "SCREEN_STREAM_ADDRESS"
        private const val INDEX_HTML_START_STOP_ADDRESS = "START_STOP_ADDRESS"
        private const val INDEX_HTML_ENABLE_BUTTONS = "ENABLE_BUTTONS"

        private const val PINREQUEST_HTML = "pinrequest.html"
        private const val PINREQUEST_HTML_STREAM_REQUIRE_PIN = "STREAM_REQUIRE_PIN"
        private const val PINREQUEST_HTML_ENTER_PIN = "ENTER_PIN"
        private const val PINREQUEST_HTML_FOUR_DIGITS = "FOUR_DIGITS"
        private const val PINREQUEST_HTML_SUBMIT_TEXT = "SUBMIT_TEXT"
        private const val PINREQUEST_HTML_WRONG_PIN_MESSAGE = "WRONG_PIN_MESSAGE"

        const val DEFAULT_HTML_ADDRESS = "/"
        private const val DEFAULT_STREAM_ADDRESS = "/stream.mjpeg"
        private const val DEFAULT_START_STOP_ADDRESS = "/start-stop"
        const val DEFAULT_PIN_ADDRESS = "/?pin="

        const val ICON_PNG_ADDRESS = "/favicon.ico"
        const val LOGO_PNG_ADDRESS = "/logo.png"
        const val FULLSCREEN_ON_PNG_ADDRESS = "/fullscreen-on.png"
        const val FULLSCREEN_OFF_PNG_ADDRESS = "/fullscreen-off.png"
        const val START_STOP_PNG_ADDRESS = "/start-stop.png"
    }

    private val applicationContext: Context = context.applicationContext

    val faviconPng = getFileFromAssets(applicationContext, FAVICON_PNG)
    val logoPng = getFileFromAssets(applicationContext, LOGO_PNG)
    val fullScreenOnPng = getFileFromAssets(applicationContext, FULLSCREEN_ON_PNG)
    val fullScreenOffPng = getFileFromAssets(applicationContext, FULLSCREEN_OFF_PNG)
    val startStopPng = getFileFromAssets(applicationContext, START_STOP_PNG)

    private val baseIndexHtml =
        String(getFileFromAssets(applicationContext, INDEX_HTML), StandardCharsets.UTF_8)

    private val basePinRequestHtml =
        String(getFileFromAssets(applicationContext, PINREQUEST_HTML), StandardCharsets.UTF_8)
            .replaceFirst(
                PINREQUEST_HTML_STREAM_REQUIRE_PIN.toRegex(),
                applicationContext.getString(R.string.html_stream_require_pin)
            )
            .replaceFirst(
                PINREQUEST_HTML_ENTER_PIN.toRegex(),
                applicationContext.getString(R.string.html_enter_pin)
            )
            .replaceFirst(
                PINREQUEST_HTML_FOUR_DIGITS.toRegex(),
                applicationContext.getString(R.string.html_four_digits)
            )
            .replaceFirst(
                PINREQUEST_HTML_SUBMIT_TEXT.toRegex(),
                applicationContext.getString(R.string.html_submit_text)
            )

    private var htmlEnableButtons = settingsReadOnly.htmlEnableButtons
    private var htmlBackColor = settingsReadOnly.htmlBackColor
    private var enablePin = settingsReadOnly.enablePin
    private var pin = settingsReadOnly.pin

    fun prepareForConfigure(): Pair<Boolean, Boolean> {
        htmlEnableButtons = settingsReadOnly.htmlEnableButtons
        htmlBackColor = settingsReadOnly.htmlBackColor
        enablePin = settingsReadOnly.enablePin
        pin = settingsReadOnly.pin

        return Pair(htmlEnableButtons, enablePin)
    }

    fun configureStreamAddress(): String =
        if (enablePin) "/" + randomString(16) + ".mjpeg"
        else DEFAULT_STREAM_ADDRESS

    fun configureStartStopAddress(): String =
        if (enablePin) "/" + randomString(16)
        else DEFAULT_START_STOP_ADDRESS

    fun configureIndexHtml(streamAddress: String, startStopAddress: String): String =
        baseIndexHtml
            .replaceFirst(
                INDEX_HTML_ENABLE_BUTTONS.toRegex(),
                htmlEnableButtons.toString()
            )
            .replaceFirst(
                INDEX_HTML_BACKGROUND_COLOR.toRegex(),
                "#%06X".format(0xFFFFFF and htmlBackColor)
            )
            .replaceFirst(INDEX_HTML_SCREEN_STREAM_ADDRESS.toRegex(), streamAddress)
            .replaceFirst(INDEX_HTML_START_STOP_ADDRESS.toRegex(), startStopAddress)

    fun configurePinAddress(): String =
        if (enablePin) DEFAULT_PIN_ADDRESS + pin else DEFAULT_PIN_ADDRESS

    fun configurePinRequestHtml(): String =
        if (enablePin)
            basePinRequestHtml.replaceFirst(PINREQUEST_HTML_WRONG_PIN_MESSAGE.toRegex(), "&nbsp")
        else
            ""

    fun configurePinRequestErrorHtml(): String =
        if (enablePin)
            basePinRequestHtml
                .replaceFirst(
                    PINREQUEST_HTML_WRONG_PIN_MESSAGE.toRegex(),
                    applicationContext.getString(R.string.html_wrong_pin)
                )
        else
            ""

    private fun getFileFromAssets(context: Context, fileName: String): ByteArray {
        XLog.d(getLog("getFileFromAssets", fileName))

        context.assets.open(fileName).use { inputStream ->
            val fileBytes = ByteArray(inputStream.available())
            inputStream.read(fileBytes)
            fileBytes.isNotEmpty() || throw IllegalStateException("$fileName is empty")
            return fileBytes
        }
    }
}