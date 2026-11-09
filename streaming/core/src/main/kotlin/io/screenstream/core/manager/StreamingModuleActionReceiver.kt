package io.screenstream.core.manager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

/**
 * Non-exported receiver that routes notification Exit, Stop, and recovery actions to
 * [StreamingModuleManager]. Each action's data URI encodes its exact target and keeps PendingIntents for
 * different targets distinct; malformed or non-canonical URIs are ignored.
 */
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
                get<StreamingModuleManager>().requestStreamStop(attempt, fromNotification = true)
            }

            ACTION_RECOVER -> {
                val (instance, errorToken) = receivedIntent.decodeRecovery() ?: return
                get<StreamingModuleManager>().requestRecovery(instance, errorToken)
            }
        }
    }

    public companion object {
        private const val ACTION_STOP_STREAM: String = "io.screenstream.action.STOP_CAPTURE"
        private const val ACTION_EXIT: String = "io.screenstream.action.EXIT"
        private const val ACTION_RECOVER: String = "io.screenstream.action.RECOVER_MODULE"
        private const val SCHEME: String = "screenstream"
        private const val STOP_AUTHORITY: String = "capture-stop"
        private const val EXIT_AUTHORITY: String = "module-exit"
        private const val RECOVERY_AUTHORITY: String = "module-recovery"

        /**
         * Immutable Exit for [instance]; the manager applies it only while that instance is admitted,
         * including during startup.
         */
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

        /** Immutable Stop for [attempt], routed with `fromNotification = true`; the controller validates it. */
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

        /**
         * Immutable in-place recovery for [instanceId]'s error episode identified by [errorToken]; the
         * controller validates the token.
         */
        public fun recoveryAction(context: Context, instanceId: StreamingModule.InstanceId, errorToken: Uuid): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StreamingModuleActionReceiver::class.java)
                .setAction(ACTION_RECOVER)
                .setData(instanceId.recoveryUri(errorToken)),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun Intent.decodeRecovery(): Pair<StreamingModule.InstanceId, Uuid>? {
            val uri = data ?: return null
            if (uri.scheme != SCHEME || uri.authority != RECOVERY_AUTHORITY || uri.pathSegments.size != 3 ||
                uri.query != null || uri.fragment != null
            ) return null
            val (moduleId, instanceText, errorText) = uri.pathSegments
            if (moduleId.isBlank()) return null
            val instanceUuid = Uuid.parseOrNull(instanceText) ?: return null
            val errorToken = Uuid.parseOrNull(errorText) ?: return null
            val instance = StreamingModule.InstanceId(StreamingModule.Id(moduleId), instanceUuid)
            return (instance to errorToken).takeIf { instance.recoveryUri(errorToken) == uri }
        }

        private fun StreamingModule.InstanceId.recoveryUri(errorToken: Uuid): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(RECOVERY_AUTHORITY)
            .appendPath(moduleId.value)
            .appendPath(uuid.toString())
            .appendPath(errorToken.toString())
            .build()
    }
}
