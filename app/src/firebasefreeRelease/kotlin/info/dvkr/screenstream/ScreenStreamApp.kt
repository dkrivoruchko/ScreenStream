package info.dvkr.screenstream

import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogItem
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.elvishew.xlog.interceptor.AbstractFilterInterceptor

class ScreenStreamApp : BaseApp() {

    override fun initLogger() {
        val logConfiguration = LogConfiguration.Builder()
            .logLevel(LogLevel.DEBUG)
            .tag("SSApp")
            .addInterceptor(object : AbstractFilterInterceptor() {
                override fun reject(log: LogItem): Boolean = settingsReadOnly.loggingOn.not()
            })
            .build()

        XLog.init(logConfiguration, filePrinter)
    }
}