package info.dvkr.screenstream.di

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.common.settings.AppSettingsImpl
import info.dvkr.screenstream.di.migration.SettingsMigration
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.settings.MjpegSettingsImpl
import info.dvkr.screenstream.service.helper.NotificationHelper
import info.dvkr.screenstream.webrtc.WebRtcEnvironment
import info.dvkr.screenstream.webrtc.WebRtcSettings
import info.dvkr.screenstream.webrtc.WebRtcSettingsImpl
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

val baseKoinModule = module {

    single {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
            migrations = listOf(SettingsMigration()),
            produceFile = { androidApplication().preferencesDataStoreFile("user_settings") }
        )
    }

    single<AppSettings> { AppSettingsImpl(get()) }

    single<MjpegSettings> { MjpegSettingsImpl(get()) }

    single<WebRtcSettings> { WebRtcSettingsImpl(get()) }

    single {
        WebRtcEnvironment(
            androidApplication().packageName,
            BuildConfig.SIGNALING_SERVER,
            "/app/nonce",
            "/app/socket",
            BuildConfig.CLOUD_PROJECT_NUMBER.toLong(),
        )
    }

    single { NotificationHelper(androidApplication()) }

}