package info.dvkr.screenstream

import android.os.StrictMode
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.AndroidPrinter

class ScreenStreamApp : BaseApp() {

    override fun initLogger() {

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .permitDiskReads()
                .permitDiskWrites()
                .penaltyLog()
                .build()
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )

        val logConfiguration = LogConfiguration.Builder().tag("SSApp").build()
        XLog.init(logConfiguration, AndroidPrinter(), filePrinter)
    }
}