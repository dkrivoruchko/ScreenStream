package info.dvkr.screenstream

import android.content.Context
import info.dvkr.screenstream.common.notification.NotificationHelper
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.manager.StreamingModuleErrorNotification

/** Required manager presentation uses the existing error notification and strings. */
internal class LegacyStreamingModuleErrorDisplay(
    private val context: Context,
    private val notifications: NotificationHelper,
) : StreamingModuleErrorNotification.Display {
    override fun show(state: StreamingModuleManager.State.Failed) {
        show((state.failure as? StreamingModuleManager.Failure.ModuleFailed)?.messageResource ?: R.string.app_error_title)
    }

    override fun show(state: StreamingModuleManager.State.Unresponsive) { show(R.string.app_error_title) }

    private fun show(message: Int) {
        if (notifications.errorNotificationsEnabled()) {
            notifications.showNotification(NOTIFICATION_ID, notifications.getErrorNotification(context, context.getString(message), null))
        }
    }

    override fun cancel() { notifications.cancelNotification(NOTIFICATION_ID) }

    private companion object {
        private const val NOTIFICATION_ID: Int = 500
    }
}
