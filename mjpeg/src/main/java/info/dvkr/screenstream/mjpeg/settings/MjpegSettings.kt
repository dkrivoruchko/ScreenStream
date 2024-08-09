package info.dvkr.screenstream.mjpeg.settings

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.StateFlow


public interface MjpegSettings {

    public object Key {
        public val KEEP_AWAKE: Preferences.Key<Boolean> = booleanPreferencesKey("KEEP_AWAKE")
        public val STOP_ON_SLEEP: Preferences.Key<Boolean> = booleanPreferencesKey("STOP_ON_SLEEP")
        public val STOP_ON_CONFIGURATION_CHANGE: Preferences.Key<Boolean> = booleanPreferencesKey("STOP_ON_CONFIGURATION_CHANGE")
        public val NOTIFY_SLOW_CONNECTIONS: Preferences.Key<Boolean> = booleanPreferencesKey("NOTIFY_SLOW_CONNECTIONS")

        public val HTML_ENABLE_BUTTONS: Preferences.Key<Boolean> = booleanPreferencesKey("HTML_ENABLE_BUTTONS")
        public val HTML_SHOW_PRESS_START: Preferences.Key<Boolean> = booleanPreferencesKey("HTML_SHOW_PRESS_START")
        public val HTML_BACK_COLOR: Preferences.Key<Int> = intPreferencesKey("HTML_BACK_COLOR")
        public val HTML_FIT_WINDOW: Preferences.Key<Boolean> = booleanPreferencesKey("HTML_FIT_WINDOW")

        public val VR_MODE: Preferences.Key<Int> = intPreferencesKey("VR_MODE")
        public val IMAGE_CROP: Preferences.Key<Boolean> = booleanPreferencesKey("IMAGE_CROP")
        public val IMAGE_CROP_TOP: Preferences.Key<Int> = intPreferencesKey("IMAGE_CROP_TOP")
        public val IMAGE_CROP_BOTTOM: Preferences.Key<Int> = intPreferencesKey("IMAGE_CROP_BOTTOM")
        public val IMAGE_CROP_LEFT: Preferences.Key<Int> = intPreferencesKey("IMAGE_CROP_LEFT")
        public val IMAGE_CROP_RIGHT: Preferences.Key<Int> = intPreferencesKey("IMAGE_CROP_RIGHT")
        public val IMAGE_GRAYSCALE: Preferences.Key<Boolean> = booleanPreferencesKey("IMAGE_GRAYSCALE")
        public val JPEG_QUALITY: Preferences.Key<Int> = intPreferencesKey("JPEG_QUALITY")
        public val RESIZE_FACTOR: Preferences.Key<Int> = intPreferencesKey("RESIZE_FACTOR")
        public val ROTATION: Preferences.Key<Int> = intPreferencesKey("ROTATION")
        public val MAX_FPS: Preferences.Key<Int> = intPreferencesKey("MAX_FPS")

        public val ENABLE_PIN: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_PIN")
        public val HIDE_PIN_ON_START: Preferences.Key<Boolean> = booleanPreferencesKey("HIDE_PIN_ON_START")
        public val NEW_PIN_ON_APP_START: Preferences.Key<Boolean> = booleanPreferencesKey("NEW_PIN_ON_APP_START")
        public val AUTO_CHANGE_PIN: Preferences.Key<Boolean> = booleanPreferencesKey("AUTO_CHANGE_PIN")
        public val PIN: Preferences.Key<String> = stringPreferencesKey("PIN")
        public val BLOCK_ADDRESS: Preferences.Key<Boolean> = booleanPreferencesKey("BLOCK_ADDRESS")

        public val USE_WIFI_ONLY: Preferences.Key<Boolean> = booleanPreferencesKey("USE_WIFI_ONLY")
        public val ENABLE_IPV6: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_IPV6")
        public val ENABLE_LOCAL_HOST: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_LOCAL_HOST")
        public val LOCAL_HOST_ONLY: Preferences.Key<Boolean> = booleanPreferencesKey("LOCAL_HOST_ONLY")
        public val SERVER_PORT: Preferences.Key<Int> = intPreferencesKey("SERVER_PORT")
    }

    public object Default {
        public const val KEEP_AWAKE: Boolean = true
        public const val STOP_ON_SLEEP: Boolean = false
        public const val STOP_ON_CONFIGURATION_CHANGE: Boolean = false
        public const val NOTIFY_SLOW_CONNECTIONS: Boolean = false

        public const val HTML_ENABLE_BUTTONS: Boolean = false
        public const val HTML_SHOW_PRESS_START: Boolean = true
        public const val HTML_BACK_COLOR: Int = -15723496// "FF101418".toLong(radix = 16).toInt()
        public const val HTML_FIT_WINDOW: Boolean = true

        public const val VR_MODE_DISABLE: Int = 0
        public const val VR_MODE_LEFT: Int = 1
        public const val VR_MODE_RIGHT: Int = 2
        public const val IMAGE_CROP: Boolean = false
        public const val IMAGE_CROP_TOP: Int = 0
        public const val IMAGE_CROP_BOTTOM: Int = 0
        public const val IMAGE_CROP_LEFT: Int = 0
        public const val IMAGE_CROP_RIGHT: Int = 0
        public const val IMAGE_GRAYSCALE: Boolean = false
        public const val JPEG_QUALITY: Int = 80
        public const val RESIZE_FACTOR: Int = 50
        public const val ROTATION: Int = Values.ROTATION_0
        public const val MAX_FPS: Int = 30

        public const val ENABLE_PIN: Boolean = false
        public const val HIDE_PIN_ON_START: Boolean = true
        public const val NEW_PIN_ON_APP_START: Boolean = true
        public const val AUTO_CHANGE_PIN: Boolean = false
        public const val PIN: String = "000000"
        public const val BLOCK_ADDRESS: Boolean = true

        public const val USE_WIFI_ONLY: Boolean = true
        public const val ENABLE_IPV6: Boolean = false
        public const val ENABLE_LOCAL_HOST: Boolean = false
        public const val LOCAL_HOST_ONLY: Boolean = false
        public const val SERVER_PORT: Int = 8080
    }

    public object Values {
        public const val RESIZE_DISABLED: Int = 100

        public const val ROTATION_0: Int = 0
        public const val ROTATION_90: Int = 90
        public const val ROTATION_180: Int = 180
        public const val ROTATION_270: Int = 270
    }

    @Immutable
    public data class Data(
        public val keepAwake: Boolean = Default.KEEP_AWAKE,
        public val stopOnSleep: Boolean = Default.STOP_ON_SLEEP,
        public val stopOnConfigurationChange: Boolean = Default.STOP_ON_CONFIGURATION_CHANGE,
        public val notifySlowConnections: Boolean = Default.NOTIFY_SLOW_CONNECTIONS,
        public val htmlEnableButtons: Boolean = Default.HTML_ENABLE_BUTTONS,
        public val htmlShowPressStart: Boolean = Default.HTML_SHOW_PRESS_START,
        public val htmlBackColor: Int = Default.HTML_BACK_COLOR,
        public val htmlFitWindow: Boolean = Default.HTML_FIT_WINDOW,

        public val vrMode: Int = Default.VR_MODE_DISABLE,
        public val imageCrop: Boolean = Default.IMAGE_CROP,
        public val imageCropTop: Int = Default.IMAGE_CROP_TOP,
        public val imageCropBottom: Int = Default.IMAGE_CROP_BOTTOM,
        public val imageCropLeft: Int = Default.IMAGE_CROP_LEFT,
        public val imageCropRight: Int = Default.IMAGE_CROP_RIGHT,
        public val imageGrayscale: Boolean = Default.IMAGE_GRAYSCALE,
        public val jpegQuality: Int = Default.JPEG_QUALITY,
        public val resizeFactor: Int = Default.RESIZE_FACTOR,
        public val rotation: Int = Default.ROTATION,
        public val maxFPS: Int = Default.MAX_FPS,

        public val enablePin: Boolean = Default.ENABLE_PIN,
        public val hidePinOnStart: Boolean = Default.HIDE_PIN_ON_START,
        public val newPinOnAppStart: Boolean = Default.NEW_PIN_ON_APP_START,
        public val autoChangePin: Boolean = Default.AUTO_CHANGE_PIN,
        public val pin: String = Default.PIN,
        public val blockAddress: Boolean = Default.BLOCK_ADDRESS,

        public val useWiFiOnly: Boolean = Default.USE_WIFI_ONLY,
        public val enableIPv6: Boolean = Default.ENABLE_IPV6,
        public val enableLocalHost: Boolean = Default.ENABLE_LOCAL_HOST,
        public val localHostOnly: Boolean = Default.LOCAL_HOST_ONLY,
        public val serverPort: Int = Default.SERVER_PORT,
    )

    public val data: StateFlow<Data>
    public suspend fun updateData(transform: Data.() -> Data)
}