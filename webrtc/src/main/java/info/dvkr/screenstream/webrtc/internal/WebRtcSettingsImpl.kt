package info.dvkr.screenstream.webrtc.internal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getCatching
import info.dvkr.screenstream.common.setValue
import info.dvkr.screenstream.webrtc.WebRtcSettings
import info.dvkr.screenstream.webrtc.WebRtcKoinScope
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Scope

@Scope(WebRtcKoinScope::class)
internal class PreferenceDataStoreProvider(context: Context) {

    private companion object {
        @Volatile
        private var instance: DataStore<Preferences>? = null

        private fun getInstance(context: Context): DataStore<Preferences> = instance ?: synchronized(this) {
            instance ?: PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
                produceFile = { context.preferencesDataStoreFile("WebRTC_settings") }
            ).also { instance = it }
        }
    }

    internal val dataStore: DataStore<Preferences> = getInstance(context)
}

@Scope(WebRtcKoinScope::class)
internal class WebRtcSettingsImpl(preferenceDataStoreProvider: PreferenceDataStoreProvider) : WebRtcSettings {

    private val dataStore: DataStore<Preferences> = preferenceDataStoreProvider.dataStore

    override val lastStreamIdFlow: Flow<String> = dataStore.getCatching(WebRtcSettings.Key.LAST_STREAM_ID, WebRtcSettings.Default.LAST_STREAM_ID)
    override suspend fun setLastStreamId(value: String): Unit = dataStore.setValue(WebRtcSettings.Key.LAST_STREAM_ID, value)

    override val streamPasswordFlow: Flow<String> = dataStore.getCatching(WebRtcSettings.Key.STREAM_PASSWORD, WebRtcSettings.Default.STREAM_PASSWORD)
    override suspend fun setStreamPassword(value: String): Unit = dataStore.setValue(WebRtcSettings.Key.STREAM_PASSWORD, value)

    override val enableMicFlow: Flow<Boolean> = dataStore.getCatching(WebRtcSettings.Key.ENABLE_MIC, WebRtcSettings.Default.ENABLE_MIC)
    override suspend fun setEnableMic(value: Boolean): Unit = dataStore.setValue(WebRtcSettings.Key.ENABLE_MIC, value)

    override val micPermissionDeniedFlow: Flow<Boolean> = dataStore.getCatching(WebRtcSettings.Key.MIC_PERMISSION_DENIED, WebRtcSettings.Default.MIC_PERMISSION_DENIED)
    override suspend fun setMicPermissionDenied(value: Boolean): Unit = dataStore.setValue(WebRtcSettings.Key.MIC_PERMISSION_DENIED, value)


    override val keepAwakeFlow: Flow<Boolean> = dataStore.getCatching(WebRtcSettings.Key.KEEP_AWAKE, WebRtcSettings.Default.KEEP_AWAKE)
    override suspend fun setKeepAwake(value: Boolean): Unit = dataStore.setValue(WebRtcSettings.Key.KEEP_AWAKE, value)

    override val stopOnSleepFlow: Flow<Boolean> = dataStore.getCatching(WebRtcSettings.Key.STOP_ON_SLEEP, WebRtcSettings.Default.STOP_ON_SLEEP)
    override suspend fun setStopOnSleep(value: Boolean): Unit = dataStore.setValue(WebRtcSettings.Key.STOP_ON_SLEEP, value)
}