package info.dvkr.screenstream.webrtc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import info.dvkr.screenstream.common.settings.getCatching
import info.dvkr.screenstream.common.settings.setValue
import kotlinx.coroutines.flow.Flow

public class WebRtcSettingsImpl(private val dataStore: DataStore<Preferences>) : WebRtcSettings {

    override val lastStreamIdFlow: Flow<String> = dataStore.getCatching(WebRtcSettings.Key.LAST_STREAM_ID, WebRtcSettings.Default.LAST_STREAM_ID)
    override suspend fun setLastStreamId(value: String) = dataStore.setValue(WebRtcSettings.Key.LAST_STREAM_ID, value)

    override val streamPasswordFlow: Flow<String> = dataStore.getCatching(WebRtcSettings.Key.STREAM_PASSWORD, WebRtcSettings.Default.STREAM_PASSWORD)
    override suspend fun setStreamPassword(value: String) = dataStore.setValue(WebRtcSettings.Key.STREAM_PASSWORD, value)

    override val enableMicFlow: Flow<Boolean> = dataStore.getCatching(WebRtcSettings.Key.ENABLE_MIC, WebRtcSettings.Default.ENABLE_MIC)
    override suspend fun setEnableMic(value: Boolean) = dataStore.setValue(WebRtcSettings.Key.ENABLE_MIC, value)

    override val micPermissionDeniedFlow: Flow<Boolean> = dataStore.getCatching(WebRtcSettings.Key.MIC_PERMISSION_DENIED, WebRtcSettings.Default.MIC_PERMISSION_DENIED)
    override suspend fun setMicPermissionDenied(value: Boolean) = dataStore.setValue(WebRtcSettings.Key.MIC_PERMISSION_DENIED, value)
}