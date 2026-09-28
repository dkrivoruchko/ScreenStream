package io.screenstream.streaming.manager

import android.os.SystemClock
import androidx.annotation.MainThread
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.settings.AppSettings
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.module.StreamingModuleApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Process-lifetime owner of module selection, instances, transitions, and coordinator errors.
 * Commands and reports are checked against their original instance independently of UI collection.
 *
 * @param modules Available modules, ordered by priority for selection.
 * @param appSettings Saved application selection and its updates.
 * @param errorNotification Owner of the process notification for coordinator errors.
 * @param dispatcher Dispatcher for process-owned work and queued module reports.
 */
@Singleton(binds = [StreamingModuleManager::class])
internal class StreamingModuleManagerImpl internal constructor(
    modules: List<StreamingModuleApi>,
    private val appSettings: AppSettings,
    private val errorNotification: StreamingModuleErrorNotification,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StreamingModuleManager {
    /** Registered modules copied for the selector, highest priority first. */
    private val modules: List<StreamingModuleApi> = modules.sortedByDescending { it.priority }.toList().also {
        require(it.isNotEmpty()) { "At least one streaming module is required" }
    }

    private val moduleById: Map<StreamingModule.Id, StreamingModuleApi> = this.modules.associateBy { it.id }.also {
        require(it.size == this.modules.size) { "Streaming module IDs must be unique" }
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var selectedModuleId: StreamingModule.Id? = null
    private var currentInstance: StreamingModuleLaunch? = null
    private var operationState: OperationState = OperationState.Idle
    private var operationTimer: Job? = null
    private var lastSettingsWrite: Job? = null

    /** One complete process state, independent of Activity collection. */
    override val state: StateFlow<StreamingModuleManager.State>
        field = MutableStateFlow<StreamingModuleManager.State>(StreamingModuleManager.State.NoModule)

    /** Select a known module after initialization, or launch the current selection when null. */
    @MainThread
    override fun selectModule(moduleId: StreamingModule.Id?) {
        if (operationState !is OperationState.Idle && operationState !is OperationState.Exited) return
        if (moduleId != null && moduleId !in moduleById) {
            XLog.w(this@StreamingModuleManagerImpl.getLog("Select", "Unknown streaming module ID=$moduleId"))
            return
        }
        if (selectedModuleId == null) {
            val selectionWait = OperationState.AwaitingSelection(moduleId)
            operationState = selectionWait
            publishState()
            scope.launch { initializeSelection(selectionWait) }
            return
        }
        selectLoadedModule(moduleId)
    }

    /** Resolve the first selection once; a revoked wait cannot mutate a later opening. */
    private suspend fun initializeSelection(selectionWait: OperationState.AwaitingSelection) {
        val savedModuleId = try {
            appSettings.initialize()
            appSettings.data.value.streamingModule
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (operationState === selectionWait) {
                operationState = OperationState.Idle
                state.value = StreamingModuleManager.State.NoModule
            }
            XLog.e(this@StreamingModuleManagerImpl.getLog("Initialize", "Failed to initialize module selection"), error)
            throw error
        }
        if (operationState !== selectionWait) return
        val resolvedModuleId = savedModuleId.takeIf { it in moduleById } ?: modules.first().id
        selectedModuleId = resolvedModuleId
        if (savedModuleId == AppSettings.Default.STREAMING_MODULE_NONE) {
            persistSettings {
                if (streamingModule == AppSettings.Default.STREAMING_MODULE_NONE) copy(streamingModule = resolvedModuleId) else this
            }
        }
        operationState = OperationState.Idle
        selectLoadedModule(selectionWait.requestedModuleId)
    }

    private fun selectLoadedModule(moduleId: StreamingModule.Id?) {
        val selected = selectedModuleId ?: return
        val target = moduleId ?: selected
        if (currentInstance?.id?.moduleId == target) return
        if (moduleId != null && target != selected) persistSettings { copy(streamingModule = target) }
        beginPreparation(target)
    }

    /** Restart only the exact current launch while its current error still permits it. */
    @MainThread
    override fun restartModule(expectedInstanceId: StreamingModuleApi.InstanceId) {
        if (operationState !is OperationState.Idle || selectedModuleId == null) return
        val instance = currentInstance ?: return
        if (instance.id != expectedInstanceId ||
            (instance.state !is StreamingModuleLaunch.LaunchState.Failed && instance.state !is StreamingModuleLaunch.LaunchState.Unresponsive)
        ) return
        beginPreparation(instance.id.moduleId)
    }

    /**
     * Exit revokes the current instance and pending launch, ends any transition, and closes launch
     * admission while active. Await process-owned shutdown with a deadline at most three seconds
     * after the first Exit request. Repeated calls await the same operation and deadline.
     * Caller cancellation ends only its wait; shutdown continues. Return means the wait ended,
     * including when cleanup remains unconfirmed. The calling Activity then finishes its task.
     * A later Activity may prepare the selected module after Exit completes.
     */
    @MainThread
    override suspend fun exit() {
        val completion = when (val operation = operationState) {
            is OperationState.AwaitingShutdown -> (operation.afterWait as? AfterShutdownWait.Exit)?.completion ?: beginExit()
            is OperationState.Exited -> operation.completion
            else -> beginExit()
        }
        if (operationState is OperationState.AwaitingShutdown) checkOperationDeadline()
        completion.await()
    }

    /**
     * Route an external Start to the current [instanceId] after its first status. The module decides
     * capture readiness when handling the request; stale or conflicting requests leave state
     * unchanged.
     */
    override fun requestStreamStart(instanceId: StreamingModuleApi.InstanceId) {
        scope.launch(dispatcher) {
            checkOperationDeadline()
            val instance = currentInstance ?: return@launch
            if (operationState !is OperationState.Idle || !instance.isAdmitted() || instance.id != instanceId || !instance.hasLiveStatus()) return@launch
            try {
                instance.module.requestStreamStart(instanceId)
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
    override fun requestStreamStop(attempt: StreamingModuleApi.CaptureAttemptId) {
        val instance = currentInstance ?: return
        if (operationState !is OperationState.Idle || !instance.isAdmitted() || instance.id != attempt.instanceId || !instance.hasLiveStatus()) return
        try {
            instance.module.requestStreamStop(attempt)
        } catch (error: Exception) {
            XLog.e(this@StreamingModuleManagerImpl.getLog("ExternalStop", "Failed to dispatch Stop for attempt=$attempt"), error)
        }
    }

    /** Current preparation or Exit step, including the completed Exit barrier. */
    private sealed interface OperationState {
        data object Idle : OperationState
        class AwaitingSelection(val requestedModuleId: StreamingModule.Id?) : OperationState
        class AwaitingShutdown(val instance: StreamingModuleLaunch, var afterWait: AfterShutdownWait) : OperationState
        class AwaitingFirstStatus(val instance: StreamingModuleLaunch, var deadlineElapsedMillis: Long? = null) : OperationState
        class Exited(val completion: CompletableDeferred<Unit>) : OperationState
    }

    /** Continuation after shutdown; Exit may replace a pending launch. */
    private sealed interface AfterShutdownWait {
        class Launch(val moduleId: StreamingModule.Id) : AfterShutdownWait
        class Exit(val completion: CompletableDeferred<Unit>) : AfterShutdownWait
    }

    /** Accept a selection, then launch it after the prior instance finishes or reaches its shutdown deadline. */
    private fun beginPreparation(moduleId: StreamingModule.Id) {
        selectedModuleId = moduleId
        val previousInstance = currentInstance
        if (previousInstance == null || previousInstance.isFinishReported) {
            launchInstance(moduleId)
            return
        }

        val shutdownWait = beginShutdownWait(previousInstance, AfterShutdownWait.Launch(moduleId))
        if (operationState !== shutdownWait || shutdownWait.afterWait !is AfterShutdownWait.Launch) return
        checkOperationDeadline()
        if (operationState === shutdownWait) publishState()
    }

    /** Give Exit priority over pending preparation and reuse the instance's first shutdown deadline. */
    private fun beginExit(): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        val operation = operationState
        if (operation is OperationState.AwaitingShutdown) {
            operation.afterWait = AfterShutdownWait.Exit(completion)
            checkOperationDeadline()
            if (operationState === operation) publishState()
            return completion
        }

        val instance = currentInstance
        if (instance == null || instance.isFinishReported) {
            finishExit(completion)
            return completion
        }

        val shutdownWait = beginShutdownWait(instance, AfterShutdownWait.Exit(completion))
        if (operationState !== shutdownWait) return completion
        checkOperationDeadline()
        if (operationState === shutdownWait) publishState()
        return completion
    }

    /** Install one exact shutdown wait before dispatching the original instance's Shutdown. */
    private fun beginShutdownWait(instance: StreamingModuleLaunch, afterWait: AfterShutdownWait): OperationState.AwaitingShutdown {
        instance.beginClosing()
        val deadlineElapsedMillis = instance.shutdownDeadlineElapsedMillis ?: (SystemClock.elapsedRealtime() + SHUTDOWN_TIMEOUT.inWholeMilliseconds)
        val shutdownWait = OperationState.AwaitingShutdown(instance, afterWait)
        operationTimer?.cancel()
        operationTimer = null
        operationState = shutdownWait
        scheduleOperationTimeout(shutdownWait, deadlineElapsedMillis)
        instance.requestShutdown(deadlineElapsedMillis)
        return shutdownWait
    }

    private fun continueAfterShutdownWait(shutdownWait: OperationState.AwaitingShutdown) {
        if (operationState !== shutdownWait) return
        operationTimer?.cancel()
        operationTimer = null
        operationState = OperationState.Idle
        when (val afterWait = shutdownWait.afterWait) {
            is AfterShutdownWait.Launch -> launchInstance(afterWait.moduleId)
            is AfterShutdownWait.Exit -> finishExit(afterWait.completion)
        }
    }

    private fun launchInstance(moduleId: StreamingModule.Id) {
        val instance = StreamingModuleLaunch(StreamingModuleApi.InstanceId(moduleId, Uuid.random()), moduleById.getValue(moduleId), scope) {
            checkOperationDeadline()
            handleHeartbeatTimeout(it)
        }
        val firstStatusWait = OperationState.AwaitingFirstStatus(instance)
        currentInstance = instance
        operationTimer?.cancel()
        operationTimer = null
        operationState = firstStatusWait
        instance.admit()
        try {
            instance.module.requestLaunch(InstanceCallbacks(instance))
        } catch (error: Exception) {
            if (operationState === firstStatusWait && currentInstance === instance && !instance.isClosing) {
                XLog.e(this@StreamingModuleManagerImpl.getLog("Launch", "Failed to launch instanceId=${instance.id}"), error)
                failInstance(instance, StreamingModuleManager.Failure.LaunchFailed)
            }
            return
        }
        if (operationState !== firstStatusWait || currentInstance !== instance || instance.isClosing) return
        val deadlineElapsedMillis = SystemClock.elapsedRealtime() + FIRST_STATUS_TIMEOUT.inWholeMilliseconds
        firstStatusWait.deadlineElapsedMillis = deadlineElapsedMillis
        instance.admit(deadlineElapsedMillis)
        scheduleOperationTimeout(firstStatusWait, deadlineElapsedMillis)
        publishState()
    }

    private fun finishExit(completion: CompletableDeferred<Unit>) {
        currentInstance?.beginClosing()
        currentInstance = null
        val exitedState = OperationState.Exited(completion)
        operationTimer?.cancel()
        operationTimer = null
        operationState = exitedState
        publishState()
        completion.complete(Unit)
    }

    private fun failInstance(instance: StreamingModuleLaunch, error: StreamingModuleManager.Failure) {
        if (!instance.fail(error)) return
        if (currentInstance !== instance || instance.isClosing) return
        val firstStatusWait = operationState as? OperationState.AwaitingFirstStatus
        if (firstStatusWait?.instance === instance) {
            operationTimer?.cancel()
            operationTimer = null
            operationState = OperationState.Idle
        }
        if (!instance.isFinishReported) {
            instance.requestShutdown(SystemClock.elapsedRealtime() + SHUTDOWN_TIMEOUT.inWholeMilliseconds)
        }
        if (currentInstance === instance && !instance.isClosing && operationState is OperationState.Idle) publishState()
    }

    private fun scheduleOperationTimeout(expectedOperation: OperationState, deadlineElapsedMillis: Long) {
        operationTimer?.cancel()
        operationTimer = scope.launch {
            while (operationState === expectedOperation) {
                val remainingMillis = deadlineElapsedMillis - SystemClock.elapsedRealtime()
                if (remainingMillis < 0L) {
                    checkOperationDeadline()
                    return@launch
                }
                delay((remainingMillis + 1L).milliseconds)
            }
        }
    }

    /** Check and settle the current wait when its absolute elapsed deadline has passed. */
    private fun checkOperationDeadline() {
        when (val operation = operationState) {
            is OperationState.AwaitingShutdown -> {
                val deadlineElapsedMillis = operation.instance.shutdownDeadlineElapsedMillis ?: return
                if (SystemClock.elapsedRealtime() > deadlineElapsedMillis) continueAfterShutdownWait(operation)
            }

            is OperationState.AwaitingFirstStatus -> {
                val deadlineElapsedMillis = operation.deadlineElapsedMillis ?: return
                if (SystemClock.elapsedRealtime() > deadlineElapsedMillis) failInstance(operation.instance, StreamingModuleManager.Failure.LaunchFailed)
            }

            else -> Unit
        }
    }

    /** Callbacks retain their instance identity and queue reports through the coordinator's deadline gate. */
    private inner class InstanceCallbacks(private val instance: StreamingModuleLaunch) : StreamingModuleApi.InstanceCallbacks {
        override val instanceId: StreamingModuleApi.InstanceId = instance.id

        override fun isCurrent(): Boolean = instance.isAdmitted()

        override fun reportRunning(status: StreamingModuleApi.Status, heartbeatAtUptimeMillis: Long) {
            scope.launch(dispatcher) {
                checkOperationDeadline()
                handleRunningReport(instance, status, heartbeatAtUptimeMillis)
            }
        }

        override fun reportFailed(messageResource: Int?) {
            scope.launch(dispatcher) {
                checkOperationDeadline()
                handleFailureReport(instance, messageResource)
            }
        }

        override fun reportFinished(cleanupCompleted: Boolean) {
            scope.launch(dispatcher) {
                checkOperationDeadline()
                handleFinishedReport(instance, cleanupCompleted)
            }
        }
    }

    private fun handleRunningReport(instance: StreamingModuleLaunch, status: StreamingModuleApi.Status, heartbeatAtUptimeMillis: Long) {
        if (currentInstance !== instance || !instance.acceptStatus(status, heartbeatAtUptimeMillis)) return

        val firstStatusWait = operationState as? OperationState.AwaitingFirstStatus
        if (firstStatusWait?.instance === instance) {
            operationTimer?.cancel()
            operationTimer = null
            operationState = OperationState.Idle
        }
        publishState()
    }

    private fun handleFailureReport(instance: StreamingModuleLaunch, messageResource: Int?) {
        if (currentInstance !== instance || instance.isClosing || instance.isFinishReported) return
        failInstance(instance, instance.failureFor(messageResource))
    }

    private fun handleFinishedReport(instance: StreamingModuleLaunch, cleanupCompleted: Boolean) {
        if (!instance.markFinished(cleanupCompleted)) return
        val shutdownWait = operationState as? OperationState.AwaitingShutdown
        if (shutdownWait?.instance === instance) {
            continueAfterShutdownWait(shutdownWait)
            return
        }
        val firstStatusWait = operationState as? OperationState.AwaitingFirstStatus
        if (firstStatusWait?.instance === instance) {
            operationTimer?.cancel()
            operationTimer = null
            operationState = OperationState.Idle
        }
        if (currentInstance === instance) publishState()
    }

    private fun handleHeartbeatTimeout(instance: StreamingModuleLaunch) {
        if (currentInstance !== instance) return
        if (instance.checkHeartbeat()) publishState()
    }

    private fun publishState() {
        val published = when (val operation = operationState) {
            is OperationState.AwaitingSelection,
            is OperationState.AwaitingFirstStatus -> StreamingModuleManager.State.Switching

            is OperationState.AwaitingShutdown ->
                if (operation.afterWait is AfterShutdownWait.Exit) {
                    StreamingModuleManager.State.Exiting
                } else {
                    StreamingModuleManager.State.Switching
                }

            is OperationState.Exited -> StreamingModuleManager.State.Exiting
            is OperationState.Idle -> {
                val instance = currentInstance
                if (instance == null) {
                    StreamingModuleManager.State.NoModule
                } else {
                    when (val launch = instance.state) {
                        is StreamingModuleLaunch.LaunchState.Running -> StreamingModuleManager.State.Running(instance.id, launch.status)
                        is StreamingModuleLaunch.LaunchState.Unresponsive -> StreamingModuleManager.State.Unresponsive(instance.id, launch.lastStatus)
                        is StreamingModuleLaunch.LaunchState.Failed -> StreamingModuleManager.State.Failed(instance.id, launch.failure)
                        StreamingModuleLaunch.LaunchState.Starting,
                        StreamingModuleLaunch.LaunchState.Stopped -> error("Idle manager cannot expose a starting or stopped launch")
                    }
                }
            }
        }
        state.value = published
        // A synchronous collector may reenter and publish a newer state during the assignment.
        when (val current = state.value) {
            is StreamingModuleManager.State.Failed -> errorNotification.show(current)
            is StreamingModuleManager.State.Unresponsive -> errorNotification.show(current)
            else -> errorNotification.cancel()
        }
    }

    private fun persistSettings(transform: AppSettings.Data.() -> AppSettings.Data) {
        val previousWrite = lastSettingsWrite
        lastSettingsWrite = scope.launch {
            previousWrite?.join()
            try {
                appSettings.updateData(transform)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                XLog.e(this@StreamingModuleManagerImpl.getLog("SettingsWrite", "Failed to save app settings"), error)
            }
        }
    }

    private companion object {
        val FIRST_STATUS_TIMEOUT = 3.seconds
        val SHUTDOWN_TIMEOUT = 3.seconds
    }
}
