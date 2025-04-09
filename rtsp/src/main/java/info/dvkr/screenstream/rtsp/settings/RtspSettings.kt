package info.dvkr.screenstream.rtsp.settings

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
        public val PROTOCOL: Preferences.Key<String> = stringPreferencesKey("PROTOCOL")

        public val VIDEO_CODEC_AUTO_SELECT: Preferences.Key<Boolean> = booleanPreferencesKey("VIDEO_CODEC_AUTO_SELECT")
        public val VIDEO_CODEC: Preferences.Key<String> = stringPreferencesKey("VIDEO_CODEC")
        public val VIDEO_RESIZE_FACTOR: Preferences.Key<Float> = floatPreferencesKey("VIDEO_RESIZE_FACTOR")
        public val VIDEO_FPS: Preferences.Key<Int> = intPreferencesKey("VIDEO_FPS")
        public val VIDEO_BITRATE: Preferences.Key<Int> = intPreferencesKey("VIDEO_BITRATE")

        public val AUDIO_CODEC_AUTO_SELECT: Preferences.Key<Boolean> = booleanPreferencesKey("AUDIO_CODEC_AUTO_SELECT")
        public val AUDIO_CODEC: Preferences.Key<String> = stringPreferencesKey("AUDIO_CODEC")
        public val AUDIO_BITRATE: Preferences.Key<Int> = intPreferencesKey("AUDIO_BITRATE")

        public val ENABLE_MIC: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_MIC")
        public val ENABLE_DEVICE_AUDIO: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_DEVICE_AUDIO")
        public val STEREO_AUDIO: Preferences.Key<Boolean> = booleanPreferencesKey("STEREO_AUDIO")
        public val AUDIO_ECHO_CANCELLER: Preferences.Key<Boolean> = booleanPreferencesKey("AUDIO_ECHO_CANCELLER")
        public val AUDIO_NOISE_SUPPRESSOR: Preferences.Key<Boolean> = booleanPreferencesKey("AUDIO_NOISE_SUPPRESSOR")
    }

    public object Default {
        public const val KEEP_AWAKE: Boolean = true
        public const val STOP_ON_SLEEP: Boolean = false
        public const val STOP_ON_CONFIGURATION_CHANGE: Boolean = false

        public const val SERVER_ADDRESS: String = "rtsp://192.168.231.11:8554/mystream" //"rtsp://"
        public const val PROTOCOL: String = "TCP" // Must match Protocol enum values

        public const val VIDEO_CODEC_AUTO_SELECT: Boolean = true
        public const val VIDEO_CODEC: String = ""
        public const val VIDEO_RESIZE_FACTOR: Float = 50F
        public const val VIDEO_FPS: Int = 30
        public const val VIDEO_BITRATE: Int = 4500 * 1000

        public const val AUDIO_CODEC_AUTO_SELECT: Boolean = true
        public const val AUDIO_CODEC: String = ""
        public const val AUDIO_BITRATE: Int = 128 * 1000
        public const val ENABLE_MIC: Boolean = false
        public const val ENABLE_DEVICE_AUDIO: Boolean = false
        public const val STEREO_AUDIO: Boolean = false
        public const val AUDIO_ECHO_CANCELLER: Boolean = true
        public const val AUDIO_NOISE_SUPPRESSOR: Boolean = false
    }

    @Immutable
    public data class Data(
        public val keepAwake: Boolean = Default.KEEP_AWAKE,
        public val stopOnSleep: Boolean = Default.STOP_ON_SLEEP,
        public val stopOnConfigurationChange: Boolean = Default.STOP_ON_CONFIGURATION_CHANGE,

        public val serverAddress: String = Default.SERVER_ADDRESS,
        public val protocol: String = Default.PROTOCOL,

        public val videoCodecAutoSelect: Boolean = Default.VIDEO_CODEC_AUTO_SELECT,
        public val videoCodec: String = Default.VIDEO_CODEC,
        public val videoResizeFactor: Float = Default.VIDEO_RESIZE_FACTOR,
        public val videoFps: Int = Default.VIDEO_FPS,
        public val videoBitrateBits: Int = Default.VIDEO_BITRATE,

        public val audioCodecAutoSelect: Boolean = Default.AUDIO_CODEC_AUTO_SELECT,
        public val audioCodec: String = Default.AUDIO_CODEC,
        public val audioBitrateBits: Int = Default.AUDIO_BITRATE,
        public val enableMic: Boolean = Default.ENABLE_MIC,
        public val enableDeviceAudio: Boolean = Default.ENABLE_DEVICE_AUDIO,
        public val stereoAudio: Boolean = Default.STEREO_AUDIO,
        public val audioEchoCanceller: Boolean = Default.AUDIO_ECHO_CANCELLER,
        public val audioNoiseSuppressor: Boolean = Default.AUDIO_NOISE_SUPPRESSOR,
    )

    public val data: StateFlow<Data>
    public suspend fun updateData(transform: Data.() -> Data)
}