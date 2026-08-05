package info.dvkr.screenstream.vnc.settings

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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.IOException

internal class VncSettingsImpl(context: Context) : VncSettings {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("VNC_settings") } // Sync name with backup config
    )

    override val data: StateFlow<VncSettings.Data> = dataStore.data
        .map { preferences -> preferences.toVncSettings() }
        .catch { cause ->
            XLog.e(this@VncSettingsImpl.getLog("getCatching"), cause)
            if (cause is IOException) emit(VncSettings.Data()) else throw cause
        }
        .stateIn(
            CoroutineScope(Dispatchers.IO),
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
            VncSettings.Data()
        )

    override suspend fun updateData(transform: VncSettings.Data.() -> VncSettings.Data) = withContext(NonCancellable + Dispatchers.IO) {
        dataStore.edit { preferences ->
            val newSettings = transform.invoke(preferences.toVncSettings())

            preferences.apply {
                clear()

                if (newSettings.keepAwake != VncSettings.Default.KEEP_AWAKE)
                    set(VncSettings.Key.KEEP_AWAKE, newSettings.keepAwake)

                if (newSettings.stopOnSleep != VncSettings.Default.STOP_ON_SLEEP)
                    set(VncSettings.Key.STOP_ON_SLEEP, newSettings.stopOnSleep)

                if (newSettings.stopOnConfigurationChange != VncSettings.Default.STOP_ON_CONFIGURATION_CHANGE)
                    set(VncSettings.Key.STOP_ON_CONFIGURATION_CHANGE, newSettings.stopOnConfigurationChange)

                if (newSettings.mode != VncSettings.Default.MODE)
                    set(VncSettings.Key.MODE, newSettings.mode.name)

                if (newSettings.serverPort != VncSettings.Default.SERVER_PORT)
                    set(VncSettings.Key.SERVER_PORT, newSettings.serverPort)

                if (newSettings.reverseConnectHost != VncSettings.Default.REVERSE_CONNECT_HOST)
                    set(VncSettings.Key.REVERSE_CONNECT_HOST, newSettings.reverseConnectHost)

                if (newSettings.reverseConnectPort != VncSettings.Default.REVERSE_CONNECT_PORT)
                    set(VncSettings.Key.REVERSE_CONNECT_PORT, newSettings.reverseConnectPort)

                if (newSettings.reconnectDelaySeconds != VncSettings.Default.RECONNECT_DELAY_SECONDS)
                    set(VncSettings.Key.RECONNECT_DELAY_SECONDS, newSettings.reconnectDelaySeconds)

                if (newSettings.maxFPS != VncSettings.Default.MAX_FPS)
                    set(VncSettings.Key.MAX_FPS, newSettings.maxFPS)

                if (newSettings.zlibEncoding != VncSettings.Default.ZLIB_ENCODING)
                    set(VncSettings.Key.ZLIB_ENCODING, newSettings.zlibEncoding)

                if (newSettings.interfaceFilter != VncSettings.Default.INTERFACE_FILTER)
                    set(VncSettings.Key.INTERFACE_FILTER, newSettings.interfaceFilter)

                if (newSettings.addressFilter != VncSettings.Default.ADDRESS_FILTER)
                    set(VncSettings.Key.ADDRESS_FILTER, newSettings.addressFilter)

                if (newSettings.enableIPv4 != VncSettings.Default.ENABLE_IPV4)
                    set(VncSettings.Key.ENABLE_IPV4, newSettings.enableIPv4)

                if (newSettings.enableIPv6 != VncSettings.Default.ENABLE_IPV6)
                    set(VncSettings.Key.ENABLE_IPV6, newSettings.enableIPv6)
            }
        }
        Unit
    }

    private fun Preferences.toVncSettings(): VncSettings.Data = VncSettings.Data(
        keepAwake = this[VncSettings.Key.KEEP_AWAKE] ?: VncSettings.Default.KEEP_AWAKE,
        stopOnSleep = this[VncSettings.Key.STOP_ON_SLEEP] ?: VncSettings.Default.STOP_ON_SLEEP,
        stopOnConfigurationChange = this[VncSettings.Key.STOP_ON_CONFIGURATION_CHANGE] ?: VncSettings.Default.STOP_ON_CONFIGURATION_CHANGE,
        mode = runCatching {
            VncSettings.Values.Mode.valueOf(this[VncSettings.Key.MODE] ?: VncSettings.Default.MODE.name)
        }.getOrDefault(VncSettings.Default.MODE),
        serverPort = this[VncSettings.Key.SERVER_PORT] ?: VncSettings.Default.SERVER_PORT,
        reverseConnectHost = this[VncSettings.Key.REVERSE_CONNECT_HOST] ?: VncSettings.Default.REVERSE_CONNECT_HOST,
        reverseConnectPort = this[VncSettings.Key.REVERSE_CONNECT_PORT] ?: VncSettings.Default.REVERSE_CONNECT_PORT,
        reconnectDelaySeconds = this[VncSettings.Key.RECONNECT_DELAY_SECONDS] ?: VncSettings.Default.RECONNECT_DELAY_SECONDS,
        maxFPS = this[VncSettings.Key.MAX_FPS] ?: VncSettings.Default.MAX_FPS,
        zlibEncoding = this[VncSettings.Key.ZLIB_ENCODING] ?: VncSettings.Default.ZLIB_ENCODING,
        interfaceFilter = this[VncSettings.Key.INTERFACE_FILTER] ?: VncSettings.Default.INTERFACE_FILTER,
        addressFilter = this[VncSettings.Key.ADDRESS_FILTER] ?: VncSettings.Default.ADDRESS_FILTER,
        enableIPv4 = this[VncSettings.Key.ENABLE_IPV4] ?: VncSettings.Default.ENABLE_IPV4,
        enableIPv6 = this[VncSettings.Key.ENABLE_IPV6] ?: VncSettings.Default.ENABLE_IPV6,
    )
}
