package info.dvkr.screenstream

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.crashlytics.android.Crashlytics
import com.crashlytics.android.core.CrashlyticsCore
import com.squareup.leakcanary.LeakCanary
import info.dvkr.screenstream.di.KoinModule
import io.fabric.sdk.android.Fabric
import org.koin.android.ext.android.startAndroidContext
import timber.log.Timber
import timber.log.Timber.DebugTree


class ScreenStreamApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Set up Crashlytics, disabled for debug builds
        val crashlyticsKit = Crashlytics.Builder()
                .core(CrashlyticsCore.Builder().disabled(BuildConfig.DEBUG).build())
                .build()

        // Initialize Fabric with the debug-disabled crashlytics.
        Fabric.with(this, crashlyticsKit)

        // Set up Timber
        if (BuildConfig.DEBUG) Timber.plant(DebugTree()) else Timber.plant(CrashReportingTree())

        Timber.w("[${Thread.currentThread().name}] onCreate: Start")

        // Turning on strict mode
        if (BuildConfig.DEBUG_MODE) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .penaltyDialog()
                    .build())

            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build())
        }

        // Set up LeakCanary
        if (LeakCanary.isInAnalyzerProcess(this)) return
        LeakCanary.install(this)

        // Set up DI
        startAndroidContext(this, listOf(KoinModule()))

        Timber.w("[${Thread.currentThread().name}] onCreate: End")
    }

    private class CrashReportingTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == Log.VERBOSE || priority == Log.DEBUG) return
            Crashlytics.log(priority, tag, message)
            t?.let { Crashlytics.logException(it) }
        }
    }
}