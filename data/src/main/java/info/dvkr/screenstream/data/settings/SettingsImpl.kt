package info.dvkr.screenstream.data.settings

import com.ironz.binaryprefs.Preferences
import info.dvkr.screenstream.domain.settings.Settings
import timber.log.Timber

class SettingsImpl(private val preferences: Preferences) : Settings {

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
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] Init")
    }

    override var minimizeOnStream: Boolean
        get() = preferences.getBoolean(PREF_KEY_MINIMIZE_ON_STREAM, DEFAULT_MINIMIZE_ON_STREAM)
        set(minimizeOnStream) = setBoolean(PREF_KEY_MINIMIZE_ON_STREAM, minimizeOnStream)

    override var stopOnSleep: Boolean
        get() = preferences.getBoolean(PREF_KEY_STOP_ON_SLEEP, DEFAULT_STOP_ON_SLEEP)
        set(stopOnSleep) = setBoolean(PREF_KEY_STOP_ON_SLEEP, stopOnSleep)

    override var startOnBoot: Boolean
        get() = preferences.getBoolean(PREF_KEY_START_ON_BOOT, DEFAULT_START_ON_BOOT)
        set(startOnBoot) = setBoolean(PREF_KEY_START_ON_BOOT, startOnBoot)

    override var disableMJPEGCheck: Boolean
        get() = preferences.getBoolean(PREF_KEY_MJPEG_CHECK, DEFAULT_MJPEG_CHECK)
        set(disableMJPEGCheck) = setBoolean(PREF_KEY_MJPEG_CHECK, disableMJPEGCheck)

    override var htmlBackColor: Int
        get() = preferences.getInt(PREF_KEY_HTML_BACK_COLOR, DEFAULT_HTML_BACK_COLOR)
        set(HTMLBackColor) = setInteger(PREF_KEY_HTML_BACK_COLOR, HTMLBackColor)

    override var jpegQuality: Int
        get() = preferences.getInt(PREF_KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY)
        set(jpegQuality) = setInteger(PREF_KEY_JPEG_QUALITY, jpegQuality)

    override var resizeFactor: Int
        get() = preferences.getInt(PREF_KEY_RESIZE_FACTOR, DEFAULT_RESIZE_FACTOR)
        set(resizeFactor) = setInteger(PREF_KEY_RESIZE_FACTOR, resizeFactor)

    override var enablePin: Boolean
        get() = preferences.getBoolean(PREF_KEY_ENABLE_PIN, DEFAULT_ENABLE_PIN)
        set(enablePin) = setBoolean(PREF_KEY_ENABLE_PIN, enablePin)

    override var hidePinOnStart: Boolean
        get() = preferences.getBoolean(PREF_KEY_HIDE_PIN_ON_START, DEFAULT_HIDE_PIN_ON_START)
        set(hidePinOnStart) = setBoolean(PREF_KEY_HIDE_PIN_ON_START, hidePinOnStart)

    override var newPinOnAppStart: Boolean
        get() = preferences.getBoolean(PREF_KEY_NEW_PIN_ON_APP_START, DEFAULT_NEW_PIN_ON_APP_START)
        set(newPinOnAppStart) = setBoolean(PREF_KEY_NEW_PIN_ON_APP_START, newPinOnAppStart)

    override var autoChangePin: Boolean
        get() = preferences.getBoolean(PREF_KEY_AUTO_CHANGE_PIN, DEFAULT_AUTO_CHANGE_PIN)
        set(autoChangePin) = setBoolean(PREF_KEY_AUTO_CHANGE_PIN, autoChangePin)

    override var currentPin: String
        get() = preferences.getString(PREF_KEY_SET_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        set(currentPin) = setString(PREF_KEY_SET_PIN, currentPin)

    override var useWiFiOnly: Boolean
        get() = preferences.getBoolean(PREF_KEY_USE_WIFI_ONLY, DEFAULT_USE_WIFI_ONLY)
        set(runWiFiOnly) = setBoolean(PREF_KEY_USE_WIFI_ONLY, runWiFiOnly)

    override var severPort: Int
        get() = preferences.getInt(PREF_KEY_SERVER_PORT, DEFAULT_SERVER_PORT)
        set(severPort) = setInteger(PREF_KEY_SERVER_PORT, severPort)

    // Helper methods
    private fun setBoolean(pref_key: String, pref_new_value: Boolean) {
        preferences.edit().putBoolean(pref_key, pref_new_value).commit()
    }

    private fun setInteger(pref_key: String, pref_new_value: Int) {
        preferences.edit().putInt(pref_key, pref_new_value).commit()
    }

    private fun setString(pref_key: String, pref_new_value: String) {
        preferences.edit().putString(pref_key, pref_new_value).commit()
    }
}