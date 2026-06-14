package info.dvkr.screenstream.mjpeg.settings

import androidx.annotation.IntDef
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
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
        public val HTML_KEEP_IMAGE_ON_RECONNECT: Preferences.Key<Boolean> = booleanPreferencesKey("HTML_KEEP_IMAGE_ON_RECONNECT")
        public val HTML_BACK_COLOR: Preferences.Key<Int> = intPreferencesKey("HTML_BACK_COLOR")
        public val HTML_FIT_WINDOW: Preferences.Key<Boolean> = booleanPreferencesKey("HTML_FIT_WINDOW")
        public val STREAM_FORMAT: Preferences.Key<Int> = intPreferencesKey("STREAM_FORMAT")
        public val STREAM_AUDIO_ONLY: Preferences.Key<Boolean> = booleanPreferencesKey("STREAM_AUDIO_ONLY")

        public val VIDEO_CODEC_AUTO_SELECT: Preferences.Key<Boolean> = booleanPreferencesKey("VIDEO_CODEC_AUTO_SELECT")
        public val VIDEO_CODEC: Preferences.Key<String> = stringPreferencesKey("VIDEO_CODEC")
        public val VIDEO_BITRATE: Preferences.Key<Int> = intPreferencesKey("VIDEO_BITRATE")

        public val AUDIO_BITRATE: Preferences.Key<Int> = intPreferencesKey("AUDIO_BITRATE")
        public val ENABLE_MIC: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_MIC")
        public val MUTE_MIC: Preferences.Key<Boolean> = booleanPreferencesKey("MUTE_MIC")
        public val VOLUME_MIC: Preferences.Key<Float> = floatPreferencesKey("VOLUME_MIC")
        public val ENABLE_DEVICE_AUDIO: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_DEVICE_AUDIO")
        public val MUTE_DEVICE_AUDIO: Preferences.Key<Boolean> = booleanPreferencesKey("MUTE_DEVICE_AUDIO")
        public val VOLUME_DEVICE_AUDIO: Preferences.Key<Float> = floatPreferencesKey("VOLUME_DEVICE_AUDIO")
        public val AUDIO_ECHO_CANCELLER: Preferences.Key<Boolean> = booleanPreferencesKey("AUDIO_ECHO_CANCELLER")
        public val AUDIO_NOISE_SUPPRESSOR: Preferences.Key<Boolean> = booleanPreferencesKey("AUDIO_NOISE_SUPPRESSOR")

        public val VR_MODE: Preferences.Key<Int> = intPreferencesKey("VR_MODE")
        public val IMAGE_CROP: Preferences.Key<Boolean> = booleanPreferencesKey("IMAGE_CROP")
        public val IMAGE_CROP_TOP: Preferences.Key<Int> = intPreferencesKey("IMAGE_CROP_TOP")
        public val IMAGE_CROP_BOTTOM: Preferences.Key<Int> = intPreferencesKey("IMAGE_CROP_BOTTOM")
        public val IMAGE_CROP_LEFT: Preferences.Key<Int> = intPreferencesKey("IMAGE_CROP_LEFT")
        public val IMAGE_CROP_RIGHT: Preferences.Key<Int> = intPreferencesKey("IMAGE_CROP_RIGHT")
        public val IMAGE_GRAYSCALE: Preferences.Key<Boolean> = booleanPreferencesKey("IMAGE_GRAYSCALE")
        public val JPEG_QUALITY: Preferences.Key<Int> = intPreferencesKey("JPEG_QUALITY")
        public val RESIZE_FACTOR: Preferences.Key<Int> = intPreferencesKey("RESIZE_FACTOR")
        public val RESOLUTION_WIDTH: Preferences.Key<Int> = intPreferencesKey("RESOLUTION_WIDTH")
        public val RESOLUTION_HEIGHT: Preferences.Key<Int> = intPreferencesKey("RESOLUTION_HEIGHT")
        public val RESOLUTION_STRETCH: Preferences.Key<Boolean> = booleanPreferencesKey("RESOLUTION_STRETCH")
        public val ROTATION: Preferences.Key<Int> = intPreferencesKey("ROTATION")
        public val FLIP: Preferences.Key<Int> = intPreferencesKey("FLIP")
        public val MAX_FPS: Preferences.Key<Int> = intPreferencesKey("MAX_FPS")

        public val ENABLE_PIN: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_PIN")
        public val HIDE_PIN_ON_START: Preferences.Key<Boolean> = booleanPreferencesKey("HIDE_PIN_ON_START")
        public val NEW_PIN_ON_APP_START: Preferences.Key<Boolean> = booleanPreferencesKey("NEW_PIN_ON_APP_START")
        public val AUTO_CHANGE_PIN: Preferences.Key<Boolean> = booleanPreferencesKey("AUTO_CHANGE_PIN")
        public val PIN: Preferences.Key<String> = stringPreferencesKey("PIN")
        public val BLOCK_ADDRESS: Preferences.Key<Boolean> = booleanPreferencesKey("BLOCK_ADDRESS")

        public val INTERFACE_FILTER: Preferences.Key<Int> = intPreferencesKey("INTERFACE_FILTER")
        public val ADDRESS_FILTER: Preferences.Key<Int> = intPreferencesKey("ADDRESS_FILTER")
        public val ENABLE_IPV4: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_IPV4")
        public val ENABLE_IPV6: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_IPV6")
        public val SERVER_PORT: Preferences.Key<Int> = intPreferencesKey("SERVER_PORT")
    }

    public object Default {
        public const val KEEP_AWAKE: Boolean = true
        public const val STOP_ON_SLEEP: Boolean = false
        public const val STOP_ON_CONFIGURATION_CHANGE: Boolean = false
        public const val NOTIFY_SLOW_CONNECTIONS: Boolean = false

        public const val HTML_ENABLE_BUTTONS: Boolean = false
        public const val HTML_SHOW_PRESS_START: Boolean = true
        public const val HTML_KEEP_IMAGE_ON_RECONNECT: Boolean = true
        public const val HTML_BACK_COLOR: Int = -15723496// "FF101418".toLong(radix = 16).toInt()
        public const val HTML_FIT_WINDOW: Boolean = true
        public const val STREAM_FORMAT: Int = Values.STREAM_FORMAT_MJPEG
        public const val STREAM_AUDIO_ONLY: Boolean = false

        public const val VIDEO_CODEC_AUTO_SELECT: Boolean = true
        public const val VIDEO_CODEC: String = ""
        public const val VIDEO_BITRATE: Int = 4500 * 1000

        public const val AUDIO_BITRATE: Int = 128 * 1000
        public const val ENABLE_MIC: Boolean = false
        public const val MUTE_MIC: Boolean = false
        public const val VOLUME_MIC: Float = 1.0F
        public const val ENABLE_DEVICE_AUDIO: Boolean = false
        public const val MUTE_DEVICE_AUDIO: Boolean = false
        public const val VOLUME_DEVICE_AUDIO: Float = 1.0F
        public const val AUDIO_ECHO_CANCELLER: Boolean = true
        public const val AUDIO_NOISE_SUPPRESSOR: Boolean = false

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
        public const val RESOLUTION_WIDTH: Int = 0
        public const val RESOLUTION_HEIGHT: Int = 0
        public const val RESOLUTION_STRETCH: Boolean = true
        public const val ROTATION: Int = Values.ROTATION_0
        public const val FLIP: Int = Values.FLIP_NONE
        public const val MAX_FPS: Int = 30

        public const val ENABLE_PIN: Boolean = false
        public const val HIDE_PIN_ON_START: Boolean = true
        public const val NEW_PIN_ON_APP_START: Boolean = true
        public const val AUTO_CHANGE_PIN: Boolean = false
        public const val PIN: String = "000000"
        public const val BLOCK_ADDRESS: Boolean = true

        public const val INTERFACE_FILTER: Int = Values.INTERFACE_WIFI or Values.INTERFACE_ETHERNET
        public const val ADDRESS_FILTER: Int = Values.ADDRESS_PRIVATE
        public const val ENABLE_IPV4: Boolean = true
        public const val ENABLE_IPV6: Boolean = false
        public const val SERVER_PORT: Int = 8080
    }

    public object Values {
        public const val RESIZE_DISABLED: Int = 100

        public const val ROTATION_0: Int = 0
        public const val ROTATION_90: Int = 90
        public const val ROTATION_180: Int = 180
        public const val ROTATION_270: Int = 270
        public const val FLIP_NONE: Int = 0
        public const val FLIP_HORIZONTAL: Int = 1
        public const val FLIP_VERTICAL: Int = 2

        public const val STREAM_FORMAT_MJPEG: Int = 0
        public const val STREAM_FORMAT_MP4: Int = 1


        @IntDef(flag = true, value = [INTERFACE_WIFI, INTERFACE_MOBILE, INTERFACE_ETHERNET, INTERFACE_VPN])
        @Retention(AnnotationRetention.SOURCE)
        public annotation class InterfaceMask

        public const val INTERFACE_ALL: Int = 0
        public const val INTERFACE_WIFI: Int = 1
        public const val INTERFACE_MOBILE: Int = 1 shl 1
        public const val INTERFACE_ETHERNET: Int = 1 shl 2
        public const val INTERFACE_VPN: Int = 1 shl 3

        @IntDef(flag = true, value = [ADDRESS_PRIVATE, ADDRESS_LOCALHOST, ADDRESS_PUBLIC])
        @Retention(AnnotationRetention.SOURCE)
        public annotation class AddressMask

        public const val ADDRESS_ALL: Int = 0
        public const val ADDRESS_PRIVATE: Int = 1
        public const val ADDRESS_LOCALHOST: Int = 1 shl 1
        public const val ADDRESS_PUBLIC: Int = 1 shl 2
    }

    @Immutable
    public data class Data(
        public val keepAwake: Boolean = Default.KEEP_AWAKE,
        public val stopOnSleep: Boolean = Default.STOP_ON_SLEEP,
        public val stopOnConfigurationChange: Boolean = Default.STOP_ON_CONFIGURATION_CHANGE,
        public val notifySlowConnections: Boolean = Default.NOTIFY_SLOW_CONNECTIONS,
        public val htmlEnableButtons: Boolean = Default.HTML_ENABLE_BUTTONS,
        public val htmlShowPressStart: Boolean = Default.HTML_SHOW_PRESS_START,
        public val htmlKeepImageOnReconnect: Boolean = Default.HTML_KEEP_IMAGE_ON_RECONNECT,
        public val htmlBackColor: Int = Default.HTML_BACK_COLOR,
        public val htmlFitWindow: Boolean = Default.HTML_FIT_WINDOW,
        public val streamFormat: Int = Default.STREAM_FORMAT,
        public val streamAudioOnly: Boolean = Default.STREAM_AUDIO_ONLY,

        public val videoCodecAutoSelect: Boolean = Default.VIDEO_CODEC_AUTO_SELECT,
        public val videoCodec: String = Default.VIDEO_CODEC,
        public val videoBitrateBits: Int = Default.VIDEO_BITRATE,

        public val audioBitrateBits: Int = Default.AUDIO_BITRATE,
        public val enableMic: Boolean = Default.ENABLE_MIC,
        public val muteMic: Boolean = Default.MUTE_MIC,
        public val volumeMic: Float = Default.VOLUME_MIC,
        public val enableDeviceAudio: Boolean = Default.ENABLE_DEVICE_AUDIO,
        public val muteDeviceAudio: Boolean = Default.MUTE_DEVICE_AUDIO,
        public val volumeDeviceAudio: Float = Default.VOLUME_DEVICE_AUDIO,
        public val audioEchoCanceller: Boolean = Default.AUDIO_ECHO_CANCELLER,
        public val audioNoiseSuppressor: Boolean = Default.AUDIO_NOISE_SUPPRESSOR,

        public val vrMode: Int = Default.VR_MODE_DISABLE,
        public val imageCrop: Boolean = Default.IMAGE_CROP,
        public val imageCropTop: Int = Default.IMAGE_CROP_TOP,
        public val imageCropBottom: Int = Default.IMAGE_CROP_BOTTOM,
        public val imageCropLeft: Int = Default.IMAGE_CROP_LEFT,
        public val imageCropRight: Int = Default.IMAGE_CROP_RIGHT,
        public val imageGrayscale: Boolean = Default.IMAGE_GRAYSCALE,
        public val jpegQuality: Int = Default.JPEG_QUALITY,
        public val resizeFactor: Int = Default.RESIZE_FACTOR,
        public val resolutionWidth: Int = Default.RESOLUTION_WIDTH,
        public val resolutionHeight: Int = Default.RESOLUTION_HEIGHT,
        public val resolutionStretch: Boolean = Default.RESOLUTION_STRETCH,
        public val rotation: Int = Default.ROTATION,
        public val flip: Int = Default.FLIP,
        public val maxFPS: Int = Default.MAX_FPS,

        public val enablePin: Boolean = Default.ENABLE_PIN,
        public val hidePinOnStart: Boolean = Default.HIDE_PIN_ON_START,
        public val newPinOnAppStart: Boolean = Default.NEW_PIN_ON_APP_START,
        public val autoChangePin: Boolean = Default.AUTO_CHANGE_PIN,
        public val pin: String = Default.PIN,
        public val blockAddress: Boolean = Default.BLOCK_ADDRESS,

        public val interfaceFilter: Int = Default.INTERFACE_FILTER,
        public val addressFilter: Int = Default.ADDRESS_FILTER,
        public val enableIPv4: Boolean = Default.ENABLE_IPV4,
        public val enableIPv6: Boolean = Default.ENABLE_IPV6,
        public val serverPort: Int = Default.SERVER_PORT,
    )

    public val data: StateFlow<Data>
    public suspend fun updateData(transform: Data.() -> Data)
}
