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
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

public abstract class BaseApp : Application() {

    protected open fun configureReleaseLogger(builder: LogConfiguration.Builder): Unit = Unit

    public abstract val streamingModules: Array<Module>

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
                    }
                    .penaltyLog()
                    .build()
            )
        }

        initLogger(isDebuggable)

        val defaultModule = module {
            single(createdAtStart = true) { AdMob(get()) }
            single(createdAtStart = true) { AppStreamingAnalytics(get()) } bind (StreamingAnalytics::class)
            single { NotificationHelperImpl(get()) } bind (NotificationHelper::class)
        }

        startKoin {
            allowOverride(false)
            androidContext(this@BaseApp)
            modules(defaultModule, *streamingModules)
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
