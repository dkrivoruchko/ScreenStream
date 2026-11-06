package io.screenstream.mjpeg

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File

/** One application files directory per store; a production store is never reopened at the same path. */
internal class IsolatedSettingsContext(base: Context, private val directory: File) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory
}

/** Seed only before constructing the production store. No second DataStore owns this file. */
internal suspend fun seedSettings(context: Context, json: String, name: String = "mjpeg") {
    val file = context.preferencesDataStoreFile(name)
    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
    file.outputStream().use { PreferencesFileSerializer.writeTo(preferencesOf(stringPreferencesKey("DATA") to json), it) }
}

/** Read after the public update returned and all test writers settled; no file mutation or second owner. */
internal suspend fun readSavedSettingsJson(context: Context): String = context.preferencesDataStoreFile("mjpeg").inputStream().use {
    checkNotNull(PreferencesFileSerializer.readFrom(it)[stringPreferencesKey("DATA")])
}
