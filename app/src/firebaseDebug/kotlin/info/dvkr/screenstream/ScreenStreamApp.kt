package info.dvkr.screenstream

import android.os.StrictMode
import androidx.fragment.app.strictmode.FragmentStrictMode
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.AndroidPrinter

class ScreenStreamApp : BaseApp() {

    override fun initLogger() {
        System.setProperty("kotlinx.coroutines.debug", "on")

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

        FragmentStrictMode.defaultPolicy =
            FragmentStrictMode.Policy.Builder()
                .detectFragmentReuse()
                .detectFragmentTagUsage()
                .detectRetainInstanceUsage()
                .detectSetUserVisibleHint()
                .detectTargetFragmentUsage()
                .detectWrongFragmentContainer()
                .build()

        val logConfiguration = LogConfiguration.Builder().tag("SSApp").build()
        XLog.init(logConfiguration, AndroidPrinter(), filePrinter)
    }
}