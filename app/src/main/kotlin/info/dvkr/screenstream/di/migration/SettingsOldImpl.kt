package info.dvkr.screenstream.di.migration

import com.ironz.binaryprefs.Preferences
import com.ironz.binaryprefs.PreferencesEditor
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty


class SettingsOldImpl(preferences: Preferences) : SettingsOld {

    override var nightMode: Int
            by bindPreference(preferences, SettingsOld.Key.NIGHT_MODE, SettingsOld.Default.NIGHT_MODE)

    override var keepAwake: Boolean
            by bindPreference(preferences, SettingsOld.Key.KEEP_AWAKE, SettingsOld.Default.KEEP_AWAKE)

    override var stopOnSleep: Boolean
            by bindPreference(preferences, SettingsOld.Key.STOP_ON_SLEEP, SettingsOld.Default.STOP_ON_SLEEP)

    override var startOnBoot: Boolean
            by bindPreference(preferences, SettingsOld.Key.START_ON_BOOT, SettingsOld.Default.START_ON_BOOT)

    override var autoStartStop: Boolean
            by bindPreference(preferences, SettingsOld.Key.AUTO_START_STOP, SettingsOld.Default.AUTO_START_STOP)

    override var notifySlowConnections: Boolean
            by bindPreference(preferences, SettingsOld.Key.NOTIFY_SLOW_CONNECTIONS, SettingsOld.Default.NOTIFY_SLOW_CONNECTIONS)


    override var htmlEnableButtons: Boolean
            by bindPreference(preferences, SettingsOld.Key.HTML_ENABLE_BUTTONS, SettingsOld.Default.HTML_ENABLE_BUTTONS)

    override var htmlShowPressStart: Boolean
            by bindPreference(preferences, SettingsOld.Key.HTML_SHOW_PRESS_START, SettingsOld.Default.HTML_SHOW_PRESS_START)

    override var htmlBackColor: Int
            by bindPreference(preferences, SettingsOld.Key.HTML_BACK_COLOR, SettingsOld.Default.HTML_BACK_COLOR)


    override var vrMode: Int
            by bindPreference(preferences, SettingsOld.Key.VR_MODE, SettingsOld.Default.VR_MODE_DISABLE)

    override var imageCrop: Boolean
            by bindPreference(preferences, SettingsOld.Key.IMAGE_CROP, SettingsOld.Default.IMAGE_CROP)

    override var imageCropTop: Int
            by bindPreference(preferences, SettingsOld.Key.IMAGE_CROP_TOP, SettingsOld.Default.IMAGE_CROP_TOP)

    override var imageCropBottom: Int
            by bindPreference(preferences, SettingsOld.Key.IMAGE_CROP_BOTTOM, SettingsOld.Default.IMAGE_CROP_BOTTOM)

    override var imageCropLeft: Int
            by bindPreference(preferences, SettingsOld.Key.IMAGE_CROP_LEFT, SettingsOld.Default.IMAGE_CROP_LEFT)

    override var imageCropRight: Int
            by bindPreference(preferences, SettingsOld.Key.IMAGE_CROP_RIGHT, SettingsOld.Default.IMAGE_CROP_RIGHT)

    override var imageGrayscale: Boolean
            by bindPreference(preferences, SettingsOld.Key.IMAGE_GRAYSCALE, SettingsOld.Default.IMAGE_GRAYSCALE)

    override var jpegQuality: Int
            by bindPreference(preferences, SettingsOld.Key.JPEG_QUALITY, SettingsOld.Default.JPEG_QUALITY)

    override var resizeFactor: Int
            by bindPreference(preferences, SettingsOld.Key.RESIZE_FACTOR, SettingsOld.Default.RESIZE_FACTOR)

    override var rotation: Int
            by bindPreference(preferences, SettingsOld.Key.ROTATION, SettingsOld.Default.ROTATION)

    override var maxFPS: Int
            by bindPreference(preferences, SettingsOld.Key.MAX_FPS, SettingsOld.Default.MAX_FPS)


    override var enablePin: Boolean
            by bindPreference(preferences, SettingsOld.Key.ENABLE_PIN, SettingsOld.Default.ENABLE_PIN)

    override var hidePinOnStart: Boolean
            by bindPreference(preferences, SettingsOld.Key.HIDE_PIN_ON_START, SettingsOld.Default.HIDE_PIN_ON_START)

    override var newPinOnAppStart: Boolean
            by bindPreference(preferences, SettingsOld.Key.NEW_PIN_ON_APP_START, SettingsOld.Default.NEW_PIN_ON_APP_START)

    override var autoChangePin: Boolean
            by bindPreference(preferences, SettingsOld.Key.AUTO_CHANGE_PIN, SettingsOld.Default.AUTO_CHANGE_PIN)

    override var pin: String
            by bindPreference(preferences, SettingsOld.Key.PIN, SettingsOld.Default.PIN)

    override var blockAddress: Boolean
            by bindPreference(preferences, SettingsOld.Key.BLOCK_ADDRESS, SettingsOld.Default.BLOCK_ADDRESS)


    override var useWiFiOnly: Boolean
            by bindPreference(preferences, SettingsOld.Key.USE_WIFI_ONLY, SettingsOld.Default.USE_WIFI_ONLY)

    override var enableIPv6: Boolean
            by bindPreference(preferences, SettingsOld.Key.ENABLE_IPV6, SettingsOld.Default.ENABLE_IPV6)

    override var enableLocalHost: Boolean
            by bindPreference(preferences, SettingsOld.Key.ENABLE_LOCAL_HOST, SettingsOld.Default.ENABLE_LOCAL_HOST)

    override var localHostOnly: Boolean
            by bindPreference(preferences, SettingsOld.Key.LOCAL_HOST_ONLY, SettingsOld.Default.LOCAL_HOST_ONLY)

    override var severPort: Int
            by bindPreference(preferences, SettingsOld.Key.SERVER_PORT, SettingsOld.Default.SERVER_PORT)

    override var loggingVisible: Boolean
            by bindPreference(preferences, SettingsOld.Key.LOGGING_VISIBLE, SettingsOld.Default.LOGGING_VISIBLE)

    override var loggingOn: Boolean
            by bindPreference(preferences, SettingsOld.Key.LOGGING_ON, SettingsOld.Default.LOGGING_ON)


    override var lastIAURequestTimeStamp: Long
            by bindPreference(preferences, SettingsOld.Key.LAST_IAU_REQUEST_TIMESTAMP, SettingsOld.Default.LAST_IAU_REQUEST_TIMESTAMP)

    private class PreferenceDelegate<T>(
        private val preferences: Preferences,
        private val key: String,
        private val defaultValue: T,
        private val getter: Preferences.(String, T) -> T,
        private val setter: PreferencesEditor.(String, T) -> PreferencesEditor
    ) : ReadWriteProperty<Any, T> {
        override fun getValue(thisRef: Any, property: KProperty<*>): T =
            preferences.getter(key, defaultValue)

        override fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
            preferences.edit().setter(key, value).commit()
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T> bindPreference(preferences: Preferences, key: String, defaultValue: T): ReadWriteProperty<Any, T> =
        when (defaultValue) {
            is Boolean -> PreferenceDelegate(
                preferences, key, defaultValue, Preferences::getBoolean, PreferencesEditor::putBoolean
            )
            is Int -> PreferenceDelegate(
                preferences, key, defaultValue, Preferences::getInt, PreferencesEditor::putInt
            )
            is Long -> PreferenceDelegate(
                preferences, key, defaultValue, Preferences::getLong, PreferencesEditor::putLong
            )
            is Float -> PreferenceDelegate(
                preferences, key, defaultValue, Preferences::getFloat, PreferencesEditor::putFloat
            )
            is String -> PreferenceDelegate(
                preferences, key, defaultValue, Preferences::getString, PreferencesEditor::putString
            )
            else -> throw IllegalArgumentException("Unsupported preference type")
        } as ReadWriteProperty<Any, T>
}