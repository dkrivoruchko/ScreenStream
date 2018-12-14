package info.dvkr.screenstream.di

import com.ironz.binaryprefs.BinaryPreferencesBuilder
import com.ironz.binaryprefs.Preferences
import info.dvkr.screenstream.data.settings.SettingsImpl
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module.module
import timber.log.Timber

val baseKoinModule = module {

    single<Preferences> {
        BinaryPreferencesBuilder(androidApplication())
            .supportInterProcess(true)
            .exceptionHandler { ex -> Timber.e(ex) }
            .build()
    }

    single { SettingsImpl(get()) as Settings } bind SettingsReadOnly::class
}