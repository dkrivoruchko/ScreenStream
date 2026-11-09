package io.screenstream.core.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.serialization.json.Json
import java.io.File

/** One owner per path; seed before construction and inspect only after admitted writes settle. */
internal class CoreSettingsTestContext(base: Context, private val directory: File) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory
}

internal suspend fun seedCoreSettings(context: Context, json: String) {
    val file = context.preferencesDataStoreFile("streaming_settings")
    val directory = checkNotNull(file.parentFile)
    check(directory.mkdirs() || directory.isDirectory)
    file.outputStream().use { PreferencesFileSerializer.writeTo(preferencesOf(stringPreferencesKey("DATA") to json), it) }
}

internal suspend fun readCoreSettings(context: Context): StreamingSettingsStore.Data =
    context.preferencesDataStoreFile("streaming_settings").inputStream().use {
        Json.decodeFromString(checkNotNull(PreferencesFileSerializer.readFrom(it)[stringPreferencesKey("DATA")]))
    }
