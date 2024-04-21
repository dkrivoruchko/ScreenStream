package info.dvkr.screenstream.common.settings

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
import info.dvkr.screenstream.common.module.StreamingModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.Single
import java.io.IOException

@Single(createdAtStart = true)
internal class AppSettingsImpl(context: Context) : AppSettings {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("app_settings") } // Sync name with backup config
    )

    override val data: StateFlow<AppSettings.Data> = dataStore.data
        .map { preferences -> preferences.toAppSettings() }
        .catch { cause ->
            XLog.e(this@AppSettingsImpl.getLog("getCatching"), cause)
            if (cause is IOException) emit(AppSettings.Data()) else throw cause
        }
        .stateIn(
            CoroutineScope(Dispatchers.IO),
            SharingStarted.Eagerly,
            AppSettings.Data()
        )

    override suspend fun updateData(transform: AppSettings.Data.() -> AppSettings.Data) {
        dataStore.edit { preferences ->
            val newSettings = transform.invoke(preferences.toAppSettings())

            preferences.apply {
                clear()

                if (newSettings.streamingModule.value != AppSettings.Default.STREAMING_MODULE.value)
                    set(AppSettings.Key.STREAMING_MODULE, newSettings.streamingModule.value)

                if (newSettings.nightMode != AppSettings.Default.NIGHT_MODE)
                    set(AppSettings.Key.NIGHT_MODE, newSettings.nightMode)

                if (newSettings.dynamicTheme != AppSettings.Default.DYNAMIC_THEME)
                    set(AppSettings.Key.DYNAMIC_THEME, newSettings.dynamicTheme)
            }
        }
    }

    private fun Preferences.toAppSettings(): AppSettings.Data = AppSettings.Data(
        streamingModule = StreamingModule.Id(this[AppSettings.Key.STREAMING_MODULE] ?: AppSettings.Default.STREAMING_MODULE.value),
        nightMode = this[AppSettings.Key.NIGHT_MODE] ?: AppSettings.Default.NIGHT_MODE,
        dynamicTheme = this[AppSettings.Key.DYNAMIC_THEME] ?: AppSettings.Default.DYNAMIC_THEME,
    )
}