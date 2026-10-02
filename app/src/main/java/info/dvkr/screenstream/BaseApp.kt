package info.dvkr.screenstream

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.AndroidPrinter
import com.elvishew.xlog.printer.Printer
import io.screenstream.streaming.logV
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinApplication
import org.koin.core.logger.Level
import org.koin.core.logger.Logger
import org.koin.plugin.module.dsl.startKoin

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
            if (isDebuggable) logger(KoinLogger)
            allowOverride(false)
            androidContext(this@BaseApp)
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

    private object KoinLogger : Logger(Level.DEBUG) {
        override fun display(level: Level, msg: String) {
            logV(tag = "display", msg = "Koin [$level] $msg")
        }
    }
}
