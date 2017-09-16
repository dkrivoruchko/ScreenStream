package info.dvkr.screenstream

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.crashlytics.android.Crashlytics
import com.crashlytics.android.core.CrashlyticsCore
import com.squareup.leakcanary.LeakCanary
import info.dvkr.screenstream.dagger.component.AppComponent
import info.dvkr.screenstream.dagger.component.DaggerAppComponent
import info.dvkr.screenstream.dagger.module.AppModule
import io.fabric.sdk.android.Fabric


class ScreenStreamApp : Application() {
    private val TAG = "ScreenStreamApp"
    private lateinit var appComponent: AppComponent

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")

        // Turning on strict mode
        if (BuildConfig.DEBUG_MODE) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .detectCustomSlowCalls()
                    .permitDiskReads()
                    .permitDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .penaltyFlashScreen()
                    .build())

            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
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

        appComponent = DaggerAppComponent.builder().appModule(AppModule(this)).build()

        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: End")
        Crashlytics.log(1, TAG, "onCreate")
    }

    fun appComponent(): AppComponent = appComponent
}