@file:JvmName("DataStoreUtils")

package info.dvkr.screenstream.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.elvishew.xlog.XLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

public fun <T> DataStore<Preferences>.getCatching(key: Preferences.Key<T>, default: T): Flow<T> = data.catch { cause ->
    if (cause is IOException) {
        XLog.e(getLog("getCatching [${key.name}]"), cause)
        emit(emptyPreferences())
    } else {
        XLog.e(getLog("getCatching [${key.name}]"), cause)
        throw cause
    }
}.map { it[key] ?: default }

public suspend fun <T> DataStore<Preferences>.setValue(key: Preferences.Key<T>, value: T) {
    try {
        edit { it[key] = value }
    } catch (cause: IOException) {
        XLog.e(getLog("setValue [${key.name}]"), cause)
    }
}