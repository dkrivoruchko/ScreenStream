package info.dvkr.screenstream

import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogItem
import com.elvishew.xlog.interceptor.AbstractFilterInterceptor
import com.elvishew.xlog.internal.util.StackTraceUtil
import com.google.firebase.crashlytics.FirebaseCrashlytics

public class ScreenStreamApp : BaseApp() {

    override fun configureReleaseLogger(builder: LogConfiguration.Builder) {
        val crashlytics = FirebaseCrashlytics.getInstance()

        builder
            .throwableFormatter {
                crashlytics.recordException(it)
                StackTraceUtil.getStackTraceString(it)
            }
            .addInterceptor(object : AbstractFilterInterceptor() {
                override fun reject(log: LogItem): Boolean {
                    crashlytics.log(log.msg)
                    return false
                }
            })
    }
}
