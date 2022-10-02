package info.dvkr.screenstream.common.settings

import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow

interface AppSettings {

    object Key {
        val NIGHT_MODE = intPreferencesKey("NIGHT_MODE")
        val KEEP_AWAKE = booleanPreferencesKey("KEEP_AWAKE")
        val STOP_ON_SLEEP = booleanPreferencesKey("TOP_ON_SLEEP")
        val START_ON_BOOT = booleanPreferencesKey("START_ON_BOOT")
        val AUTO_START_STOP = booleanPreferencesKey("AUTO_START_STOP")

        val LOGGING_VISIBLE = booleanPreferencesKey("LOGGING_VISIBLE")

        val LAST_UPDATE_REQUEST_MILLIS = longPreferencesKey("LAST_UPDATE_REQUEST_MILLIS")
        val ADD_TILE_ASKED = booleanPreferencesKey("ADD_TILE_ASKED")
    }

    object Default {
        var NIGHT_MODE = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) 3 else -1
        const val KEEP_AWAKE = false
        const val STOP_ON_SLEEP = false
        const val START_ON_BOOT = false
        const val AUTO_START_STOP = false

        const val LOGGING_VISIBLE = false

        const val LAST_IAU_REQUEST_TIMESTAMP = 0L
        const val ADD_TILE_ASKED = false
    }

    val nightModeFlow: Flow<Int>
    suspend fun setNightMode(value: Int)

    val keepAwakeFlow: Flow<Boolean>
    suspend fun setKeepAwake(value: Boolean)

    val stopOnSleepFlow: Flow<Boolean>
    suspend fun setStopOnSleep(value: Boolean)

    val startOnBootFlow: Flow<Boolean>
    suspend fun setStartOnBoot(value: Boolean)

    val autoStartStopFlow: Flow<Boolean>
    suspend fun setAutoStartStop(value: Boolean)


    val loggingVisibleFlow: Flow<Boolean>
    suspend fun setLoggingVisible(value: Boolean)


    val lastUpdateRequestMillisFlow: Flow<Long>
    suspend fun setLastUpdateRequestMillis(value: Long)

    val addTileAsked: Flow<Boolean>
    suspend fun setAddTileAsked(value: Boolean)
}