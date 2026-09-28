package info.dvkr.screenstream

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.AndroidPrinter
import com.elvishew.xlog.printer.Printer
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.notification.NotificationHelper
import info.dvkr.screenstream.notification.NotificationHelperImpl
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinApplication
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.startKoin
import org.koin.plugin.module.dsl.viewModel

@KoinApplication
public abstract class BaseApp : Application() {

    protected open fun configureReleaseLogger(builder: LogConfiguration.Builder): Unit = Unit

    override fun onCreate() {
        super.onCreate()

        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        if (isDebuggable) {
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) detectImplicitUriPermissionGrant()
                    }
                    .penaltyLog()
                    .build()
            )
        }

        initLogger(isDebuggable)

        startKoin<BaseApp> {
            allowOverride(false)
            androidContext(this@BaseApp)
            modules(module {
                single<AdMob>()
                single<AppStreamingAnalytics>() withOptions {
                    bind<StreamingAnalytics>()
                    createdAtStart()
                }
                single<NotificationHelperImpl>() withOptions { bind<NotificationHelper>() }
                viewModel<SingleActivityViewModel>()
            })
        }
    }

    private fun initLogger(isDebuggable: Boolean) {
        val logConfiguration = LogConfiguration.Builder()
            .tag("SSApp")
            .apply { if (isDebuggable.not()) configureReleaseLogger(this) }
            .build()
        val printers = if (isDebuggable) arrayOf<Printer>(AndroidPrinter()) else emptyArray()

        XLog.init(logConfiguration, *printers)
    }
}
