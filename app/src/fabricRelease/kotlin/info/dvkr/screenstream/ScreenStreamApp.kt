package info.dvkr.screenstream

import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogItem
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.elvishew.xlog.interceptor.AbstractFilterInterceptor
import com.elvishew.xlog.internal.util.StackTraceUtil
import com.google.firebase.crashlytics.FirebaseCrashlytics

class ScreenStreamApp : BaseApp() {

    override fun initLogger() {

        val logConfiguration = LogConfiguration.Builder()
            .logLevel(LogLevel.DEBUG)
            .tag("SSApp")
            .throwableFormatter {
                FirebaseCrashlytics.getInstance().recordException(it)
                StackTraceUtil.getStackTraceString(it)
            }
            .addInterceptor(object : AbstractFilterInterceptor() {
                override fun reject(log: LogItem): Boolean {
                    if (log.level >= LogLevel.DEBUG) FirebaseCrashlytics.getInstance().log(log.msg)
                    return settingsReadOnly.loggingOn.not()
                }
            })
            .build()

        XLog.init(logConfiguration, filePrinter)
    }
}