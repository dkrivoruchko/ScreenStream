package info.dvkr.screenstream.webrtc

import androidx.annotation.RestrictTo
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow

@RestrictTo(RestrictTo.Scope.LIBRARY)
public interface WebRtcSettings {

    public object Key {
        public val LAST_STREAM_ID: Preferences.Key<String> = stringPreferencesKey("LAST_STREAM_ID")
        public val STREAM_PASSWORD: Preferences.Key<String> = stringPreferencesKey("STREAM_PASSWORD")
        public val ENABLE_MIC: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_MIC")
        public val MIC_PERMISSION_DENIED: Preferences.Key<Boolean> = booleanPreferencesKey("MIC_PERMISSION_DENIED")

        public val KEEP_AWAKE: Preferences.Key<Boolean> = booleanPreferencesKey("KEEP_AWAKE")
        public val STOP_ON_SLEEP: Preferences.Key<Boolean> = booleanPreferencesKey("STOP_ON_SLEEP")
    }

    public object Default {
        public const val LAST_STREAM_ID: String = ""
        public const val STREAM_PASSWORD: String = ""
        public const val ENABLE_MIC: Boolean = false
        public const val MIC_PERMISSION_DENIED: Boolean = false

        public const val KEEP_AWAKE: Boolean = false
        public const val STOP_ON_SLEEP: Boolean = false
    }

    public val lastStreamIdFlow: Flow<String>
    public suspend fun setLastStreamId(value: String)

    public val streamPasswordFlow: Flow<String>
    public suspend fun setStreamPassword(value: String)

    public val enableMicFlow: Flow<Boolean>
    public suspend fun setEnableMic(value: Boolean)

    public val micPermissionDeniedFlow: Flow<Boolean>
    public suspend fun setMicPermissionDenied(value: Boolean)


    public val keepAwakeFlow: Flow<Boolean>
    public suspend fun setKeepAwake(value: Boolean)

    public val stopOnSleepFlow: Flow<Boolean>
    public suspend fun setStopOnSleep(value: Boolean)
}