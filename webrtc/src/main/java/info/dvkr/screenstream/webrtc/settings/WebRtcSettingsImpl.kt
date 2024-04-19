package info.dvkr.screenstream.webrtc.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.Single
import java.io.IOException

@Single
internal class WebRtcSettingsImpl(
    context: Context
) : WebRtcSettings {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("WebRTC_settings") } // Sync name with backup config
    )

    override val data: StateFlow<WebRtcSettings.Data> = dataStore.data
        .map { preferences -> preferences.toWebRtcSettings() }
        .catch { cause ->
            XLog.e(this@WebRtcSettingsImpl.getLog("getCatching"), cause)
            if (cause is IOException) emit(WebRtcSettings.Data()) else throw cause
        }
        .stateIn(
            CoroutineScope(Dispatchers.IO),
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
            WebRtcSettings.Data()
        )

    override suspend fun updateData(transform: WebRtcSettings.Data.() -> WebRtcSettings.Data) {
        dataStore.edit { preferences ->
            val newSettings = transform.invoke(preferences.toWebRtcSettings())

            preferences.apply {
                clear()

                if (newSettings.lastStreamId != WebRtcSettings.Default.LAST_STREAM_ID)
                    set(WebRtcSettings.Key.LAST_STREAM_ID, newSettings.lastStreamId)

                if (newSettings.streamPassword != WebRtcSettings.Default.STREAM_PASSWORD)
                    set(WebRtcSettings.Key.STREAM_PASSWORD, newSettings.streamPassword)

                if (newSettings.enableMic != WebRtcSettings.Default.ENABLE_MIC)
                    set(WebRtcSettings.Key.ENABLE_MIC, newSettings.enableMic)

                if (newSettings.micPermissionDenied != WebRtcSettings.Default.MIC_PERMISSION_DENIED)
                    set(WebRtcSettings.Key.MIC_PERMISSION_DENIED, newSettings.micPermissionDenied)


                if (newSettings.keepAwake != WebRtcSettings.Default.KEEP_AWAKE)
                    set(WebRtcSettings.Key.KEEP_AWAKE, newSettings.keepAwake)

                if (newSettings.stopOnSleep != WebRtcSettings.Default.STOP_ON_SLEEP)
                    set(WebRtcSettings.Key.STOP_ON_SLEEP, newSettings.stopOnSleep)
            }
        }
    }

    private fun Preferences.toWebRtcSettings(): WebRtcSettings.Data = WebRtcSettings.Data(
        lastStreamId = this[WebRtcSettings.Key.LAST_STREAM_ID] ?: WebRtcSettings.Default.LAST_STREAM_ID,
        streamPassword = this[WebRtcSettings.Key.STREAM_PASSWORD] ?: WebRtcSettings.Default.STREAM_PASSWORD,
        enableMic = this[WebRtcSettings.Key.ENABLE_MIC] ?: WebRtcSettings.Default.ENABLE_MIC,
        micPermissionDenied = this[WebRtcSettings.Key.MIC_PERMISSION_DENIED] ?: WebRtcSettings.Default.MIC_PERMISSION_DENIED,

        keepAwake = this[WebRtcSettings.Key.KEEP_AWAKE] ?: WebRtcSettings.Default.KEEP_AWAKE,
        stopOnSleep = this[WebRtcSettings.Key.STOP_ON_SLEEP] ?: WebRtcSettings.Default.STOP_ON_SLEEP
    )
}