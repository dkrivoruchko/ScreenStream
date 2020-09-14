package info.dvkr.screenstream.data.settings

import android.content.SharedPreferences
import android.os.Build
import com.ironz.binaryprefs.Preferences
import com.ironz.binaryprefs.PreferencesEditor
import java.util.*
import kotlin.properties.ReadWriteProperty
import kotlin.random.Random
import kotlin.reflect.KProperty


class SettingsImpl(private val preferences: Preferences) : Settings {

    override var nightMode: Int
            by bindPreference(preferences, Settings.Key.NIGHT_MODE, Settings.Default.NIGHT_MODE)

    override var stopOnSleep: Boolean
            by bindPreference(preferences, Settings.Key.STOP_ON_SLEEP, Settings.Default.STOP_ON_SLEEP)

    override var startOnBoot: Boolean
            by bindPreference(preferences, Settings.Key.START_ON_BOOT, Settings.Default.START_ON_BOOT)

    override var autoStartStop: Boolean
            by bindPreference(preferences, Settings.Key.AUTO_START_STOP, Settings.Default.AUTO_START_STOP)

    override var notifySlowConnections: Boolean
            by bindPreference(preferences, Settings.Key.NOTIFY_SLOW_CONNECTIONS, Settings.Default.NOTIFY_SLOW_CONNECTIONS)


    override var htmlEnableButtons: Boolean
            by bindPreference(preferences, Settings.Key.HTML_ENABLE_BUTTONS, Settings.Default.HTML_ENABLE_BUTTONS)

    override var htmlBackColor: Int
            by bindPreference(preferences, Settings.Key.HTML_BACK_COLOR, Settings.Default.HTML_BACK_COLOR)


    override var vrMode: Int
            by bindPreference(preferences, Settings.Key.VR_MODE, Settings.Default.VR_MODE_DISABLE)

    override var imageCrop: Boolean
            by bindPreference(preferences, Settings.Key.IMAGE_CROP, Settings.Default.IMAGE_CROP)

    override var imageCropTop: Int
            by bindPreference(preferences, Settings.Key.IMAGE_CROP_TOP, Settings.Default.IMAGE_CROP_TOP)

    override var imageCropBottom: Int
            by bindPreference(preferences, Settings.Key.IMAGE_CROP_BOTTOM, Settings.Default.IMAGE_CROP_BOTTOM)

    override var imageCropLeft: Int
            by bindPreference(preferences, Settings.Key.IMAGE_CROP_LEFT, Settings.Default.IMAGE_CROP_LEFT)

    override var imageCropRight: Int
            by bindPreference(preferences, Settings.Key.IMAGE_CROP_RIGHT, Settings.Default.IMAGE_CROP_RIGHT)

    override var jpegQuality: Int
            by bindPreference(preferences, Settings.Key.JPEG_QUALITY, Settings.Default.JPEG_QUALITY)

    override var resizeFactor: Int
            by bindPreference(preferences, Settings.Key.RESIZE_FACTOR, Settings.Default.RESIZE_FACTOR)

    override var rotation: Int
            by bindPreference(preferences, Settings.Key.ROTATION, Settings.Default.ROTATION)

    override var maxFPS: Int
            by bindPreference(preferences, Settings.Key.MAX_FPS, Settings.Default.MAX_FPS)


    override var enablePin: Boolean
            by bindPreference(preferences, Settings.Key.ENABLE_PIN, Settings.Default.ENABLE_PIN)

    override var hidePinOnStart: Boolean
            by bindPreference(preferences, Settings.Key.HIDE_PIN_ON_START, Settings.Default.HIDE_PIN_ON_START)

    override var newPinOnAppStart: Boolean
            by bindPreference(preferences, Settings.Key.NEW_PIN_ON_APP_START, Settings.Default.NEW_PIN_ON_APP_START)

    override var autoChangePin: Boolean
            by bindPreference(preferences, Settings.Key.AUTO_CHANGE_PIN, Settings.Default.AUTO_CHANGE_PIN)

    override var pin: String
            by bindPreference(preferences, Settings.Key.PIN, Settings.Default.PIN)

    override var useWiFiOnly: Boolean
            by bindPreference(preferences, Settings.Key.USE_WIFI_ONLY, Settings.Default.USE_WIFI_ONLY)

    override var enableIPv6: Boolean
            by bindPreference(preferences, Settings.Key.ENABLE_IPV6, Settings.Default.ENABLE_IPV6)

    override var enableLocalHost: Boolean
            by bindPreference(preferences, Settings.Key.ENABLE_LOCAL_HOST, Settings.Default.ENABLE_LOCAL_HOST)

    override var localHostOnly: Boolean
            by bindPreference(preferences, Settings.Key.LOCAL_HOST_ONLY, Settings.Default.LOCAL_HOST_ONLY)

    override var severPort: Int
            by bindPreference(preferences, Settings.Key.SERVER_PORT, Settings.Default.SERVER_PORT)

    override var loggingVisible: Boolean
            by bindPreference(preferences, Settings.Key.LOGGING_VISIBLE, Settings.Default.LOGGING_VISIBLE)

    override var loggingOn: Boolean
            by bindPreference(preferences, Settings.Key.LOGGING_ON, Settings.Default.LOGGING_ON)


    override var lastIAURequestTimeStamp: Long
            by bindPreference(preferences, Settings.Key.LAST_IAU_REQUEST_TIMESTAMP, Settings.Default.LAST_IAU_REQUEST_TIMESTAMP)

    override fun autoChangePinOnStart() {
        if (enablePin && newPinOnAppStart) pin = randomPin()
    }

    override fun checkAndChangeAutoChangePinOnStop(): Boolean =
        if (enablePin && autoChangePin) {
            pin = randomPin(); true
        } else {
            false
        }

    init {
        // Update from 28 to 29
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && nightMode == 3) nightMode = -1
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && nightMode == -1) nightMode = 3
    }

    private val changeListenerSet = Collections.synchronizedSet(HashSet<SettingsReadOnly.OnSettingsChangeListener>())

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        synchronized(changeListenerSet) {
            changeListenerSet.forEach { listener -> listener.onSettingsChanged(key) }
        }
    }

    override fun registerChangeListener(listener: SettingsReadOnly.OnSettingsChangeListener) {
        synchronized(changeListenerSet) {
            if (changeListenerSet.isEmpty()) {
                changeListenerSet.add(listener)
                preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
            } else {
                changeListenerSet.add(listener)
            }
        }
    }

    override fun unregisterChangeListener(listener: SettingsReadOnly.OnSettingsChangeListener) {
        synchronized(changeListenerSet) {
            changeListenerSet.remove(listener)

            if (changeListenerSet.isEmpty()) {
                preferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
            }
        }
    }

    private fun randomPin(): String = Random.nextInt(10).toString() + Random.nextInt(10).toString() +
            Random.nextInt(10).toString() + Random.nextInt(10).toString()

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