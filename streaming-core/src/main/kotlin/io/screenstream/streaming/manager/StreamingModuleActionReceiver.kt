package io.screenstream.streaming.manager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.module.StreamingModule
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

/** Routes exact notification actions without starting an Activity or Service. */
public class StreamingModuleActionReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context?, intent: Intent?) {
        val receivedIntent = intent ?: return
        when (receivedIntent.action) {
            ACTION_EXIT -> {
                val instance = receivedIntent.decodeExitInstance() ?: return
                get<StreamingModuleManager>().exit(instance)
            }

            ACTION_STOP_STREAM -> {
                val attempt = receivedIntent.decodeStopAttempt() ?: return
                get<StreamingModuleManager>().requestStreamStop(attempt)
            }
        }
    }

    public companion object {
        private const val ACTION_STOP_STREAM: String = "io.screenstream.action.STOP_CAPTURE"
        private const val ACTION_EXIT: String = "io.screenstream.action.EXIT"
        private const val SCHEME: String = "screenstream"
        private const val STOP_AUTHORITY: String = "capture-stop"
        private const val EXIT_AUTHORITY: String = "module-exit"

        /** Immutable Exit for this original module instance, including during startup. */
        public fun exitAction(context: Context, instance: StreamingModule.InstanceId): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StreamingModuleActionReceiver::class.java).setAction(ACTION_EXIT).setData(instance.exitUri()),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun Intent.decodeExitInstance(): StreamingModule.InstanceId? {
            val uri = data ?: return null
            if (uri.scheme != SCHEME || uri.authority != EXIT_AUTHORITY || uri.pathSegments.size != 2 ||
                uri.query != null || uri.fragment != null
            ) return null
            val (moduleId, instanceText) = uri.pathSegments
            if (moduleId.isBlank()) return null
            val instanceUuid = Uuid.parseOrNull(instanceText) ?: return null
            return StreamingModule.InstanceId(StreamingModule.Id(moduleId), instanceUuid).takeIf { it.exitUri() == uri }
        }

        private fun StreamingModule.InstanceId.exitUri(): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(EXIT_AUTHORITY)
            .appendPath(moduleId.value)
            .appendPath(uuid.toString())
            .build()

        /** Immutable notification action for this exact capture attempt, without starting a Service. */
        public fun stopAction(context: Context, attempt: StreamingModule.CaptureAttemptId): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StreamingModuleActionReceiver::class.java)
                .setAction(ACTION_STOP_STREAM)
                .setData(attempt.stopUri()),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun Intent.decodeStopAttempt(): StreamingModule.CaptureAttemptId? {
            val uri = data ?: return null
            if (uri.scheme != SCHEME || uri.authority != STOP_AUTHORITY || uri.pathSegments.size != 3 ||
                uri.query != null || uri.fragment != null
            ) return null
            val (moduleId, instanceText, attemptText) = uri.pathSegments
            if (moduleId.isBlank()) return null
            val instanceUuid = Uuid.parseOrNull(instanceText) ?: return null
            val attemptUuid = Uuid.parseOrNull(attemptText) ?: return null
            return StreamingModule.CaptureAttemptId(
                instanceId = StreamingModule.InstanceId(StreamingModule.Id(moduleId), instanceUuid),
                uuid = attemptUuid,
            ).takeIf { it.stopUri() == uri }
        }

        private fun StreamingModule.CaptureAttemptId.stopUri(): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(STOP_AUTHORITY)
            .appendPath(instanceId.moduleId.value)
            .appendPath(instanceId.uuid.toString())
            .appendPath(uuid.toString())
            .build()
    }
}
