package info.dvkr.screenstream.common.settings

import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow

interface AppSettings {

    object Key {
        val STREAM_MODE = intPreferencesKey("STREAM_MODE")
        val NIGHT_MODE = intPreferencesKey("NIGHT_MODE")
        val KEEP_AWAKE = booleanPreferencesKey("KEEP_AWAKE")
        val STOP_ON_SLEEP = booleanPreferencesKey("TOP_ON_SLEEP")

        val LOGGING_VISIBLE = booleanPreferencesKey("LOGGING_VISIBLE")

        val LAST_UPDATE_REQUEST_MILLIS = longPreferencesKey("LAST_UPDATE_REQUEST_MILLIS")
    }

    object Default {
        const val STREAM_MODE = Values.STREAM_MODE_WEBRTC
        var NIGHT_MODE = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) 3 else -1
        const val KEEP_AWAKE = false
        const val STOP_ON_SLEEP = false

        const val LOGGING_VISIBLE = false

        const val LAST_IAU_REQUEST_TIMESTAMP = 0L
    }

    object Values {
        const val STREAM_MODE_WEBRTC = 0
        const val STREAM_MODE_MJPEG = 1
    }

    val streamModeFlow: Flow<Int>
    suspend fun setStreamMode(value: Int)

    val nightModeFlow: Flow<Int>
    suspend fun setNightMode(value: Int)

    val keepAwakeFlow: Flow<Boolean>
    suspend fun setKeepAwake(value: Boolean)

    val stopOnSleepFlow: Flow<Boolean>
    suspend fun setStopOnSleep(value: Boolean)


    val loggingVisibleFlow: Flow<Boolean>
    suspend fun setLoggingVisible(value: Boolean)


    val lastUpdateRequestMillisFlow: Flow<Long>
    suspend fun setLastUpdateRequestMillis(value: Long)
}