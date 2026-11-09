package io.screenstream.core.manager

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.logE
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import kotlinx.coroutines.flow.StateFlow

/**
 * Manager policy for the generic problem notification of the current instance; [notifications] builds and
 * delivers it. An operation error or unresponsive health of one instance forms a single warning episode
 * with one delivery attempt; detail changes and re-enabled delivery do not repost it. Terminal failure
 * replaces the warning, and every other state cancels the notification.
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
                    logE("ErrorNotification", message, failure)
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
