package info.dvkr.screenstream.common.settings

import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import info.dvkr.screenstream.common.module.StreamingModule
import kotlinx.coroutines.flow.Flow

public interface AppSettings {

    public object Key {
        public val STREAMING_MODULE: Preferences.Key<String> = stringPreferencesKey("STREAMING_MODULE")
        public val NIGHT_MODE: Preferences.Key<Int> = intPreferencesKey("NIGHT_MODE")

        public val LAST_UPDATE_REQUEST_MILLIS: Preferences.Key<Long> = longPreferencesKey("LAST_UPDATE_REQUEST_MILLIS")
    }

    public object Default {
        public val STREAMING_MODULE: StreamingModule.Id = StreamingModule.Id("_NONE_")
        public var NIGHT_MODE: Int = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) 3 else -1

        public const val LAST_IAU_REQUEST_TIMESTAMP: Long = 0L
    }

    public val streamingModuleFlow: Flow<StreamingModule.Id>
    public suspend fun setStreamingModule(value: StreamingModule.Id)

    public val nightModeFlow: Flow<Int>
    public suspend fun setNightMode(value: Int)


    public val lastUpdateRequestMillisFlow: Flow<Long>
    public suspend fun setLastUpdateRequestMillis(value: Long)
}