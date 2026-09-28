package io.screenstream.streaming.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import info.dvkr.screenstream.common.module.StreamingModule
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.module.StreamingModuleApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

/** Routes an exact notification Stop to an already admitted module without starting an Activity or Service. */
public class CaptureStopReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context?, intent: Intent?) {
        val attempt = intent.decode() ?: return
        get<StreamingModuleManager>().requestStreamStop(attempt)
    }

    internal companion object {
        private const val ACTION: String = "io.screenstream.action.STOP_CAPTURE"
        private const val SCHEME: String = "screenstream"
        private const val AUTHORITY: String = "capture-stop"

        internal fun forAttempt(context: Context, attempt: StreamingModuleApi.CaptureAttemptId): Intent =
            Intent(context, CaptureStopReceiver::class.java)
                .setAction(ACTION)
                .setData(attempt.uriFor())

        private fun Intent?.decode(): StreamingModuleApi.CaptureAttemptId? {
            if (this?.action != ACTION) return null
            val uri = data ?: return null
            if (uri.scheme != SCHEME || uri.authority != AUTHORITY || uri.pathSegments.size != 3 || uri.query != null || uri.fragment != null) return null
            val (moduleId, instanceUuid, attemptUuid) = uri.pathSegments
            if (moduleId.isBlank()) return null
            return try {
                StreamingModuleApi.CaptureAttemptId(
                    instanceId = StreamingModuleApi.InstanceId(StreamingModule.Id(moduleId), Uuid.parse(instanceUuid)),
                    uuid = Uuid.parse(attemptUuid),
                ).takeIf { it.uriFor() == uri }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun StreamingModuleApi.CaptureAttemptId.uriFor(): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(AUTHORITY)
            .appendPath(instanceId.moduleId.value)
            .appendPath(instanceId.uuid.toString())
            .appendPath(uuid.toString())
            .build()
    }
}
