package info.dvkr.screenstream

import android.app.Application
import com.elvishew.xlog.flattener.ClassicFlattener
import com.elvishew.xlog.printer.file.FilePrinter
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.di.baseKoinModule
import info.dvkr.screenstream.logging.DateSuffixFileNameGenerator
import info.dvkr.screenstream.logging.getLogFolder
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

abstract class BaseApp : Application() {

    protected val settingsReadOnly: SettingsReadOnly by inject()
    protected val filePrinter: FilePrinter by lazy {
        FilePrinter.Builder(getLogFolder())
            .fileNameGenerator(DateSuffixFileNameGenerator(this@BaseApp.hashCode().toString()))
            .cleanStrategy(FileLastModifiedCleanStrategy(86400000)) // One day
            .flattener(ClassicFlattener())
            .build()
    }

    abstract fun initLogger()

    override fun onCreate() {
        super.onCreate()

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

        initLogger()
    }
}