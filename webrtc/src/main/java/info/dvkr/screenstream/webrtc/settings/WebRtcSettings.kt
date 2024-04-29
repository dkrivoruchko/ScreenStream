package info.dvkr.screenstream.webrtc.settings

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.StateFlow

public interface WebRtcSettings {

    public object Key {
        public val LAST_STREAM_ID: Preferences.Key<String> = stringPreferencesKey("LAST_STREAM_ID")
        public val STREAM_PASSWORD: Preferences.Key<String> = stringPreferencesKey("STREAM_PASSWORD")
        public val ENABLE_MIC: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_MIC")
        public val ENABLE_DEVICE_AUDIO: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_DEVICE_AUDIO")
        public val AUDIO_PERMISSION_DENIED: Preferences.Key<Boolean> = booleanPreferencesKey("MIC_PERMISSION_DENIED")

        public val KEEP_AWAKE: Preferences.Key<Boolean> = booleanPreferencesKey("KEEP_AWAKE")
        public val STOP_ON_SLEEP: Preferences.Key<Boolean> = booleanPreferencesKey("STOP_ON_SLEEP")
    }

    public object Default {
        public const val LAST_STREAM_ID: String = ""
        public const val STREAM_PASSWORD: String = ""
        public const val ENABLE_MIC: Boolean = false
        public const val ENABLE_DEVICE_AUDIO: Boolean = false
        public const val AUDIO_PERMISSION_DENIED: Boolean = false

        public const val KEEP_AWAKE: Boolean = true
        public const val STOP_ON_SLEEP: Boolean = false
    }

    @Immutable
    public data class Data(
        public val lastStreamId: String = Default.LAST_STREAM_ID,
        public val streamPassword: String = Default.STREAM_PASSWORD,
        public val enableMic: Boolean = Default.ENABLE_MIC,
        public val enableDeviceAudio: Boolean = Default.ENABLE_DEVICE_AUDIO,
        public val audioPermissionDenied: Boolean = Default.AUDIO_PERMISSION_DENIED,

        public val keepAwake: Boolean = Default.KEEP_AWAKE,
        public val stopOnSleep: Boolean = Default.STOP_ON_SLEEP,
    )

    public val data: StateFlow<Data>
    public suspend fun updateData(transform: Data.() -> Data)
}