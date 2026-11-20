package io.screenstream.mjpeg

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import io.screenstream.core.StreamingKoinModule
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.capture.ConsentRequestObserver
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class MjpegServiceTestHost(
    val settings: MjpegSettings,
    val http: ControlledHttpDelivery = ControlledHttpDelivery(),
    val monitor: ControlledAddressMonitor = ControlledAddressMonitor(),
    val access: ControlledCaptureAccess = ControlledCaptureAccess(),
    targetSdk: Int? = null,
    engineFactory: MjpegEngineFactory = DefaultMjpegEngineFactory,
    delivery: HttpDelivery = http,
) {
    val notifications = RecordingModuleNotifications()
    val serviceController: ServiceController<MjpegStreamingModuleService>
    val service: MjpegStreamingModuleService get() = serviceController.get()
    lateinit var controller: MjpegStreamingModuleController
        private set

    /** Observes the real manager; the controller receives its original handle. */
    lateinit var managerObservation: RecordingManagerHandle
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
                modules(silentMjpegLoggerModule)
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
                        managerObservation = RecordingManagerHandle(originalHandle)
                        MjpegStreamingModuleController(
                            managerHandle = originalHandle, service = originalService, settings = lazyOf(settings),
                            addressMonitor = monitor, captureAccess = access, http = delivery,
                            analyticsFactory = DiscardingSessionAnalyticsFactory, engineFactory = engineFactory,
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
                    is StreamingModuleManager.State.Running -> managerObservation.status.value = state.status
                    is StreamingModuleManager.State.Unresponsive -> managerObservation.status.value = state.lastStatus
                    is StreamingModuleManager.State.Failed -> managerObservation.failed.complete(Unit)
                    else -> Unit
                }
            }.launchIn(observationScope)
        } catch (failure: Throwable) {
            fun retainFailure(action: () -> Unit) {
                runCatching(action).onFailure { if (it !== failure) failure.addSuppressed(it) }
            }
            retainFailure { if (::controller.isInitialized) controller.requestModuleShutdown() }
            http.allowStopToReturn.complete(Unit)
            monitor.allowCloseToReturn.complete(Unit)
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
            http.allowStopToReturn.complete(Unit)
            monitor.allowCloseToReturn.complete(Unit)
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
