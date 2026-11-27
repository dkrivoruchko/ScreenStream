package info.dvkr.screenstream

import android.app.ActivityManager
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StrictMode
import io.screenstream.core.StreamingModuleManager
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.annotation.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.parameter.parametersOf
import org.koin.plugin.module.dsl.withConfiguration

@KoinApplication
public class ScreenStreamApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        if (isDebuggable) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
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
                    .detectCleartextNetwork()
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detectContentUriWithoutPermission()
//                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detectUntaggedSockets()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) detectCredentialProtectedWhileLocked()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) detectIncorrectContextUse()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) detectUnsafeIntentLaunch()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) detectBlockedBackgroundActivityLaunch()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) detectImplicitUriPermissionGrant()
                    }
                    .penaltyLog()
                    .build()
            )
        }

        startKoin {
            if (isDebuggable) androidLogger(Level.DEBUG)
            androidContext(this@ScreenStreamApp)
            // Module descriptors share the StreamingModule binding during initial registration.
            withConfiguration<ScreenStreamApp>()
            allowOverride(false)
        }

        observeAppExit()
    }

    private fun observeAppExit() {
        val streamingModuleManager = get<StreamingModuleManager>()
        val activityManager = getSystemService(ActivityManager::class.java)

        streamingModuleManager.state
            .map { state -> state == StreamingModuleManager.State.Closing || state == StreamingModuleManager.State.Closed }
            .distinctUntilChanged()
            .filter { it }
            .onEach {
                activityManager.appTasks.forEach { task ->
                    try {
                        task.finishAndRemoveTask()
                    } catch (failure: IllegalArgumentException) {
                        val log: Logger = get { parametersOf(this@ScreenStreamApp) }
                        log.d("observeAppExit", "App task was already removed during Exit", failure)
                    }
                }
            }.launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
}
