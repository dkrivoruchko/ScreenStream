package info.dvkr.screenstream

import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogItem
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.elvishew.xlog.interceptor.AbstractFilterInterceptor
import com.elvishew.xlog.internal.util.StackTraceUtil
import com.google.android.gms.ads.MobileAds
import com.google.firebase.crashlytics.FirebaseCrashlytics
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.MjpegKoinModule
import info.dvkr.screenstream.webrtc.WebRtcKoinModule
import org.koin.core.module.Module
import org.koin.ksp.generated.module

public class ScreenStreamApp : BaseApp() {

    override val isAdEnabled: Boolean
        get() = true

    override fun initLogger() {
        val logConfiguration = LogConfiguration.Builder()
            .logLevel(LogLevel.VERBOSE)
            .tag("SSApp")
            .throwableFormatter {
                FirebaseCrashlytics.getInstance().recordException(it)
                StackTraceUtil.getStackTraceString(it)
            }
            .addInterceptor(object : AbstractFilterInterceptor() {
                override fun reject(log: LogItem): Boolean {
                    FirebaseCrashlytics.getInstance().log(log.msg)
                    return isLoggingOn
                }
            })
            .build()

        XLog.init(logConfiguration, filePrinter)
    }

    override fun initAd() {
        runCatching { MobileAds.initialize(this) }
            .onFailure { XLog.e(getLog("initAd", it.message), it) }
    }

    override val streamingModules: Array<Module> = arrayOf(MjpegKoinModule().module, WebRtcKoinModule().module)
}