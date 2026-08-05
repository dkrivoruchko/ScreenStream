package info.dvkr.screenstream.vnc.ui.main.cards

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
import info.dvkr.screenstream.vnc.R
import info.dvkr.screenstream.vnc.internal.VncEvent
import info.dvkr.screenstream.vnc.ui.VncError
import info.dvkr.screenstream.vnc.ui.isStartupPolicyError

@Composable
internal fun ErrorCard(
    error: VncError,
    sendEvent: (event: VncEvent) -> Unit,
    retryStartupPolicyError: () -> Unit,
    openNotificationSettings: () -> Unit,
    openLocalNetworkSettings: () -> Unit,
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
                        error is VncError.NotificationPermissionRequired -> {
                            sendEvent(VncEvent.Intentable.RecoverError)
                            openNotificationSettings()
                        }

                        error is VncError.LocalNetworkPermissionRequired -> {
                            sendEvent(VncEvent.Intentable.RecoverError)
                            openLocalNetworkSettings()
                        }

                        else -> sendEvent(VncEvent.Intentable.RecoverError)
                    }
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.End),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(MaterialTheme.colorScheme.onError))
            ) {
                val buttonTextId = when {
                    startupPolicyError -> R.string.vnc_error_start_screen_sharing
                    error is VncError.NotificationPermissionRequired || error is VncError.LocalNetworkPermissionRequired -> R.string.vnc_error_open_settings
                    else -> R.string.vnc_error_recover
                }
                Text(text = stringResource(buttonTextId), color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}
