package info.dvkr.screenstream

import android.content.Context
import androidx.annotation.StringRes
import info.dvkr.screenstream.common.notification.NotificationHelper
import io.screenstream.streaming.manager.StreamingModuleErrorNotification

/** App-owned delivery policy for module warnings and terminal failures. */
internal class LegacyStreamingModuleErrorDisplay(
    private val context: Context,
    private val notifications: NotificationHelper,
) : StreamingModuleErrorNotification.Display {
    override fun show(@StringRes messageResource: Int?) {
        if (!notifications.errorNotificationsEnabled()) return
        val message = context.getString(messageResource ?: R.string.app_module_error_title)
        notifications.showNotification(NOTIFICATION_ID, notifications.getErrorNotification(context, message, null))
    }

    override fun cancel() {
        notifications.cancelNotification(NOTIFICATION_ID)
    }

    private companion object {
        private const val NOTIFICATION_ID: Int = 500
    }
}
