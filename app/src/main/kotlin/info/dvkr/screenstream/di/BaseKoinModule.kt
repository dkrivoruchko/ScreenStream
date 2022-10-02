package info.dvkr.screenstream.di

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elvishew.xlog.XLog
import com.ironz.binaryprefs.BinaryPreferencesBuilder
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.common.settings.AppSettingsImpl
import info.dvkr.screenstream.di.migration.SettingsDataMigration
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.settings.MjpegSettingsImpl
import info.dvkr.screenstream.service.helper.NotificationHelper
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

val baseKoinModule = module {

    single<com.ironz.binaryprefs.Preferences> {
        BinaryPreferencesBuilder(androidApplication())
            .supportInterProcess(true)
            .memoryCacheMode(BinaryPreferencesBuilder.MemoryCacheMode.EAGER)
            .exceptionHandler { ex -> XLog.e(ex) }
            .build()
    }

    single {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
            migrations = listOf(SettingsDataMigration(androidApplication(), get())),
            produceFile = { androidApplication().preferencesDataStoreFile("user_settings") }
        )
    }

    single<AppSettings> { AppSettingsImpl(get()) }

    single<MjpegSettings> { MjpegSettingsImpl(get()) }

    single { NotificationHelper(androidApplication()) }
}