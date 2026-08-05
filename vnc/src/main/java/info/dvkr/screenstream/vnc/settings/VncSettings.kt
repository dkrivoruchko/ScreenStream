package info.dvkr.screenstream.vnc.settings

import androidx.annotation.IntDef
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.StateFlow

public interface VncSettings {

    public object Key {
        public val KEEP_AWAKE: Preferences.Key<Boolean> = booleanPreferencesKey("KEEP_AWAKE")
        public val STOP_ON_SLEEP: Preferences.Key<Boolean> = booleanPreferencesKey("STOP_ON_SLEEP")
        public val STOP_ON_CONFIGURATION_CHANGE: Preferences.Key<Boolean> = booleanPreferencesKey("STOP_ON_CONFIGURATION_CHANGE")

        public val MODE: Preferences.Key<String> = stringPreferencesKey("MODE")

        public val SERVER_PORT: Preferences.Key<Int> = intPreferencesKey("SERVER_PORT")
        public val REVERSE_CONNECT_HOST: Preferences.Key<String> = stringPreferencesKey("REVERSE_CONNECT_HOST")
        public val REVERSE_CONNECT_PORT: Preferences.Key<Int> = intPreferencesKey("REVERSE_CONNECT_PORT")
        public val RECONNECT_DELAY_SECONDS: Preferences.Key<Int> = intPreferencesKey("RECONNECT_DELAY_SECONDS")

        public val MAX_FPS: Preferences.Key<Int> = intPreferencesKey("MAX_FPS")
        public val ZLIB_ENCODING: Preferences.Key<Boolean> = booleanPreferencesKey("ZLIB_ENCODING")

        public val INTERFACE_FILTER: Preferences.Key<Int> = intPreferencesKey("INTERFACE_FILTER")
        public val ADDRESS_FILTER: Preferences.Key<Int> = intPreferencesKey("ADDRESS_FILTER")
        public val ENABLE_IPV4: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_IPV4")
        public val ENABLE_IPV6: Preferences.Key<Boolean> = booleanPreferencesKey("ENABLE_IPV6")
    }

    public object Default {
        public const val KEEP_AWAKE: Boolean = true
        public const val STOP_ON_SLEEP: Boolean = false
        public const val STOP_ON_CONFIGURATION_CHANGE: Boolean = false

        public val MODE: Values.Mode = Values.Mode.SERVER

        public const val SERVER_PORT: Int = 5900
        public const val REVERSE_CONNECT_HOST: String = ""
        public const val REVERSE_CONNECT_PORT: Int = 5900
        public const val RECONNECT_DELAY_SECONDS: Int = 5

        public const val MAX_FPS: Int = 15
        public const val ZLIB_ENCODING: Boolean = true

        public const val INTERFACE_FILTER: Int = Values.INTERFACE_WIFI or Values.INTERFACE_ETHERNET
        public const val ADDRESS_FILTER: Int = Values.ADDRESS_PRIVATE
        public const val ENABLE_IPV4: Boolean = true
        public const val ENABLE_IPV6: Boolean = false
    }

    public object Values {
        public enum class Mode { SERVER, CLIENT }

        @IntDef(flag = true, value = [INTERFACE_WIFI, INTERFACE_MOBILE, INTERFACE_ETHERNET, INTERFACE_VPN])
        @Retention(AnnotationRetention.SOURCE)
        public annotation class InterfaceMask

        public const val INTERFACE_ALL: Int = 0
        public const val INTERFACE_WIFI: Int = 1
        public const val INTERFACE_MOBILE: Int = 1 shl 1
        public const val INTERFACE_ETHERNET: Int = 1 shl 2
        public const val INTERFACE_VPN: Int = 1 shl 3

        @IntDef(flag = true, value = [ADDRESS_PRIVATE, ADDRESS_LOCALHOST, ADDRESS_PUBLIC])
        @Retention(AnnotationRetention.SOURCE)
        public annotation class AddressMask

        public const val ADDRESS_ALL: Int = 0
        public const val ADDRESS_PRIVATE: Int = 1
        public const val ADDRESS_LOCALHOST: Int = 1 shl 1
        public const val ADDRESS_PUBLIC: Int = 1 shl 2
    }

    @Immutable
    public data class Data(
        public val keepAwake: Boolean = Default.KEEP_AWAKE,
        public val stopOnSleep: Boolean = Default.STOP_ON_SLEEP,
        public val stopOnConfigurationChange: Boolean = Default.STOP_ON_CONFIGURATION_CHANGE,

        public val mode: Values.Mode = Default.MODE,

        public val serverPort: Int = Default.SERVER_PORT,
        public val reverseConnectHost: String = Default.REVERSE_CONNECT_HOST,
        public val reverseConnectPort: Int = Default.REVERSE_CONNECT_PORT,
        public val reconnectDelaySeconds: Int = Default.RECONNECT_DELAY_SECONDS,

        public val maxFPS: Int = Default.MAX_FPS,
        public val zlibEncoding: Boolean = Default.ZLIB_ENCODING,

        public val interfaceFilter: Int = Default.INTERFACE_FILTER,
        public val addressFilter: Int = Default.ADDRESS_FILTER,
        public val enableIPv4: Boolean = Default.ENABLE_IPV4,
        public val enableIPv6: Boolean = Default.ENABLE_IPV6,
    )

    public val data: StateFlow<Data>
    public suspend fun updateData(transform: Data.() -> Data)
}
