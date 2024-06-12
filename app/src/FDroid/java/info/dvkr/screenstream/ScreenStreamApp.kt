package info.dvkr.screenstream

import android.content.pm.ApplicationInfo
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogItem
import com.elvishew.xlog.interceptor.AbstractFilterInterceptor
import info.dvkr.screenstream.common.CommonKoinModule
import info.dvkr.screenstream.logger.AppLogger
import info.dvkr.screenstream.mjpeg.MjpegKoinModule
import org.koin.core.module.Module
import org.koin.ksp.generated.module

public class ScreenStreamApp : BaseApp() {

    override fun configureLogger(builder: LogConfiguration.Builder) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return

        builder.addInterceptor(object : AbstractFilterInterceptor() {
            override fun reject(log: LogItem): Boolean = AppLogger.isLoggingOn
        })
    }

    override val streamingModules: Array<Module> = arrayOf(CommonKoinModule().module, MjpegKoinModule().module)
}