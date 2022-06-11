package info.dvkr.screenstream

import android.app.Application
import com.elvishew.xlog.flattener.ClassicFlattener
import com.elvishew.xlog.printer.file.FilePrinter
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy
import info.dvkr.screenstream.di.baseKoinModule
import info.dvkr.screenstream.logging.DateSuffixFileNameGenerator
import info.dvkr.screenstream.logging.getLogFolder
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

abstract class BaseApp : Application() {

    protected val filePrinter: FilePrinter by lazy {
        FilePrinter.Builder(getLogFolder())
            .fileNameGenerator(DateSuffixFileNameGenerator(this@BaseApp.hashCode().toString()))
            .cleanStrategy(FileLastModifiedCleanStrategy(86400000)) // One day
            .flattener(ClassicFlattener())
            .build()
    }

    val lastAdLoadTimeMap: MutableMap<String, Long> = mutableMapOf()

    abstract fun initLogger()

    override fun onCreate() {
        super.onCreate()

        initLogger()

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@BaseApp)
            modules(baseKoinModule)
        }

//        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
//        Thread.setDefaultUncaughtExceptionHandler { thread: Thread, throwable: Throwable ->
//            XLog.e("Uncaught throwable in thread ${thread.name}", throwable)
//            defaultHandler?.uncaughtException(thread, throwable)
//        }

    }

    internal val sharedPreferences by lazy(LazyThreadSafetyMode.NONE) {
        getSharedPreferences("logging.xml", MODE_PRIVATE)
    }

    internal var isLoggingOn: Boolean
        get() = sharedPreferences.getBoolean(LOGGING_ON_KEY, false)
        set(value) {
            sharedPreferences.edit().putBoolean(LOGGING_ON_KEY, value).commit()
        }

    internal companion object {
        const val LOGGING_ON_KEY = "loggingOn"
    }
}