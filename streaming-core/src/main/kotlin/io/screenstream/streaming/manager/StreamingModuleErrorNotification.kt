package io.screenstream.streaming.manager

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Singleton

/**
 * One process-owned notification for the current module-instance problem. Each continuous warning
 * episode gets one delivery attempt; a terminal failure replaces it.
 *
 * @param display Android presentation of the selected error.
 */
@Singleton
public class StreamingModuleErrorNotification internal constructor(private val display: Display) {
    /** Platform delivery of the selected error notification. */
    public interface Display {
        /** Present [messageResource] or generic text. */
        @MainThread
        public fun show(@StringRes messageResource: Int?)

        /** Remove the current module-instance problem notification. */
        @MainThread
        public fun cancel()
    }

    private var lastAttempt: Problem? = null
    private var hasAttemptedUpdate: Boolean = false
    private var isReconciling: Boolean = false

    /** Always pass the same manager flow; reentrant publications are read after delivery completes. */
    @MainThread
    internal fun update(states: StateFlow<StreamingModuleManager.State>) {
        if (isReconciling) return
        isReconciling = true
        try {
            while (true) {
                val target = when (val current = states.value) {
                    is StreamingModuleManager.State.Running -> if (current.status.errorMessage != null) Problem(current.instanceId) else null
                    is StreamingModuleManager.State.Unresponsive -> Problem(current.instanceId)
                    is StreamingModuleManager.State.Failed -> Problem(current.instanceId, isTerminal = true, messageResource = current.messageResource)
                    else -> null
                }
                if (hasAttemptedUpdate && lastAttempt == target) break
                lastAttempt = target
                hasAttemptedUpdate = true
                try {
                    if (target == null) {
                        display.cancel()
                    } else {
                        display.show(target.messageResource)
                    }
                } catch (failure: Exception) {
                    val message = if (target == null) {
                        "Failed to cancel error notification"
                    } else {
                        "Failed to show error notification for problem=$target"
                    }
                    XLog.e(this@StreamingModuleErrorNotification.getLog("ErrorNotification", message), failure)
                }
            }
        } finally {
            isReconciling = false
        }
    }

    private data class Problem(
        val instanceId: StreamingModule.InstanceId,
        val isTerminal: Boolean = false,
        @get:StringRes val messageResource: Int? = null,
    )
}
