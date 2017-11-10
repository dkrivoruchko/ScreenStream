package info.dvkr.screenstream

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.crashlytics.android.Crashlytics
import com.crashlytics.android.core.CrashlyticsCore
import com.squareup.leakcanary.LeakCanary
import info.dvkr.screenstream.di.KoinModule
import io.fabric.sdk.android.Fabric
import org.koin.android.ext.android.startAndroidContext


class ScreenStreamApp : Application() {
    private val TAG = "ScreenStreamApp"

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")

        // Turning on strict mode
        if (BuildConfig.DEBUG_MODE) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .penaltyDialog()
                    .build())

            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build())
        }

        if (LeakCanary.isInAnalyzerProcess(this)) {
            // This process is dedicated to LeakCanary for heap analysis.
            // You should not initAppState your app in this process.
            return
        }
        LeakCanary.install(this)

        // Set up Crashlytics, disabled for debug builds
        val crashlyticsKit = Crashlytics.Builder()
                .core(CrashlyticsCore.Builder().disabled(BuildConfig.DEBUG).build())
                .build()

        // Initialize Fabric with the debug-disabled crashlytics.
        Fabric.with(this, crashlyticsKit)

        startAndroidContext(this, listOf(KoinModule()))

        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: End")
        Crashlytics.log(1, TAG, "onCreate")
    }
}