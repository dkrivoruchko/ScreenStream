package info.dvkr.screenstream.mjpeg.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow

interface MjpegSettings {

    object Key {
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
    }

    object Default {
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
    }

    object Values {
        const val RESIZE_DISABLED = 100

        const val ROTATION_0 = 0
        const val ROTATION_90 = 90
        const val ROTATION_180 = 180
        const val ROTATION_270 = 270
    }

    val notifySlowConnectionsFlow: Flow<Boolean>
    suspend fun setNotifySlowConnections(value: Boolean)

    val htmlEnableButtonsFlow: Flow<Boolean>
    suspend fun setHtmlEnableButtons(value: Boolean)

    val htmlShowPressStartFlow: Flow<Boolean>
    suspend fun setHtmlShowPressStart(value: Boolean)

    val htmlBackColorFlow: Flow<Int>
    suspend fun setHtmlBackColor(value: Int)


    val vrModeFlow: Flow<Int>
    suspend fun setVrMode(value: Int)

    val imageCropFlow: Flow<Boolean>
    suspend fun setImageCrop(value: Boolean)

    val imageCropTopFlow: Flow<Int>
    suspend fun setImageCropTop(value: Int)

    val imageCropBottomFlow: Flow<Int>
    suspend fun setImageCropBottom(value: Int)

    val imageCropLeftFlow: Flow<Int>
    suspend fun setImageCropLeft(value: Int)

    val imageCropRightFlow: Flow<Int>
    suspend fun setImageCropRight(value: Int)

    val imageGrayscaleFlow: Flow<Boolean>
    suspend fun setImageGrayscale(value: Boolean)

    val jpegQualityFlow: Flow<Int>
    suspend fun setJpegQuality(value: Int)

    val resizeFactorFlow: Flow<Int>
    suspend fun setResizeFactor(value: Int)

    val rotationFlow: Flow<Int>
    suspend fun setRotation(value: Int)

    val maxFPSFlow: Flow<Int>
    suspend fun setMaxFPS(value: Int)


    val enablePinFlow: Flow<Boolean>
    suspend fun setEnablePin(value: Boolean)

    val hidePinOnStartFlow: Flow<Boolean>
    suspend fun setHidePinOnStart(value: Boolean)

    val newPinOnAppStartFlow: Flow<Boolean>
    suspend fun setNewPinOnAppStart(value: Boolean)

    val autoChangePinFlow: Flow<Boolean>
    suspend fun setAutoChangePin(value: Boolean)

    val pinFlow: Flow<String>
    suspend fun setPin(value: String)

    val blockAddressFlow: Flow<Boolean>
    suspend fun setBlockAddress(value: Boolean)


    val useWiFiOnlyFlow: Flow<Boolean>
    suspend fun setUseWiFiOnly(value: Boolean)

    val enableIPv6Flow: Flow<Boolean>
    suspend fun setEnableIPv6(value: Boolean)

    val enableLocalHostFlow: Flow<Boolean>
    suspend fun setEnableLocalHost(value: Boolean)

    val localHostOnlyFlow: Flow<Boolean>
    suspend fun setLocalHostOnly(value: Boolean)

    val serverPortFlow: Flow<Int>
    suspend fun setServerPort(value: Int)
}