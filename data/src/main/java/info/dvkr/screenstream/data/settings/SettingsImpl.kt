package info.dvkr.screenstream.data.settings

import com.ironz.binaryprefs.Preferences
import com.ironz.binaryprefs.PreferencesEditor
import info.dvkr.screenstream.domain.settings.Settings
import timber.log.Timber
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class SettingsImpl(preferences: Preferences) : Settings {

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

    override var minimizeOnStream: Boolean by bindPreference(preferences, PREF_KEY_MINIMIZE_ON_STREAM, DEFAULT_MINIMIZE_ON_STREAM)

    override var stopOnSleep: Boolean by bindPreference(preferences, PREF_KEY_STOP_ON_SLEEP, DEFAULT_STOP_ON_SLEEP)

    override var startOnBoot: Boolean by bindPreference(preferences, PREF_KEY_START_ON_BOOT, DEFAULT_START_ON_BOOT)

    override var disableMJPEGCheck: Boolean by bindPreference(preferences, PREF_KEY_MJPEG_CHECK, DEFAULT_MJPEG_CHECK)

    override var htmlBackColor: Int by bindPreference(preferences, PREF_KEY_HTML_BACK_COLOR, DEFAULT_HTML_BACK_COLOR)

    override var jpegQuality: Int by bindPreference(preferences, PREF_KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY)

    override var resizeFactor: Int by bindPreference(preferences, PREF_KEY_RESIZE_FACTOR, DEFAULT_RESIZE_FACTOR)

    override var enablePin: Boolean by bindPreference(preferences, PREF_KEY_ENABLE_PIN, DEFAULT_ENABLE_PIN)

    override var hidePinOnStart: Boolean by bindPreference(preferences, PREF_KEY_HIDE_PIN_ON_START, DEFAULT_HIDE_PIN_ON_START)

    override var newPinOnAppStart: Boolean by bindPreference(preferences, PREF_KEY_NEW_PIN_ON_APP_START, DEFAULT_NEW_PIN_ON_APP_START)

    override var autoChangePin: Boolean by bindPreference(preferences, PREF_KEY_AUTO_CHANGE_PIN, DEFAULT_AUTO_CHANGE_PIN)

    override var currentPin: String by bindPreference(preferences, PREF_KEY_SET_PIN, DEFAULT_PIN)

    override var useWiFiOnly: Boolean by bindPreference(preferences, PREF_KEY_USE_WIFI_ONLY, DEFAULT_USE_WIFI_ONLY)

    override var severPort: Int by bindPreference(preferences, PREF_KEY_SERVER_PORT, DEFAULT_SERVER_PORT)

    private class PreferenceDelegate<T>(
            private val preferences: Preferences,
            private val key: String,
            private val defaultValue: T,
            private val getter: Preferences.(String, T) -> T,
            private val setter: PreferencesEditor.(String, T) -> PreferencesEditor
    ) : ReadWriteProperty<Any, T> {
        override fun getValue(thisRef: Any, property: KProperty<*>) =
                preferences.getter(key, defaultValue)

        override fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
            preferences.edit().setter(key, value).commit()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> bindPreference(preferences: Preferences, key: String, defaultValue: T): ReadWriteProperty<Any, T> =
            when (defaultValue) {
                is Boolean -> PreferenceDelegate(preferences, key, defaultValue, Preferences::getBoolean, PreferencesEditor::putBoolean)
                is Int -> PreferenceDelegate(preferences, key, defaultValue, Preferences::getInt, PreferencesEditor::putInt)
                is Long -> PreferenceDelegate(preferences, key, defaultValue, Preferences::getLong, PreferencesEditor::putLong)
                is Float -> PreferenceDelegate(preferences, key, defaultValue, Preferences::getFloat, PreferencesEditor::putFloat)
                is String -> PreferenceDelegate(preferences, key, defaultValue, Preferences::getString, PreferencesEditor::putString)
                else -> throw IllegalArgumentException("Unsupported preference type")
            } as ReadWriteProperty<Any, T>
}