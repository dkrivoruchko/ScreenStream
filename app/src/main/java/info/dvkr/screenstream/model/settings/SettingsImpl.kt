package info.dvkr.screenstream.model.settings

import android.util.Log
import com.f2prateek.rx.preferences.RxSharedPreferences
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.model.Settings

class SettingsImpl(private val mRxSharedPreferences: RxSharedPreferences) : Settings {
    private val TAG = "SettingsImpl"

    companion object {
        private val PREF_KEY_MINIMIZE_ON_STREAM = "PREF_KEY_MINIMIZE_ON_STREAM" // Boolean
        private val DEFAULT_MINIMIZE_ON_STREAM = true
        private val PREF_KEY_STOP_ON_SLEEP = "PREF_KEY_STOP_ON_SLEEP" // Boolean
        private val DEFAULT_STOP_ON_SLEEP = false
        private val PREF_KEY_START_ON_BOOT = "PREF_KEY_START_ON_BOOT" // Boolean
        private val DEFAULT_START_ON_BOOT = false
        private val PREF_KEY_MJPEG_CHECK = "PREF_KEY_MJPEG_CHECK" // Boolean
        private val DEFAULT_MJPEG_CHECK = false
        private val PREF_KEY_HTML_BACK_COLOR = "PREF_KEY_HTML_BACK_COLOR" // Int
        private val DEFAULT_HTML_BACK_COLOR = java.lang.Long.parseLong("ff000000", 16).toInt()

        private val PREF_KEY_RESIZE_FACTOR = "PREF_KEY_RESIZE_FACTOR" // Int
        private val DEFAULT_RESIZE_FACTOR = 50
        private val PREF_KEY_JPEG_QUALITY = "PREF_KEY_JPEG_QUALITY" // Int
        private val DEFAULT_JPEG_QUALITY = 80

        private val PREF_KEY_ENABLE_PIN = "PREF_KEY_ENABLE_PIN" // Boolean
        private val DEFAULT_ENABLE_PIN = false
        private val PREF_KEY_HIDE_PIN_ON_START = "PREF_KEY_HIDE_PIN_ON_START" // Boolean
        private val DEFAULT_HIDE_PIN_ON_START = true
        private val PREF_KEY_NEW_PIN_ON_APP_START = "PREF_KEY_NEW_PIN_ON_APP_START" // Boolean
        private val DEFAULT_NEW_PIN_ON_APP_START = true
        private val PREF_KEY_AUTO_CHANGE_PIN = "PREF_KEY_AUTO_CHANGE_PIN" // Boolean
        private val DEFAULT_AUTO_CHANGE_PIN = false
        private val PREF_KEY_SET_PIN = "PREF_KEY_SET_PIN" // String
        private val DEFAULT_PIN = "0000"

        private val PREF_KEY_USE_WIFI_ONLY = "PREF_KEY_USE_WIFI_ONLY" // Boolean
        private val DEFAULT_USE_WIFI_ONLY = true
        private val PREF_KEY_SERVER_PORT = "PREF_KEY_SERVER_PORT" // Int
        private val DEFAULT_SERVER_PORT = 8080
    }

    init {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] Constructor")
    }

    override var minimizeOnStream: Boolean
        get() = getBoolean(PREF_KEY_MINIMIZE_ON_STREAM, DEFAULT_MINIMIZE_ON_STREAM)
        set(minimizeOnStream) = mRxSharedPreferences.getBoolean(PREF_KEY_MINIMIZE_ON_STREAM).set(minimizeOnStream)

    override var stopOnSleep: Boolean
        get() = getBoolean(PREF_KEY_STOP_ON_SLEEP, DEFAULT_STOP_ON_SLEEP)
        set(stopOnSleep) = mRxSharedPreferences.getBoolean(PREF_KEY_STOP_ON_SLEEP).set(stopOnSleep)

    override var startOnBoot: Boolean
        get() = getBoolean(PREF_KEY_START_ON_BOOT, DEFAULT_START_ON_BOOT)
        set(startOnBoot) = mRxSharedPreferences.getBoolean(PREF_KEY_START_ON_BOOT).set(startOnBoot)

    override var disableMJPEGCheck: Boolean
        get() = getBoolean(PREF_KEY_MJPEG_CHECK, DEFAULT_MJPEG_CHECK)
        set(disableMJPEGCheck) = mRxSharedPreferences.getBoolean(PREF_KEY_MJPEG_CHECK).set(disableMJPEGCheck)

    override var htmlBackColor: Int
        get() = getInteger(PREF_KEY_HTML_BACK_COLOR, DEFAULT_HTML_BACK_COLOR)
        set(HTMLBackColor) = mRxSharedPreferences.getInteger(PREF_KEY_HTML_BACK_COLOR).set(HTMLBackColor)

    override var jpegQuality: Int
        get() = getInteger(PREF_KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY)
        set(jpegQuality) = mRxSharedPreferences.getInteger(PREF_KEY_JPEG_QUALITY).set(jpegQuality)

    override var resizeFactor: Int
        get() = getInteger(PREF_KEY_RESIZE_FACTOR, DEFAULT_RESIZE_FACTOR)
        set(resizeFactor) = mRxSharedPreferences.getInteger(PREF_KEY_RESIZE_FACTOR).set(resizeFactor)

    override var enablePin: Boolean
        get() = getBoolean(PREF_KEY_ENABLE_PIN, DEFAULT_ENABLE_PIN)
        set(enablePin) = mRxSharedPreferences.getBoolean(PREF_KEY_ENABLE_PIN).set(enablePin)

    override var hidePinOnStart: Boolean
        get() = getBoolean(PREF_KEY_HIDE_PIN_ON_START, DEFAULT_HIDE_PIN_ON_START)
        set(hidePinOnStart) = mRxSharedPreferences.getBoolean(PREF_KEY_HIDE_PIN_ON_START).set(hidePinOnStart)

    override var newPinOnAppStart: Boolean
        get() = getBoolean(PREF_KEY_NEW_PIN_ON_APP_START, DEFAULT_NEW_PIN_ON_APP_START)
        set(newPinOnAppStart) = mRxSharedPreferences.getBoolean(PREF_KEY_NEW_PIN_ON_APP_START).set(newPinOnAppStart)

    override var autoChangePin: Boolean
        get() = getBoolean(PREF_KEY_AUTO_CHANGE_PIN, DEFAULT_AUTO_CHANGE_PIN)
        set(autoChangePin) = mRxSharedPreferences.getBoolean(PREF_KEY_AUTO_CHANGE_PIN).set(autoChangePin)

    override var currentPin: String
        get() = getString(PREF_KEY_SET_PIN, DEFAULT_PIN)
        set(currentPin) = mRxSharedPreferences.getString(PREF_KEY_SET_PIN).set(currentPin)

    override var useWiFiOnly: Boolean
        get() = getBoolean(PREF_KEY_USE_WIFI_ONLY, DEFAULT_USE_WIFI_ONLY)
        set(runWiFiOnly) = mRxSharedPreferences.getBoolean(PREF_KEY_USE_WIFI_ONLY).set(runWiFiOnly)

    override var severPort: Int
        get() = getInteger(PREF_KEY_SERVER_PORT, DEFAULT_SERVER_PORT)
        set(severPort) = mRxSharedPreferences.getInteger(PREF_KEY_SERVER_PORT).set(severPort)

    // Helper methods
    private fun getBoolean(pref_key: String, pref_default: Boolean): Boolean =
            mRxSharedPreferences.getBoolean(pref_key, pref_default).get() ?: pref_default

    private fun getInteger(pref_key: String, pref_default: Int): Int =
            mRxSharedPreferences.getInteger(pref_key, pref_default).get() ?: pref_default

    private fun getString(pref_key: String, pref_default: String): String =
            mRxSharedPreferences.getString(pref_key, pref_default).get() ?: pref_default
}