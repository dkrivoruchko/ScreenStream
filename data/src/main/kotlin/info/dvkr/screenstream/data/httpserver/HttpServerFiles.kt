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
        private const val FAVICON_ICO = "favicon.ico"
        private const val LOGO_PNG = "logo.png"

        private const val INDEX_HTML = "index.html"
        private const val INDEX_HTML_BACKGROUND_COLOR = "BACKGROUND_COLOR"
        private const val INDEX_HTML_SCREEN_STREAM_ADDRESS = "SCREEN_STREAM_ADDRESS"
        private const val INDEX_HTML_START_STOP_ADDRESS = "START_STOP_ADDRESS"
        private const val INDEX_HTML_FULLSCREEN_TEXT = "FULLSCREEN_TEXT"
        private const val INDEX_HTML_START_STOP_TEXT = "START_STOP_TEXT"

        private const val PINREQUEST_HTML = "pinrequest.html"
        private const val PINREQUEST_HTML_STREAM_REQUIRE_PIN = "STREAM_REQUIRE_PIN"
        private const val PINREQUEST_HTML_ENTER_PIN = "ENTER_PIN"
        private const val PINREQUEST_HTML_FOUR_DIGITS = "FOUR_DIGITS"
        private const val PINREQUEST_HTML_SUBMIT_TEXT = "SUBMIT_TEXT"
        private const val PINREQUEST_HTML_WRONG_PIN_MESSAGE = "WRONG_PIN_MESSAGE"

        const val DEFAULT_HTML_ADDRESS = "/"
        private const val DEFAULT_STREAM_ADDRESS = "/stream.mjpeg"
        private const val DEFAULT_START_STOP_ADDRESS = "/start-stop"
        const val DEFAULT_ICON_ADDRESS = "/favicon.ico"
        const val DEFAULT_PIN_ADDRESS = "/?pin="
        const val DEFAULT_LOGO_ADDRESS = "/logo.png"
    }

    private val applicationContext: Context = context.applicationContext

    val favicon = getFileFromAssets(applicationContext, FAVICON_ICO)
    val logo = getFileFromAssets(applicationContext, LOGO_PNG)

    private val baseIndexHtml =
        String(getFileFromAssets(applicationContext, INDEX_HTML), StandardCharsets.UTF_8)
            .replaceFirst(
                INDEX_HTML_FULLSCREEN_TEXT.toRegex(),
                applicationContext.getString(R.string.html_fullscreen_button)
            )
            .replaceFirst(
                INDEX_HTML_START_STOP_TEXT.toRegex(),
                applicationContext.getString(R.string.html_start_stop_button)
            )

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

    fun configureStreamAddress(): Pair<String, Boolean> =
        Pair(
            if (settingsReadOnly.enablePin) "/" + randomString(16) + ".mjpeg"
            else HttpServerFiles.DEFAULT_STREAM_ADDRESS
            , settingsReadOnly.enablePin
        )

    fun configureStartStopAddress(): String =
        if (settingsReadOnly.enablePin) "/" + randomString(16)
        else HttpServerFiles.DEFAULT_START_STOP_ADDRESS

    fun configureIndexHtml(streamAddress: String, startStopAddress: String): String =
        baseIndexHtml
            .replaceFirst(
                INDEX_HTML_BACKGROUND_COLOR.toRegex(),
                "#%06X".format(0xFFFFFF and settingsReadOnly.htmlBackColor)
            )
            .replaceFirst(INDEX_HTML_SCREEN_STREAM_ADDRESS.toRegex(), streamAddress)
            .replaceFirst(INDEX_HTML_START_STOP_ADDRESS.toRegex(), startStopAddress)

    fun configurePinAddress(): String =
        if (settingsReadOnly.enablePin) DEFAULT_PIN_ADDRESS + settingsReadOnly.pin
        else DEFAULT_PIN_ADDRESS

    fun configurePinRequestHtmlPage(): String =
        if (settingsReadOnly.enablePin)
            basePinRequestHtml.replaceFirst(PINREQUEST_HTML_WRONG_PIN_MESSAGE.toRegex(), "&nbsp")
        else
            ""

    fun configurePinRequestErrorHtmlPage(): String =
        if (settingsReadOnly.enablePin)
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