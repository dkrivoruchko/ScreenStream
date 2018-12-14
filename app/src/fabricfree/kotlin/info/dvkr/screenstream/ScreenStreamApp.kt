package info.dvkr.screenstream

import android.app.Application
import android.util.Log
import info.dvkr.screenstream.di.baseKoinModule
import org.koin.android.ext.android.startKoin
import org.koin.log.Logger
import timber.log.Timber

class ScreenStreamApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Set up Timber
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == Log.VERBOSE || priority == Log.DEBUG) return
            }
        })

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread: Thread, throwable: Throwable ->
            Timber.e(throwable, "Uncaught throwable in thread ${thread.name}")
            defaultHandler.uncaughtException(thread, throwable)
        }

//        AndroidThreeTen.init(this)

        // Set up DI
        startKoin(this,
            listOf(baseKoinModule),
            loadProperties = true,
            logger = object : Logger {
                override fun debug(msg: String) = Timber.tag("Koin").d(msg)
                override fun err(msg: String) = Timber.tag("Koin").e(msg)
                override fun info(msg: String) = Timber.tag("Koin").i(msg)
            })
    }
}