package info.dvkr.screenstream.webrtc.ui.main

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
import info.dvkr.screenstream.common.notification.NotificationHelper
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import info.dvkr.screenstream.webrtc.ui.WebRtcError
import org.koin.compose.koinInject

@Composable
internal fun ErrorCard(
    error: WebRtcError,
    sendEvent: (event: WebRtcEvent) -> Unit,
    modifier: Modifier = Modifier,
    notificationHelper: NotificationHelper = koinInject()
) {
    val context = LocalContext.current

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.error)
                .padding(12.dp)
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
        ) {
            Text(
                text = error.toString(context),
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedButton(
                onClick = {
                    sendEvent(WebRtcEvent.Intentable.RecoverError)
                    if (error is WebRtcError.NotificationPermissionRequired) {
                        context.startActivity(notificationHelper.getStreamNotificationSettingsIntent())
                    }
                },
                modifier = Modifier.padding(top = 8.dp).align(Alignment.End),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(MaterialTheme.colorScheme.onError))
//TODO          border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(MaterialTheme.colorScheme.onError))
            ) {
                val buttonTextId = if (error is WebRtcError.NotificationPermissionRequired) R.string.webrtc_error_open_settings
                else R.string.webrtc_error_recover
                Text(text = stringResource(buttonTextId), color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}