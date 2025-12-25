package info.dvkr.screenstream.rtsp.settings

import androidx.annotation.IntDef
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.StateFlow

public interface RtspSettings {

    public object Key {
        public val KEEP_AWAKE: Preferences.Key<Boolean> = booleanPreferencesKey("KEEP_AWAKE")
        public val STOP_ON_SLEEP: Preferences.Key<Boolean> = booleanPreferencesKey("STOP_ON_SLEEP")
        public val STOP_ON_CONFIGURATION_CHANGE: Preferences.Key<Boolean> = booleanPreferencesKey("STOP_ON_CONFIGURATION_CHANGE")

        public val SERVER_ADDRESS: Preferences.Key<String> = stringPreferencesKey("SERVER_ADDRESS")
        public val CLIENT_PROTOCOL: Preferences.Key<String> = stringPreferencesKey("CLIENT_PROTOCOL")
        public val SERVER_PROTOCOL: Preferences.Key<String> = stringPreferencesKey("SERVER_PROTOCOL")
        public val MODE: Preferences.Key<String> = stringPreferencesKey("MODE")

        public val VIDEO_CODEC_AUTO_SELECT: Preferences.Key<Boolean> = booleanPreferencesKey("VIDEO_CODEC_AUTO_SELECT")
        public val VIDEO_CODEC: Preferences.Key<String> = stringPreferencesKey("VIDEO_CODEC")
        public val VIDEO_RESIZE_FACTOR: Preferences.Key<Float> = floatPreferencesKey("VIDEO_RESIZE_FACTOR")
        public val VIDEO_FPS: Preferences.Key<Int> = intPreferencesKey("VIDEO_FPS")
        public val VIDEO_BITRATE: Preferences.Key<Int> = intPreferencesKey("VIDEO_BITRATE")

        public val AUDIO_CODEC_AUTO_SELECT: Preferences.Key<Boolean> = booleanPreferencesKey("AUDIO_CODEC_AUTO_SELECT")
        public val AUDIO_CODEC: Preferences.Key<String> = stringPreferencesKey("AUDIO_CODEC")
        public val AUDIO_BITRATE: Preferences.Key<Int> = intPreferencesKey("AUDIO_BITRATE")

        public val ENABLE_MIC: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_MIC")
        public val MUTE_MIC: Preferences.Key<Boolean> = booleanPreferencesKey("MUTE_MIC")
        public val VOLUME_MIC: Preferences.Key<Float> = floatPreferencesKey("VOLUME_MIC")
        public val ENABLE_DEVICE_AUDIO: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_DEVICE_AUDIO")
        public val MUTE_DEVICE_AUDIO: Preferences.Key<Boolean> = booleanPreferencesKey("MUTE_DEVICE_AUDIO")
        public val VOLUME_DEVICE_AUDIO: Preferences.Key<Float> = floatPreferencesKey("VOLUME_DEVICE_AUDIO")
        public val STEREO_AUDIO: Preferences.Key<Boolean> = booleanPreferencesKey("STEREO_AUDIO")
        public val AUDIO_ECHO_CANCELLER: Preferences.Key<Boolean> = booleanPreferencesKey("AUDIO_ECHO_CANCELLER")
        public val AUDIO_NOISE_SUPPRESSOR: Preferences.Key<Boolean> = booleanPreferencesKey("AUDIO_NOISE_SUPPRESSOR")

        public val INTERFACE_FILTER: Preferences.Key<Int> = intPreferencesKey("INTERFACE_FILTER")
        public val ADDRESS_FILTER: Preferences.Key<Int> = intPreferencesKey("ADDRESS_FILTER")
        public val ENABLE_IPV4: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_IPV4")
        public val ENABLE_IPV6: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_IPV6")
        public val SERVER_PORT: Preferences.Key<Int> = intPreferencesKey("SERVER_PORT")
        public val SERVER_PATH: Preferences.Key<String> = stringPreferencesKey("SERVER_PATH")
    }

    public object Default {
        public const val KEEP_AWAKE: Boolean = true
        public const val STOP_ON_SLEEP: Boolean = false
        public const val STOP_ON_CONFIGURATION_CHANGE: Boolean = false

        public const val SERVER_ADDRESS: String = "rtsp://"
        public val CLIENT_PROTOCOL: Values.ClientProtocolPolicy = Values.ClientProtocolPolicy.TCP
        public val SERVER_PROTOCOL: Values.ServerProtocolPolicy = Values.ServerProtocolPolicy.AUTO
        public val MODE: Values.Mode = Values.Mode.SERVER

        public const val VIDEO_CODEC_AUTO_SELECT: Boolean = true
        public const val VIDEO_CODEC: String = ""
        public const val VIDEO_RESIZE_FACTOR: Float = 50F
        public const val VIDEO_FPS: Int = 30
        public const val VIDEO_BITRATE: Int = 4500 * 1000

        public const val AUDIO_CODEC_AUTO_SELECT: Boolean = true
        public const val AUDIO_CODEC: String = ""
        public const val AUDIO_BITRATE: Int = 128 * 1000
        public const val ENABLE_MIC: Boolean = false
        public const val MUTE_MIC: Boolean = false
        public const val VOLUME_MIC: Float = 1.0F
        public const val ENABLE_DEVICE_AUDIO: Boolean = false
        public const val MUTE_DEVICE_AUDIO: Boolean = false
        public const val VOLUME_DEVICE_AUDIO: Float = 1.0F
        public const val STEREO_AUDIO: Boolean = false
        public const val AUDIO_ECHO_CANCELLER: Boolean = true
        public const val AUDIO_NOISE_SUPPRESSOR: Boolean = false

        public const val INTERFACE_FILTER: Int = Values.INTERFACE_WIFI or Values.INTERFACE_ETHERNET
        public const val ADDRESS_FILTER: Int = Values.ADDRESS_PRIVATE
        public const val ENABLE_IPV4: Boolean = true
        public const val ENABLE_IPV6: Boolean = false
        public const val SERVER_PORT: Int = 8554
        public const val SERVER_PATH: String = "screen"
    }

    public object Values {
        public enum class Mode { SERVER, CLIENT }
        public enum class ClientProtocolPolicy { TCP, UDP }
        public enum class ServerProtocolPolicy { AUTO, TCP, UDP }

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

        public val serverAddress: String = Default.SERVER_ADDRESS,
        public val clientProtocol: Values.ClientProtocolPolicy = Default.CLIENT_PROTOCOL,
        public val serverProtocol: Values.ServerProtocolPolicy = Default.SERVER_PROTOCOL,
        public val mode: Values.Mode = Default.MODE,

        public val videoCodecAutoSelect: Boolean = Default.VIDEO_CODEC_AUTO_SELECT,
        public val videoCodec: String = Default.VIDEO_CODEC,
        public val videoResizeFactor: Float = Default.VIDEO_RESIZE_FACTOR,
        public val videoFps: Int = Default.VIDEO_FPS,
        public val videoBitrateBits: Int = Default.VIDEO_BITRATE,

        public val audioCodecAutoSelect: Boolean = Default.AUDIO_CODEC_AUTO_SELECT,
        public val audioCodec: String = Default.AUDIO_CODEC,
        public val audioBitrateBits: Int = Default.AUDIO_BITRATE,
        public val enableMic: Boolean = Default.ENABLE_MIC,
        public val muteMic: Boolean = Default.MUTE_MIC,
        public val volumeMic: Float = Default.VOLUME_MIC,
        public val enableDeviceAudio: Boolean = Default.ENABLE_DEVICE_AUDIO,
        public val muteDeviceAudio: Boolean = Default.MUTE_DEVICE_AUDIO,
        public val volumeDeviceAudio: Float = Default.VOLUME_DEVICE_AUDIO,
        public val stereoAudio: Boolean = Default.STEREO_AUDIO,
        public val audioEchoCanceller: Boolean = Default.AUDIO_ECHO_CANCELLER,
        public val audioNoiseSuppressor: Boolean = Default.AUDIO_NOISE_SUPPRESSOR,

        public val interfaceFilter: Int = Default.INTERFACE_FILTER,
        public val addressFilter: Int = Default.ADDRESS_FILTER,
        public val enableIPv4: Boolean = Default.ENABLE_IPV4,
        public val enableIPv6: Boolean = Default.ENABLE_IPV6,
        public val serverPort: Int = Default.SERVER_PORT,
        public val serverPath: String = Default.SERVER_PATH,
    )

    public val data: StateFlow<Data>
    public suspend fun updateData(transform: Data.() -> Data)
}
