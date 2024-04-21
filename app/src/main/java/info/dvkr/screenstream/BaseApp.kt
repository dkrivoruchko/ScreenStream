package info.dvkr.screenstream

import android.app.Application
import android.os.Build
import android.os.StrictMode
import com.elvishew.xlog.LogConfiguration
import com.jakewharton.processphoenix.ProcessPhoenix
import info.dvkr.screenstream.logger.AppLogger
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.ksp.generated.defaultModule

public abstract class BaseApp : Application() {

    public abstract fun configureLogger(builder: LogConfiguration.Builder)

    public abstract val streamingModules: Array<Module>

    override fun onCreate() {
        super.onCreate()

        if (ProcessPhoenix.isPhoenixProcess(this)) return

        if (BuildConfig.DEBUG) {
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

        startKoin {
            allowOverride(false)
            androidContext(this@BaseApp)
            modules(defaultModule, *streamingModules)
        }
    }
}