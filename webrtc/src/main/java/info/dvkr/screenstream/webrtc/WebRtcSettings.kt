package info.dvkr.screenstream.webrtc

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow

public interface WebRtcSettings {

    object Key {
        val LAST_STREAM_ID = stringPreferencesKey("LAST_STREAM_ID")
        val STREAM_PASSWORD = stringPreferencesKey("STREAM_PASSWORD")
        val ENABLE_MIC = booleanPreferencesKey("ENABLE_MIC")
        val MIC_PERMISSION_DENIED = booleanPreferencesKey("MIC_PERMISSION_DENIED")
    }

    object Default {
        const val LAST_STREAM_ID = ""
        const val STREAM_PASSWORD = ""
        const val ENABLE_MIC = false
        const val MIC_PERMISSION_DENIED = false
    }

    val lastStreamIdFlow: Flow<String>
    suspend fun setLastStreamId(value: String)

    val streamPasswordFlow: Flow<String>
    suspend fun setStreamPassword(value: String)

    val enableMicFlow: Flow<Boolean>
    suspend fun setEnableMic(value: Boolean)

    val micPermissionDeniedFlow: Flow<Boolean>
    suspend fun setMicPermissionDenied(value: Boolean)
}