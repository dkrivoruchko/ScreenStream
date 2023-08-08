package info.dvkr.screenstream.di.migration

import android.os.Build
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.settings.AppSettings

class SettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val shouldMigrate = Build.MANUFACTURER in listOf("OnePlus", "OPPO") && currentData.get(AppSettings.Key.KEEP_AWAKE) ?: false
        XLog.i(getLog("shouldMigrate", "shouldMigrate: $shouldMigrate"))
        return shouldMigrate
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        XLog.i(getLog("migrate"))
        val currentMutablePrefs = currentData.toMutablePreferences()
        // "OnePlus" and "OPPO" devices kill process when screen goes off if WakeLock is hold by the app.
        // This will disable Keep Awake setting for this manufactures.
        // To allow to hold WakeLock when screen is off in system application settings enable "Allow background activity"
        currentMutablePrefs.set(AppSettings.Key.KEEP_AWAKE, false)
        return currentMutablePrefs.toPreferences()
    }

    override suspend fun cleanUp() {
        XLog.i(getLog("cleanUp"))
    }
}