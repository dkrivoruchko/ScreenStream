package io.screenstream.streaming.manager

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleNotifications
import kotlinx.coroutines.flow.StateFlow

/**
 * Manager-owned notification policy for the current module-instance problem. Each continuous warning
 * episode gets one delivery attempt; a terminal failure replaces it. Android presentation and delivery
 * belong to [notifications].
 */
internal class ModuleErrorNotificationCoordinator(private val notifications: StreamingModuleNotifications) {
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
                    is StreamingModuleManager.State.Running -> if (current.status.error != null) Problem(current.instanceId) else null
                    is StreamingModuleManager.State.Unresponsive -> Problem(current.instanceId)
                    is StreamingModuleManager.State.Failed -> Problem(current.instanceId, isTerminal = true, messageResource = current.messageResource)
                    else -> null
                }
                if (hasAttemptedUpdate && lastAttempt == target) break
                lastAttempt = target
                hasAttemptedUpdate = true
                try {
                    notifications.updateError(
                        StreamingModuleNotifications.NOTIFICATION_ID_MODULE_ERROR,
                        target?.let { StreamingModuleNotifications.ErrorContent.ModuleProblem(it.messageResource) },
                    )
                } catch (failure: Exception) {
                    val message = if (target == null) {
                        "Failed to cancel error notification"
                    } else {
                        "Failed to show error notification for problem=$target"
                    }
                    XLog.e(this@ModuleErrorNotificationCoordinator.getLog("ErrorNotification", message), failure)
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
