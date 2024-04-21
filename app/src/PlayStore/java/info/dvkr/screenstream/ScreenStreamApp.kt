package info.dvkr.screenstream

import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogItem
import com.elvishew.xlog.interceptor.AbstractFilterInterceptor
import com.elvishew.xlog.internal.util.StackTraceUtil
import com.google.firebase.crashlytics.FirebaseCrashlytics
import info.dvkr.screenstream.common.CommonKoinModule
import info.dvkr.screenstream.logger.AppLogger
import info.dvkr.screenstream.mjpeg.MjpegKoinModule
import info.dvkr.screenstream.webrtc.WebRtcKoinModule
import org.koin.core.module.Module
import org.koin.ksp.generated.module

public class ScreenStreamApp : BaseApp() {

    override fun configureLogger(builder: LogConfiguration.Builder) {
        if (BuildConfig.DEBUG) return

        val crashlytics = FirebaseCrashlytics.getInstance()

        builder
            .throwableFormatter {
                crashlytics.recordException(it)
                StackTraceUtil.getStackTraceString(it)
            }
            .addInterceptor(object : AbstractFilterInterceptor() {
                override fun reject(log: LogItem): Boolean {
                    crashlytics.log(log.msg)
                    return AppLogger.isLoggingOn
                }
            })
    }

    override val streamingModules: Array<Module> =
        arrayOf(CommonKoinModule().module, MjpegKoinModule().module, WebRtcKoinModule().module)
}