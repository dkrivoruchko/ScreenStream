package info.dvkr.screenstream.di

import com.elvishew.xlog.XLog
import com.ironz.binaryprefs.BinaryPreferencesBuilder
import com.ironz.binaryprefs.Preferences
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsImpl
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.service.helper.NotificationHelper
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.bind
import org.koin.dsl.module

val baseKoinModule = module {

    single<Preferences> {
        BinaryPreferencesBuilder(androidApplication())
            .supportInterProcess(true)
            .exceptionHandler { ex -> XLog.e(ex) }
            .build()
    }

    single<Settings> { SettingsImpl(get()) } bind SettingsReadOnly::class

    single { NotificationHelper(androidApplication()) }
}