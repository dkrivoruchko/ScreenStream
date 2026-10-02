package io.screenstream.mjpeg

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.common.notification.NotificationHelper
import io.screenstream.capture.EncodedFrame
import io.screenstream.mjpeg.access.AccessPreparation
import io.screenstream.mjpeg.access.prepareAccess
import io.screenstream.mjpeg.capture.MjpegCaptureAttempt
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.image.StreamImageOutput
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.MjpegContent
import io.screenstream.mjpeg.ui.UiController
import io.screenstream.mjpeg.ui.mapAddressServer
import io.screenstream.streaming.capture.ScreenCaptureAccess
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleService
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * The product-policy owner for one installed MJPEG module. One typed event loop handles component
 * snapshots and access completions; capture, HTTP, discovery and image work keep their own owners.
 * Direct Start reserves an inert attempt, while Stop and Shutdown cut off admission immediately.
 */
@Factory(binds = [])
internal class MjpegStreamingModuleController(
    @InjectedParam private val runtime: StreamingModule.Controller.Runtime,
    @InjectedParam private val service: MjpegStreamingModuleService,
    private val settings: Lazy<MjpegSettings>,
    private val addressMonitor: NetworkAddressMonitor,
    private val captureAccess: ScreenCaptureAccess,
    private val notifications: NotificationHelper,
    private val http: HttpDelivery,
) : StreamingModule.Controller, UiController, MjpegCaptureAttempt.ImageOutput {
    override val instanceId: StreamingModule.InstanceId = runtime.instanceId
    private val gate = Any()
    private val workJob = SupervisorJob()
    private val scope = CoroutineScope(workJob + Dispatchers.Default)
    private val events = Channel<Event>(Channel.RENDEZVOUS)
    private val captureChanges = MutableStateFlow<CaptureChange?>(null)
    private val permissionChanges = MutableStateFlow(hasLocalNetworkPermission())
    private val retryChanges = MutableStateFlow(0L)
    private val pendingRetries = LinkedHashSet<HttpDelivery.ServerId>()
    private val images = StreamImageOutput(http, service.assets) {
        fail(Problem.InternalFailure, it)
    }
    private val uiState = MutableStateFlow(UiController.State(instanceId, UiController.Action.Start(enabled = false)))
    override val state: StateFlow<UiController.State> = uiState.asStateFlow()

    // The gate protects direct commands and admission; reconciliation runs only in the event loop.
    private var started = false
    private var closing = false
    private var firstProblem: Problem? = null
    private var latestSettings: MjpegSettings.Data? = null
    private var settingsObserver: Job? = null
    private var settingsObservationEpoch = 0L
    private var settingsObservedEpoch = 0L
    private var requestedFilter: NetworkAddressMonitor.Filter? = null
    private var networkLoss: NetworkLoss? = null
    private var capture: MjpegCaptureAttempt? = null
    private var captureErrorShown = false
    private var lastCaptureIssue: HttpDelivery.WebCaptureIssue? = null
    private var lastWebPresentation: WebPresentation? = null
    private var lastStatus: StreamingModule.Status? = null
    private var permissionFilter: NetworkAddressMonitor.Filter? = null
    private var permissionRequest: UiController.PermissionRequestKey? = null
    private var permissionRequestedFilter: NetworkAddressMonitor.Filter? = null
    private var lanGranted = permissionChanges.value
    private var httpStarted = false
    private var accessReady = false
    private var appliedAccess: AccessPreparation.Prepared? = null
    private var pendingAccess: AccessOperation? = null
    private var rotatePin = true
    private var rotationRecorded: StreamingModule.CaptureAttemptId? = null

    /** Independent of event work and waiters; completion includes exact Service destruction. */
    private val cleanupTask: Deferred<Boolean> = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
        finishCleanup()
    }

    override fun startModule() {
        if (!runtime.isCurrent()) { requestModuleShutdown(); return }
        val job = synchronized(gate) {
            if (started || closing) return
            started = true
            scope.launch(start = CoroutineStart.LAZY) { runEvents() }
        }
        job.start()
    }

    /** Reserve exactly one attempt and Busy synchronously; repeated Start cannot repeat consent. */
    override fun requestStreamStart() {
        try {
            val info = http.info.value
            var publication: HttpDelivery.Publication? = null
            val attempt = synchronized(gate) {
                if (closing || capture != null || !runtime.isCurrent() || !accessReady || !hasListeningAddress(info)) return
                val image = latestSettings?.image ?: return
                val id = StreamingModule.CaptureAttemptId(instanceId, Uuid.random())
                MjpegCaptureAttempt(
                    id = id,
                    service = service,
                    captureAccess = captureAccess,
                    notifications = notifications,
                    imageOutput = this,
                    image = image,
                    isInstanceCurrent = runtime::isCurrent,
                ) { captureChanged(id) }.also {
                    capture = it
                    captureErrorShown = false
                    lastCaptureIssue = null
                    publication = images.reserveCapture(id)
                    uiState.value = uiState.value.copy(action = UiController.Action.Busy)
                }
            }
            images.apply(publication)
            attempt.start()
            captureChanged(attempt.id)
        } catch (failure: Throwable) { fail(Problem.InternalFailure, failure) }
    }

    override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId) {
        if (attempt.instanceId != instanceId || !runtime.isCurrent()) return
        val owned = synchronized(gate) { capture?.takeIf { !closing && it.id == attempt } } ?: return
        owned.requestStop(MjpegCaptureAttempt.StopReason.User)
    }

    /** Synchronous borrowed-frame adapter; the image owner alone admits the capture source. */
    override fun onFrame(id: StreamingModule.CaptureAttemptId, frame: EncodedFrame) {
        images.offerFrame(id, frame)
    }

    override fun reserveUnavailable(id: StreamingModule.CaptureAttemptId, reason: MjpegCaptureAttempt.UnavailableReason): (() -> Unit)? {
        val publication = images.reserveUnavailable(id, reason) ?: return null
        return {
            images.apply(publication)
            captureChanged(id)
        }
    }

    override fun reserveResumed(id: StreamingModule.CaptureAttemptId): (() -> Unit)? {
        val publication = images.reserveResumed(id) ?: return null
        return { images.apply(publication) }
    }

    override fun requestModuleShutdown() {
        val (attempt, publication) = synchronized(gate) {
            closing = true
            networkLoss = null
            pendingAccess?.valid = false
            uiState.value = UiController.State(instanceId, UiController.Action.Busy)
            capture to images.reserveClose()
        }
        try { service.closeForegroundAdmission() }
        finally {
            try { images.apply(publication) }
            finally {
                images.requestStop()
                try { http.requestStop() }
                finally {
                    try { attempt?.requestStop(MjpegCaptureAttempt.StopReason.Shutdown) }
                    finally { scope.cancel(); cleanupTask.start() }
                }
            }
        }
    }

    override suspend fun awaitCleanup(): Boolean = cleanupTask.await()

    override fun onServiceDestroyed() {
        try { runtime.reportFailed() } finally { requestModuleShutdown() }
    }

    @Composable
    override fun Content(window: WindowSizeClass, modifier: Modifier) {
        MjpegContent(
            uiController = this,
            settings = settings.value,
            onStart = ::requestStreamStart,
            onStop = ::requestStreamStop,
            modifier = modifier,
        )
    }

    override fun refreshLocalNetworkPermission() {
        val granted = hasLocalNetworkPermission()
        synchronized(gate) {
            if (closing) return
            lanGranted = granted
            permissionChanges.value = granted
        }
    }

    override fun claimLocalNetworkPermissionRequest(key: UiController.PermissionRequestKey, automatic: Boolean): Boolean = synchronized(gate) {
        val filter = permissionFilter
        if (closing || !runtime.isCurrent() || key !== permissionRequest || filter == null || lanGranted ||
            latestSettings?.network?.filter != filter || automatic && permissionRequestedFilter == filter) false
        else { if (automatic) permissionRequestedFilter = filter; true }
    }

    /** Preserve independent Retry commands, coalescing only repeated requests for the same identity. */
    override fun retryServer(id: UiController.ServerId) {
        val info = http.info.value
        synchronized(gate) {
            if (closing || !runtime.isCurrent()) return
            val server = info.addresses.firstOrNull { it.id === id.deliveryId } ?: return
            if (server.state !is HttpDelivery.ServerState.Failed || !isBindAllowed(server.address, server.port)) return
            pendingRetries.retainAll(info.addresses.map { it.id }.toSet())
            if (pendingRetries.add(server.id)) retryChanges.value++
        }
    }

    /** Snapshot senders may conflate; operation completion uses ordinary, lossless send. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runEvents() {
        try {
            observeComponents()
            var nextHeartbeat = SystemClock.uptimeMillis()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!runtime.isCurrent() || isClosing()) return
                val now = SystemClock.uptimeMillis()
                if (now >= nextHeartbeat) {
                    reportStatus(now)
                    nextHeartbeat = now + HEARTBEAT_MILLIS
                    refreshLocalNetworkPermission()
                }
                val networkLossWait = reconcileNetworkLoss()
                val waitMillis = minOf((nextHeartbeat - SystemClock.uptimeMillis()).coerceAtLeast(0L), networkLossWait ?: Long.MAX_VALUE)
                val event: Event? = select {
                    events.onReceive { it }
                    onTimeout(waitMillis.milliseconds) { null }
                }
                when (event) {
                    is Event.SettingsChanged -> onSettingsChanged(event.settings, event.epoch)
                    is Event.AddressesChanged -> onAddressesChanged(event.state)
                    is Event.HttpChanged -> onHttpChanged(event.info)
                    is Event.CaptureChanged -> onCaptureChanged(event.id)
                    is Event.AccessPrepared -> onAccessPrepared(event.operation, event.result)
                    is Event.PermissionChanged -> onPermissionChanged()
                    is Event.RetryRequested -> onRetryRequested(event.id)
                    null -> Unit
                }
                val status = publishState()
                if (status != lastStatus) reportStatus(SystemClock.uptimeMillis(), status)
            }
        } catch (failure: CancellationException) {
            if (!isClosing()) fail(Problem.InternalFailure, failure)
        } catch (failure: Throwable) { fail(Problem.InternalFailure, failure) }
        finally { requestModuleShutdown() }
    }

    private fun observeComponents() {
        startSettingsObservation()
        scope.launch {
            try { addressMonitor.state.collectLatest { events.send(Event.AddressesChanged(it)) } }
            catch (failure: CancellationException) { if (!isClosing()) fail(Problem.ObservationFailed, failure) }
            catch (failure: Throwable) { fail(Problem.ObservationFailed, failure) }
        }
        scope.launch {
            try { http.info.collectLatest { events.send(Event.HttpChanged(it)) } }
            catch (failure: CancellationException) { if (!isClosing()) fail(Problem.ObservationFailed, failure) }
            catch (failure: Throwable) { fail(Problem.ObservationFailed, failure) }
        }
        scope.launch { captureChanges.collectLatest { it?.let { change -> events.send(Event.CaptureChanged(change.id)) } } }
        scope.launch { permissionChanges.collectLatest { events.send(Event.PermissionChanged) } }
        scope.launch {
            retryChanges.collectLatest {
                val pending = synchronized(gate) { pendingRetries.toList() }
                pending.forEach { events.send(Event.RetryRequested(it)) }
            }
        }
    }

    /** A durable PIN result needs a fresh subscription baseline; the same collector continues afterward. */
    private fun startSettingsObservation(operation: AccessOperation? = null, prepared: AccessPreparation.Prepared? = null) {
        val (epoch, previous) = synchronized(gate) {
            if (closing || operation != null && !ownsAccess(operation)) return
            settingsObservationEpoch++
            operation?.let {
                it.prepared = prepared
                it.observationEpoch = settingsObservationEpoch
                accessReady = false
            }
            settingsObservationEpoch to settingsObserver
        }
        previous?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var hasSnapshot = synchronized(gate) { latestSettings != null }
            try {
                settings.value.data.collectLatest { value ->
                    var publication: HttpDelivery.Publication? = null
                    val accepted = synchronized(gate) {
                        if (closing || epoch != settingsObservationEpoch) false else {
                            latestSettings = value
                            settingsObservedEpoch = epoch
                            if (value.access != appliedAccess?.settings) accessReady = false
                            pendingAccess?.let { if (!it.matches(value.access)) it.valid = false }
                            publication = reserveStoppedImage()
                            true
                        }
                    }
                    if (accepted) {
                        images.apply(publication)
                        hasSnapshot = true
                        events.send(Event.SettingsChanged(value, epoch))
                    }
                }
            } catch (failure: CancellationException) {
                if (synchronized(gate) { !closing && epoch == settingsObservationEpoch }) {
                    fail(if (hasSnapshot) Problem.ObservationFailed else Problem.PreparationFailed, failure)
                }
            } catch (failure: Throwable) {
                if (synchronized(gate) { !closing && epoch == settingsObservationEpoch }) {
                    fail(if (hasSnapshot) Problem.ObservationFailed else Problem.PreparationFailed, failure)
                }
            }
        }
        synchronized(gate) { settingsObserver = job }
        job.start()
    }

    private suspend fun onSettingsChanged(eventSettings: MjpegSettings.Data, epoch: Long) {
        val value = synchronized(gate) {
            if (epoch != settingsObservationEpoch) return
            latestSettings ?: eventSettings
        }
        val (attempt, filterChanged) = synchronized(gate) {
            if (closing) return
            if (permissionFilter != value.network.filter) {
                permissionFilter = value.network.filter
                permissionRequest = if (needsLocalNetworkPermission(value.network.filter)) UiController.PermissionRequestKey() else null
            }
            val changed = requestedFilter != value.network.filter
            requestedFilter = value.network.filter
            capture to changed
        }
        attempt?.updateImage(value.image)
        if (filterChanged) addressMonitor.updateFilter(value.network.filter)
        configureServers(addressMonitor.state.value)
        val prepared = synchronized(gate) {
            pendingAccess?.takeIf { it.valid && it.prepared != null && it.observationEpoch == settingsObservedEpoch }
        }
        if (prepared != null) onAccessPrepared(prepared, checkNotNull(prepared.prepared))
        prepareDesiredAccess()
        showStoppedImage()
    }

    private suspend fun onAddressesChanged(value: NetworkAddressMonitor.State) {
        if (value is NetworkAddressMonitor.State.Closed || value is NetworkAddressMonitor.State.Failed) {
            if (!isClosing()) fail(Problem.ObservationFailed, IllegalStateException("Address monitor stopped while controller is open"))
            return
        }
        configureServers(addressMonitor.state.value)
    }

    /** One exact attempt's empty selection; timeout edits keep the original observed-loss time. */
    private fun reconcileNetworkLoss(): Long? {
        var stop: MjpegCaptureAttempt? = null
        val remaining = synchronized(gate) {
            val data = latestSettings
            val attempt = capture?.takeIf { !it.snapshot().stopRequested }
            val observed = addressMonitor.state.value as? NetworkAddressMonitor.State.Observed
            if (closing || data == null || attempt == null || observed == null || observed.filter != data.network.filter || observed.addresses.isNotEmpty()) {
                networkLoss = null
                return@synchronized null
            }
            val now = SystemClock.elapsedRealtime()
            val episode = networkLoss?.takeIf { it.attempt == attempt.id && it.filter == observed.filter }
                ?: NetworkLoss(attempt.id, observed.filter, now).also { networkLoss = it }
            val waitMillis = episode.observedAtElapsedRealtimeMillis + data.behavior.stopAfterNetworkLossSeconds * 1_000L - now
            if (waitMillis > 0L) waitMillis else {
                networkLoss = null
                stop = attempt
                null
            }
        }
        stop?.requestStop(MjpegCaptureAttempt.StopReason.System)
        return remaining
    }

    private fun onHttpChanged(info: HttpDelivery.Info) {
        if (info.fatalIssue != null && !isClosing()) {
            fail(Problem.InternalFailure, IllegalStateException("HTTP delivery failed: ${info.fatalIssue}"))
        }
    }

    private fun onCaptureChanged(id: StreamingModule.CaptureAttemptId) {
        val attempt = synchronized(gate) { capture?.takeIf { it.id == id } } ?: return
        val snapshot = attempt.snapshot()
        var publication: HttpDelivery.Publication? = null
        val notify = synchronized(gate) {
            if (closing || capture !== attempt) return
            if (snapshot.stopRequested && snapshot.projectionAccepted && rotationRecorded != id &&
                latestSettings?.access?.pinEnabled == true && latestSettings?.access?.pinPolicy == AccessSettings.PinPolicy.NewAfterStream) {
                rotationRecorded = id
                rotatePin = true
                accessReady = false
                pendingAccess?.valid = false
            }
            val showError = !captureErrorShown && (snapshot.failure != null || snapshot.cleanupSucceeded == false)
            if (showError) captureErrorShown = true
            webCaptureIssue(snapshot)?.let { lastCaptureIssue = it }
            if (snapshot.failure != null || snapshot.cleanupSucceeded == false) publication = images.reserveFailure(id)
            if (snapshot.cleanupSucceeded == true) capture = null
            showError
        }
        images.apply(publication)
        if (notify) {
            scope.launch(Dispatchers.Main.immediate) {
                notifications.showNotification(ERROR_NOTIFICATION_ID, notifications.getErrorNotification(service, service.getString(R.string.mjpeg_capture_error), null))
            }
        }
        prepareDesiredAccess()
        showStoppedImage()
    }

    private suspend fun onPermissionChanged() {
        configureServers(addressMonitor.state.value)
    }

    private suspend fun onRetryRequested(id: HttpDelivery.ServerId) {
        val admitted = synchronized(gate) { !closing && pendingRetries.remove(id) }
        if (admitted) http.retryServer(id)
    }

    /** HTTP owns the complete plan and socket identities; no consumer filtering or address map. */
    private suspend fun configureServers(value: NetworkAddressMonitor.State) {
        val network = synchronized(gate) { latestSettings?.network.takeUnless { closing } } ?: return
        // Keep the HTTP plan until discovery publishes the newly requested selection.
        if (value is NetworkAddressMonitor.State.Observed && value.filter != network.filter) return
        val addresses = when (value) {
            is NetworkAddressMonitor.State.Observed -> value.addresses
            else -> null
        }.orEmpty()
        http.configureServers(addresses.map { address ->
            HttpDelivery.DesiredServer(address, if (synchronized(gate) { !needsLocalNetworkPermission(address) || lanGranted }) HttpDelivery.ServerAdmission.Allowed
                else HttpDelivery.ServerAdmission.MissingLocalNetworkPermission)
        }, network.httpPort)
    }

    /** Claim one operation; durable work and consent/bind/render never run in the event loop. */
    private fun prepareDesiredAccess() {
        val (operation, obsolete) = synchronized(gate) {
            val desired = latestSettings?.access ?: return
            if (closing || capture?.snapshot()?.stopRequested == false) return
            val current = pendingAccess
            if (current?.valid == true && current.observationEpoch != null && settingsObservedEpoch < checkNotNull(current.observationEpoch)) return
            if (!rotatePin && appliedAccess?.settings == desired) {
                pendingAccess = null
                current?.valid = false
                accessReady = true
                null to current?.job
            } else {
                if (current?.valid == true && current.matches(desired) && current.rotation == rotationRecorded) return
                accessReady = false
                val next = AccessOperation(desired, rotatePin, rotationRecorded, appliedAccess)
                pendingAccess = next
                next to current?.also { it.valid = false }?.job
            }
        }
        obsolete?.cancel()
        if (operation == null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = prepareAccess(operation.expected, operation.rotate, operation.previousApplied, settings.value) { candidate ->
                    synchronized(gate) {
                        if (!ownsAccess(operation) || latestSettings?.access != operation.expected) false
                        else { operation.savedCandidate = candidate; true }
                    }
                }
                events.send(Event.AccessPrepared(operation, result))
            } catch (failure: CancellationException) {
                if (synchronized(gate) { ownsAccess(operation) }) fail(Problem.PreparationFailed, failure)
            } catch (failure: Throwable) {
                if (synchronized(gate) { ownsAccess(operation) }) fail(Problem.PreparationFailed, failure)
            }
        }
        synchronized(gate) { operation.job = job }
        job.start()
    }

    private suspend fun onAccessPrepared(operation: AccessOperation, result: AccessPreparation) {
        val needsBaseline = synchronized(gate) {
            ownsAccess(operation) && result is AccessPreparation.Prepared && operation.savedCandidate != null && operation.observationEpoch == null
        }
        if (needsBaseline) {
            startSettingsObservation(operation, result as AccessPreparation.Prepared)
            return
        }
        val candidate = synchronized(gate) {
            if (!ownsAccess(operation) || !operation.matches(latestSettings?.access) ||
                operation.observationEpoch?.let { settingsObservedEpoch < it } == true) return@synchronized null
            result as? AccessPreparation.Prepared
        }
        if (candidate == null) {
            synchronized(gate) { if (pendingAccess === operation) pendingAccess = null }
            prepareDesiredAccess()
            return
        }
        val initial = synchronized(gate) { !httpStarted }
        val applied = if (initial) http.start(candidate.settings, candidate.token, ::isBindAllowed)
            else http.updateAccess(candidate.settings, candidate.token)
        synchronized(gate) {
            if (applied) {
                httpStarted = true
                appliedAccess = candidate
                if (ownsAccess(operation) && rotationRecorded == operation.rotation) rotatePin = false
            }
            if (pendingAccess === operation) pendingAccess = null
            accessReady = applied && !closing && operation.valid && !rotatePin && operation.matches(latestSettings?.access)
        }
        if (!applied && !isClosing()) { fail(Problem.InternalFailure, IllegalStateException("HTTP rejected prepared access")); return }
        prepareDesiredAccess()
        showStoppedImage()
    }

    /** Exact operation plus its own admitted PIN emission; foreign edits invalidate it permanently. */
    private fun ownsAccess(operation: AccessOperation): Boolean = !closing && runtime.isCurrent() && pendingAccess === operation && operation.valid

    private fun showStoppedImage() {
        val publication = synchronized(gate) { reserveStoppedImage() }
        images.apply(publication)
    }

    /** Caller holds the controller gate; read capture facts before reserving the image effect. */
    private fun reserveStoppedImage(): HttpDelivery.Publication? {
        val data = latestSettings ?: return null
        val snapshot = capture?.snapshot()
        val allowed = !closing && accessReady && snapshot?.stopRequested != false && snapshot?.failure == null && snapshot?.cleanupSucceeded != false
        return images.reserveStopped(data.behavior.postStopImage, allowed)
    }

    private fun captureChanged(id: StreamingModule.CaptureAttemptId) {
        synchronized(gate) {
            if (closing || capture?.id != id) return
            captureChanges.value = CaptureChange(id, (captureChanges.value?.sequence ?: 0L) + 1L)
        }
    }

    private fun publishState(): StreamingModule.Status {
        val info = http.info.value
        val (status, presentation) = synchronized(gate) {
            val attempt = capture
            val snapshot = attempt?.snapshot()
            val action = when {
                closing -> UiController.Action.Busy
                attempt == null -> UiController.Action.Start(enabled = latestSettings != null && accessReady && hasListeningAddress(info))
                snapshot?.cleanupSucceeded == false -> UiController.Action.Start(enabled = false)
                snapshot?.streaming == true || snapshot?.phase == MjpegCaptureAttempt.Phase.Suspended -> UiController.Action.Stop(attempt.id)
                else -> UiController.Action.Busy
            }
            val servers = info.addresses.map {
                mapAddressServer(it, isBindAllowed(it.address, it.port), accessReady, appliedAccess?.token)
            }
            uiState.value = UiController.State(
                instanceId = instanceId,
                action = action,
                addressServers = servers,
                permissionRequest = permissionRequest.takeIf { !lanGranted && !closing },
                suspensionProblem = snapshot?.suspensionProblem,
            )
            val issue = snapshot?.let(::webCaptureIssue) ?: lastCaptureIssue
            val phase = when (snapshot?.phase) {
                MjpegCaptureAttempt.Phase.Stopped -> HttpDelivery.WebCaptureState.Stopped
                MjpegCaptureAttempt.Phase.WaitingForPermission -> HttpDelivery.WebCaptureState.WaitingForPermission
                MjpegCaptureAttempt.Phase.Starting -> HttpDelivery.WebCaptureState.Starting
                MjpegCaptureAttempt.Phase.Active -> HttpDelivery.WebCaptureState.Active
                MjpegCaptureAttempt.Phase.Reconfiguring -> HttpDelivery.WebCaptureState.Reconfiguring
                MjpegCaptureAttempt.Phase.Suspended -> HttpDelivery.WebCaptureState.Suspended
                MjpegCaptureAttempt.Phase.Stopping -> HttpDelivery.WebCaptureState.Stopping
                MjpegCaptureAttempt.Phase.Failed -> HttpDelivery.WebCaptureState.Failed
                null -> if (issue == null) HttpDelivery.WebCaptureState.Stopped else HttpDelivery.WebCaptureState.Failed
            }
            StreamingModule.Status(snapshot?.streaming == true, info.viewers.isNotEmpty(), attempt?.id) to
                    latestSettings?.web?.let { WebPresentation(phase, issue, it) }
        }
        if (presentation != null && presentation != lastWebPresentation) {
            http.updatePresentation(presentation.capture, presentation.issue, presentation.display)
            lastWebPresentation = presentation
        }
        return status
    }

    private fun webCaptureIssue(snapshot: MjpegCaptureAttempt.Snapshot): HttpDelivery.WebCaptureIssue? = when {
        snapshot.cleanupSucceeded == false -> HttpDelivery.WebCaptureIssue.CleanupFailed
        snapshot.failure == MjpegCaptureAttempt.Failure.CaptureFailed -> HttpDelivery.WebCaptureIssue.CaptureFailed
        else -> null
    }

    private fun reportStatus(now: Long, status: StreamingModule.Status = synchronized(gate) {
        val attempt = capture
        StreamingModule.Status(attempt?.snapshot()?.streaming == true, http.info.value.viewers.isNotEmpty(), attempt?.id)
    }) {
        runtime.reportRunning(status, now)
        lastStatus = status
    }

    /** The provider snapshot is the sole selected-address authority, including during filter updates. */
    private fun isBindAllowed(address: NetworkAddressMonitor.Address, port: Int): Boolean = runtime.isCurrent() && synchronized(gate) {
        val network = latestSettings?.network
        val selected = when (val snapshot = addressMonitor.state.value) {
            is NetworkAddressMonitor.State.Observed -> snapshot.addresses.takeIf { snapshot.filter == network?.filter }
            else -> null
        }
        val current = selected?.firstOrNull { it.id == address.id }
        !closing && network?.httpPort == port && current != null &&
                (!needsLocalNetworkPermission(current) || lanGranted)
    }

    private fun hasListeningAddress(info: HttpDelivery.Info): Boolean = info.addresses.any {
        it.state == HttpDelivery.ServerState.Listening && isBindAllowed(it.address, it.port)
    }

    @SuppressLint("InlinedApi")
    private fun hasLocalNetworkPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            service.applicationInfo.targetSdkVersion < Build.VERSION_CODES.CINNAMON_BUN ||
            service.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun needsLocalNetworkPermission(filter: NetworkAddressMonitor.Filter): Boolean =
        filter.categories.any { it != NetworkAddressMonitor.AddressCategory.Loopback } &&
                filter.interfaceTypes.any { it == NetworkAddressMonitor.InterfaceType.Wifi || it == NetworkAddressMonitor.InterfaceType.Ethernet ||
                        it == NetworkAddressMonitor.InterfaceType.Other }

    private fun needsLocalNetworkPermission(address: NetworkAddressMonitor.Address): Boolean = !address.ip.isLoopbackAddress &&
            (address.interfaceType == NetworkAddressMonitor.InterfaceType.Wifi || address.interfaceType == NetworkAddressMonitor.InterfaceType.Ethernet ||
                    address.interfaceType == NetworkAddressMonitor.InterfaceType.Other)

    /** All owners start cleanup independently; one failed obligation cannot skip another. */
    private suspend fun finishCleanup(): Boolean = coroutineScope {
        val httpCleanup = async {
            try { http.stop(); true } catch (failure: Throwable) { Log.e(TAG, "MJPEG HTTP cleanup failed", failure); false }
        }
        val addressMonitorCleanup = async {
            try { addressMonitor.close(); true } catch (failure: Throwable) { Log.e(TAG, "MJPEG address monitor cleanup failed", failure); false }
        }
        val imageCleanup = async {
            try { images.awaitCleanup(); true } catch (failure: Throwable) { Log.e(TAG, "MJPEG image cleanup failed", failure); false }
        }
        val platformCleanup = async {
            val attemptSucceeded = try { synchronized(gate) { capture }?.awaitCleanup() ?: true }
            catch (failure: Throwable) { Log.e(TAG, "MJPEG capture cleanup failed", failure); false }
            var succeeded = try {
                when (service.removeForeground()) {
                    is StreamingModuleService.ReleaseResult.Failed, StreamingModuleService.ReleaseResult.Unconfirmed -> false
                    StreamingModuleService.ReleaseResult.NotRequired, StreamingModuleService.ReleaseResult.ApiCompleted -> true
                }
            } catch (failure: Throwable) { Log.e(TAG, "MJPEG foreground cleanup failed", failure); false }
            try { service.stopSelf() } catch (failure: Throwable) { succeeded = false; Log.e(TAG, "MJPEG Service stop failed", failure) }
            try { service.awaitDestroyed() }
            catch (failure: Throwable) { succeeded = false; Log.e(TAG, "MJPEG Service destruction wait failed", failure) }
            attemptSucceeded && succeeded
        }
        workJob.join()
        val addressMonitorSucceeded = addressMonitorCleanup.await()
        val imageSucceeded = imageCleanup.await()
        val platformSucceeded = platformCleanup.await()
        val httpSucceeded = httpCleanup.await()
        addressMonitorSucceeded && imageSucceeded && platformSucceeded && httpSucceeded
    }

    private fun fail(problem: Problem, failure: Throwable) {
        synchronized(gate) {
            if (closing || firstProblem != null) return
            firstProblem = problem
        }
        Log.e(TAG, "MJPEG controller failed: $problem", failure)
        try { runtime.reportFailed() } finally { requestModuleShutdown() }
    }

    private fun isClosing(): Boolean = synchronized(gate) { closing }

    private sealed interface Event {
        data class SettingsChanged(val settings: MjpegSettings.Data, val epoch: Long) : Event
        data class AddressesChanged(val state: NetworkAddressMonitor.State) : Event
        data class HttpChanged(val info: HttpDelivery.Info) : Event
        data class CaptureChanged(val id: StreamingModule.CaptureAttemptId) : Event
        data class AccessPrepared(val operation: AccessOperation, val result: AccessPreparation) : Event
        data object PermissionChanged : Event
        data class RetryRequested(val id: HttpDelivery.ServerId) : Event
    }

    private data class CaptureChange(val id: StreamingModule.CaptureAttemptId, val sequence: Long)
    private data class NetworkLoss(
        val attempt: StreamingModule.CaptureAttemptId,
        val filter: NetworkAddressMonitor.Filter,
        val observedAtElapsedRealtimeMillis: Long,
    )
    private data class WebPresentation(
        val capture: HttpDelivery.WebCaptureState,
        val issue: HttpDelivery.WebCaptureIssue?,
        val display: WebPageSettings,
    )
    private class AccessOperation(
        val expected: AccessSettings,
        val rotate: Boolean,
        val rotation: StreamingModule.CaptureAttemptId?,
        val previousApplied: AccessPreparation.Prepared?,
    ) {
        var valid = true
        var savedCandidate: AccessSettings? = null
        var prepared: AccessPreparation.Prepared? = null
        var observationEpoch: Long? = null
        var job: Job? = null
        fun matches(access: AccessSettings?): Boolean = if (observationEpoch != null) access == prepared?.settings
            else access == expected || access == savedCandidate && savedCandidate != null
    }

    private enum class Problem { PreparationFailed, ObservationFailed, InternalFailure }
    private companion object {
        private const val TAG: String = "MjpegStreamingModuleController"
        private const val ERROR_NOTIFICATION_ID: Int = 410
        private const val HEARTBEAT_MILLIS: Long = 1_000L
    }
}
