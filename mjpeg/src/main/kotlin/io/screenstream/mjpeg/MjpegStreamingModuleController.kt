package io.screenstream.mjpeg

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.screenoff.AndroidScreenOffObserver
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.MjpegUiController
import io.screenstream.mjpeg.ui.UiController
import io.screenstream.mjpeg.ui.UiController.Action
import io.screenstream.streaming.capture.ScreenCaptureAccess
import io.screenstream.streaming.logE
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.permission.RuntimePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Serializes commands and applied settings for one module instance. Setup edits wait for successful
 * capture cleanup; Start and exact Stop are admitted when the event loop handles them.
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

    private val controllerJob = SupervisorJob()
    private val controllerScope = CoroutineScope(controllerJob + Dispatchers.Default)
    private val screenOffObserver = AndroidScreenOffObserver(service)
    private val uiController = MjpegUiController(
        instanceId = instanceId,
        onUiCommand = { enqueue(Event.UiCommand(it)) },
        settings = settings,
    )

    private val eventQueue = Channel<Event>(Channel.UNLIMITED)
    private val shutdownRequested = CompletableDeferred<Unit>()

    // Admission and terminal shutdown share this gate so cleanup cannot miss a newly created owner.
    // Retain the last admitted attempt even after its cleanup finishes.
    private val captureAdmissionGate = Any()
    private var captureAttempt: CaptureAttempt? = null

    // Only the event loop mutates these module facts and each attempt's processed state.
    private var effectiveSettings: MjpegSettings.Data? = null
    private var latestObservedSettings: MjpegSettings.Data? = null
    private var lastFailure: MjpegCaptureSession.State.Failure? = null

    private var addressState: NetworkAddressMonitor.State = NetworkAddressMonitor.State.NotStarted
    private var selectedAddresses: List<NetworkAddress> = emptyList()
    private var screenOffRegistration: ScreenOffRegistration? = null
    private var slowSampleWatermarkMillis: Long? = null
    private var slowEpisodeWarningConsumed = false
    private var lastSlowToastAttemptMillis: Long? = null

    private var reportedStatus: StreamingModule.Status? = null
    private var localNetworkPermissionGranted = RuntimePermission.LocalNetwork.isGranted(service)
    private val activeCaptureAttempt: CaptureAttempt?
        get() = captureAttempt?.takeIf { it.processedState.cleanupSucceeded == null }
    private val isCaptureIdle: Boolean
        get() = !shutdownRequested.isCompleted && effectiveSettings != null &&
                (captureAttempt == null || captureAttempt?.processedState?.cleanupSucceeded == true)

    private val isStopDelayed: Boolean
        get() {
            val capture = activeCaptureAttempt ?: return false
            val stoppingSince = capture.stopRequestedAtElapsedRealtimeMillis ?: return false
            return capture.session.state.value.cleanupSucceeded != true &&
                    SystemClock.elapsedRealtime() - stoppingSince >= STOP_WARNING_MILLIS
        }

    private val errorMessage: String?
        get() = lastFailure?.let { failure ->
            failure.cause.message?.trim()?.takeIf { it.isNotEmpty() } ?: failure.stage.ifBlank { failure.cause.javaClass.name }
        } ?: "Capture cleanup is still pending".takeIf { isStopDelayed }

    private val editPolicy: MjpegSettings.EditPolicy
        get() = if (isCaptureIdle) MjpegSettings.EditPolicy.All else MjpegSettings.EditPolicy.LiveOnly

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

    override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean) {
        enqueue(Event.UiCommand(UiController.Command.Stop(attempt)))
    }

    /** The sole terminal ingress: close owners before cancelling work, independently of the queue. */
    override fun requestModuleShutdown() {
        screenOffObserver.close()
        if (!shutdownRequested.complete(Unit)) return
        try {
            service.closeForegroundAdmission()
        } finally {
            try {
                val session = synchronized(captureAdmissionGate) { captureAttempt?.session }
                session?.requestStop(MjpegCaptureSession.StopReason.Shutdown)
            } finally {
                try {
                    http.requestStop()
                } finally {
                    controllerScope.cancel()
                    eventQueue.close()
                    cleanupResult.start()
                }
            }
        }
    }

    override suspend fun awaitCleanup(): Boolean = cleanupResult.await()

    @Composable
    override fun Content(window: WindowSizeClass, modifier: Modifier) {
        uiController.Content(modifier = modifier)
    }

    override fun onServiceDestroyed() {
        try {
            runtime.reportFailed()
        } finally {
            requestModuleShutdown()
        }
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

                val addressLossDelayMillis = checkAddressLossTimeout()
                val heartbeatDelayMillis = (nextHeartbeatAtUptimeMillis - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                val waitMillis = minOf(heartbeatDelayMillis, addressLossDelayMillis ?: Long.MAX_VALUE)
                val event: Event? = select {
                    eventQueue.onReceive { it }
                    onTimeout(waitMillis.milliseconds) { null }
                }
                if (shutdownRequested.isCompleted || !runtime.isCurrent()) break

                handleEvent(event)

                if (shutdownRequested.isCompleted) break
                publishCurrentState()
            }
        } catch (cause: Throwable) {
            if (!shutdownRequested.isCompleted) fail(FailureSource.Controller, cause)
        } finally {
            slowSampleWatermarkMillis = null
            slowEpisodeWarningConsumed = false
            requestModuleShutdown()
        }
    }

    private suspend fun handleEvent(event: Event?) {
        when (event) {
            is Event.UiCommand -> when (val uiCommand = event.uiCommand) {
                UiController.Command.Start -> startCapture()
                is UiController.Command.Stop -> stopCapture(uiCommand.attempt, MjpegCaptureSession.StopReason.User)
                is UiController.Command.RetryServer -> http.retryServer(uiCommand.serverId)
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
            is Event.ScreenOff -> {
                val registration = screenOffRegistration
                if (registration != null && registration.attempt == event.attempt && registration.id == event.registrationId &&
                    effectiveSettings?.behavior?.stopOnScreenOff == true
                ) {
                    stopCapture(event.attempt, MjpegCaptureSession.StopReason.System)
                }
            }

            is Event.CaptureChanged -> reconcileCapture(event.attempt)
            is Event.ComponentFailed -> fail(event.source, event.cause)
            null -> Unit
        }
    }

    private suspend fun publishCurrentState() {
        val delivery = readDeliveryOrThrow()
        updateSlowViewerWarning(delivery)
        publishUi(delivery)
        val status = moduleStatus(delivery)
        if (status != reportedStatus) reportStatus(SystemClock.uptimeMillis(), status)
    }

    private fun startCapture() {
        val acceptedSettings = effectiveSettings ?: return
        if (!isCaptureIdle || !hasAvailableListener()) return
        val id = StreamingModule.CaptureAttemptId(instanceId)
        if (acceptedSettings.behavior.stopOnScreenOff) {
            try {
                if (registerScreenOffStop(id) != AndroidScreenOffObserver.RegistrationResult.Registered) return
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                reportScreenOffFailure(failure)
                return
            }
        }
        val capture = synchronized(captureAdmissionGate) {
            if (shutdownRequested.isCompleted) return@synchronized null
            val initialCaptureGeneration = Any()
            val session = MjpegCaptureSession(
                id = id,
                initialImageSettings = acceptedSettings.image,
                initialCaptureGeneration = initialCaptureGeneration,
                context = service,
                controllerScope = controllerScope,
                captureAccess = captureAccess,
                http = http,
                enterForeground = { service.enterCaptureForeground(id, requiresMicrophoneType = false) },
                leaveForeground = { service.leaveCaptureForeground(id) },
            )
            val attempt = CaptureAttempt(session = session, failureBeforeStart = lastFailure)
            if (!http.registerCapture(id, initialCaptureGeneration)) return@synchronized null
            captureAttempt = attempt
            session.start()
            attempt
        }

        if (capture == null) {
            removeScreenOffStop()
            return
        }

        capture.session.state
            .onEach { enqueue(Event.CaptureChanged(id)) }
            .takeWhile { it.cleanupSucceeded == null }
            .launchIn(controllerScope)
    }

    private suspend fun stopCapture(attempt: StreamingModule.CaptureAttemptId, reason: MjpegCaptureSession.StopReason) {
        val capture = activeCaptureAttempt ?: return
        if (capture.session.id != attempt) return

        capture.addressLoss = null
        capture.stopRequestedAtElapsedRealtimeMillis = capture.stopRequestedAtElapsedRealtimeMillis ?: SystemClock.elapsedRealtime()
        capture.session.requestStop(reason)
        removeScreenOffStop()
        reconcileCapture(attempt)
    }

    private suspend fun reconcileCapture(attempt: StreamingModule.CaptureAttemptId) {
        val capture = activeCaptureAttempt ?: return
        if (capture.session.id != attempt) return

        capture.session.updateScreenAwake(effectiveSettings?.behavior?.keepScreenAwake == true)
        val latestState = capture.session.state.value
        service.updateCaptureNotification(attempt, latestState.isStreaming)
        if (!capture.processedState.startSucceeded && latestState.startSucceeded && lastFailure === capture.failureBeforeStart) {
            lastFailure = null
        }
        capture.stopRequestedAtElapsedRealtimeMillis = when {
            latestState.cleanupSucceeded != null -> null
            latestState.isStopRequested -> capture.stopRequestedAtElapsedRealtimeMillis ?: SystemClock.elapsedRealtime()
            else -> null
        }
        if (latestState.isStopRequested) removeScreenOffStop()
        reportNewSessionFailure(attempt, capture.processedState.captureFailure, latestState.captureFailure)
        reportNewSessionFailure(attempt, capture.processedState.cleanupFailure, latestState.cleanupFailure)

        if (latestState.cleanupSucceeded != null) capture.addressLoss = null
        capture.processedState = latestState

        if (latestState.cleanupSucceeded == false) {
            runtime.reportFailed()
            requestModuleShutdown()
            return
        }

        if (latestState.cleanupSucceeded == true) {
            latestObservedSettings?.let { applySettings(it) }
        }
    }

    private fun reportNewSessionFailure(
        attempt: StreamingModule.CaptureAttemptId,
        previous: MjpegCaptureSession.State.Failure?,
        current: MjpegCaptureSession.State.Failure?,
    ) {
        if (current == null || current === previous) return
        logE(current.stage, "Capture failed: $attempt", current.cause)
        lastFailure = current
    }

    private suspend fun applySettings(observed: MjpegSettings.Data) {
        latestObservedSettings = observed
        val previous = effectiveSettings
        val accepted = if (previous == null) observed else editPolicy.effective(previous, observed)
        effectiveSettings = accepted
        activeCaptureAttempt?.session?.updateScreenAwake(accepted.behavior.keepScreenAwake)

        if (previous?.image != accepted.image) {
            activeCaptureAttempt?.session?.updateImageSettings(accepted.image)
        }

        if (previous == null) addressMonitor.start()

        if (previous?.network != accepted.network) {
            updateServerPlan()
        }

        // PIN visibility is local UI state and does not change HTTP access.
        val httpConfigurationChanged = previous == null ||
                previous.access.copy(hidePinOnCaptureStart = accepted.access.hidePinOnCaptureStart) != accepted.access ||
                previous.web != accepted.web || previous.behavior.postStopImage != accepted.behavior.postStopImage
        if (httpConfigurationChanged) {
            val applied = if (previous == null) {
                val logoBytes = withContext(Dispatchers.IO) {
                    service.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
                }
                http.start(
                    accessSettings = accepted.access,
                    postStopImage = accepted.behavior.postStopImage,
                    webSettings = accepted.web,
                    logoBytes = logoBytes,
                )
            } else {
                http.updateConfiguration(
                    accessSettings = accepted.access,
                    postStopImage = accepted.behavior.postStopImage,
                    webSettings = accepted.web,
                )
            }
            check(applied) { "HTTP rejected observed configuration" }
        }
        updateScreenOffStop()
    }

    private fun registerScreenOffStop(attempt: StreamingModule.CaptureAttemptId): AndroidScreenOffObserver.RegistrationResult {
        val registrationId = Uuid.random()
        val result = screenOffObserver.register(registrationId) {
            enqueue(Event.ScreenOff(attempt, registrationId))
        }
        if (result == AndroidScreenOffObserver.RegistrationResult.Registered) {
            screenOffRegistration = ScreenOffRegistration(attempt, registrationId)
        }
        return result
    }

    private suspend fun updateScreenOffStop() {
        val capture = activeCaptureAttempt
        if (effectiveSettings?.behavior?.stopOnScreenOff != true || capture == null ||
            capture.session.state.value.isStopRequested
        ) {
            removeScreenOffStop()
            return
        }
        if (screenOffRegistration?.attempt == capture.session.id) return

        try {
            when (registerScreenOffStop(capture.session.id)) {
                AndroidScreenOffObserver.RegistrationResult.Registered -> Unit
                AndroidScreenOffObserver.RegistrationResult.ScreenOff ->
                    stopCapture(capture.session.id, MjpegCaptureSession.StopReason.System)

                AndroidScreenOffObserver.RegistrationResult.Rejected -> Unit
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            reportScreenOffFailure(failure)
            stopCapture(capture.session.id, MjpegCaptureSession.StopReason.Error)
        }
    }

    private fun removeScreenOffStop() {
        val registration = screenOffRegistration ?: return
        screenOffRegistration = null
        try {
            screenOffObserver.unregister(registration.id)
        } catch (failure: Exception) {
            reportScreenOffFailure(failure)
        }
    }

    private fun reportScreenOffFailure(failure: Exception) {
        logE("ScreenOff", "Could not manage the screen-off receiver", failure)
        lastFailure = MjpegCaptureSession.State.Failure("ScreenOff", failure)
    }

    private suspend fun checkLocalNetworkPermission() {
        val granted = RuntimePermission.LocalNetwork.isGranted(service)
        if (localNetworkPermissionGranted == granted) return
        localNetworkPermissionGranted = granted
        updateServerPlan()
    }

    /** Select from processed discovery evidence; retain the last selection while discovery is loading. */
    private suspend fun updateServerPlan() {
        val network = effectiveSettings?.network ?: return
        val observed = addressState as? NetworkAddressMonitor.State.Observed
        if (observed != null) selectedAddresses = observed.inventory.select(network.filter)
        val addresses = selectedAddresses.filter { !it.requiresLocalNetworkPermission || localNetworkPermissionGranted }
        http.configureServers(addresses = addresses, port = network.httpPort)
    }

    /** A single capture's observed empty selection; timeout edits retain the beginning of that loss. */
    private suspend fun checkAddressLossTimeout(): Long? {
        val capture = activeCaptureAttempt ?: return null
        val data = effectiveSettings
        val observed = addressState as? NetworkAddressMonitor.State.Observed
        val captureState = capture.session.state.value
        if (captureState.isStopRequested ||
            data == null || observed == null ||
            observed.inventory.select(data.network.filter).isNotEmpty()
        ) {
            capture.addressLoss = null
            return null
        }

        val now = SystemClock.elapsedRealtime()
        val filter = data.network.filter
        val episode = capture.addressLoss?.takeIf { it.filter == filter }
            ?: AddressLossEpisode(filter = filter, observedAtElapsedRealtimeMillis = now)
        capture.addressLoss = episode

        val remaining = episode.observedAtElapsedRealtimeMillis + data.behavior.stopAfterNetworkLossSeconds.seconds.inWholeMilliseconds - now
        if (remaining > 0L) return remaining

        // Recovery may already be queued; the deadline must respect the current selected-address fact.
        val current = addressMonitor.state.value as? NetworkAddressMonitor.State.Observed
        if (current == null || current.inventory.select(filter).isNotEmpty()) {
            capture.addressLoss = null
            return null
        }
        stopCapture(capture.session.id, MjpegCaptureSession.StopReason.System)
        return null
    }

    /** UI availability checks current observations; HTTP owns the last applied ready plan. */
    private fun hasAvailableListener(delivery: HttpDelivery.Info = http.info.value): Boolean {
        val network = effectiveSettings?.network ?: return false
        val observed = addressMonitor.state.value as? NetworkAddressMonitor.State.Observed ?: return false
        val addresses = observed.inventory.select(network.filter)
        return !shutdownRequested.isCompleted && runtime.isCurrent() &&
                delivery.servers.any { listener ->
                    listener.state == HttpDelivery.ServerState.Listening && listener.port == network.httpPort &&
                            addresses.any { address ->
                                address.id == listener.address.id && (!address.requiresLocalNetworkPermission || localNetworkPermissionGranted)
                            } &&
                            selectedAddresses.any { address ->
                                address.id == listener.address.id && (!address.requiresLocalNetworkPermission || localNetworkPermissionGranted)
                            }
                }
    }

    private fun readDeliveryOrThrow(): HttpDelivery.Info = http.info.value.also { delivery -> delivery.fatalCause?.let { throw it } }

    private suspend fun updateSlowViewerWarning(delivery: HttpDelivery.Info) {
        val session = activeCaptureAttempt?.session
        val state = session?.state?.value
        if (shutdownRequested.isCompleted || effectiveSettings?.behavior?.notifySlowViewers != true ||
            session == null || state == null || !state.isStreaming || state.status != MjpegCaptureSession.State.Status.Active
        ) {
            slowSampleWatermarkMillis = null
            slowEpisodeWarningConsumed = false
            return
        }

        val watermark = slowSampleWatermarkMillis
        if (watermark == null) {
            // A previous capture generation or eligibility period may still own the sampled client rows.
            slowSampleWatermarkMillis = SystemClock.elapsedRealtime()
            return
        }
        val sampledAt = delivery.sampledAtElapsedRealtimeMillis
        if (sampledAt <= watermark) return
        slowSampleWatermarkMillis = sampledAt

        if (delivery.clients.none { it.isSlow }) {
            slowEpisodeWarningConsumed = false
            return
        }
        if (slowEpisodeWarningConsumed) return
        val lastAttemptAt = lastSlowToastAttemptMillis
        if (lastAttemptAt != null && sampledAt - lastAttemptAt < SLOW_VIEWER_WARNING_COOLDOWN_MILLIS) return

        slowEpisodeWarningConsumed = true
        withContext(Dispatchers.Main.immediate) {
            currentCoroutineContext().ensureActive()
            val currentState = session.state.value
            if (shutdownRequested.isCompleted || !currentState.isStreaming || currentState.status != MjpegCaptureSession.State.Status.Active) {
                return@withContext
            }
            try {
                Toast.makeText(service, R.string.mjpeg_stream_slow_viewer_warning, Toast.LENGTH_LONG).show()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                this@MjpegStreamingModuleController.logE("SlowViewer.toast", "Could not show slow viewer warning", failure)
            }
        }
        lastSlowToastAttemptMillis = SystemClock.elapsedRealtime()
    }

    private fun publishUi(delivery: HttpDelivery.Info) {
        // A completed loop turn with settings has also applied the initial HTTP configuration.
        val settings = effectiveSettings
        val capture = activeCaptureAttempt
        uiController.update(
            action = captureAction(delivery),
            suspensionProblem = capture?.processedState?.suspensionProblem,
            selectedAddresses = selectedAddresses,
            port = settings?.network?.httpPort,
            servers = delivery.servers,
            localNetworkPermissionGranted = localNetworkPermissionGranted,
            accessApplied = settings != null,
            appliedAccessToken = http.appliedAccessToken,
            appliedPin = settings?.access?.takeIf { it.pinEnabled }?.pin,
            hidePinOnCapture = capture != null && settings?.access?.hidePinOnCaptureStart == true,
            editPolicy = editPolicy,
        )
    }

    private fun moduleStatus(delivery: HttpDelivery.Info = readDeliveryOrThrow()): StreamingModule.Status {
        val capture = captureAttempt
        return StreamingModule.Status(
            isStreaming = activeCaptureAttempt?.processedState?.isStreaming == true,
            hasConsumer = delivery.hasConsumer,
            captureAttempt = capture?.takeUnless { it.processedState.cleanupSucceeded == true }?.session?.id,
            errorMessage = errorMessage,
        )
    }

    private fun captureAction(delivery: HttpDelivery.Info): Action {
        if (isCaptureIdle) return Action.Start(enabled = hasAvailableListener(delivery))
        if (shutdownRequested.isCompleted || effectiveSettings == null) return Action.Busy
        val capture = captureAttempt ?: return Action.Busy
        val state = capture.processedState
        return when {
            state.cleanupSucceeded == false -> Action.Start(enabled = false)
            state.isStreaming || state.status == MjpegCaptureSession.State.Status.Suspended -> Action.Stop(capture.session.id)
            else -> Action.Busy
        }
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
            val screenOffSucceeded = screenOffObserver.close()
            val attemptSucceeded = try {
                val session = synchronized(captureAdmissionGate) { captureAttempt?.session }
                session?.awaitCleanup() ?: true
            } catch (failure: Throwable) {
                this@MjpegStreamingModuleController.logE("Cleanup.capture", "Capture cleanup failed", failure)
                false
            }

            val serviceSucceeded = try {
                service.stopAndAwaitDestruction()
            } catch (failure: Throwable) {
                this@MjpegStreamingModuleController.logE("Cleanup.service", "Service cleanup failed", failure)
                false
            }

            screenOffSucceeded && attemptSucceeded && serviceSucceeded
        }

        // Join admitted controller operations even after terminal shutdown.
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

    private class CaptureAttempt(
        val session: MjpegCaptureSession,
        val failureBeforeStart: MjpegCaptureSession.State.Failure?,
    ) {
        var processedState: MjpegCaptureSession.State = session.state.value
        var addressLoss: AddressLossEpisode? = null
        var stopRequestedAtElapsedRealtimeMillis: Long? = null
    }

    private sealed interface Event {
        data class UiCommand(val uiCommand: UiController.Command) : Event
        data class SettingsObserved(val settings: MjpegSettings.Data) : Event
        data class AddressStateChanged(val state: NetworkAddressMonitor.State) : Event
        data object DeliveryChanged : Event
        data class CaptureChanged(val attempt: StreamingModule.CaptureAttemptId) : Event
        data class ScreenOff(val attempt: StreamingModule.CaptureAttemptId, val registrationId: Uuid) : Event
        data class ComponentFailed(val source: FailureSource, val cause: Throwable) : Event
    }

    private data class AddressLossEpisode(val filter: NetworkAddressFilter, val observedAtElapsedRealtimeMillis: Long)

    private data class ScreenOffRegistration(val attempt: StreamingModule.CaptureAttemptId, val id: Uuid)

    private enum class FailureSource { Settings, AddressMonitor, Delivery, Controller }
    private companion object {
        private const val HEARTBEAT_MILLIS: Long = 1_000L
        private const val STOP_WARNING_MILLIS: Long = 3_000L
        private const val SLOW_VIEWER_WARNING_COOLDOWN_MILLIS: Long = 30_000L
    }
}
