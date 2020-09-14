package info.dvkr.screenstream.data.settings

import android.os.Build

interface Settings : SettingsReadOnly {

    object Key {
        const val NIGHT_MODE = "PREF_KEY_NIGHT_MODE_V2"
        const val STOP_ON_SLEEP = "PREF_KEY_STOP_ON_SLEEP"
        const val START_ON_BOOT = "PREF_KEY_START_ON_BOOT"
        const val AUTO_START_STOP = "PREF_KEY_AUTO_START_STOP"
        const val NOTIFY_SLOW_CONNECTIONS = "PREF_KEY_NOTIFY_SLOW_CONNECTIONS"

        const val HTML_ENABLE_BUTTONS = "PREF_KEY_HTML_ENABLE_BUTTONS"
        const val HTML_BACK_COLOR = "PREF_KEY_HTML_BACK_COLOR"

        const val VR_MODE = "PREF_KEY_VR_MODE"
        const val IMAGE_CROP = "PREF_KEY_IMAGE_CROP"
        const val IMAGE_CROP_TOP = "PREF_KEY_IMAGE_CROP_TOP"
        const val IMAGE_CROP_BOTTOM = "PREF_KEY_IMAGE_CROP_BOTTOM"
        const val IMAGE_CROP_LEFT = "PREF_KEY_IMAGE_CROP_LEFT"
        const val IMAGE_CROP_RIGHT = "PREF_KEY_IMAGE_CROP_RIGHT"
        const val JPEG_QUALITY = "PREF_KEY_JPEG_QUALITY"
        const val RESIZE_FACTOR = "PREF_KEY_RESIZE_FACTOR"
        const val ROTATION = "PREF_KEY_ROTATION"
        const val MAX_FPS = "PREF_KEY_MAX_FPS_2"

        const val ENABLE_PIN = "PREF_KEY_ENABLE_PIN"
        const val HIDE_PIN_ON_START = "PREF_KEY_HIDE_PIN_ON_START"
        const val NEW_PIN_ON_APP_START = "PREF_KEY_NEW_PIN_ON_APP_START"
        const val AUTO_CHANGE_PIN = "PREF_KEY_AUTO_CHANGE_PIN"
        const val PIN = "PREF_KEY_SET_PIN"

        const val USE_WIFI_ONLY = "PREF_KEY_USE_WIFI_ONLY"
        const val ENABLE_IPV6 = "PREF_KEY_ENABLE_IPV6"
        const val ENABLE_LOCAL_HOST = "PREF_KEY_ENABLE_LOCAL_HOST"
        const val LOCAL_HOST_ONLY = "PREF_KEY_LOCAL_HOST_ONLY"
        const val SERVER_PORT = "PREF_KEY_SERVER_PORT"
        const val LOGGING_VISIBLE = "PREF_KEY_LOGGING_VISIBLE"
        const val LOGGING_ON = "PREF_KEY_LOGGING_ON"

        const val LAST_IAU_REQUEST_TIMESTAMP = "PREF_KEY_LAST_IAU_REQUEST_TIMESTAMP"
    }

    object Default {
        var NIGHT_MODE = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) 3 else -1
        const val STOP_ON_SLEEP = false
        const val START_ON_BOOT = false
        const val AUTO_START_STOP = false
        const val NOTIFY_SLOW_CONNECTIONS = true

        const val HTML_ENABLE_BUTTONS = true
        const val HTML_BACK_COLOR = -16777216 // "ff000000".toLong(radix = 16).toInt()

        const val VR_MODE_DISABLE = 0
        const val VR_MODE_LEFT = 1
        const val VR_MODE_RIGHT = 2
        const val IMAGE_CROP = false
        const val IMAGE_CROP_TOP = 0
        const val IMAGE_CROP_BOTTOM = 0
        const val IMAGE_CROP_LEFT = 0
        const val IMAGE_CROP_RIGHT = 0
        const val JPEG_QUALITY = 80
        const val RESIZE_FACTOR = 50
        const val ROTATION = Values.ROTATION_0
        const val MAX_FPS = 30

        const val ENABLE_PIN = false
        const val HIDE_PIN_ON_START = true
        const val NEW_PIN_ON_APP_START = true
        const val AUTO_CHANGE_PIN = false
        const val PIN = "0000"

        const val USE_WIFI_ONLY = true
        const val ENABLE_IPV6 = false
        const val ENABLE_LOCAL_HOST = false
        const val LOCAL_HOST_ONLY = false
        const val SERVER_PORT = 8080
        const val LOGGING_VISIBLE = false
        const val LOGGING_ON = false

        const val LAST_IAU_REQUEST_TIMESTAMP = 0L
    }

    object Values {
        const val RESIZE_DISABLED = 100

        const val ROTATION_0 = 0
        const val ROTATION_90 = 90
        const val ROTATION_180 = 180
        const val ROTATION_270 = 270
    }

    override var nightMode: Int
    override var stopOnSleep: Boolean
    override var startOnBoot: Boolean
    override var autoStartStop: Boolean
    override var notifySlowConnections: Boolean

    override var htmlEnableButtons: Boolean
    override var htmlBackColor: Int

    override var vrMode: Int
    override var imageCrop: Boolean
    override var imageCropTop: Int
    override var imageCropBottom: Int
    override var imageCropLeft: Int
    override var imageCropRight: Int
    override var jpegQuality: Int
    override var resizeFactor: Int
    override var rotation: Int
    override var maxFPS: Int

    override var enablePin: Boolean
    override var hidePinOnStart: Boolean
    override var newPinOnAppStart: Boolean
    override var autoChangePin: Boolean
    override var pin: String

    override var useWiFiOnly: Boolean
    override var enableIPv6: Boolean
    override var enableLocalHost: Boolean
    override var localHostOnly: Boolean
    override var severPort: Int
    override var loggingVisible: Boolean
    override var loggingOn: Boolean

    override var lastIAURequestTimeStamp: Long
}