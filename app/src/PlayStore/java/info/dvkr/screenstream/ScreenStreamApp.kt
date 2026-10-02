package info.dvkr.screenstream

import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogItem
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.interceptor.AbstractFilterInterceptor
import com.elvishew.xlog.internal.util.StackTraceUtil
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.screenstream.streaming.configureLogWriter

public class ScreenStreamApp : BaseApp() {

    override fun configureReleaseLogger(builder: LogConfiguration.Builder) {
        val crashlytics = FirebaseCrashlytics.getInstance()

        configureLogWriter { priority, tag, message, cause ->
            val level = LogLevel.getShortLevelName(priority)
            crashlytics.log("$level $message")
            if (cause != null) {
                val keys = CustomKeysAndValues.Builder()
                    .putString("log_level", level)
                    .putString("log_tag", tag)
                    .putString("log_message", message)
                    .build()
                crashlytics.recordException(cause, keys)
            }
        }

        builder
            .throwableFormatter {
                crashlytics.recordException(it)
                StackTraceUtil.getStackTraceString(it)
            }
            .addInterceptor(object : AbstractFilterInterceptor() {
                override fun reject(log: LogItem): Boolean {
                    crashlytics.log("${LogLevel.getShortLevelName(log.level)} ${log.msg}")
                    return false
                }
            })
    }
}
