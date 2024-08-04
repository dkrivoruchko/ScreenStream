package info.dvkr.screenstream.common.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import info.dvkr.screenstream.common.module.StreamingModule
import kotlinx.coroutines.flow.StateFlow

public interface AppSettings {

    public object Key {
        public val STREAMING_MODULE: Preferences.Key<String> = stringPreferencesKey("STREAMING_MODULE")
        public val NIGHT_MODE: Preferences.Key<Int> = intPreferencesKey("NIGHT_MODE")
        public val DYNAMIC_THEME: Preferences.Key<Boolean> = booleanPreferencesKey("DYNAMIC_THEME")
    }

    public object Default {
        public val STREAMING_MODULE: StreamingModule.Id = StreamingModule.Id("_NONE_")
        public const val NIGHT_MODE: Int = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
        public const val DYNAMIC_THEME: Boolean = false
    }

    @Immutable
    public data class Data(
        public val streamingModule: StreamingModule.Id = Default.STREAMING_MODULE,
        public val nightMode: Int = Default.NIGHT_MODE,
        public val dynamicTheme: Boolean = Default.DYNAMIC_THEME,
    )

    public val data: StateFlow<Data>
    public suspend fun updateData(transform: Data.() -> Data)
}