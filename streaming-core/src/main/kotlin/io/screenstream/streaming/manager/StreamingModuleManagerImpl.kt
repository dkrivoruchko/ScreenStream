package io.screenstream.streaming.manager

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.settings.StreamingSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Singleton
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Process-lifetime owner of module selection, instances, transitions, and coordinator errors.
 * Commands and reports are checked against their original instance independently of UI collection.
 *
 * @param modules Available modules, ordered by priority for selection.
 * @param streamingSettings Saved streaming selection and its updates.
 * @param errorNotification Owner of the process notification for coordinator errors.
 * @param dispatcher Dispatcher for process-owned work and queued module reports.
 */
@Singleton(binds = [StreamingModuleManager::class])
internal class StreamingModuleManagerImpl internal constructor(
    modules: List<StreamingModule>,
    private val androidContext: Context,
    private val streamingSettings: StreamingSettings,
    private val errorNotification: StreamingModuleErrorNotification,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StreamingModuleManager {
    /** Registered modules copied for the selector, highest priority first. */
    override val modules: List<StreamingModule> = Collections.unmodifiableList(modules.sortedByDescending { it.priority }).also {
        require(it.isNotEmpty()) { "At least one streaming module is required" }
    }

    private val moduleById: Map<StreamingModule.Id, StreamingModule> = this.modules.associateBy { it.id }.also {
        require(it.size == this.modules.size) { "Streaming module IDs must be unique" }
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var selectedModuleId: StreamingModule.Id? = null
    private var currentInstance: LaunchRecord? = null
    private var transitionJob: Job? = null
    private var exitCompletion: CompletableDeferred<Unit>? = null
    private val deadlineWake = Channel<Unit>(Channel.CONFLATED)
    private var lastSettingsWrite: Job? = null

    /** One complete process state, independent of Activity collection. */
    override val state: StateFlow<StreamingModuleManager.State>
        field = MutableStateFlow<StreamingModuleManager.State>(StreamingModuleManager.State.NoModule)

    override val currentInstanceId: StateFlow<StreamingModule.InstanceId?> = state.map { current ->
        when (current) {
            is StreamingModuleManager.State.Running -> current.instanceId
            is StreamingModuleManager.State.Unresponsive -> current.instanceId
            is StreamingModuleManager.State.Failed -> current.instanceId
            StreamingModuleManager.State.NoModule,
            StreamingModuleManager.State.Switching,
            StreamingModuleManager.State.Exiting -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Select a known module, keeping one process-owned transition until startup settles. */
    @MainThread
    override fun selectModule(moduleId: StreamingModule.Id?) {
        if (transitionJob != null) return
        if (moduleId != null && moduleId !in moduleById) {
            XLog.w(this@StreamingModuleManagerImpl.getLog("Select", "Unknown streaming module ID=$moduleId"))
            return
        }
        if (selectedModuleId != null && currentInstance?.instanceId?.moduleId == (moduleId ?: selectedModuleId)) return
        beginPreparation(moduleId)
    }

    /** Restart only the current launch whose applied error still permits it. */
    @MainThread
    override fun restartModule(expectedInstanceId: StreamingModule.InstanceId) {
        if (transitionJob != null || selectedModuleId == null) return
        val instance = currentInstance ?: return
        if (instance.instanceId != expectedInstanceId ||
            (instance.state !is LaunchState.Failed && instance.state !is LaunchState.Unresponsive)
        ) return
        beginPreparation(expectedInstanceId.moduleId)
    }

    /**
     * Give Exit priority over opening. Repeated callers share the first Exit barrier; cancellation
     * ends only their wait. Cleanup continues in the process after the three-second UI deadline.
     */
    @MainThread
    override suspend fun exit() {
        val completion = exitCompletion ?: beginExit()
        wakeTransitionWaiter()
        completion.await()
    }

    /** Install the transition before publication so synchronous callers cannot open another one. */
    private fun beginPreparation(requestedModuleId: StreamingModule.Id?) {
        exitCompletion = null
        val loadedTarget = selectedModuleId?.let { requestedModuleId ?: it }
        val previous = currentInstance
        val previousDeadline = previous?.takeUnless { it.isFinishReported }?.let {
            it.shutdownDeadlineElapsedMillis ?: shutdownDeadline()
        }
        lateinit var opening: Job
        opening = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (selectedModuleId == null) {
                    streamingSettings.initialize()
                    if (transitionJob !== opening) return@launch
                    val saved = streamingSettings.data.value.selectedModuleId
                    val resolved = saved?.takeIf { it in moduleById } ?: modules.first().id
                    selectedModuleId = resolved
                    if (saved == null) persistSettings {
                        if (selectedModuleId == null) copy(selectedModuleId = resolved) else this
                    }
                }
                if (transitionJob !== opening) return@launch
                val selected = checkNotNull(selectedModuleId)
                val target = loadedTarget ?: (requestedModuleId ?: selected)
                if (loadedTarget == null) {
                    if (target != selected) persistSettings { copy(selectedModuleId = target) }
                    selectedModuleId = target
                }
                if (previous != null && previousDeadline != null) {
                    awaitUntil(previous.cleanupSettled, previousDeadline)
                }
                if (transitionJob !== opening) return@launch
                val instance = launchInstance(target)
                val started = awaitUntil(instance.startupSettled, instance.firstStatusDeadlineElapsedMillis)
                if (transitionJob !== opening) return@launch
                if (!started) failInstance(instance, StreamingModuleManager.Failure.LaunchFailed)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                XLog.e(this@StreamingModuleManagerImpl.getLog("Initialize", "Failed to open streaming module"), error)
                throw error
            } finally {
                if (transitionJob === opening) {
                    transitionJob = null
                    publishState()
                }
            }
        }
        transitionJob = opening
        if (loadedTarget != null) {
            val changed = loadedTarget != selectedModuleId
            selectedModuleId = loadedTarget
            if (changed) persistSettings { copy(selectedModuleId = loadedTarget) }
        }
        if (previous != null && previousDeadline != null) {
            previous.beginClosing()
            previous.requestModuleShutdown(previousDeadline)
        }
        try {
            publishState()
        } finally {
            opening.start()
        }
    }

    /** Install Exit before cancelling an opening; that cancellation never cancels instance cleanup. */
    private fun beginExit(): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        val previousTransition = transitionJob
        val instance = currentInstance
        lateinit var exiting: Job
        exiting = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (instance != null && !instance.isFinishReported) {
                    awaitUntil(instance.cleanupSettled, checkNotNull(instance.shutdownDeadlineElapsedMillis))
                }
            } finally {
                if (transitionJob === exiting) {
                    currentInstance = null
                    transitionJob = null
                    try {
                        publishState()
                    } finally {
                        completion.complete(Unit)
                    }
                }
            }
        }
        exitCompletion = completion
        transitionJob = exiting
        previousTransition?.cancel()
        instance?.beginClosing()
        instance?.requestModuleShutdown(instance.shutdownDeadlineElapsedMillis ?: shutdownDeadline())
        try {
            publishState()
        } finally {
            exiting.start()
        }
        return completion
    }

    /** Wait against elapsed time, recomputing after reports and deep-sleep wake-ups. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitUntil(signal: CompletableDeferred<Unit>, deadlineElapsedMillis: Long): Boolean {
        while (true) {
            if (signal.isCompleted) return true
            val remaining = deadlineElapsedMillis - SystemClock.elapsedRealtime()
            if (remaining < 0L) return false
            select {
                signal.onAwait { }
                deadlineWake.onReceive { }
                onTimeout((remaining + 1L).milliseconds) { }
            }
        }
    }

    private fun shutdownDeadline(): Long = SystemClock.elapsedRealtime() + SHUTDOWN_TIMEOUT.inWholeMilliseconds

    /**
     * Route an external Start to the current [instanceId] after its first status. The module decides
     * capture readiness when handling the request; stale or conflicting requests leave state
     * unchanged.
     */
    override fun requestStreamStart(instanceId: StreamingModule.InstanceId) {
        scope.launch(dispatcher) {
            wakeTransitionWaiter()
            val instance = currentInstance ?: return@launch
            if (transitionJob != null || !instance.isAdmitted() || instance.instanceId != instanceId || !instance.hasLiveStatus()) return@launch
            try {
                instance.controllerForCommands()?.requestStreamStart()
            } catch (error: Exception) {
                XLog.e(this@StreamingModuleManagerImpl.getLog("ExternalStart", "Failed to dispatch Start for instanceId=$instanceId"), error)
            }
        }
    }

    /**
     * Route an external Stop to the current launch; its module validates the exact live [attempt].
     * Check admission synchronously on Main before enqueueing the module command. A status report
     * may lag the notification that first exposed the Stop action.
     */
    @MainThread
    override fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId) {
        val instance = currentInstance ?: return
        if (transitionJob != null || !instance.isAdmitted() || instance.instanceId != attempt.instanceId || !instance.hasLiveStatus()) return
        try {
            instance.controllerForCommands()?.requestStreamStop(attempt)
        } catch (error: Exception) {
            XLog.e(this@StreamingModuleManagerImpl.getLog("ExternalStop", "Failed to dispatch Stop for attempt=$attempt"), error)
        }
    }

    /** Reserve identity and deadline before Android can synchronously deliver the startup. */
    private fun launchInstance(moduleId: StreamingModule.Id): LaunchRecord {
        val instance = LaunchRecord(StreamingModule.InstanceId(moduleId), moduleById.getValue(moduleId).serviceClass)
        currentInstance = instance
        instance.firstStatusDeadlineElapsedMillis = SystemClock.elapsedRealtime() + FIRST_STATUS_TIMEOUT.inWholeMilliseconds
        instance.admit(instance.firstStatusDeadlineElapsedMillis)
        try {
            val startup = Intent(androidContext, instance.serviceClass).apply {
                action = StreamingModuleManager.ACTION_START_MODULE
                putExtra(EXTRA_MODULE, instance.instanceId.moduleId.value)
                putExtra(EXTRA_UUID, instance.instanceId.uuid.toString())
            }
            if (androidContext.startService(startup) == null) instance.reportFailure(null)
        } catch (error: Throwable) {
            XLog.e(this@StreamingModuleManagerImpl.getLog("Startup", "Android module startup failed"), error)
            instance.reportFailure(null)
        } finally {
            instance.dispatchDone.complete(Unit)
        }
        return instance
    }

    private fun failInstance(instance: LaunchRecord, failure: StreamingModuleManager.Failure) {
        if (!instance.fail(failure)) return
        if (!instance.isFinishReported) instance.requestModuleShutdown(shutdownDeadline())
        try {
            if (currentInstance === instance && !instance.isClosing) publishState()
        } finally {
            instance.startupSettled.complete(Unit)
        }
    }

    /** Wake the one transition waiter; report handlers independently validate first-status deadlines. */
    private fun wakeTransitionWaiter() {
        deadlineWake.trySend(Unit)
    }

    private fun handleRunningReport(instance: LaunchRecord, status: StreamingModule.Status, heartbeatAtUptimeMillis: Long) {
        if (currentInstance !== instance) return
        if (instance.state == LaunchState.Starting &&
            SystemClock.elapsedRealtime() > instance.firstStatusDeadlineElapsedMillis
        ) {
            failInstance(instance, StreamingModuleManager.Failure.LaunchFailed)
            return
        }
        if (!instance.acceptStatus(status, heartbeatAtUptimeMillis)) return
        try {
            publishState()
        } finally {
            instance.startupSettled.complete(Unit)
        }
    }

    private fun handleFailureReport(instance: LaunchRecord, messageResource: Int?) {
        if (currentInstance !== instance || instance.isClosing || instance.isFinishReported) return
        failInstance(instance, instance.failureFor(messageResource))
    }

    private fun handleFinishedReport(instance: LaunchRecord, cleanupCompleted: Boolean) {
        if (!instance.markFinished(cleanupCompleted)) return
        if (currentInstance === instance) publishState()
    }

    private fun handleHeartbeatTimeout(instance: LaunchRecord) {
        if (currentInstance === instance && instance.checkHeartbeat()) publishState()
    }

    private fun publishState() {
        state.value = when {
            exitCompletion != null -> StreamingModuleManager.State.Exiting
            transitionJob != null -> StreamingModuleManager.State.Switching
            else -> currentInstance?.let { instance ->
                when (val launch = instance.state) {
                    is LaunchState.Running -> StreamingModuleManager.State.Running(instance.instanceId, launch.status)
                    is LaunchState.Unresponsive -> StreamingModuleManager.State.Unresponsive(instance.instanceId, launch.lastStatus)
                    is LaunchState.Failed -> StreamingModuleManager.State.Failed(instance.instanceId, launch.failure)
                    LaunchState.Starting, LaunchState.Stopped -> StreamingModuleManager.State.NoModule
                }
            } ?: StreamingModuleManager.State.NoModule
        }
        // A synchronous collector may publish a newer state during assignment.
        when (val current = state.value) {
            is StreamingModuleManager.State.Failed -> errorNotification.show(current)
            is StreamingModuleManager.State.Unresponsive -> errorNotification.show(current)
            else -> errorNotification.cancel()
        }
    }

    @Composable
    override fun InstanceContent(
        instanceId: StreamingModule.InstanceId,
        window: WindowSizeClass,
        modifier: Modifier,
        fallback: @Composable () -> Unit,
    ) {
        val owner = currentInstance?.takeIf { it.instanceId == instanceId }
        val installed = owner?.controllerState?.collectAsStateWithLifecycle()?.value
        if (installed != null && owner.isAdmitted()) installed.Content(window, modifier) else fallback()
    }

    @MainThread
    override fun onServiceStart(
        service: Service,
        existingController: StreamingModule.Controller?,
        intent: Intent?,
        createController: (StreamingModule.Controller.Runtime) -> StreamingModule.Controller,
    ) {
        wakeTransitionWaiter()
        if (intent?.action != StreamingModuleManager.ACTION_START_MODULE) return
        val module = intent.getStringExtra(EXTRA_MODULE) ?: return
        val uuid = intent.getStringExtra(EXTRA_UUID) ?: return
        val instanceId = runCatching { StreamingModule.InstanceId(StreamingModule.Id(module), Uuid.parse(uuid)) }.getOrNull() ?: return
        val instance = currentInstance?.takeIf { it.instanceId == instanceId } ?: return
        if (!instance.isAdmitted() || instance.startupReceived) return
        if (!instance.serviceClass.isInstance(service) || intent.component != ComponentName(androidContext, instance.serviceClass)) {
            instance.reportFailure(null)
            return
        }
        if (existingController != null) {
            instance.reportFailure(null)
            return
        }
        instance.installController(createController)
    }

    private fun persistSettings(transform: StreamingSettings.Data.() -> StreamingSettings.Data) {
        val previousWrite = lastSettingsWrite
        lastSettingsWrite = scope.launch {
            previousWrite?.join()
            try {
                streamingSettings.updateData(transform)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                XLog.e(this@StreamingModuleManagerImpl.getLog("SettingsWrite", "Failed to save streaming settings"), error)
            }
        }
    }

    /** Logical launch facts only; its controller owns every Service and resource obligation. */
    private inner class LaunchRecord(val instanceId: StreamingModule.InstanceId, val serviceClass: Class<out Service>) {
        private val gate = Any()
        val dispatchDone = CompletableDeferred<Unit>()
        val startupSettled = CompletableDeferred<Unit>()
        val cleanupSettled = CompletableDeferred<Unit>()
        var firstStatusDeadlineElapsedMillis = Long.MAX_VALUE
        private var controllerSetupCompletion = CompletableDeferred(Unit)
        val controllerState = MutableStateFlow<StreamingModule.Controller?>(null)
        private var controller: StreamingModule.Controller? = null
        var startupReceived = false
            private set
        private var cleanupFailed = false
        private var pendingRunning: Report.Running? = null
        private var pendingFailure: Report.Failed? = null
        private var pendingFinished: Report.Finished? = null
        private var draining = false
        @Volatile
        private var failureQueued = false
        @Volatile
        private var shutdownRequested = false
        @Volatile
        private var admittedUntilElapsedMillis = Long.MIN_VALUE
        var state: LaunchState = LaunchState.Starting
            private set
        var shutdownDeadlineElapsedMillis: Long? = null
            private set
        private var heartbeatCheck: Job? = null
        var isFinishReported = false
            private set
        var isClosing = false
            private set

        private val runtime = object : StreamingModule.Controller.Runtime {
            override val instanceId: StreamingModule.InstanceId = this@LaunchRecord.instanceId
            override fun isCurrent(): Boolean = isAdmitted()
            override fun reportRunning(status: StreamingModule.Status, heartbeatAtUptimeMillis: Long) {
                synchronized(gate) {
                    if (!isAdmitted()) return
                    pendingRunning = Report.Running(status, heartbeatAtUptimeMillis)
                }
                scheduleReports()
            }

            override fun reportFailed(messageResource: Int?) = reportFailure(messageResource)
        }

        /** Adopt before accessing identity or starting; even a rejected late result is cleaned up. */
        fun installController(factory: (StreamingModule.Controller.Runtime) -> StreamingModule.Controller) {
            val setup = synchronized(gate) {
                if (!isAdmitted() || startupReceived) return
                startupReceived = true
                CompletableDeferred<Unit>().also { controllerSetupCompletion = it }
            }
            try {
                val created = factory(runtime)
                synchronized(gate) { controller = created }
                check(created.instanceId == instanceId) { "Controller identity does not match its launch" }
                if (!isAdmitted()) {
                    requestControllerShutdown(created); return
                }
                controllerState.value = created
                if (isAdmitted()) created.startModule() else requestControllerShutdown(created)
            } catch (error: Throwable) {
                XLog.e(this@StreamingModuleManagerImpl.getLog("Startup", "Module controller startup failed"), error)
                reportFailure(null)
            } finally {
                setup.complete(Unit)
            }
        }

        fun controllerForCommands(): StreamingModule.Controller? = synchronized(gate) { controller?.takeIf { isAdmitted() } }

        fun reportFailure(messageResource: Int?) {
            val accepted = synchronized(gate) {
                if (shutdownRequested || failureQueued) false else {
                    failureQueued = true
                    pendingFailure = Report.Failed(messageResource)
                    true
                }
            }
            if (!accepted) return
            scheduleReports()
            beginShutdown()
        }

        private fun requestControllerShutdown(current: StreamingModule.Controller) {
            try {
                current.requestModuleShutdown()
            } catch (error: Throwable) {
                synchronized(gate) { cleanupFailed = true }
                XLog.e(this@StreamingModuleManagerImpl.getLog("Shutdown", "Module shutdown failed"), error)
            }
        }

        fun requestModuleShutdown(deadlineIfFirst: Long) {
            if (shutdownDeadlineElapsedMillis == null) shutdownDeadlineElapsedMillis = deadlineIfFirst
            beginShutdown()
        }

        /** Close admission immediately; the process keeps waiting after the UI deadline or cancellation. */
        private fun beginShutdown() {
            val first = synchronized(gate) {
                if (shutdownRequested) false else {
                    shutdownRequested = true; true
                }
            }
            if (!first) return
            revokeAdmission()
            synchronized(gate) { controller }?.let(::requestControllerShutdown)
            scope.launch(Dispatchers.Default) {
                dispatchDone.await()
                synchronized(gate) { controllerSetupCompletion }.await()
                val ownedController = synchronized(gate) { controller }
                if (ownedController != null) withContext(dispatcher) { requestControllerShutdown(ownedController) }
                val succeeded = if (ownedController == null) true else try {
                    ownedController.awaitCleanup()
                } catch (error: Throwable) {
                    XLog.e(this@StreamingModuleManagerImpl.getLog("Cleanup", "Module cleanup failed"), error)
                    false
                }
                synchronized(gate) { pendingFinished = Report.Finished(succeeded && !cleanupFailed) }
                scheduleReports()
            }
        }

        /** Bounded slots preserve accepted Running, first failure, then the final cleanup outcome. */
        private fun scheduleReports() {
            val start = synchronized(gate) {
                if (draining) false else {
                    draining = true; true
                }
            }
            if (!start) return
            scope.launch {
                while (true) {
                    val report = synchronized(gate) {
                        when {
                            pendingRunning != null -> pendingRunning.also { pendingRunning = null }
                            pendingFailure != null -> pendingFailure.also { pendingFailure = null }
                            pendingFinished != null -> pendingFinished.also { pendingFinished = null }
                            else -> {
                                draining = false; null
                            }
                        }
                    } ?: return@launch
                    try {
                        wakeTransitionWaiter()
                        when (report) {
                            is Report.Running -> handleRunningReport(this@LaunchRecord, report.status, report.heartbeatAtUptimeMillis)
                            is Report.Failed -> handleFailureReport(this@LaunchRecord, report.messageResource)
                            is Report.Finished -> handleFinishedReport(this@LaunchRecord, report.cleanupCompleted)
                        }
                    } catch (error: Exception) {
                        XLog.e(this@StreamingModuleManagerImpl.getLog("Report", "Failed to apply module report"), error)
                    } finally {
                        if (report is Report.Finished) {
                            synchronized(gate) { controller = null }
                            controllerState.value = null
                            synchronized(gate) {
                                pendingRunning = null
                                pendingFailure = null
                                pendingFinished = null
                                draining = false
                            }
                            startupSettled.complete(Unit)
                            cleanupSettled.complete(Unit)
                        }
                    }
                    if (report is Report.Finished) return@launch
                }
            }
        }

        fun admit(deadlineElapsedMillis: Long = Long.MAX_VALUE) {
            admittedUntilElapsedMillis = deadlineElapsedMillis
        }

        private fun revokeAdmission() {
            admittedUntilElapsedMillis = Long.MIN_VALUE
            controllerState.value = null
        }

        fun acceptStatus(status: StreamingModule.Status, heartbeatAtUptimeMillis: Long): Boolean {
            if (isClosing || isFinishReported || state is LaunchState.Failed || state == LaunchState.Stopped) return false
            val previousHeartbeat = when (val previous = state) {
                is LaunchState.Running -> previous.heartbeatAtUptimeMillis
                is LaunchState.Unresponsive -> previous.heartbeatAtUptimeMillis
                else -> null
            }
            if (previousHeartbeat != null && heartbeatAtUptimeMillis < previousHeartbeat) return false

            if (!shutdownRequested) admit()
            val fresh = SystemClock.uptimeMillis() - heartbeatAtUptimeMillis <= HEARTBEAT_TIMEOUT.inWholeMilliseconds
            state = if (fresh) LaunchState.Running(status, heartbeatAtUptimeMillis)
            else LaunchState.Unresponsive(status, heartbeatAtUptimeMillis)
            if (!shutdownRequested) scheduleHeartbeatCheck()
            return true
        }

        fun failureFor(messageResource: Int?): StreamingModuleManager.Failure =
            if (state is LaunchState.Running || state is LaunchState.Unresponsive) StreamingModuleManager.Failure.ModuleFailed(messageResource)
            else StreamingModuleManager.Failure.LaunchFailed

        fun fail(failure: StreamingModuleManager.Failure): Boolean {
            if (state is LaunchState.Failed) return false
            state = LaunchState.Failed(failure)
            revokeAdmission()
            heartbeatCheck?.cancel()
            heartbeatCheck = null
            return true
        }

        fun beginClosing() {
            isClosing = true
            revokeAdmission()
            heartbeatCheck?.cancel()
            heartbeatCheck = null
        }

        fun markFinished(cleanupCompleted: Boolean): Boolean {
            if (isFinishReported) return false
            val previous = state
            isFinishReported = true
            revokeAdmission()
            heartbeatCheck?.cancel()
            heartbeatCheck = null
            state = when {
                previous is LaunchState.Failed -> previous
                isClosing || shutdownDeadlineElapsedMillis != null -> LaunchState.Stopped
                previous is LaunchState.Running || previous is LaunchState.Unresponsive -> LaunchState.Failed(StreamingModuleManager.Failure.ModuleFailed())
                else -> LaunchState.Failed(StreamingModuleManager.Failure.LaunchFailed)
            }
            if (!cleanupCompleted) XLog.w(this@StreamingModuleManagerImpl.getLog("Finished", "Cleanup failed or unconfirmed for instance=$instanceId"))
            return true
        }

        fun checkHeartbeat(): Boolean {
            val running = state as? LaunchState.Running ?: return false
            if (isClosing || isFinishReported) return false
            if (SystemClock.uptimeMillis() - running.heartbeatAtUptimeMillis > HEARTBEAT_TIMEOUT.inWholeMilliseconds) {
                state = LaunchState.Unresponsive(running.status, running.heartbeatAtUptimeMillis)
                return true
            }
            scheduleHeartbeatCheck()
            return false
        }

        private fun scheduleHeartbeatCheck() {
            heartbeatCheck?.cancel()
            heartbeatCheck = null
            val running = state as? LaunchState.Running ?: return
            if (isClosing || isFinishReported) return
            heartbeatCheck = scope.launch {
                val remainingMillis = running.heartbeatAtUptimeMillis + HEARTBEAT_TIMEOUT.inWholeMilliseconds - SystemClock.uptimeMillis()
                delay((remainingMillis + 1L).coerceAtLeast(1L).milliseconds)
                wakeTransitionWaiter()
                handleHeartbeatTimeout(this@LaunchRecord)
            }
        }

        fun isAdmitted(): Boolean = !shutdownRequested && !failureQueued && SystemClock.elapsedRealtime() <= admittedUntilElapsedMillis

        fun hasLiveStatus(): Boolean = state is LaunchState.Running || state is LaunchState.Unresponsive

    }

    private sealed interface Report {
        class Running(val status: StreamingModule.Status, val heartbeatAtUptimeMillis: Long) : Report
        class Failed(val messageResource: Int?) : Report
        class Finished(val cleanupCompleted: Boolean) : Report
    }

    private sealed interface LaunchState {
        data object Starting : LaunchState
        class Running(val status: StreamingModule.Status, val heartbeatAtUptimeMillis: Long) : LaunchState
        class Unresponsive(val lastStatus: StreamingModule.Status, val heartbeatAtUptimeMillis: Long) : LaunchState
        class Failed(val failure: StreamingModuleManager.Failure) : LaunchState
        data object Stopped : LaunchState
    }

    private companion object {
        const val EXTRA_MODULE = "io.screenstream.streaming.MODULE_ID"
        const val EXTRA_UUID = "io.screenstream.streaming.INSTANCE_UUID"
        val HEARTBEAT_TIMEOUT = 3.seconds
        val FIRST_STATUS_TIMEOUT = 3.seconds
        val SHUTDOWN_TIMEOUT = 3.seconds
    }
}
