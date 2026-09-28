package io.screenstream.streaming.manager

import android.os.SystemClock
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.module.StreamingModuleApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Manager-side state, admission, and report finality for one logical launch; not a Service or resource owner. */
internal class StreamingModuleLaunch(
    val id: StreamingModuleApi.InstanceId,
    val module: StreamingModuleApi,
    private val scope: CoroutineScope,
    private val onHeartbeatDue: (StreamingModuleLaunch) -> Unit,
) {
    /** Current launch outcome; source timestamps remain private to this launch. */
    sealed interface LaunchState {
        data object Starting : LaunchState
        data class Running(val status: StreamingModuleApi.Status, val heartbeatAtUptimeMillis: Long) : LaunchState
        data class Unresponsive(val lastStatus: StreamingModuleApi.Status, val heartbeatAtUptimeMillis: Long) : LaunchState
        data class Failed(val failure: StreamingModuleManager.Failure) : LaunchState
        data object Stopped : LaunchState
    }

    @Volatile
    private var admittedUntilElapsedMillis: Long = Long.MIN_VALUE
    var state: LaunchState = LaunchState.Starting
        private set
    var shutdownDeadlineElapsedMillis: Long? = null
        private set
    private var heartbeatCheck: Job? = null

    /** Receipt of reportFinished, independent of whether cleanup completed. */
    var isFinishReported: Boolean = false
        private set
    var isClosing: Boolean = false
        private set

    fun admit(deadlineElapsedMillis: Long = Long.MAX_VALUE) {
        admittedUntilElapsedMillis = deadlineElapsedMillis
    }

    private fun revokeAdmission() {
        admittedUntilElapsedMillis = Long.MIN_VALUE
    }

    fun acceptStatus(status: StreamingModuleApi.Status, heartbeatAtUptimeMillis: Long): Boolean {
        if (isClosing || isFinishReported || state is LaunchState.Failed || state == LaunchState.Stopped || !isAdmitted()) return false
        val previousHeartbeat = when (val previous = state) {
            is LaunchState.Running -> previous.heartbeatAtUptimeMillis
            is LaunchState.Unresponsive -> previous.heartbeatAtUptimeMillis
            else -> null
        }
        if (previousHeartbeat != null && heartbeatAtUptimeMillis < previousHeartbeat) return false

        admit()
        val fresh = SystemClock.uptimeMillis() - heartbeatAtUptimeMillis <= HEARTBEAT_TIMEOUT.inWholeMilliseconds
        state = if (fresh) LaunchState.Running(status, heartbeatAtUptimeMillis)
        else LaunchState.Unresponsive(status, heartbeatAtUptimeMillis)
        scheduleHeartbeatCheck()
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
        if (!cleanupCompleted) XLog.w(this@StreamingModuleLaunch.getLog("Finished", "Cleanup failed or unconfirmed for instance=$id"))
        return true
    }

    fun requestShutdown(deadlineIfFirst: Long) {
        if (shutdownDeadlineElapsedMillis != null) return
        shutdownDeadlineElapsedMillis = deadlineIfFirst
        try {
            module.requestShutdown(id, deadlineIfFirst)
        } catch (error: Exception) {
            XLog.e(this@StreamingModuleLaunch.getLog("Shutdown", "Failed to dispatch Shutdown for instance=$id, deadline=$deadlineIfFirst"), error)
        }
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
            onHeartbeatDue(this@StreamingModuleLaunch)
        }
    }

    fun isAdmitted(): Boolean = SystemClock.elapsedRealtime() <= admittedUntilElapsedMillis

    fun hasLiveStatus(): Boolean = state is LaunchState.Running || state is LaunchState.Unresponsive

    private companion object {
        val HEARTBEAT_TIMEOUT: Duration = 3.seconds
    }
}
