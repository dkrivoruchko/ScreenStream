package info.dvkr.screenstream

import android.app.Application
import android.os.StrictMode
import com.squareup.leakcanary.LeakCanary
import info.dvkr.screenstream.di.koinModule
import info.dvkr.screenstream.domain.utils.Utils
import org.koin.android.ext.android.startKoin
import org.koin.log.Logger
import timber.log.Timber
import timber.log.Timber.DebugTree


class ScreenStreamApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Set up Timber
        Timber.plant(DebugTree())
        Timber.w("[${Utils.getLogPrefix(this)}] onCreate: Start")

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread: Thread, throwable: Throwable ->
            Timber.e(throwable, "Uncaught throwable in thread ${thread.name}")
            defaultHandler.uncaughtException(thread, throwable)
        }

        // Turning on strict mode
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
//              .penaltyDialog()
                .build()
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )

        // Set up LeakCanary
        if (LeakCanary.isInAnalyzerProcess(this)) return
        LeakCanary.install(this)

        // Set up DI
        startKoin(this, listOf(koinModule), logger = object : Logger {
            override fun debug(msg: String) = Timber.d(msg)
            override fun err(msg: String) = Timber.e(msg)
            override fun log(msg: String) = Timber.d(msg)
        })

        Timber.w("[${Utils.getLogPrefix(this)}] onCreate: End")
    }
}