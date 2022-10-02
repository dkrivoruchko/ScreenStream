package info.dvkr.screenstream.common.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class AppSettingsImpl(private val dataStore: DataStore<Preferences>) : AppSettings {

    override val nightModeFlow: Flow<Int> = getCatching(AppSettings.Key.NIGHT_MODE, AppSettings.Default.NIGHT_MODE)
    override suspend fun setNightMode(value: Int) = setValue(AppSettings.Key.NIGHT_MODE, value)

    override val keepAwakeFlow: Flow<Boolean> = getCatching(AppSettings.Key.KEEP_AWAKE, AppSettings.Default.KEEP_AWAKE)
    override suspend fun setKeepAwake(value: Boolean) = setValue(AppSettings.Key.KEEP_AWAKE, value)

    override val stopOnSleepFlow: Flow<Boolean> = getCatching(AppSettings.Key.STOP_ON_SLEEP, AppSettings.Default.STOP_ON_SLEEP)
    override suspend fun setStopOnSleep(value: Boolean) = setValue(AppSettings.Key.STOP_ON_SLEEP, value)

    override val startOnBootFlow: Flow<Boolean> = getCatching(AppSettings.Key.START_ON_BOOT, AppSettings.Default.START_ON_BOOT)
    override suspend fun setStartOnBoot(value: Boolean) = setValue(AppSettings.Key.START_ON_BOOT, value)

    override val autoStartStopFlow: Flow<Boolean> = getCatching(AppSettings.Key.AUTO_START_STOP, AppSettings.Default.AUTO_START_STOP)
    override suspend fun setAutoStartStop(value: Boolean) = setValue(AppSettings.Key.AUTO_START_STOP, value)


    override val loggingVisibleFlow: Flow<Boolean> = getCatching(AppSettings.Key.LOGGING_VISIBLE, AppSettings.Default.LOGGING_VISIBLE)
    override suspend fun setLoggingVisible(value: Boolean) = setValue(AppSettings.Key.LOGGING_VISIBLE, value)

    override val lastUpdateRequestMillisFlow: Flow<Long> = getCatching(AppSettings.Key.LAST_UPDATE_REQUEST_MILLIS, AppSettings.Default.LAST_IAU_REQUEST_TIMESTAMP)

    override suspend fun setLastUpdateRequestMillis(value: Long) = setValue(AppSettings.Key.LAST_UPDATE_REQUEST_MILLIS, value)

    override val addTileAsked: Flow<Boolean> = getCatching(AppSettings.Key.ADD_TILE_ASKED, AppSettings.Default.ADD_TILE_ASKED)
    override suspend fun setAddTileAsked(value: Boolean) = setValue(AppSettings.Key.ADD_TILE_ASKED, value)

    private fun <T> getCatching(key: Preferences.Key<T>, default: T): Flow<T> = dataStore.data.catch { cause ->
        if (cause is IOException) {
            XLog.e(this@AppSettingsImpl.getLog("getCatching [${key.name}]"), cause)
            emit(emptyPreferences())
        } else {
            XLog.e(this@AppSettingsImpl.getLog("getCatching [${key.name}]"), cause)
            throw cause
        }
    }.map { it[key] ?: default }

    private suspend fun <T> setValue(key: Preferences.Key<T>, value: T) {
        try {
            dataStore.edit { it[key] = value }
        } catch (cause: IOException) {
            XLog.e(this@AppSettingsImpl.getLog("setValue [${key.name}]"), cause)
        }
    }
}