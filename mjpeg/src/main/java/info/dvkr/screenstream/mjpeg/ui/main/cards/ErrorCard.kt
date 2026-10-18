package info.dvkr.screenstream.mjpeg.ui.main.cards

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
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.ui.MjpegError
import info.dvkr.screenstream.mjpeg.ui.isStartupPolicyError

@Composable
internal fun ErrorCard(
    error: MjpegError,
    sendEvent: (event: MjpegEvent) -> Unit,
    retryStartupPolicyError: () -> Unit,
    requestNotificationPermission: () -> Unit,
    requestLocalNetworkPermission: () -> Unit,
    notificationOpensSettings: Boolean,
    localNetworkOpensSettings: Boolean,
    modifier: Modifier = Modifier
) {
    val startupPolicyError = error.isStartupPolicyError()

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
                    when {
                        startupPolicyError -> retryStartupPolicyError()
                        error is MjpegError.NotificationPermissionRequired -> requestNotificationPermission()
                        error is MjpegError.LocalNetworkPermissionRequired -> requestLocalNetworkPermission()

                        else -> sendEvent(MjpegEvent.Intentable.RecoverError)
                    }
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.End),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(MaterialTheme.colorScheme.onError))
            ) {
                val buttonTextId = when {
                    startupPolicyError -> R.string.mjpeg_error_start_screen_sharing
                    error is MjpegError.NotificationPermissionRequired && notificationOpensSettings -> R.string.mjpeg_error_open_settings
                    error is MjpegError.LocalNetworkPermissionRequired && localNetworkOpensSettings -> R.string.mjpeg_error_open_settings
                    else -> R.string.mjpeg_error_recover
                }
                Text(text = stringResource(buttonTextId), color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}
