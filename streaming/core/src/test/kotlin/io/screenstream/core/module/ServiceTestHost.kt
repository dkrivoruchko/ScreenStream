package io.screenstream.core.module

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationCompat
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.core.manager.StreamingModuleManagerImpl
import io.screenstream.core.settings.StreamingSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAppOpsManager
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowService
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** Real manager delivery and Android lifecycle; the controller's cleanup result is a supplied input. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ServiceTestHost(directory: File) : AutoCloseable {
    val scheduler = TestCoroutineScheduler()
    val notifications = ServiceNotifications()
    private val app: Context = RuntimeEnvironment.getApplication()
    private val starts = ArrayDeque<Intent>()
    private val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun startService(service: Intent): ComponentName {
            starts.addLast(service)
            return checkNotNull(service.component)
        }

        override fun startForegroundService(service: Intent): ComponentName = error("Expected ordinary Service dispatch")
    }
    private val settings = StreamingSettingsStore(context)
    val manager: StreamingModuleManagerImpl
    private val lifecycle: org.robolectric.android.controller.ServiceController<CoreServiceHost>
    val service: CoreServiceHost get() = lifecycle.get()
    lateinit var controller: ServiceControllerProbe
        private set
    val platform: ServicePlatformShadow get() = Shadow.extract(service)
    val posts: ServiceNotificationShadow get() = Shadow.extract(service.getSystemService(NotificationManager::class.java))
    val appOps: ServiceAppOpsShadow get() = Shadow.extract(service.getSystemService(AppOpsManager::class.java))
    var destroyed: Boolean = false
        private set

    init {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        runBlocking { withTimeout(5.seconds) { settings.initialize() } }
        manager = StreamingModuleManagerImpl(listOf(ServiceDescriptor), context, settings, notifications, StandardTestDispatcher(scheduler))
        loadKoinModules(module {
            single { manager }
            single<StreamingModuleNotifications> { notifications }
        })
        CoreServiceHost.factory = { handle -> ServiceControllerProbe(handle).also { controller = it } }
        manager.selectModule(ServiceDescriptor.id)
        scheduler.runCurrent()
        lifecycle = Robolectric.buildService(CoreServiceHost::class.java, starts.removeFirst()).create().startCommand(0, 1)
        scheduler.runCurrent()
        check(controller.started)
    }

    fun attempt(): StreamingModule.CaptureAttemptId = StreamingModule.CaptureAttemptId(controller.instanceId)
    fun drainRefresh() = shadowOf(Looper.getMainLooper()).idle()

    fun destroy() {
        if (destroyed) return
        destroyed = true
        lifecycle.destroy()
    }

    override fun close() {
        try {
            controller.onDestroyed = {}
            platform.stopFailure = null
            appOps.unwatchFailure = null
            controller.cleanup.complete(true)
            manager.exit()
            scheduler.runCurrent()
            destroy()
            scheduler.runCurrent()
        } finally {
            CoreServiceHost.factory = { error("No Service test factory installed") }
            Dispatchers.resetMain()
        }
    }
}

internal class CoreServiceHost : StreamingModuleService() {
    override fun createController(managerHandle: StreamingModule.Controller.ManagerHandle): StreamingModule.Controller = factory(managerHandle)

    companion object {
        var factory: (StreamingModule.Controller.ManagerHandle) -> StreamingModule.Controller = { error("No factory") }
    }
}

internal object ServiceDescriptor : StreamingModule {
    override val id = StreamingModule.Id("service-test")
    override val priority = 1
    override val description = StreamingModule.Description(1, 2, 3, 4, 5, 6, 7)
    override val serviceClass: Class<out Service> = CoreServiceHost::class.java
    override val foregroundSlot = object : StreamingModuleNotifications.ForegroundSlot {
        override val id = 417
    }
}

internal class ServiceControllerProbe(val handle: StreamingModule.Controller.ManagerHandle) : StreamingModule.Controller {
    override val instanceId = handle.instanceId
    var started = false
    val shutdown = CompletableDeferred<Unit>()
    val cleanup = CompletableDeferred<Boolean>()
    val destroyed = CompletableDeferred<Unit>()
    var onShutdown: () -> Unit = {}
    var onDestroyed: () -> Unit = {}
    override fun startModule() {
        started = true
        handle.reportRunning(StreamingModule.Status(isStreaming = false, hasConsumer = false, captureAttempt = null, error = null), SystemClock.uptimeMillis())
    }

    override fun requestStreamStart() = Unit
    override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean) = Unit
    override fun requestModuleShutdown() {
        shutdown.complete(Unit)
        onShutdown()
    }

    override suspend fun awaitCleanup(): Boolean = cleanup.await()
    override fun onServiceDestroyed() {
        destroyed.complete(Unit)
        onDestroyed()
    }

    @Composable
    override fun Content(window: WindowSizeClass, modifier: Modifier) = Unit
}

internal class ServiceNotifications : StreamingModuleNotifications {
    val actions = mutableListOf<StreamingModuleNotifications.ForegroundAction>()
    val built = mutableListOf<Notification>()
    val channelChecks = mutableListOf<StreamingModuleNotifications.Channel>()
    var enabled = true
    var buildFailure: RuntimeException? = null
    var availabilityFailure: RuntimeException? = null
    override fun buildForeground(action: StreamingModuleNotifications.ForegroundAction): Notification {
        actions.add(action)
        buildFailure?.let { throw it }
        return NotificationCompat.Builder(RuntimeEnvironment.getApplication(), "service-test")
            .setSmallIcon(1).setContentTitle("Service ${built.size}").build().also(built::add)
    }

    override fun updateError(notificationId: Int, content: StreamingModuleNotifications.ErrorContent?) = Unit
    override fun areNotificationsEnabled(channel: StreamingModuleNotifications.Channel): Boolean {
        channelChecks.add(channel)
        availabilityFailure?.let { throw it }
        return enabled
    }

    override fun channelSettingsIntent(channel: StreamingModuleNotifications.Channel): Intent? = null
}

/** Records framework calls; failures and callback delivery are supplied inputs, not Android enforcement. */
@Implements(Service::class)
internal class ServicePlatformShadow : ShadowService() {
    data class Promotion(val id: Int, val notification: Notification, val types: Int?)

    val promotions = mutableListOf<Promotion>()
    val events = mutableListOf<String>()
    var stopFailure: RuntimeException? = null

    @Implementation
    override fun startForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT < 29) promotions.add(Promotion(id, notification, null))
        super.startForeground(id, notification)
    }

    @Implementation(minSdk = 29)
    override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
        promotions.add(Promotion(id, notification, foregroundServiceType))
        super.startForeground(id, notification, foregroundServiceType)
    }

    @Implementation
    override fun stopSelf() {
        events.add("stop")
        stopFailure?.let { throw it }
        super.stopSelf()
    }
}

@Implements(NotificationManager::class)
internal class ServiceNotificationShadow : ShadowNotificationManager() {
    data class Post(val id: Int, val notification: Notification)

    val posts = mutableListOf<Post>()
    var postFailure: RuntimeException? = null

    @Implementation
    override fun notify(id: Int, notification: Notification) {
        posts.add(Post(id, notification))
        postFailure?.let { throw it }
        super.notify(id, notification)
    }
}

@Implements(AppOpsManager::class)
internal class ServiceAppOpsShadow : ShadowAppOpsManager() {
    data class Watch(val op: String, val packageName: String?, val listener: AppOpsManager.OnOpChangedListener)

    val watches = mutableListOf<Watch>()
    val removals = mutableListOf<AppOpsManager.OnOpChangedListener>()
    var unwatchFailure: RuntimeException? = null

    @Implementation
    fun startWatchingMode(op: String, packageName: String?, callback: AppOpsManager.OnOpChangedListener) {
        watches.add(Watch(op, packageName, callback))
        watchFailure?.let { throw it }
    }

    @Implementation
    override fun stopWatchingMode(callback: AppOpsManager.OnOpChangedListener) {
        removals.add(callback)
        unwatchFailure?.let { throw it }
        super.stopWatchingMode(callback)
    }

    fun emit(changedPackage: String?) {
        val watch = watches.single()
        watch.listener.onOpChanged(watch.op, changedPackage)
    }

    companion object {
        var watchFailure: RuntimeException? = null

        @JvmStatic
        @Resetter
        fun resetFailures() {
            watchFailure = null
        }
    }
}
