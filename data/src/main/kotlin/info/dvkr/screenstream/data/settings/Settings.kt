package info.dvkr.screenstream.data.settings

import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

interface Settings : SettingsReadOnly {

    object Key {
        val NIGHT_MODE = intPreferencesKey("NIGHT_MODE")
        val KEEP_AWAKE = booleanPreferencesKey("KEEP_AWAKE")
        val STOP_ON_SLEEP = booleanPreferencesKey("TOP_ON_SLEEP")
        val START_ON_BOOT = booleanPreferencesKey("START_ON_BOOT")
        val AUTO_START_STOP = booleanPreferencesKey("AUTO_START_STOP")
        val NOTIFY_SLOW_CONNECTIONS = booleanPreferencesKey("NOTIFY_SLOW_CONNECTIONS")

        val HTML_ENABLE_BUTTONS = booleanPreferencesKey("HTML_ENABLE_BUTTONS")
        val HTML_SHOW_PRESS_START = booleanPreferencesKey("HTML_SHOW_PRESS_START")
        val HTML_BACK_COLOR = intPreferencesKey("HTML_BACK_COLOR")

        val VR_MODE = intPreferencesKey("VR_MODE")
        val IMAGE_CROP = booleanPreferencesKey("IMAGE_CROP")
        val IMAGE_CROP_TOP = intPreferencesKey("IMAGE_CROP_TOP")
        val IMAGE_CROP_BOTTOM = intPreferencesKey("IMAGE_CROP_BOTTOM")
        val IMAGE_CROP_LEFT = intPreferencesKey("IMAGE_CROP_LEFT")
        val IMAGE_CROP_RIGHT = intPreferencesKey("IMAGE_CROP_RIGHT")
        val IMAGE_GRAYSCALE = booleanPreferencesKey("IMAGE_GRAYSCALE")
        val JPEG_QUALITY = intPreferencesKey("JPEG_QUALITY")
        val RESIZE_FACTOR = intPreferencesKey("RESIZE_FACTOR")
        val ROTATION = intPreferencesKey("ROTATION")
        val MAX_FPS = intPreferencesKey("MAX_FPS")

        val ENABLE_PIN = booleanPreferencesKey("ENABLE_PIN")
        val HIDE_PIN_ON_START = booleanPreferencesKey("HIDE_PIN_ON_START")
        val NEW_PIN_ON_APP_START = booleanPreferencesKey("NEW_PIN_ON_APP_START")
        val AUTO_CHANGE_PIN = booleanPreferencesKey("AUTO_CHANGE_PIN")
        val PIN = stringPreferencesKey("PIN")
        val BLOCK_ADDRESS = booleanPreferencesKey("BLOCK_ADDRESS")

        val USE_WIFI_ONLY = booleanPreferencesKey("USE_WIFI_ONLY")
        val ENABLE_IPV6 = booleanPreferencesKey("ENABLE_IPV6")
        val ENABLE_LOCAL_HOST = booleanPreferencesKey("ENABLE_LOCAL_HOST")
        val LOCAL_HOST_ONLY = booleanPreferencesKey("LOCAL_HOST_ONLY")
        val SERVER_PORT = intPreferencesKey("SERVER_PORT")
        val LOGGING_VISIBLE = booleanPreferencesKey("LOGGING_VISIBLE")

        val LAST_UPDATE_REQUEST_MILLIS = longPreferencesKey("LAST_UPDATE_REQUEST_MILLIS")
    }

    object Default {
        var NIGHT_MODE = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) 3 else -1
        const val KEEP_AWAKE = false
        const val STOP_ON_SLEEP = false
        const val START_ON_BOOT = false
        const val AUTO_START_STOP = false
        const val NOTIFY_SLOW_CONNECTIONS = true

        const val HTML_ENABLE_BUTTONS = false
        const val HTML_SHOW_PRESS_START = true
        const val HTML_BACK_COLOR = -16777216 // "ff000000".toLong(radix = 16).toInt()

        const val VR_MODE_DISABLE = 0
        const val VR_MODE_LEFT = 1
        const val VR_MODE_RIGHT = 2
        const val IMAGE_CROP = false
        const val IMAGE_CROP_TOP = 0
        const val IMAGE_CROP_BOTTOM = 0
        const val IMAGE_CROP_LEFT = 0
        const val IMAGE_CROP_RIGHT = 0
        const val IMAGE_GRAYSCALE = false
        const val JPEG_QUALITY = 80
        const val RESIZE_FACTOR = 50
        const val ROTATION = Values.ROTATION_0
        const val MAX_FPS = 30

        const val ENABLE_PIN = false
        const val HIDE_PIN_ON_START = true
        const val NEW_PIN_ON_APP_START = true
        const val AUTO_CHANGE_PIN = false
        const val PIN = "0000"
        const val BLOCK_ADDRESS = true

        const val USE_WIFI_ONLY = true
        const val ENABLE_IPV6 = false
        const val ENABLE_LOCAL_HOST = false
        const val LOCAL_HOST_ONLY = false
        const val SERVER_PORT = 8080
        const val LOGGING_VISIBLE = false

        const val LAST_IAU_REQUEST_TIMESTAMP = 0L
    }

    object Values {
        const val RESIZE_DISABLED = 100

        const val ROTATION_0 = 0
        const val ROTATION_90 = 90
        const val ROTATION_180 = 180
        const val ROTATION_270 = 270
    }

    suspend fun setNightMode(value: Int)
    suspend fun setKeepAwake(value: Boolean)
    suspend fun setStopOnSleep(value: Boolean)
    suspend fun setStartOnBoot(value: Boolean)
    suspend fun setAutoStartStop(value: Boolean)
    suspend fun setNotifySlowConnections(value: Boolean)
    suspend fun setHtmlEnableButtons(value: Boolean)
    suspend fun setHtmlShowPressStart(value: Boolean)
    suspend fun setHtmlBackColor(value: Int)

    suspend fun setVrMode(value: Int)
    suspend fun setImageCrop(value: Boolean)
    suspend fun setImageCropTop(value: Int)
    suspend fun setImageCropBottom(value: Int)
    suspend fun setImageCropLeft(value: Int)
    suspend fun setImageCropRight(value: Int)
    suspend fun setImageGrayscale(value: Boolean)
    suspend fun setJpegQuality(value: Int)
    suspend fun setResizeFactor(value: Int)
    suspend fun setRotation(value: Int)
    suspend fun setMaxFPS(value: Int)

    suspend fun setEnablePin(value: Boolean)
    suspend fun setHidePinOnStart(value: Boolean)
    suspend fun setNewPinOnAppStart(value: Boolean)
    suspend fun setAutoChangePin(value: Boolean)
    suspend fun setPin(value: String)
    suspend fun setBlockAddress(value: Boolean)

    suspend fun setUseWiFiOnly(value: Boolean)
    suspend fun setEnableIPv6(value: Boolean)
    suspend fun setEnableLocalHost(value: Boolean)
    suspend fun setLocalHostOnly(value: Boolean)
    suspend fun setServerPort(value: Int)

    suspend fun setLoggingVisible(value: Boolean)

    suspend fun setLastUpdateRequestMillis(value: Long)
}