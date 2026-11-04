package info.dvkr.screenstream.rtsp.ui.main.mode

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.ui.RtspClientStatus
import info.dvkr.screenstream.rtsp.ui.RtspError

@Composable
internal fun ClientMode(
    clientStatus: RtspClientStatus,
    error: RtspError?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        val statusMessage = when (clientStatus) {
            RtspClientStatus.ACTIVE -> stringResource(R.string.rtsp_connection_connected)
            RtspClientStatus.STARTING -> stringResource(R.string.rtsp_connection_connecting)
            RtspClientStatus.IDLE -> stringResource(R.string.rtsp_connection_disconnected)
            RtspClientStatus.ERROR -> when (error) {
                is RtspError.ClientError.Failed -> stringResource(error.id) + (error.message?.let { " [$it]" } ?: "")
                is RtspError.UnknownError -> error.toString(LocalContext.current)
                is RtspError -> stringResource(error.id)
                else -> stringResource(R.string.rtsp_connection_error)
            }
        }

        val statusColor = when (clientStatus) {
            RtspClientStatus.ERROR -> MaterialTheme.colorScheme.error
            RtspClientStatus.ACTIVE -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor,
            modifier = Modifier.padding(vertical = 16.dp)
        )
    }
}
