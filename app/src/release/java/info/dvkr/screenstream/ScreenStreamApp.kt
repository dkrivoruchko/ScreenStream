package info.dvkr.screenstream

import android.app.Application
import android.util.Log
import com.crashlytics.android.Crashlytics
import info.dvkr.screenstream.di.koinModule
import io.fabric.sdk.android.Fabric
import org.koin.android.ext.android.startKoin
import timber.log.Timber


class ScreenStreamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Fabric.with(this, Crashlytics())

        // Set up Timber
        Timber.plant(CrashReportingTree())
        Timber.w("[${Thread.currentThread().name}] onCreate: Start")

        // Set up DI
        startKoin(this, listOf(koinModule))

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