package io.screenstream.mjpeg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.module.StreamingModule
import io.screenstream.streaming.module.StreamingModuleApi
import org.koin.core.annotation.Singleton

/** Coordinator API kept separate from the temporary legacy shell registration. */
@Singleton(binds = [StreamingModuleApi::class])
internal class MjpegStreamingModule(
    private val moduleHost: MjpegStreamingModuleHost,
) : StreamingModuleApi {
    internal companion object {
        internal const val FOREGROUND_NOTIFICATION_ID: Int = 400
        internal val Id: StreamingModule.Id = StreamingModule.Id("MJPEG2")
    }

    override val id: StreamingModule.Id = Id
    override val priority: Int = 25
    override val nameResource: Int = R.string.mjpeg2_stream_mode
    override val descriptionResource: Int = R.string.mjpeg2_stream_mode_description
    override val detailsResource: Int = R.string.mjpeg2_stream_mode_details

    @Composable
    override fun StreamUIContent(
        instanceId: StreamingModuleApi.InstanceId,
        windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
        modifier: Modifier,
    ) {
        Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.mjpeg2_stream_mode_description),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.mjpeg2_stream_mode_details),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    override fun requestLaunch(callbacks: StreamingModuleApi.InstanceCallbacks) {
        moduleHost.requestLaunch(callbacks)
    }

    override fun requestStreamStart(instanceId: StreamingModuleApi.InstanceId) {
        moduleHost.controllerFor(instanceId)?.requestStreamStart()
    }

    override fun requestStreamStop(attempt: StreamingModuleApi.CaptureAttemptId) {
        moduleHost.controllerFor(attempt.instanceId)?.requestStreamStop(attempt)
    }

    override fun requestShutdown(instanceId: StreamingModuleApi.InstanceId, waitUntilElapsedMillis: Long) {
        moduleHost.requestShutdown(instanceId)
    }
}
