package info.dvkr.screenstream.mjpeg.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

internal val mjpegMigrations: List<DataMigration<Preferences>> = listOf(
    object : DataMigration<Preferences> {

        private val OLD_USE_WIFI_ONLY = booleanPreferencesKey("USE_WIFI_ONLY")
        private val OLD_ENABLE_LOCAL_HOST = booleanPreferencesKey("ENABLE_LOCAL_HOST")
        private val OLD_LOCAL_HOST_ONLY = booleanPreferencesKey("LOCAL_HOST_ONLY")

        override suspend fun shouldMigrate(currentData: Preferences): Boolean {
            return currentData[OLD_USE_WIFI_ONLY] != null ||
                    currentData[OLD_ENABLE_LOCAL_HOST] != null ||
                    currentData[OLD_LOCAL_HOST_ONLY] != null
        }

        override suspend fun migrate(currentData: Preferences): Preferences {
            val mutablePreferences = currentData.toMutablePreferences()

            currentData[OLD_USE_WIFI_ONLY]?.let { useWifiOnly ->
                mutablePreferences[MjpegSettings.Key.INTERFACE_FILTER] = if (useWifiOnly) {
                    MjpegSettings.Values.INTERFACE_WIFI
                } else {
                    0  // All interfaces (no filter)
                }
                mutablePreferences.remove(OLD_USE_WIFI_ONLY)
            }

            if (currentData[OLD_LOCAL_HOST_ONLY] != null || currentData[OLD_ENABLE_LOCAL_HOST] != null) {
                val localHostOnly = currentData[OLD_LOCAL_HOST_ONLY] ?: false
                val enableLocalHost = currentData[OLD_ENABLE_LOCAL_HOST] ?: false

                val addressFilter = when {
                    localHostOnly -> MjpegSettings.Values.ADDRESS_LOCALHOST
                    !enableLocalHost -> MjpegSettings.Values.ADDRESS_PRIVATE or MjpegSettings.Values.ADDRESS_PUBLIC
                    else -> 0  // All addresses (no filter)
                }
                mutablePreferences[MjpegSettings.Key.ADDRESS_FILTER] = addressFilter

                mutablePreferences.remove(OLD_LOCAL_HOST_ONLY)
                mutablePreferences.remove(OLD_ENABLE_LOCAL_HOST)
            }

            return mutablePreferences.toPreferences()
        }

        override suspend fun cleanUp() {
            // No cleanup needed
        }
    }
)