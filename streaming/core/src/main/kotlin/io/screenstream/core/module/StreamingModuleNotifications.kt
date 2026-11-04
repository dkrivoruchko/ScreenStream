package io.screenstream.core.module

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.AnyThread
import androidx.annotation.StringRes

/** App presentation and delivery of streaming notifications; their lifecycle stays with each owner. */
public interface StreamingModuleNotifications {
    public enum class Channel(public val id: String, public val defaultImportance: Int) {
        Streaming("io.screenstream.notification.CHANNEL_STREAMING", NotificationManager.IMPORTANCE_DEFAULT),
        Error("io.screenstream.notification.CHANNEL_ERROR", NotificationManager.IMPORTANCE_HIGH),
    }

    /** A stable positive foreground notification ID reserved for one Android Service component. */
    public interface ForegroundSlot {
        public val id: Int
    }

    /** Build even when notifications are blocked. Foreground construction failures propagate. */
    @AnyThread
    public fun buildForeground(action: ForegroundAction): Notification

    /** Replace this owner's error slot, or always attempt cancellation for null. Delivery is best effort. */
    @AnyThread
    public fun updateError(notificationId: Int, content: ErrorContent?)

    /** Current delivery settings; visibility and Service-start authority have separate conditions. */
    @AnyThread
    public fun areNotificationsEnabled(channel: Channel): Boolean

    /** Resolvable channel settings, or app details before API 26; null when a settings intent is unavailable. */
    @AnyThread
    public fun channelSettingsIntent(channel: Channel): Intent?

    /**
     * One foreground button and its matching presentation. The owner supplies an explicit immutable
     * action from [io.screenstream.core.manager.StreamingModuleActionReceiver]: Exit targets
     * the original instance and StopStream its exact capture attempt through a broadcast. Preserve those
     * identities when handling old actions; module ID or notification slot alone is insufficient.
     */
    public sealed interface ForegroundAction {
        public val pendingIntent: PendingIntent

        public data class Exit(override val pendingIntent: PendingIntent) : ForegroundAction

        public data class StopStream(override val pendingIntent: PendingIntent) : ForegroundAction
    }

    /** Current error presentation selected by its owner. */
    public sealed interface ErrorContent {
        /**
         * Module detail and optional recovery prepared by the original module owner. A recovery
         * action targets that instance and its module-owned current error episode for in-place recovery.
         */
        public data class Text(
            public val message: String,
            public val recoveryAction: PendingIntent? = null,
        ) : ErrorContent

        /** Manager-selected resource, or app-provided generic module-problem text when null. */
        public data class ModuleProblem(
            @get:StringRes public val messageResource: Int? = null,
        ) : ErrorContent
    }

    public companion object {
        public const val NOTIFICATION_ID_MODULE_ERROR: Int = 1000
    }
}
