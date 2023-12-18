package info.dvkr.screenstream

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.elvishew.xlog.XLog
import com.elvishew.xlog.flattener.ClassicFlattener
import com.elvishew.xlog.printer.file.FilePrinter
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy
import info.dvkr.screenstream.common.CommonKoinModule
import info.dvkr.screenstream.common.StreamingModulesManager
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.logging.DateSuffixFileNameGenerator
import info.dvkr.screenstream.logging.getLogFolder
import info.dvkr.screenstream.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.module.Module
import org.koin.ksp.generated.defaultModule
import org.koin.ksp.generated.module

public abstract class BaseApp : Application() {

    public abstract fun initLogger()

    public abstract fun initAd()
    public abstract val isAdEnabled: Boolean

    public abstract val streamingModules: Array<Module>

    internal val lastAdLoadTimeMap: MutableMap<String, Long> = mutableMapOf()

    private val appSettings: AppSettings by inject(mode = LazyThreadSafetyMode.NONE)
    private val streamingModulesManager: StreamingModulesManager by inject(mode = LazyThreadSafetyMode.NONE)

    override fun onCreate() {
        super.onCreate()

        initLogger()

        initAd()

        startKoin {
            allowOverride(false)
            androidLogger(Level.ERROR)
            androidContext(this@BaseApp)
            modules(defaultModule, CommonKoinModule().module, *streamingModules)
        }

        runBlocking {
            val currentModuleId = appSettings.streamingModuleFlow.first()
            if (streamingModulesManager.hasModule(currentModuleId).not()) {
                val defaultModuleId = streamingModulesManager.getDefaultModuleId()
                appSettings.setStreamingModule(defaultModuleId)
                XLog.i(this@BaseApp.getLog("onCreate", "Set module: $defaultModuleId"))
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                XLog.d(this@BaseApp.getLog("ProcessLifecycleOwner", "onStart"))
            }

            override fun onStop(owner: LifecycleOwner) {
                XLog.d(this@BaseApp.getLog("ProcessLifecycleOwner", "onStop"))
            }
        })
    }

    protected val filePrinter: FilePrinter by lazy {
        FilePrinter.Builder(getLogFolder())
            .fileNameGenerator(DateSuffixFileNameGenerator(this@BaseApp.hashCode().toString()))
            .cleanStrategy(FileLastModifiedCleanStrategy(86400000)) // One day
            .flattener(ClassicFlattener())
            .build()
    }

    internal val sharedPreferences by lazy(LazyThreadSafetyMode.NONE) {
        getSharedPreferences("logging.xml", MODE_PRIVATE)
    }

    internal var isLoggingOn: Boolean
        get() = sharedPreferences.getBoolean(LOGGING_ON_KEY, false)
        @SuppressLint("ApplySharedPref")
        set(value) {
            sharedPreferences.edit().putBoolean(LOGGING_ON_KEY, value).commit()
        }

    internal companion object {
        const val LOGGING_ON_KEY = "loggingOn"
    }
}