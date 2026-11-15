package info.dvkr.screenstream.rtmp.ui.main.mode

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.rtmp.R
import info.dvkr.screenstream.rtmp.ui.RtmpClientStatus
import info.dvkr.screenstream.rtmp.ui.RtmpError

@Composable
internal fun ClientMode(
    clientStatus: RtmpClientStatus,
    error: RtmpError?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        val statusMessage = when (clientStatus) {
            RtmpClientStatus.ACTIVE -> stringResource(R.string.rtmp_connection_connected)
            RtmpClientStatus.STARTING -> stringResource(R.string.rtmp_connection_connecting)
            RtmpClientStatus.IDLE -> stringResource(R.string.rtmp_connection_disconnected)
            RtmpClientStatus.ERROR -> when (error) {
                is RtmpError.ClientError.Failed -> stringResource(error.id) + (error.message?.let { " [$it]" } ?: "")
                is RtmpError.UnknownError -> error.toString(LocalContext.current)
                is RtmpError -> stringResource(error.id)
                else -> stringResource(R.string.rtmp_connection_error)
            }
        }

        val statusColor = when (clientStatus) {
            RtmpClientStatus.ERROR -> MaterialTheme.colorScheme.error
            RtmpClientStatus.ACTIVE -> MaterialTheme.colorScheme.primary
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
