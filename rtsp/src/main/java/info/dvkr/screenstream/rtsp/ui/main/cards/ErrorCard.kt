package info.dvkr.screenstream.rtsp.ui.main.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.RtspEvent
import info.dvkr.screenstream.rtsp.ui.RtspError

@Composable
internal fun ErrorCard(
    error: RtspError,
    sendEvent: (event: RtspEvent) -> Unit,
    openNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.error)
                .padding(12.dp)
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
        ) {
            Text(
                text = error.toString(LocalContext.current),
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedButton(
                onClick = {
                    sendEvent(RtspEvent.Intentable.RecoverError)
                    if (error is RtspError.NotificationPermissionRequired) {
                        openNotificationSettings()
                    }
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.End),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(MaterialTheme.colorScheme.onError))
            ) {
                val buttonTextId = if (error is RtspError.NotificationPermissionRequired) R.string.rtsp_error_open_settings
                else R.string.rtsp_error_recover
                Text(text = stringResource(buttonTextId), color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}
