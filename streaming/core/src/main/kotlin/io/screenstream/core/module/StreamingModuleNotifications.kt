package io.screenstream.core.module

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.AnyThread
import androidx.annotation.StringRes

/**
 * App-supplied provider that builds and delivers streaming notifications. Each caller decides when its
 * notification is shown, updated, or cleared.
 */
public interface StreamingModuleNotifications {
    public enum class Channel(public val id: String, public val defaultImportance: Int) {
        Streaming("io.screenstream.notification.CHANNEL_STREAMING", NotificationManager.IMPORTANCE_DEFAULT),
        Error("io.screenstream.notification.CHANNEL_ERROR", NotificationManager.IMPORTANCE_HIGH),
    }

    /** A stable positive foreground notification ID reserved for one Android Service component. */
    public interface ForegroundSlot {
        public val id: Int
    }

    /** Builds foreground content even when delivery is blocked; failures propagate to the Service. */
    @AnyThread
    public fun buildForeground(action: ForegroundAction): Notification

    /** Posts [content] in the caller's [notificationId] slot, or always attempts cancellation for null. Best effort. */
    @AnyThread
    public fun updateError(notificationId: Int, content: ErrorContent?)

    /**
     * Whether [channel] notifications can currently be delivered. Visibility of foreground notifications
     * and foreground-service eligibility follow separate Android rules.
     */
    @AnyThread
    public fun areNotificationsEnabled(channel: Channel): Boolean

    /** Resolvable channel settings, or app details before API 26; null when a settings intent is unavailable. */
    @AnyThread
    public fun channelSettingsIntent(channel: Channel): Intent?

    /**
     * The foreground button and its presentation: Exit while waiting or preparing capture, StopStream once
     * capture is confirmed. [pendingIntent] comes from [io.screenstream.core.manager.StreamingModuleActionReceiver]
     * and targets the exact instance or capture attempt; attach it unchanged.
     */
    public sealed interface ForegroundAction {
        public val pendingIntent: PendingIntent

        public data class Exit(override val pendingIntent: PendingIntent) : ForegroundAction

        public data class StopStream(override val pendingIntent: PendingIntent) : ForegroundAction
    }

    /** Error presentation selected by its owner. */
    public sealed interface ErrorContent {
        /**
         * Controller-selected detail. [recoveryAction], from `StreamingModuleActionReceiver.recoveryAction`,
         * targets the instance's current error episode.
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
        /** Slot of the manager's generic problem notification; foreground slots must differ. */
        public const val NOTIFICATION_ID_MODULE_ERROR: Int = 1000
    }
}
