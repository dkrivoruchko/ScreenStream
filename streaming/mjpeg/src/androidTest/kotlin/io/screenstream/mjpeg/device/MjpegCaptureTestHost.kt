package io.screenstream.mjpeg.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.SessionKey
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsEvent
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsFactory
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsTracker
import io.screenstream.core.StreamingKoinModule
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.capture.ConsentRequestObserver
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.compose.ScreenCaptureConsentHost
import io.screenstream.core.configureLogWriter
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import io.screenstream.core.permission.RuntimePermissions
import io.screenstream.mjpeg.MjpegKoinModule
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.http.KtorHttpDelivery
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.NetworkSettings
import io.screenstream.mjpeg.settings.StreamBehaviorSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.modules
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Only the test APK has this Activity; production module admission, Service and capture stay real. */
class MjpegCaptureTestActivity : ComponentActivity() {
    private lateinit var fixture: MjpegCaptureTestHost

    @Suppress("DEPRECATION")
    @Deprecated("Test-only launch identity receipt preserves the production launcher")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        // This isolated Activity launches only the production consent host's external Activity.
        // Retain identity/code only; never retain or log Android's consent Intent contents.
        fixture.consentLaunchReceipts += MjpegCaptureTestHost.ConsentLaunchReceipt(
            checkNotNull(fixture.systemConsentAttempts.lastOrNull()), requestCode,
        )
        super.startActivityForResult(intent, requestCode, options)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Test-only receipt preserves the production ActivityResultRegistry dispatch")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val launch = checkNotNull(fixture.consentLaunchReceipts.lastOrNull { it.requestCode == requestCode }) {
            "Unrecognized Activity result in isolated consent fixture"
        }
        fixture.consentResultReceipts += MjpegCaptureTestHost.ConsentResultReceipt(
            launch.attempt, requestCode, resultCode, SystemClock.elapsedRealtime(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = checkNotNull(MjpegCaptureTestHost.current)
        this.fixture = fixture
        val manager = fixture.manager
        setContent {
            val managerState by manager.state.collectAsStateWithLifecycle()
            MaterialTheme {
                CompositionLocalProvider(LocalRuntimePermissions provides fixture.permissions) {
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            val instance = when (val state = managerState) {
                                is StreamingModuleManager.State.Running -> state.instanceId
                                is StreamingModuleManager.State.Unresponsive -> state.instanceId
                                else -> null
                            }
                            if (instance != null) {
                                manager.InstanceContent(
                                    instanceId = instance,
                                    window = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp = 400f, heightDp = 800f),
                                    fallback = {},
                                )
                            }
                        }
                        // Large asymmetric blocks let decoded network frames identify the visible target.
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .weight(2f)
                        ) {
                            drawRect(if (fixture.showGreenTarget.value) Color.Green else Color.Red)
                            drawRect(Color.Blue, size = Size(size.width / 4, size.height))
                            drawRect(Color.White, topLeft = Offset(size.width * 0.75f, 0f), size = Size(size.width / 4, size.height))
                        }
                    }
                    ScreenCaptureConsentHost()
                }
            }
        }
    }
}

internal class MjpegCaptureTestHost(
    private val applicationContext: Context,
    val port: Int,
    private val networkFilter: NetworkAddressFilter = loopbackFilter(),
    private val imageSettings: ImageSettings = ImageSettings(),
) {
    companion object {
        var current: MjpegCaptureTestHost? = null

        fun loopbackFilter(): NetworkAddressFilter = NetworkAddressFilter(
            families = setOf(AddressFamily.Ipv4),
            interfaceTypes = InterfaceType.entries.toSet(),
            categories = setOf(AddressCategory.Loopback),
        )
    }

    data class ConsentLaunchReceipt(val attempt: StreamingModule.CaptureAttemptId, val requestCode: Int)
    data class ConsentResultReceipt(
        val attempt: StreamingModule.CaptureAttemptId,
        val requestCode: Int,
        val resultCode: Int,
        val receivedAtElapsedMillis: Long,
    )

    val showGreenTarget = mutableStateOf(false)
    val systemConsentAttempts = CopyOnWriteArrayList<StreamingModule.CaptureAttemptId>()
    val reusedConsentAttempts = CopyOnWriteArrayList<StreamingModule.CaptureAttemptId>()
    val notificationErrors = CopyOnWriteArrayList<StreamingModuleNotifications.ErrorContent>()
    val foregroundActions = CopyOnWriteArrayList<StreamingModuleNotifications.ForegroundAction>()
    val consentLaunchReceipts = CopyOnWriteArrayList<ConsentLaunchReceipt>()
    val consentResultReceipts = CopyOnWriteArrayList<ConsentResultReceipt>()
    val httpDeliveries = CopyOnWriteArrayList<HttpDelivery>()
    private val settingsDirectory = File(applicationContext.cacheDir, "mjpeg-pipeline-${System.nanoTime()}").apply { check(mkdirs()) }
    private val settingsContext = object : ContextWrapper(applicationContext) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = settingsDirectory
    }
    lateinit var manager: StreamingModuleManager
        private set
    lateinit var settings: MjpegSettings
        private set

    // This narrow pipeline test uses loopback; it does not exercise the app's permission request UI.
    val permissions: RuntimePermissions = object : RuntimePermissions {
        override fun state(permission: RuntimePermission) = MutableStateFlow(
            if (isGranted(permission)) PermissionState.Granted else PermissionState.Requestable,
        )

        override fun isGranted(permission: RuntimePermission): Boolean = permission.isGranted(applicationContext)
        override fun tryRequest(permission: RuntimePermission): Boolean = isGranted(permission)
    }

    fun start() {
        check(current == null)
        configureLogWriter { _, _, _, _ -> }
        val seed = MjpegSettings.Data(
            image = imageSettings,
            network = NetworkSettings(
                filter = networkFilter,
                httpPort = port,
            ),
            web = WebPageSettings(title = "MJPEG capture pipeline test"),
            behavior = StreamBehaviorSettings(
                postStopImage = StreamBehaviorSettings.PostStopImage.Placeholder,
                stopOnScreenOff = false,
            ),
        )
        val preferences = settingsContext.preferencesDataStoreFile("mjpeg")
        check(preferences.parentFile!!.mkdirs() || preferences.parentFile!!.isDirectory)
        runBlocking {
            preferences.outputStream().use {
                PreferencesFileSerializer.writeTo(preferencesOf(stringPreferencesKey("DATA") to Json.encodeToString(seed)), it)
            }
        }
        try {
            startGraph()
            current = this
        } catch (failure: Throwable) {
            try {
                stopKoin()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun startGraph() {
        val koin = startKoin {
            androidContext(settingsContext)
            modules(StreamingKoinModule::class)
            modules(MjpegKoinModule::class)
            modules(module {
                factory<HttpDelivery> { KtorHttpDelivery(get()).also { httpDeliveries.add(it) } }
                single<StreamingModuleNotifications> { RecordingDeviceNotifications(applicationContext, notificationErrors, foregroundActions) }
                single<StreamingSessionAnalyticsFactory> { DiscardingSessionAnalyticsFactory }
                single<ConsentRequestObserver> {
                    object : ConsentRequestObserver {
                        override fun savedConsentTaken(attempt: StreamingModule.CaptureAttemptId) {
                            reusedConsentAttempts += attempt
                        }

                        override fun systemConsentSubmitted(attempt: StreamingModule.CaptureAttemptId, educationAccepted: Boolean) {
                            systemConsentAttempts += attempt
                        }
                    }
                }
            })
        }.koin
        manager = koin.get()
        settings = koin.get()
    }

    fun closeGraph() {
        current = null
        stopKoin()
        // DataStore owns its private scope for this process; never reopen or mutate its file.
    }

    private class RecordingDeviceNotifications(
        private val context: Context,
        private val errors: MutableList<StreamingModuleNotifications.ErrorContent>,
        private val actions: MutableList<StreamingModuleNotifications.ForegroundAction>,
    ) : StreamingModuleNotifications {
        private val notificationManager = checkNotNull(context.getSystemService(NotificationManager::class.java))

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(StreamingModuleNotifications.Channel.Streaming.id, "MJPEG test", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        override fun buildForeground(action: StreamingModuleNotifications.ForegroundAction): Notification {
            actions += action
            return NotificationCompat.Builder(context, StreamingModuleNotifications.Channel.Streaming.id)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("MJPEG pipeline test")
                .setOngoing(true)
                .addAction(0, if (action is StreamingModuleNotifications.ForegroundAction.StopStream) "Stop" else "Exit", action.pendingIntent)
                .build()
        }

        override fun updateError(notificationId: Int, content: StreamingModuleNotifications.ErrorContent?) {
            content?.let(errors::add)
        }

        override fun areNotificationsEnabled(channel: StreamingModuleNotifications.Channel): Boolean = notificationManager.areNotificationsEnabled()
        override fun channelSettingsIntent(channel: StreamingModuleNotifications.Channel): Intent? = null
    }

    private object DiscardingSessionAnalyticsFactory : StreamingSessionAnalyticsFactory {
        private val sink = object : StreamingAnalytics {
            override fun logEvent(event: StreamingAnalyticsEvent) = Unit
        }

        override fun beginConsent(key: SessionKey, streamMode: StreamMode, entryPoint: EntryPoint) =
            StreamingSessionAnalyticsTracker(sink, streamMode, entryPoint)

        override fun preflightFailed(streamMode: StreamMode, entryPoint: EntryPoint, startFailGroup: StartFailGroup) = Unit
    }
}
