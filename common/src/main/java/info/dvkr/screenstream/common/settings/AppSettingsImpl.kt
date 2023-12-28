package info.dvkr.screenstream.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.getCatching
import info.dvkr.screenstream.common.setValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single
internal class PreferenceDataStoreProvider(private val context: Context) {
    internal val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("app_settings") }
    )
}

@Single
internal class AppSettingsImpl(preferenceDataStoreProvider: PreferenceDataStoreProvider) : AppSettings {

    private val dataStore: DataStore<Preferences> = preferenceDataStoreProvider.dataStore

    override val streamingModuleFlow: Flow<StreamingModule.Id> = dataStore.getCatching(AppSettings.Key.STREAMING_MODULE, AppSettings.Default.STREAMING_MODULE.value).map { StreamingModule.Id(it) }
    override suspend fun setStreamingModule(value: StreamingModule.Id): Unit = dataStore.setValue(AppSettings.Key.STREAMING_MODULE, value.value)

    override val nightModeFlow: Flow<Int> = dataStore.getCatching(AppSettings.Key.NIGHT_MODE, AppSettings.Default.NIGHT_MODE)
    override suspend fun setNightMode(value: Int): Unit = dataStore.setValue(AppSettings.Key.NIGHT_MODE, value)


    override val lastUpdateRequestMillisFlow: Flow<Long> = dataStore.getCatching(AppSettings.Key.LAST_UPDATE_REQUEST_MILLIS, AppSettings.Default.LAST_IAU_REQUEST_TIMESTAMP)
    override suspend fun setLastUpdateRequestMillis(value: Long): Unit = dataStore.setValue(AppSettings.Key.LAST_UPDATE_REQUEST_MILLIS, value)
}