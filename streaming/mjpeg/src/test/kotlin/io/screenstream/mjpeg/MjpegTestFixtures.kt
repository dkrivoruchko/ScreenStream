package io.screenstream.mjpeg

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.projection.MediaProjection
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.SessionKey
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingAnalyticsEvent
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsFactory
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsTracker
import io.screenstream.capture.ScreenCaptureConfig
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.core.StreamingKoinModule
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.capture.ConsentRequestObserver
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.IpAddressKey
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.networkaddress.NetworkAddressInventory
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.StreamBehaviorSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.modules
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadow.api.Shadow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
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

@OptIn(ExperimentalCoroutinesApi::class)
internal class MjpegServiceHost(
    val settings: MjpegSettings,
    val http: TestHttpDelivery = TestHttpDelivery(),
    val monitor: TestAddressMonitor = TestAddressMonitor(),
    val access: TestCaptureAccess = TestCaptureAccess(),
    targetSdk: Int? = null,
    engineFactory: MjpegEngineFactory = DefaultMjpegEngineFactory,
    delivery: HttpDelivery = http,
) {
    val notifications = TestNotifications()
    val serviceController: ServiceController<MjpegStreamingModuleService>
    val service: MjpegStreamingModuleService get() = serviceController.get()
    lateinit var controller: MjpegStreamingModuleController
        private set
    lateinit var managerHandle: TestManagerHandle
        private set
    val manager: StreamingModuleManager
    private val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var destroyed = false

    init {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var attached: ServiceController<MjpegStreamingModuleService>? = null
        var startedManager: StreamingModuleManager? = null
        var koinRegistered = false
        try {
            val launched = CompletableDeferred<Intent>()
            val base = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), java.nio.file.Files.createTempDirectory("mjpeg-core-settings").toFile())
            val context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun startService(service: Intent): ComponentName = launch(service)
                override fun startForegroundService(service: Intent): ComponentName = launch(service)
                private fun launch(intent: Intent): ComponentName {
                    launched.complete(intent)
                    return checkNotNull(intent.component)
                }
            }
            val app = startKoin {
                koinRegistered = true
                modules(StreamingKoinModule::class)
                modules(module {
                    single<Context> { context }
                    single<StreamingModule> { MjpegStreamingModule() }
                    single<StreamingModuleNotifications> { notifications }
                    single<ConsentRequestObserver> {
                        object : ConsentRequestObserver {
                            override fun savedConsentTaken(attempt: StreamingModule.CaptureAttemptId) = Unit
                            override fun systemConsentSubmitted(attempt: StreamingModule.CaptureAttemptId, educationAccepted: Boolean) = Unit
                        }
                    }
                    factory { parameters ->
                        val originalHandle = parameters.get<StreamingModule.Controller.ManagerHandle>()
                        val originalService = parameters.get<MjpegStreamingModuleService>()
                        managerHandle = TestManagerHandle(originalHandle)
                        MjpegStreamingModuleController(
                            managerHandle = originalHandle, service = originalService, settings = lazyOf(settings),
                            addressMonitor = monitor, captureAccess = access, http = delivery,
                            analyticsFactory = TestSessionAnalyticsFactory, engineFactory = engineFactory,
                        ).also { controller = it }
                    }
                })
            }
            manager = app.koin.get()
            startedManager = manager
            manager.selectModule(MjpegStreamingModule.Id)
            val intent = runBlocking { withTimeout(10.seconds) { launched.await() } }
            serviceController = Robolectric.buildService(MjpegStreamingModuleService::class.java, intent)
            attached = serviceController
            serviceController.create()
            if (targetSdk != null) service.applicationInfo.targetSdkVersion = targetSdk
            serviceController.startCommand(0, 1)
            manager.state.onEach { state ->
                when (state) {
                    is StreamingModuleManager.State.Running -> managerHandle.status.value = state.status
                    is StreamingModuleManager.State.Unresponsive -> managerHandle.status.value = state.lastStatus
                    is StreamingModuleManager.State.Failed -> managerHandle.failed.complete(Unit)
                    else -> Unit
                }
            }.launchIn(observationScope)
        } catch (failure: Throwable) {
            fun retainFailure(action: () -> Unit) {
                runCatching(action).onFailure { if (it !== failure) failure.addSuppressed(it) }
            }
            retainFailure { if (::controller.isInitialized) controller.requestModuleShutdown() }
            http.finishStop.complete(Unit)
            monitor.finishClose.complete(Unit)
            retainFailure { attached?.destroy() }
            if (::controller.isInitialized) retainFailure {
                runBlocking { withTimeout(10.seconds) { controller.awaitCleanup() } }
            }
            observationScope.cancel()
            retainFailure { startedManager?.exit() }
            if (koinRegistered) retainFailure { stopKoin() }
            retainFailure { Dispatchers.resetMain() }
            throw failure
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        serviceController.destroy()
    }

    suspend fun awaitStopRequested() {
        if (destroyed) return
        withTimeout(10.seconds) {
            while (!shadowOf(service).isStoppedBySelf) yield()
        }
    }

    suspend fun close(): Boolean = withContext(NonCancellable) {
        val result = runCatching {
            controller.requestModuleShutdown()
            http.finishStop.complete(Unit)
            monitor.finishClose.complete(Unit)
            awaitStopRequested()
            destroy()
            withTimeout(10.seconds) { controller.awaitCleanup() }
        }
        var failure = result.exceptionOrNull()
        fun retainFailure(action: () -> Unit) {
            runCatching(action).onFailure { cause ->
                val primary = failure
                if (primary == null) failure = cause
                else if (cause !== primary && primary.suppressed.none { it === cause }) primary.addSuppressed(cause)
            }
        }
        retainFailure { observationScope.cancel() }
        retainFailure { manager.exit() }
        retainFailure { stopKoin() }
        retainFailure { Dispatchers.resetMain() }
        failure?.let { throw it }
        result.getOrThrow()
    }
}

internal class TestManagerHandle(
    private val delegate: StreamingModule.Controller.ManagerHandle? = null,
) : StreamingModule.Controller.ManagerHandle {
    override val instanceId = delegate?.instanceId ?: StreamingModule.InstanceId(StreamingModule.Id("MJPEG"))
    override val foregroundSlot: StreamingModuleNotifications.ForegroundSlot = delegate?.foregroundSlot ?: MjpegStreamingModule().foregroundSlot

    @Volatile
    var current = true
    val status = MutableStateFlow<StreamingModule.Status?>(null)
    val reports = CopyOnWriteArrayList<Pair<StreamingModule.Status, Long>>()
    val failed = CompletableDeferred<Unit>()
    override fun isCurrent(): Boolean = current && (delegate?.isCurrent() ?: true)
    override fun reportRunning(status: StreamingModule.Status, heartbeatAtUptimeMillis: Long) {
        reports += status to heartbeatAtUptimeMillis
        this.status.value = status
        delegate?.reportRunning(status, heartbeatAtUptimeMillis)
    }

    override fun reportFailed(messageResource: Int?) {
        current = false
        failed.complete(Unit)
        delegate?.reportFailed(messageResource)
    }
}

internal class TestAddressMonitor(holdClose: Boolean = false) : NetworkAddressMonitor {
    override val state = MutableStateFlow<NetworkAddressMonitor.State>(NetworkAddressMonitor.State.NotStarted)
    val started = MutableStateFlow(false)
    val closeEntered = CompletableDeferred<Unit>()
    val closeSettled = CompletableDeferred<Unit>()
    val finishClose = CompletableDeferred<Unit>().also { if (!holdClose) it.complete(Unit) }
    var closeFailure: Throwable? = null
    override fun start() {
        if (!closeEntered.isCompleted) started.value = true
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
    override suspend fun requestConsent(
        attempt: StreamingModule.CaptureAttemptId,
        attemptJob: Job,
        prepareForeground: () -> Boolean,
    ): ScreenCaptureAccess.ConsentResult {
        synchronized(this) { requested.value += attempt }
        val grant = consent.await() ?: return ScreenCaptureAccess.ConsentResult.NotGranted(ScreenCaptureAccess.NoGrantReason.Declined)
        return if (withContext(Dispatchers.Main.immediate) { prepareForeground() }) ScreenCaptureAccess.ConsentResult.Granted(grant)
        else ScreenCaptureAccess.ConsentResult.NotGranted(ScreenCaptureAccess.NoGrantReason.ForegroundRejected)
    }

    override fun createProjection(grant: ScreenCaptureAccess.Grant, retryAfterRecentForegroundStart: Boolean): MediaProjection {
        check(!retryAfterRecentForegroundStart)
        return Shadow.newInstanceOf(MediaProjection::class.java)
    }

    override fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId) {
        synchronized(this) { finishCalls.value += attempt }
        onFinish()
        cleanupFailure?.let { throw it }
    }

    override fun saveConsentForReuse(grant: ScreenCaptureAccess.Grant) = Unit
}

internal class TestHttpDelivery(
    holdStop: Boolean = false,
    private val onStart: () -> Unit = {},
    private val onJpeg: (Int, (ByteArray) -> Unit) -> Unit = { _, _ -> error("No engine frame is expected") },
) : HttpDelivery {
    data class Configuration(val access: AccessSettings, val postStop: StreamBehaviorSettings.PostStopImage, val web: WebPageSettings)
    data class Plan(val addresses: List<NetworkAddress>, val port: Int)
    data class CaptureUpdate(val id: StreamingModule.CaptureAttemptId, val status: MjpegCaptureSession.State.Status, val generation: Any?)

    override val info = MutableStateFlow(HttpDelivery.Info())
    override val appliedToken: SecretValue? = null
    val configuration = MutableStateFlow<Configuration?>(null)
    val startCalls = MutableStateFlow(0)
    val plan = MutableStateFlow<Plan?>(null)
    val planApplications = MutableStateFlow<List<Plan>>(emptyList())
    val captures = MutableStateFlow<List<StreamingModule.CaptureAttemptId>>(emptyList())
    val captureStatus = MutableStateFlow<Pair<StreamingModule.CaptureAttemptId, MjpegCaptureSession.State.Status>?>(null)
    val captureUpdates = CopyOnWriteArrayList<CaptureUpdate>()
    val retried = CopyOnWriteArrayList<Uuid>()
    val stopEntered = CompletableDeferred<Unit>()
    val stopSettled = CompletableDeferred<Unit>()
    val finishStop = CompletableDeferred<Unit>().also { if (!holdStop) it.complete(Unit) }

    @Volatile
    var closed = false
    var acceptConfiguration = true
    var acceptCapture = true
    var onCaptureRegistered: (StreamingModule.CaptureAttemptId) -> Unit = {}
    var stopFailure: Throwable? = null
    var initialLogo: ByteArray? = null
    var onConfiguration: () -> Unit = {}
    var configureFailure: Throwable? = null
    override suspend fun start(
        accessSettings: AccessSettings,
        postStopImage: StreamBehaviorSettings.PostStopImage,
        webSettings: WebPageSettings,
        logoBytes: ByteArray
    ): Boolean {
        synchronized(this) { startCalls.value += 1 }
        onStart()
        if (closed || configuration.value != null || !acceptConfiguration) return false
        initialLogo = logoBytes
        configuration.value = Configuration(accessSettings, postStopImage, webSettings)
        return true
    }

    override suspend fun configureServers(addresses: List<NetworkAddress>, port: Int) {
        configureFailure?.let { throw it }
        if (!closed) {
            val applied = Plan(addresses.toList(), port)
            plan.value = applied
            planApplications.value += applied
        }
    }

    override fun updateConfiguration(
        accessSettings: AccessSettings,
        postStopImage: StreamBehaviorSettings.PostStopImage,
        webSettings: WebPageSettings
    ): Boolean {
        onConfiguration()
        if (closed || configuration.value == null || !acceptConfiguration) return false
        configuration.value = Configuration(accessSettings, postStopImage, webSettings)
        return true
    }

    override fun registerCapture(id: StreamingModule.CaptureAttemptId, initialCaptureGeneration: Any): Boolean = synchronized(this) {
        if (closed || configuration.value == null || !acceptCapture) false else {
            captures.value += id
            onCaptureRegistered(id)
            true
        }
    }

    override fun updateCaptureStatus(id: StreamingModule.CaptureAttemptId, state: MjpegCaptureSession.State.Status, captureGeneration: Any?) {
        captureStatus.value = id to state
        captureUpdates += CaptureUpdate(id, state, captureGeneration)
    }

    override fun offerJpeg(expectedCaptureGeneration: Any, byteCount: Int, copyTo: (ByteArray) -> Unit) = onJpeg(byteCount, copyTo)
    override fun retryServer(id: Uuid) {
        retried += id
    }

    override fun requestStop() {
        closed = true
    }

    override suspend fun stop() {
        requestStop()
        stopEntered.complete(Unit)
        try {
            finishStop.await(); stopFailure?.let { throw it }
        } finally {
            stopSettled.complete(Unit)
        }
    }
}

internal class TestNotifications : StreamingModuleNotifications {
    val actions = CopyOnWriteArrayList<StreamingModuleNotifications.ForegroundAction>()
    val errors = CopyOnWriteArrayList<StreamingModuleNotifications.ErrorContent>()
    override fun buildForeground(action: StreamingModuleNotifications.ForegroundAction): Notification {
        actions += action
        return NotificationCompat.Builder(RuntimeEnvironment.getApplication(), "fixture").setContentTitle("Capture").build()
    }

    override fun updateError(notificationId: Int, content: StreamingModuleNotifications.ErrorContent?) {
        content?.let { errors += it }
    }

    override fun areNotificationsEnabled(channel: StreamingModuleNotifications.Channel): Boolean = true
    override fun channelSettingsIntent(channel: StreamingModuleNotifications.Channel): Intent? = null
}

internal fun emptyEngineStatistics(): ScreenCaptureStats {
    val engine = DefaultMjpegEngineFactory.create(
        RuntimeEnvironment.getApplication(), Shadow.newInstanceOf(MediaProjection::class.java), ScreenCaptureConfig(),
    )
    val statistics = engine.stats.value
    runBlocking(Dispatchers.Default) { withTimeout(5.seconds) { engine.stop() } }
    return statistics
}

internal fun observedAddresses(addresses: List<NetworkAddress>): NetworkAddressMonitor.State.Observed =
    NetworkAddressMonitor.State.Observed(NetworkAddressInventory(addresses.groupBy { IpAddressKey(it.ip) }))

internal object TestSessionAnalyticsFactory : StreamingSessionAnalyticsFactory {
    private val sink = object : StreamingAnalytics {
        override fun logEvent(event: StreamingAnalyticsEvent) = Unit
    }

    override fun beginConsent(key: SessionKey, streamMode: StreamMode, entryPoint: EntryPoint): StreamingSessionAnalytics =
        StreamingSessionAnalyticsTracker(sink, streamMode, entryPoint)

    override fun preflightFailed(streamMode: StreamMode, entryPoint: EntryPoint, startFailGroup: StartFailGroup) = Unit
}

internal class ControlledMjpegEngine(
    holdStart: Boolean = false,
    holdStop: Boolean = false,
    holdConsumer: Boolean = false,
) : MjpegEngineSession {
    private val observations = MutableSharedFlow<MjpegEngineState>(replay = 1, extraBufferCapacity = 16)
    override var currentState = MjpegEngineState(MjpegEngineState.Phase.NotStarted)
        private set
    override val states: Flow<MjpegEngineState> = observations
    override val stats: StateFlow<ScreenCaptureStats> = MutableStateFlow(emptyEngineStatistics())
    val startEntered = CompletableDeferred<Unit>()
    val finishStart = CompletableDeferred<Unit>().also { if (!holdStart) it.complete(Unit) }
    val stopEntered = CompletableDeferred<Unit>()
    val finishStop = CompletableDeferred<Unit>().also { if (!holdStop) it.complete(Unit) }
    val consumerCloseEntered = CompletableDeferred<Unit>()
    val finishConsumer = CompletableDeferred<Unit>().also { if (!holdConsumer) it.complete(Unit) }
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null
    var updateFailure: Throwable? = null
    var registerFailure: Throwable? = null
    var initialParameters: io.screenstream.capture.ScreenCaptureParameters? = null
    val updates = CopyOnWriteArrayList<io.screenstream.capture.ScreenCaptureParameters>()
    private var consumer: ((MjpegBorrowedJpeg) -> Unit)? = null

    init {
        observations.tryEmit(currentState)
    }

    override suspend fun start(parameters: io.screenstream.capture.ScreenCaptureParameters) {
        initialParameters = parameters
        startEntered.complete(Unit)
        startFailure?.let { throw it }
        publish(MjpegEngineState(MjpegEngineState.Phase.Active))
        finishStart.await()
    }

    override fun updateParameters(parameters: io.screenstream.capture.ScreenCaptureParameters) {
        updateFailure?.let { throw it }
        updates += parameters
    }

    override fun registerFrameConsumer(consumer: (MjpegBorrowedJpeg) -> Unit): MjpegFrameRegistration {
        registerFailure?.let { throw it }
        this.consumer = consumer
        return MjpegFrameRegistration {
            this.consumer = null
            consumerCloseEntered.complete(Unit)
            finishConsumer.await()
        }
    }

    override suspend fun stop() {
        stopEntered.complete(Unit)
        finishStop.await()
        stopFailure?.let { throw it }
        if (currentState.phase != MjpegEngineState.Phase.Failed && currentState.phase != MjpegEngineState.Phase.Stopped) {
            publish(MjpegEngineState(MjpegEngineState.Phase.Stopped, stopReason = io.screenstream.capture.ScreenCaptureStopReason.Requested))
        }
    }

    fun publish(state: MjpegEngineState, announce: Boolean = true) {
        currentState = state
        if (announce) check(observations.tryEmit(state))
    }

    fun announce(state: MjpegEngineState) {
        check(observations.tryEmit(state))
    }

    fun deliver(payload: ByteArray) {
        val callback = checkNotNull(consumer)
        val thread = Thread.currentThread()
        var entered = true
        try {
            callback(object : MjpegBorrowedJpeg {
                override val byteCount: Int
                    get() {
                        check(entered && Thread.currentThread() === thread); return payload.size
                    }

                override fun copyTo(destination: ByteArray): Int {
                    check(entered && Thread.currentThread() === thread)
                    require(destination.size >= payload.size)
                    payload.copyInto(destination)
                    return payload.size
                }
            })
        } finally {
            entered = false
        }
    }

    fun release() {
        finishStart.complete(Unit); finishStop.complete(Unit); finishConsumer.complete(Unit)
    }
}

@org.robolectric.annotation.Implements(MediaProjection::class)
internal class InertMjpegProjectionShadow {
    @org.robolectric.annotation.Implementation
    protected fun stop() = Unit
}
