package io.screenstream.mjpeg

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.LocalNetworkPermissionRequest
import io.screenstream.mjpeg.ui.MjpegUiController
import io.screenstream.mjpeg.ui.UiController
import io.screenstream.mjpeg.ui.UiController.Action
import io.screenstream.streaming.capture.ScreenCaptureAccess
import io.screenstream.streaming.logE
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import kotlin.time.Duration.Companion.milliseconds

/**
 * Serializes commands and applied settings for one module instance. Setup edits wait for successful
 * capture cleanup; Start, exact Stop and PIN edits are admitted when the event loop handles them.
 */
@Factory(binds = [])
internal class MjpegStreamingModuleController(
    @InjectedParam private val runtime: StreamingModule.Controller.Runtime,
    @InjectedParam private val service: MjpegStreamingModuleService,
    private val settings: Lazy<MjpegSettings>,
    private val addressMonitor: NetworkAddressMonitor,
    private val captureAccess: ScreenCaptureAccess,
    private val http: HttpDelivery,
) : StreamingModule.Controller {
    override val instanceId: StreamingModule.InstanceId = runtime.instanceId

    private val uiController = MjpegUiController(
        instanceId = instanceId,
        onUiCommand = { enqueue(Event.UiCommand(it)) },
        settings = settings,
    )

    private val controllerJob = SupervisorJob()
    private val controllerScope = CoroutineScope(controllerJob + Dispatchers.Default)
    private val eventQueue = Channel<Event>(Channel.UNLIMITED)
    private val shutdownRequested = CompletableDeferred<Unit>()

    // Admission and terminal shutdown share this gate so cleanup cannot miss a newly created owner.
    // Retain the last admitted session even after its capture activity ends.
    private val captureAdmission = Any()
    private var captureForCleanup: MjpegCaptureSession? = null

    // Only the event loop mutates these module facts and its exclusive activity.
    private var activity: Activity = Activity.NotStarted

    private var effectiveSettings: MjpegSettings.Data? = null
    private var latestObservedSettings: MjpegSettings.Data? = null
    private var pinSaveJob: Job? = null

    private var addressState: NetworkAddressMonitor.State = NetworkAddressMonitor.State.NotStarted
    private var selectedAddresses: List<NetworkAddress> = emptyList()
    private var configurationApplied = false
    private var networkLoss: NetworkLossEpisode? = null

    private var reportedStatus: StreamingModule.Status? = null
    private var localNetworkPermissionGranted = hasLocalNetworkPermission()
    private val editPolicy: MjpegSettings.EditPolicy
        get() = if (activity == Activity.Idle) MjpegSettings.EditPolicy.All else MjpegSettings.EditPolicy.LiveOnly

    // Synchronous permission launch claims the current request, never mutable controller activity.
    @Volatile
    private var permissionRequest: LocalNetworkPermissionRequest? = null

    private val eventLoopJob = controllerScope.launch(start = CoroutineStart.LAZY) { runEventLoop() }

    /** Independent of event work and waiters; completion includes admitted operations and Service destruction. */
    private val cleanupResult: Deferred<Boolean> = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
        cleanUpModule()
    }

    override fun startModule() {
        if (!runtime.isCurrent()) requestModuleShutdown() else eventLoopJob.start()
    }

    override fun requestStreamStart() {
        enqueue(Event.UiCommand(UiController.Command.Start))
    }

    override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId) {
        enqueue(Event.UiCommand(UiController.Command.Stop(attempt)))
    }

    /** The sole terminal ingress: close owners before cancelling work, independently of the queue. */
    override fun requestModuleShutdown() {
        if (!shutdownRequested.complete(Unit)) return
        try {
            service.closeForegroundAdmission()
        } finally {
            try {
                http.requestStop()
            } finally {
                try {
                    synchronized(captureAdmission) { captureForCleanup }?.stop(MjpegCaptureSession.StopReason.Shutdown)
                } finally {
                    controllerScope.cancel()
                    eventQueue.close()
                    uiController.clear()
                    cleanupResult.start()
                }
            }
        }
    }

    override suspend fun awaitCleanup(): Boolean = cleanupResult.await()

    @Composable
    override fun Content(window: WindowSizeClass, modifier: Modifier) {
        uiController.Content(window, modifier)
    }

    override fun onServiceDestroyed() {
        try {
            runtime.reportFailed()
        } finally {
            requestModuleShutdown()
        }
    }

    private fun canRequestLocalNetworkPermission(request: LocalNetworkPermissionRequest): Boolean {
        val current = permissionRequest ?: return false
        if (shutdownRequested.isCompleted) return false
        if (!runtime.isCurrent()) return false
        if (current !== request) return false
        return !hasLocalNetworkPermission()
    }

    private fun enqueue(event: Event) {
        if (!shutdownRequested.isCompleted) eventQueue.trySend(event)
    }

    private fun observeInputs() {
        settings.value.data
            .onStart {
                settings.value.updateData {
                    if (access.pinEnabled && access.pinPolicy == AccessSettings.PinPolicy.NewOnModuleStart) {
                        copy(access = access.withGeneratedPin())
                    } else this
                }
            }
            .onEach { enqueue(Event.SettingsObserved(it)) }
            .catch { enqueue(Event.ComponentFailed(FailureSource.Settings, it)) }
            .launchIn(controllerScope)

        addressMonitor.state
            .onEach { enqueue(Event.AddressStateChanged(it)) }
            .catch { enqueue(Event.ComponentFailed(FailureSource.AddressMonitor, it)) }
            .launchIn(controllerScope)

        http.info
            .distinctUntilChanged { previous, current ->
                previous.servers == current.servers && previous.hasConsumer == current.hasConsumer && previous.fatalCause === current.fatalCause
            }
            .onEach { enqueue(Event.DeliveryChanged) }
            .catch { enqueue(Event.ComponentFailed(FailureSource.Delivery, it)) }
            .launchIn(controllerScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runEventLoop() {
        try {
            observeInputs()

            var nextHeartbeatAtUptimeMillis = SystemClock.uptimeMillis()
            while (!shutdownRequested.isCompleted && runtime.isCurrent()) {
                currentCoroutineContext().ensureActive()

                val now = SystemClock.uptimeMillis()
                if (now >= nextHeartbeatAtUptimeMillis) {
                    reportStatus(now)
                    nextHeartbeatAtUptimeMillis = now + HEARTBEAT_MILLIS
                    checkLocalNetworkPermission()
                }

                val networkLossDelayMillis = updateNetworkLossDeadline()
                val heartbeatDelayMillis = (nextHeartbeatAtUptimeMillis - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                val waitMillis = minOf(heartbeatDelayMillis, networkLossDelayMillis ?: Long.MAX_VALUE)
                val event: Event? = select {
                    eventQueue.onReceive { it }
                    onTimeout(waitMillis.milliseconds) { null }
                }
                if (shutdownRequested.isCompleted || !runtime.isCurrent()) break

                when (event) {
                    is Event.UiCommand -> when (val uiCommand = event.uiCommand) {
                        UiController.Command.Start -> startCapture()
                        is UiController.Command.Stop -> stopCapture(uiCommand.attempt, MjpegCaptureSession.StopReason.User)
                        is UiController.Command.RetryServer -> http.retryServer(uiCommand.serverId)
                        is UiController.Command.EditPin -> editPin(uiCommand)
                        UiController.Command.CheckLocalNetworkPermission -> checkLocalNetworkPermission()
                    }

                    is Event.SettingsObserved -> applySettings(event.settings)
                    is Event.AddressStateChanged -> {
                        check(event.state != NetworkAddressMonitor.State.Closed && event.state != NetworkAddressMonitor.State.Failed) {
                            "NetworkAddress monitor stopped while controller is open"
                        }
                        addressState = event.state
                        updateServerPlan()
                    }

                    Event.DeliveryChanged -> Unit // The fresh delivery snapshot is read below for this turn.
                    is Event.CaptureChanged -> applyCaptureState(event.attempt)
                    is Event.ComponentFailed -> fail(event.source, event.cause)
                    null -> Unit
                }

                if (shutdownRequested.isCompleted) break
                val delivery = deliverySnapshot()
                publishUi(delivery)
                val status = moduleStatus(delivery)
                if (status != reportedStatus) reportStatus(SystemClock.uptimeMillis(), status)
            }
        } catch (cause: Throwable) {
            if (!shutdownRequested.isCompleted) fail(FailureSource.Controller, cause)
        } finally {
            activity = Activity.Closing
            requestModuleShutdown()
            uiController.clear()
        }
    }

    private fun startCapture() {
        val acceptedSettings = effectiveSettings ?: return
        if (activity != Activity.Idle || !hasAvailableListener()) return
        val id = StreamingModule.CaptureAttemptId(instanceId)
        val captureActivity = synchronized(captureAdmission) {
            if (shutdownRequested.isCompleted || !http.registerCapture(id)) return
            val session = MjpegCaptureSession(
                id = id,
                context = service,
                controllerScope = controllerScope,
                captureAccess = captureAccess,
                enterForeground = { service.enterCaptureForeground(id) },
                leaveForeground = service::leaveCaptureForeground,
                onJpeg = { byteCount, copyTo -> http.offerJpeg(id, byteCount, copyTo) },
            )
            val initialActivity = Activity.Capturing(session, session.state.value)
            session.runCapture(acceptedSettings.image)
            captureForCleanup = session
            initialActivity
        }

        activity = captureActivity
        captureActivity.session.state
            .onEach { enqueue(Event.CaptureChanged(id)) }
            .takeWhile { it.cleanupSucceeded == null }
            .launchIn(controllerScope)
    }

    private suspend fun stopCapture(attempt: StreamingModule.CaptureAttemptId, reason: MjpegCaptureSession.StopReason) {
        val capture = activity as? Activity.Capturing ?: return
        if (capture.session.id != attempt) return

        networkLoss = null
        capture.session.stop(reason)
        applyCaptureState(attempt)
    }

    private suspend fun applyCaptureState(attempt: StreamingModule.CaptureAttemptId) {
        val capture = activity as? Activity.Capturing ?: return
        if (capture.session.id != attempt) return

        val latestState = capture.session.state.value
        val captureFailure = latestState.captureFailure
        if (captureFailure != null && captureFailure !== capture.handledState.captureFailure) {
            logE(captureFailure.stage, "Capture failed: $attempt", captureFailure.cause)
            controllerScope.launch { service.showCaptureError() }
        }

        val cleanupFailure = latestState.cleanupFailure
        if (cleanupFailure != null && cleanupFailure !== capture.handledState.cleanupFailure) {
            logE(cleanupFailure.stage, "Capture failed: $attempt", cleanupFailure.cause)
            controllerScope.launch { service.showCaptureError() }
        }

        activity = when (latestState.cleanupSucceeded) {
            null -> capture.copy(handledState = latestState)
            true -> {
                networkLoss = null
                Activity.Idle
            }

            false -> {
                networkLoss = null
                Activity.BlockedCleanup(attempt)
            }
        }

        http.updateCaptureStatus(attempt, latestState.status)
        if (latestState.cleanupSucceeded == true) {
            latestObservedSettings?.let { applySettings(it) }
        }
    }

    private fun editPin(edit: UiController.Command.EditPin) {
        if (!editPolicy.canEditStreamSetup) return
        if (edit is UiController.Command.EditPin.Set) {
            if (effectiveSettings?.access?.pinPolicy != AccessSettings.PinPolicy.Permanent) return
            if (!AccessSettings.isValidPin(edit.pin.value)) return
        }
        val previousSaveJob = pinSaveJob
        pinSaveJob = controllerScope.launch {
            try {
                previousSaveJob?.join()
                settings.value.updateData {
                    copy(
                        access = when (edit) {
                            UiController.Command.EditPin.Regenerate -> access.withGeneratedPin()
                            is UiController.Command.EditPin.Set if access.pinPolicy == AccessSettings.PinPolicy.Permanent -> access.copy(pin = edit.pin)
                            is UiController.Command.EditPin.Set -> access
                        }
                    )
                }
            } catch (cause: Throwable) {
                if (cause !is CancellationException || !shutdownRequested.isCompleted) {
                    this@MjpegStreamingModuleController.logE("PIN.edit", "Could not save PIN", cause)
                }
            }
        }
    }

    private suspend fun updateHttpConfiguration() {
        val settings = effectiveSettings ?: return
        val applied = if (!configurationApplied) {
            val logoBytes = withContext(Dispatchers.IO) {
                service.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
            }
            http.start(
                accessSettings = settings.access,
                postStopImage = settings.behavior.postStopImage,
                webSettings = settings.web,
                logoBytes = logoBytes,
            )
        } else {
            http.updateConfiguration(
                accessSettings = settings.access,
                postStopImage = settings.behavior.postStopImage,
                webSettings = settings.web,
            )
        }
        check(applied) { "HTTP rejected observed configuration" }
        configurationApplied = true
    }

    private suspend fun applySettings(observed: MjpegSettings.Data) {
        latestObservedSettings = observed
        val previous = effectiveSettings
        val accepted = if (previous == null) observed else editPolicy.effective(previous, observed)
        effectiveSettings = accepted

        if (previous?.image != accepted.image) {
            (activity as? Activity.Capturing)?.session?.updateImageSettings(accepted.image)
        }

        if (previous?.network?.filter != accepted.network.filter) {
            permissionRequest = if (accepted.network.filter.requiresLocalNetworkPermission) {
                LocalNetworkPermissionRequest(::canRequestLocalNetworkPermission)
            } else {
                null
            }
            addressMonitor.updateFilter(accepted.network.filter)
        }

        if (previous?.network != accepted.network) {
            updateServerPlan()
        }
        if (activity == Activity.NotStarted) {
            activity = Activity.Idle
        }

        val httpConfigurationChanged = previous == null || previous.access != accepted.access ||
                previous.web != accepted.web || previous.behavior.postStopImage != accepted.behavior.postStopImage
        if (httpConfigurationChanged) {
            updateHttpConfiguration()
        }
    }

    private suspend fun checkLocalNetworkPermission() {
        val granted = hasLocalNetworkPermission()
        if (localNetworkPermissionGranted == granted) return
        localNetworkPermissionGranted = granted
        updateServerPlan()
    }

    /** Keep the last ready selection during discovery changes; permission always filters it immediately. */
    private suspend fun updateServerPlan() {
        val network = effectiveSettings?.network ?: return
        val observed = addressState as? NetworkAddressMonitor.State.Observed
        if (observed?.filter == network.filter) selectedAddresses = observed.addresses
        http.configureServers(
            addresses = selectedAddresses.filter { !it.requiresLocalNetworkPermission || localNetworkPermissionGranted },
            port = network.httpPort,
        )
    }

    /** A single capture's observed empty selection; timeout edits retain the beginning of that loss. */
    private suspend fun updateNetworkLossDeadline(): Long? {
        val capture = activity as? Activity.Capturing
        val data = effectiveSettings
        val observed = addressState as? NetworkAddressMonitor.State.Observed
        val captureStatus = capture?.session?.state?.value?.status
        if (capture == null ||
            captureStatus == MjpegCaptureSession.State.Status.Stopping ||
            captureStatus == MjpegCaptureSession.State.Status.Stopped ||
            captureStatus == MjpegCaptureSession.State.Status.Failed ||
            data == null || observed == null ||
            observed.filter != data.network.filter || observed.addresses.isNotEmpty()
        ) {
            networkLoss = null
            return null
        }

        val now = SystemClock.elapsedRealtime()
        val episode = networkLoss?.takeIf { it.attempt == capture.session.id && it.filter == observed.filter }
            ?: NetworkLossEpisode(attempt = capture.session.id, filter = observed.filter, observedAtElapsedRealtimeMillis = now)
        networkLoss = episode

        val remaining = episode.observedAtElapsedRealtimeMillis + data.behavior.stopAfterNetworkLossSeconds * 1_000L - now
        if (remaining > 0L) return remaining

        // Recovery may already be queued; the deadline must respect the current selected-address fact.
        val current = addressMonitor.state.value as? NetworkAddressMonitor.State.Observed
        if (current == null || current.filter != data.network.filter || current.addresses.isNotEmpty()) {
            networkLoss = null
            return null
        }
        stopCapture(capture.session.id, MjpegCaptureSession.StopReason.System)
        return null
    }

    /** UI availability checks current observations; HTTP owns the last applied ready plan. */
    private fun hasAvailableListener(delivery: HttpDelivery.Info = http.info.value): Boolean {
        val network = effectiveSettings?.network ?: return false
        val observed = addressMonitor.state.value as? NetworkAddressMonitor.State.Observed ?: return false
        return !shutdownRequested.isCompleted && runtime.isCurrent() && observed.filter == network.filter &&
                delivery.servers.any { listener ->
                    listener.state == HttpDelivery.ServerState.Listening && listener.port == network.httpPort &&
                            observed.addresses.any { it.id == listener.address.id } &&
                            selectedAddresses.any { address ->
                                address.id == listener.address.id && (!address.requiresLocalNetworkPermission || localNetworkPermissionGranted)
                            }
                }
    }

    @SuppressLint("InlinedApi")
    private fun hasLocalNetworkPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            service.applicationInfo.targetSdkVersion < Build.VERSION_CODES.CINNAMON_BUN ||
            service.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun deliverySnapshot(): HttpDelivery.Info = http.info.value.also { delivery -> delivery.fatalCause?.let { throw it } }

    private fun publishUi(delivery: HttpDelivery.Info) {
        uiController.update(
            action = activity.toUiAction(startAvailable = activity == Activity.Idle && configurationApplied && hasAvailableListener(delivery)),
            suspensionProblem = (activity as? Activity.Capturing)?.handledState?.suspensionProblem,
            selectedAddresses = selectedAddresses,
            port = effectiveSettings?.network?.httpPort,
            servers = delivery.servers,
            localNetworkPermissionGranted = localNetworkPermissionGranted,
            accessApplied = configurationApplied,
            appliedAccessToken = http.appliedAccessToken,
            permissionRequest = permissionRequest,
            editPolicy = editPolicy,
        )
    }

    private fun moduleStatus(delivery: HttpDelivery.Info = deliverySnapshot()): StreamingModule.Status {
        val currentActivity = activity
        return StreamingModule.Status(
            isStreaming = (currentActivity as? Activity.Capturing)?.handledState?.isStreaming == true,
            hasConsumer = delivery.hasConsumer,
            captureAttempt = when (currentActivity) {
                is Activity.Capturing -> currentActivity.session.id
                is Activity.BlockedCleanup -> currentActivity.attempt
                Activity.NotStarted, Activity.Idle, Activity.Closing -> null
            },
        )
    }

    private fun reportStatus(heartbeatAtUptimeMillis: Long, status: StreamingModule.Status = moduleStatus()) {
        runtime.reportRunning(status, heartbeatAtUptimeMillis)
        reportedStatus = status
    }

    /** All owners start cleanup independently; one failed obligation cannot skip another. */
    private suspend fun cleanUpModule(): Boolean = coroutineScope {
        val httpCleanup = async {
            try {
                http.stop()
                true
            } catch (_: Throwable) {
                false
            }
        }

        val addressMonitorCleanup = async {
            try {
                addressMonitor.close()
                true
            } catch (failure: Throwable) {
                this@MjpegStreamingModuleController.logE("Cleanup.addressMonitor", "NetworkAddress monitor cleanup failed", failure)
                false
            }
        }

        val platformCleanup = async {
            val attemptSucceeded = try {
                val capture = synchronized(captureAdmission) { captureForCleanup }
                capture?.awaitCleanup() ?: true
            } catch (failure: Throwable) {
                this@MjpegStreamingModuleController.logE("Cleanup.capture", "Capture cleanup failed", failure)
                false
            }

            var succeeded = try {
                service.leaveCaptureForeground()
                true
            } catch (failure: Throwable) {
                this@MjpegStreamingModuleController.logE("Cleanup.foreground", "Foreground cleanup failed", failure)
                false
            }

            try {
                service.stopSelf()
            } catch (failure: Throwable) {
                succeeded = false
                this@MjpegStreamingModuleController.logE("Cleanup.serviceStop", "Service stop failed", failure)
            }

            try {
                service.awaitDestroyed()
            } catch (failure: Throwable) {
                succeeded = false
                this@MjpegStreamingModuleController.logE("Cleanup.serviceDestroyed", "Service destruction wait failed", failure)
            }

            attemptSucceeded && succeeded
        }

        // Join admitted controller operations even after activity becomes Closing.
        controllerJob.join()
        val addressMonitorSucceeded = addressMonitorCleanup.await()
        val platformSucceeded = platformCleanup.await()
        val httpSucceeded = httpCleanup.await()
        addressMonitorSucceeded && platformSucceeded && httpSucceeded
    }

    private fun fail(source: FailureSource, cause: Throwable) {
        if (shutdownRequested.isCompleted) return
        if (cause !== http.info.value.fatalCause) logE("fail", "Controller failed: $source", cause)
        try {
            runtime.reportFailed()
        } finally {
            requestModuleShutdown()
        }
    }

    private sealed interface Activity {
        data object NotStarted : Activity
        data object Idle : Activity

        // The session owns resources; handledState is the loop's last processed state.
        data class Capturing(val session: MjpegCaptureSession, val handledState: MjpegCaptureSession.State) : Activity

        data class BlockedCleanup(val attempt: StreamingModule.CaptureAttemptId) : Activity
        data object Closing : Activity

        fun toUiAction(startAvailable: Boolean): Action = when (this) {
            Idle -> Action.Start(startAvailable)
            is BlockedCleanup -> Action.Start(enabled = false)
            is Capturing ->
                if (handledState.isStreaming || handledState.status == MjpegCaptureSession.State.Status.Suspended) {
                    Action.Stop(session.id)
                } else {
                    Action.Busy
                }

            NotStarted, Closing -> Action.Busy
        }
    }

    private sealed interface Event {
        data class UiCommand(val uiCommand: UiController.Command) : Event
        data class SettingsObserved(val settings: MjpegSettings.Data) : Event
        data class AddressStateChanged(val state: NetworkAddressMonitor.State) : Event
        data object DeliveryChanged : Event
        data class CaptureChanged(val attempt: StreamingModule.CaptureAttemptId) : Event
        data class ComponentFailed(val source: FailureSource, val cause: Throwable) : Event
    }

    private data class NetworkLossEpisode(
        val attempt: StreamingModule.CaptureAttemptId,
        val filter: NetworkAddressFilter,
        val observedAtElapsedRealtimeMillis: Long,
    )

    private enum class FailureSource { Settings, AddressMonitor, Delivery, Controller }
    private companion object {
        private const val HEARTBEAT_MILLIS: Long = 1_000L
    }
}
