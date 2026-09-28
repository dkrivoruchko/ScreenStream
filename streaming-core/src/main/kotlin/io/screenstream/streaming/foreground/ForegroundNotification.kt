package io.screenstream.streaming.foreground

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.MainThread
import info.dvkr.screenstream.common.notification.NotificationHelper
import io.screenstream.streaming.module.StreamingModuleApi
import io.screenstream.streaming.manager.CaptureStopReceiver

/** Build the existing app notification with an immutable Stop action for this exact capture attempt. */
@MainThread
public fun NotificationHelper.createForegroundNotification(
    context: Context,
    attempt: StreamingModuleApi.CaptureAttemptId,
): Notification = createForegroundNotification(
    context,
    PendingIntent.getBroadcast(context, 0, CaptureStopReceiver.forAttempt(context, attempt), PendingIntent.FLAG_IMMUTABLE),
)
