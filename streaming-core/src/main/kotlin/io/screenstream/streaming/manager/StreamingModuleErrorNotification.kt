package io.screenstream.streaming.manager

import androidx.annotation.MainThread
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import io.screenstream.streaming.StreamingModuleManager
import org.koin.core.annotation.Singleton

/**
 * One process-owned notification for the current module-instance problem. Each show replaces the prior problem.
 *
 * @param display Android presentation of the selected error.
 */
@Singleton
public class StreamingModuleErrorNotification internal constructor(private val display: Display) {
    /** Platform delivery of the selected error notification. */
    public interface Display {
        /** Present the terminal [state]. */
        @MainThread
        public fun show(state: StreamingModuleManager.State.Failed)

        /** Present the unresponsive [state]. */
        @MainThread
        public fun show(state: StreamingModuleManager.State.Unresponsive)

        /** Remove the presented coordinator error notification. */
        @MainThread
        public fun cancel()
    }

    private var desired: StreamingModuleManager.State? = null
    private var lastAttempt: StreamingModuleManager.State? = null
    private var isReconciling: Boolean = false

    /** Request the terminal problem; an identical last attempt is not delivered again. */
    @MainThread
    internal fun show(state: StreamingModuleManager.State.Failed) = update(state)

    /** Request unresponsive notification; status-only changes do not redeliver it. */
    @MainThread
    internal fun show(state: StreamingModuleManager.State.Unresponsive) = update(state)

    /** Request removal of the current error notification, without repeating the last attempt. */
    @MainThread
    internal fun cancel() {
        update(null)
    }

    private fun update(content: StreamingModuleManager.State?) {
        desired = content
        if (isReconciling) return
        isReconciling = true
        try {
            while (!sameProblem(lastAttempt, desired)) {
                val target = desired
                lastAttempt = target
                try {
                    when (target) {
                        null -> display.cancel()
                        is StreamingModuleManager.State.Failed -> display.show(target)
                        is StreamingModuleManager.State.Unresponsive -> display.show(target)
                        else -> error("Only module problems can be displayed")
                    }
                } catch (failure: Exception) {
                    val message = if (target == null) {
                        "Failed to cancel error notification"
                    } else {
                        "Failed to show error notification for state=$target"
                    }
                    XLog.e(this@StreamingModuleErrorNotification.getLog("ErrorNotification", message), failure)
                }
            }
        } finally {
            isReconciling = false
        }
    }

    private fun sameProblem(first: StreamingModuleManager.State?, second: StreamingModuleManager.State?): Boolean = when (first) {
        null -> second == null
        is StreamingModuleManager.State.Failed ->
            second is StreamingModuleManager.State.Failed && first.instanceId == second.instanceId && first.failure == second.failure

        is StreamingModuleManager.State.Unresponsive ->
            second is StreamingModuleManager.State.Unresponsive && first.instanceId == second.instanceId

        else -> false
    }
}
