package info.dvkr.screenstream

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import com.elvishew.xlog.LogConfiguration
import com.jakewharton.processphoenix.ProcessPhoenix
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.notification.NotificationHelper
import info.dvkr.screenstream.logger.AppLogger
import info.dvkr.screenstream.notification.NotificationHelperImpl
import info.dvkr.screenstream.ui.tabs.settings.SettingsTabViewModel
import info.dvkr.screenstream.ui.tabs.settings.app.AppModuleSettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

public abstract class BaseApp : Application() {

    public abstract fun configureLogger(builder: LogConfiguration.Builder)

    public abstract val streamingModules: Array<Module>

    override fun onCreate() {
        super.onCreate()

        if (ProcessPhoenix.isPhoenixProcess(this)) return

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .permitDiskReads()
                    .permitDiskWrites()
                    .penaltyLog()
                    .build()
            )

            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectFileUriExposure()
                    .detectCleartextNetwork()
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detectContentUriWithoutPermission()
//                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detectUntaggedSockets()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) detectCredentialProtectedWhileLocked()
//                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) detectIncorrectContextUse()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) detectUnsafeIntentLaunch()
                    }
                    .penaltyLog()
                    .build()
            )
        }

        AppLogger.init(this, ::configureLogger)

        val defaultModule = module {
            single(createdAtStart = true) { AdMob(get()) }
            single { NotificationHelperImpl(get()) } bind (NotificationHelper::class)
            single { AppModuleSettings() } bind (ModuleSettings::class)
            viewModel { SettingsTabViewModel(get(), get(), get()) }
        }

        startKoin {
            allowOverride(false)
            androidContext(this@BaseApp)
            modules(defaultModule, *streamingModules)
        }
    }
}