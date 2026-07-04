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
import info.dvkr.screenstream.rtsp.ui.isStartupPolicyError

@Composable
internal fun ErrorCard(
    error: RtspError,
    sendEvent: (event: RtspEvent) -> Unit,
    audioEnabled: Boolean,
    retryStartupPolicyError: () -> Unit,
    allowMicrophone: () -> Unit,
    openNotificationSettings: () -> Unit,
    openLocalNetworkSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val startupPolicyError = error.isStartupPolicyError()
    val microphonePermissionError = error is RtspError.AudioPermissionRequired && audioEnabled

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
                        microphonePermissionError -> allowMicrophone()
                        startupPolicyError -> retryStartupPolicyError()
                        error is RtspError.NotificationPermissionRequired -> {
                            sendEvent(RtspEvent.Intentable.RecoverError)
                            openNotificationSettings()
                        }

                        error is RtspError.LocalNetworkPermissionRequired -> {
                            sendEvent(RtspEvent.Intentable.RecoverError)
                            openLocalNetworkSettings()
                        }

                        else -> sendEvent(RtspEvent.Intentable.RecoverError)
                    }
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.End),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(MaterialTheme.colorScheme.onError))
            ) {
                val buttonTextId = when {
                    microphonePermissionError -> R.string.rtsp_error_allow_microphone
                    error is RtspError.AudioStartBlocked -> R.string.rtsp_error_start_with_audio
                    startupPolicyError -> R.string.rtsp_error_start_screen_sharing
                    error is RtspError.NotificationPermissionRequired || error is RtspError.LocalNetworkPermissionRequired -> R.string.rtsp_error_open_settings
                    else -> R.string.rtsp_error_recover
                }
                Text(text = stringResource(buttonTextId), color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}
