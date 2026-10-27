package io.screenstream.streaming.module

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

    /** Current delivery settings; neither proof of visibility nor authorization to start a Service. */
    @AnyThread
    public fun areNotificationsEnabled(channel: Channel): Boolean

    /** Resolvable channel settings, or app details before API 26; null if unavailable. */
    @AnyThread
    public fun channelSettingsIntent(channel: Channel): Intent?

    /** One foreground button and its matching presentation. The owner supplies an explicit immutable action. */
    public sealed interface ForegroundAction {
        public val pendingIntent: PendingIntent

        public data class Exit(override val pendingIntent: PendingIntent) : ForegroundAction

        public data class StopStream(override val pendingIntent: PendingIntent) : ForegroundAction
    }

    /** Current error presentation selected by its owner. */
    public sealed interface ErrorContent {
        /** Module detail and optional recovery prepared by the original module owner. */
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
