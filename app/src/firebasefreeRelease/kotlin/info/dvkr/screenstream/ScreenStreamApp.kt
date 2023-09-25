package info.dvkr.screenstream

import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogItem
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.elvishew.xlog.interceptor.AbstractFilterInterceptor
import info.dvkr.screenstream.mjpeg.MjpegKoinModule
import org.koin.core.module.Module
import org.koin.ksp.generated.module

public class ScreenStreamApp : BaseApp() {

    override val isAdEnabled: Boolean
        get() = false

    override fun initLogger() {
        val logConfiguration = LogConfiguration.Builder()
            .logLevel(LogLevel.VERBOSE)
            .tag("SSApp")
            .addInterceptor(object : AbstractFilterInterceptor() {
                override fun reject(log: LogItem): Boolean = isLoggingOn
            })
            .build()

        XLog.init(logConfiguration, filePrinter)
    }

    override fun initAd() {}

    override val streamingModules: Array<Module> = arrayOf(MjpegKoinModule().module)
}