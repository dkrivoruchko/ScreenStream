package info.dvkr.screenstream.data.settings

import com.ironz.binaryprefs.Preferences
import com.ironz.binaryprefs.PreferencesEditor
import info.dvkr.screenstream.domain.settings.Settings
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class SettingsImpl(preferences: Preferences) : Settings {

    override var minimizeOnStream: Boolean by bindPreference(preferences, "PREF_KEY_MINIMIZE_ON_STREAM", true)

    override var stopOnSleep: Boolean by bindPreference(preferences, "PREF_KEY_STOP_ON_SLEEP", false)

    override var startOnBoot: Boolean by bindPreference(preferences, "PREF_KEY_START_ON_BOOT", false)

    override var disableMJPEGCheck: Boolean by bindPreference(preferences, "PREF_KEY_MJPEG_CHECK", false)

    override var htmlBackColor: Int by bindPreference(preferences, "PREF_KEY_HTML_BACK_COLOR", java.lang.Long.parseLong("ff000000", 16).toInt())

    override var jpegQuality: Int by bindPreference(preferences, "PREF_KEY_JPEG_QUALITY", 80)

    override var resizeFactor: Int by bindPreference(preferences, "PREF_KEY_RESIZE_FACTOR", 50)

    override var enablePin: Boolean by bindPreference(preferences, "PREF_KEY_ENABLE_PIN", false)

    override var hidePinOnStart: Boolean by bindPreference(preferences, "PREF_KEY_HIDE_PIN_ON_START", true)

    override var newPinOnAppStart: Boolean by bindPreference(preferences, "PREF_KEY_NEW_PIN_ON_APP_START", true)

    override var autoChangePin: Boolean by bindPreference(preferences, "PREF_KEY_AUTO_CHANGE_PIN", false)

    override var currentPin: String by bindPreference(preferences, "PREF_KEY_SET_PIN", "0000")

    override var useWiFiOnly: Boolean by bindPreference(preferences, "PREF_KEY_USE_WIFI_ONLY", true)

    override var severPort: Int by bindPreference(preferences, "PREF_KEY_SERVER_PORT", 8080)

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