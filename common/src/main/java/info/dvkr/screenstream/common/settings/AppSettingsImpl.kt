package info.dvkr.screenstream.common.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow

class AppSettingsImpl(private val dataStore: DataStore<Preferences>) : AppSettings {

    override val streamModeFlow: Flow<Int> = dataStore.getCatching(AppSettings.Key.STREAM_MODE, AppSettings.Default.STREAM_MODE)
    override suspend fun setStreamMode(value: Int) = dataStore.setValue(AppSettings.Key.STREAM_MODE, value)

    override val nightModeFlow: Flow<Int> = dataStore.getCatching(AppSettings.Key.NIGHT_MODE, AppSettings.Default.NIGHT_MODE)
    override suspend fun setNightMode(value: Int) = dataStore.setValue(AppSettings.Key.NIGHT_MODE, value)

    override val keepAwakeFlow: Flow<Boolean> = dataStore.getCatching(AppSettings.Key.KEEP_AWAKE, AppSettings.Default.KEEP_AWAKE)
    override suspend fun setKeepAwake(value: Boolean) = dataStore.setValue(AppSettings.Key.KEEP_AWAKE, value)

    override val stopOnSleepFlow: Flow<Boolean> = dataStore.getCatching(AppSettings.Key.STOP_ON_SLEEP, AppSettings.Default.STOP_ON_SLEEP)
    override suspend fun setStopOnSleep(value: Boolean) = dataStore.setValue(AppSettings.Key.STOP_ON_SLEEP, value)


    override val loggingVisibleFlow: Flow<Boolean> = dataStore.getCatching(AppSettings.Key.LOGGING_VISIBLE, AppSettings.Default.LOGGING_VISIBLE)
    override suspend fun setLoggingVisible(value: Boolean) = dataStore.setValue(AppSettings.Key.LOGGING_VISIBLE, value)

    override val lastUpdateRequestMillisFlow: Flow<Long> = dataStore.getCatching(AppSettings.Key.LAST_UPDATE_REQUEST_MILLIS, AppSettings.Default.LAST_IAU_REQUEST_TIMESTAMP)
    override suspend fun setLastUpdateRequestMillis(value: Long) = dataStore.setValue(AppSettings.Key.LAST_UPDATE_REQUEST_MILLIS, value)
}