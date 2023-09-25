package info.dvkr.screenstream

import android.os.Build
import android.os.StrictMode
import androidx.fragment.app.strictmode.FragmentStrictMode
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.AndroidPrinter
import info.dvkr.screenstream.mjpeg.MjpegKoinModule
import org.koin.core.module.Module
import org.koin.ksp.generated.module

public class ScreenStreamApp : BaseApp() {

    override val isAdEnabled: Boolean
        get() = false

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
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectFileUriExposure()
//                .detectCleartextNetwork()
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detectContentUriWithoutPermission() //detectUntaggedSockets()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) detectCredentialProtectedWhileLocked()
//                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) detectIncorrectContextUse()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) detectUnsafeIntentLaunch()
                }
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

    override fun initAd() {}

    override val streamingModules: Array<Module> = arrayOf(MjpegKoinModule().module)
}