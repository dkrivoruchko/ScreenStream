package io.screenstream.mjpeg

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.projection.MediaProjection
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.common.notification.NotificationHelper
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.StreamBehaviorSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.capture.ScreenCaptureAccess
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** One application files directory per store; a production store is never reopened at the same path. */
internal class IsolatedSettingsContext(base: Context, private val directory: File) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory
}

/** Seed only before constructing the production store. No second DataStore owns this file. */
internal suspend fun seedSettings(context: Context, json: String, name: String = "mjpeg") {
    val file = context.preferencesDataStoreFile(name)
    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
    file.outputStream().use { PreferencesFileSerializer.writeTo(preferencesOf(stringPreferencesKey("DATA") to json), it) }
}

/** Read after the public update returned and all test writers settled; no file mutation or second owner. */
internal suspend fun savedSettingsJson(context: Context): String = context.preferencesDataStoreFile("mjpeg").inputStream().use {
    checkNotNull(PreferencesFileSerializer.readFrom(it)[stringPreferencesKey("DATA")])
}

/** Public Service startup stores the real controller and bound runtime before tests start ordinary work. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MjpegServiceHost(
    settings: MjpegSettings,
    val http: TestHttpDelivery = TestHttpDelivery(),
    val monitor: TestAddressMonitor = TestAddressMonitor(),
    val access: TestCaptureAccess = TestCaptureAccess(),
    val runtime: TestRuntime = TestRuntime(),
    targetSdk: Int? = null,
) {
    val notifications = TestNotifications()
    private val manager = AdmittingManager(runtime)
    val serviceController: ServiceController<MjpegStreamingModuleService>
    val service: MjpegStreamingModuleService get() = serviceController.get()
    val controller: MjpegStreamingModuleController get() = checkNotNull(manager.createdController)
    private var destroyed = false
    private var koinRegistered = false

    init {
        // Only Main is supplied by this fixture. The production Default/IO owners remain real dispatchers.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var attachedService: ServiceController<MjpegStreamingModuleService>? = null
        try {
            startKoin {
                // Koin registers this application before invoking the DSL callback.
                koinRegistered = true
                modules(module {
                    single<NotificationHelper> { notifications }
                    single<StreamingModuleManager> { manager }
                    factory { parameters ->
                        MjpegStreamingModuleController(
                            runtime = parameters.get(), service = parameters.get(), settings = lazyOf(settings),
                            addressMonitor = monitor, captureAccess = access, http = http,
                        )
                    }
                })
            }
            val startIntent = Intent(StreamingModuleManager.ACTION_START_MODULE)
            serviceController = Robolectric.buildService(MjpegStreamingModuleService::class.java, startIntent)
            attachedService = serviceController
            serviceController.create()
            if (targetSdk != null) service.applicationInfo.targetSdkVersion = targetSdk
            serviceController.startCommand(0, 1)
        } catch (failure: Throwable) {
            val createdController = manager.createdController
            runCatching { createdController?.requestModuleShutdown() }.onFailure {
                if (it !== failure) failure.addSuppressed(it)
            }
            http.finishStop.complete(Unit)
            monitor.finishClose.complete(Unit)
            runCatching { attachedService?.destroy() }.onFailure {
                if (it !== failure) failure.addSuppressed(it)
            }
            if (createdController != null) {
                runCatching {
                    runBlocking { withTimeout(10.seconds) { createdController.awaitCleanup() } }
                }.onFailure {
                    if (it !== failure) failure.addSuppressed(it)
                }
            }
            runCatching { resetTestGlobals() }.onFailure {
                if (it !== failure) failure.addSuppressed(it)
            }
            throw failure
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        serviceController.destroy()
    }

    suspend fun close(): Boolean = withContext(NonCancellable) {
        try {
            controller.requestModuleShutdown()
            http.finishStop.complete(Unit)
            monitor.finishClose.complete(Unit)
            destroy()
            withTimeout(10.seconds) { controller.awaitCleanup() }
        } finally {
            resetTestGlobals()
        }
    }

    private fun resetTestGlobals() {
        try {
            if (koinRegistered) {
                stopKoin()
                koinRegistered = false
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class AdmittingManager(private val runtime: TestRuntime) : StreamingModuleManager {
        var createdController: MjpegStreamingModuleController? = null
            private set
        override val modules: List<StreamingModule> = emptyList()
        override val currentInstanceId: StateFlow<StreamingModule.InstanceId?> = MutableStateFlow(runtime.instanceId)
        override val state: StateFlow<StreamingModuleManager.State> = MutableStateFlow(StreamingModuleManager.State.NoModule)

        override fun onServiceStart(
            service: Service,
            existingController: StreamingModule.Controller?,
            intent: Intent?,
            createController: (StreamingModule.Controller.Runtime) -> StreamingModule.Controller,
        ) {
            check(intent?.action == StreamingModuleManager.ACTION_START_MODULE)
            check(existingController == null)
            createdController = createController(runtime) as MjpegStreamingModuleController
        }

        @Composable
        override fun InstanceContent(
            instanceId: StreamingModule.InstanceId,
            window: WindowSizeClass,
            modifier: Modifier,
            fallback: @Composable () -> Unit,
        ) = error("Content is outside these fixtures")

        override fun selectModule(moduleId: StreamingModule.Id?) = error("Selection is outside these fixtures")
        override fun restartModule(expectedInstanceId: StreamingModule.InstanceId) = error("Restart is outside these fixtures")
        override suspend fun exit() = error("Manager Exit is outside these fixtures")
        override fun requestStreamStart(instanceId: StreamingModule.InstanceId) = error("Use the controller boundary")
        override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId) = error("Use the controller boundary")
    }
}

internal class TestRuntime : StreamingModule.Controller.Runtime {
    override val instanceId: StreamingModule.InstanceId = StreamingModule.InstanceId(StreamingModule.Id("MJPEG"))

    @Volatile
    var current = true
    val status = MutableStateFlow<StreamingModule.Status?>(null)
    val failed = CompletableDeferred<Unit>()
    override fun isCurrent(): Boolean = current
    override fun reportRunning(status: StreamingModule.Status, heartbeatAtUptimeMillis: Long) {
        this.status.value = status
    }

    override fun reportFailed(messageResource: Int?) {
        current = false
        failed.complete(Unit)
    }
}

internal class TestAddressMonitor(holdClose: Boolean = false) : NetworkAddressMonitor {
    override val state = MutableStateFlow<NetworkAddressMonitor.State>(NetworkAddressMonitor.State.NotStarted)
    val filter = MutableStateFlow<NetworkAddressFilter?>(null)
    val closeEntered = CompletableDeferred<Unit>()
    val closeSettled = CompletableDeferred<Unit>()
    val finishClose = CompletableDeferred<Unit>().also { if (!holdClose) it.complete(Unit) }
    var closeFailure: Throwable? = null
    override fun updateFilter(filter: NetworkAddressFilter) {
        if (!closeEntered.isCompleted) this.filter.value = filter
    }

    override suspend fun close() {
        closeEntered.complete(Unit)
        try {
            finishClose.await()
            closeFailure?.let { throw it }
            state.value = NetworkAddressMonitor.State.Closed
        } finally {
            closeSettled.complete(Unit)
        }
    }
}

internal class TestCaptureAccess(private val onFinish: () -> Unit = {}) : ScreenCaptureAccess {
    val requested = MutableStateFlow<List<StreamingModule.CaptureAttemptId>>(emptyList())
    val finishCalls = MutableStateFlow<List<StreamingModule.CaptureAttemptId>>(emptyList())
    val consent = CompletableDeferred<ScreenCaptureAccess.Grant?>()
    var cleanupFailure: Throwable? = null

    override suspend fun requestConsent(attempt: StreamingModule.CaptureAttemptId): ScreenCaptureAccess.Grant? {
        synchronized(this) { requested.value += attempt }
        return consent.await()
    }

    override fun createProjection(grant: ScreenCaptureAccess.Grant): MediaProjection = error("Engine capture is outside these fixtures")
    override fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId) {
        synchronized(this) { finishCalls.value += attempt }
        onFinish()
        cleanupFailure?.let { throw it }
    }

    override fun saveConsentForReuse(grant: ScreenCaptureAccess.Grant) = error("No successful engine startup occurred")
}

internal class TestHttpDelivery(holdStop: Boolean = false, private val onStart: () -> Unit = {}) : HttpDelivery {
    data class Configuration(val access: AccessSettings, val postStop: StreamBehaviorSettings.PostStopImage, val web: WebPageSettings)
    data class Plan(val addresses: List<NetworkAddress>, val port: Int)

    override val info = MutableStateFlow(HttpDelivery.Info())
    override val appliedAccessToken: SecretValue? = null
    val configuration = MutableStateFlow<Configuration?>(null)
    val startCalls = MutableStateFlow(0)
    val plan = MutableStateFlow<Plan?>(null)
    val planApplications = MutableStateFlow<List<Plan>>(emptyList())
    val captures = MutableStateFlow<List<StreamingModule.CaptureAttemptId>>(emptyList())
    val captureStatus = MutableStateFlow<Pair<StreamingModule.CaptureAttemptId, MjpegCaptureSession.State.Status>?>(null)
    val stopEntered = CompletableDeferred<Unit>()
    val stopSettled = CompletableDeferred<Unit>()
    val finishStop = CompletableDeferred<Unit>().also { if (!holdStop) it.complete(Unit) }

    @Volatile
    var closed = false
    var acceptConfiguration = true
    var stopFailure: Throwable? = null
    var initialLogo: ByteArray? = null

    override suspend fun start(
        accessSettings: AccessSettings,
        postStopImage: StreamBehaviorSettings.PostStopImage,
        webSettings: WebPageSettings,
        logoBytes: ByteArray,
    ): Boolean {
        synchronized(this) { startCalls.value += 1 }
        // A held synchronous call must leave admission and independent cleanup callable.
        onStart()
        if (closed || configuration.value != null || !acceptConfiguration) return false
        initialLogo = logoBytes
        configuration.value = Configuration(accessSettings, postStopImage, webSettings)
        return true
    }

    override suspend fun configureServers(addresses: List<NetworkAddress>, port: Int) {
        if (!closed) {
            val applied = Plan(addresses.toList(), port)
            // Unlike StateFlow's latest Plan, this receipt also records retained-plan applications.
            plan.value = applied
            planApplications.value += applied
        }
    }

    override fun updateConfiguration(
        accessSettings: AccessSettings,
        postStopImage: StreamBehaviorSettings.PostStopImage,
        webSettings: WebPageSettings,
    ): Boolean {
        if (closed || configuration.value == null || !acceptConfiguration) return false
        configuration.value = Configuration(accessSettings, postStopImage, webSettings)
        return true
    }

    override fun registerCapture(id: StreamingModule.CaptureAttemptId): Boolean = synchronized(this) {
        if (closed || configuration.value == null) false else {
            captures.value += id
            true
        }
    }

    override fun updateCaptureStatus(id: StreamingModule.CaptureAttemptId, state: MjpegCaptureSession.State.Status) {
        captureStatus.value = id to state
    }

    override fun offerJpeg(id: StreamingModule.CaptureAttemptId, byteCount: Int, copyTo: (ByteArray) -> Unit) = error("No engine frame is available")
    override fun retryServer(id: Uuid) = error("Retry ingress is outside these fixtures")
    override fun requestStop() {
        closed = true
    }

    override suspend fun stop() {
        requestStop()
        stopEntered.complete(Unit)
        try {
            finishStop.await()
            stopFailure?.let { throw it }
        } finally {
            stopSettled.complete(Unit)
        }
    }
}

internal class TestNotifications : NotificationHelper {
    var stopAction: PendingIntent? = null
    var errorMessage: String? = null
    val shown = MutableStateFlow<List<Int>>(emptyList())
    override fun createForegroundNotification(context: Context, stopAction: PendingIntent): Notification {
        this.stopAction = stopAction
        return NotificationCompat.Builder(context, "fixture").setContentTitle("Capture").build()
    }

    override fun getErrorNotification(context: Context, message: String, recoverIntent: Intent?): Notification {
        errorMessage = message
        return NotificationCompat.Builder(context, "fixture").setContentTitle(message).build()
    }

    override fun showNotification(notificationId: Int, notification: Notification) {
        synchronized(this) { shown.value += notificationId }
    }

    override fun canOpenAppNotificationSettings(): Boolean = false
    override fun notificationPermissionGranted(context: Context): Boolean = true
    override fun foregroundNotificationsEnabled(): Boolean = true
    override fun errorNotificationsEnabled(): Boolean = true
    override fun getNotificationSettingsIntent(): Intent = Intent()
    override fun getStreamNotificationSettingsIntent(): Intent = Intent()
    override fun createForegroundNotification(context: Context, stopIntent: Intent): Notification = error("The attempt Stop action must be supplied")
    override fun cancelNotification(notificationId: Int) = Unit
}
