package info.dvkr.screenstream.webrtc.ui.main.cards

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
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import info.dvkr.screenstream.webrtc.ui.WebRtcError
import info.dvkr.screenstream.webrtc.ui.isStartupPolicyError

@Composable
internal fun ErrorCard(
    error: WebRtcError,
    sendEvent: (event: WebRtcEvent) -> Unit,
    audioEnabled: Boolean,
    retryStartupPolicyError: () -> Unit,
    allowMicrophone: () -> Unit,
    openNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
    showRecoverAction: Boolean = true
) {
    val startupPolicyError = error.isStartupPolicyError()
    val microphonePermissionError = error is WebRtcError.AudioPermissionRequired && audioEnabled

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

            if (showRecoverAction) {
                OutlinedButton(
                    onClick = {
                        when {
                            microphonePermissionError -> allowMicrophone()
                            startupPolicyError -> retryStartupPolicyError()
                            error is WebRtcError.NotificationPermissionRequired -> {
                                sendEvent(WebRtcEvent.Intentable.RecoverError)
                                openNotificationSettings()
                            }

                            else -> sendEvent(WebRtcEvent.Intentable.RecoverError)
                        }
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .align(Alignment.End),
                    border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(MaterialTheme.colorScheme.onError))
                ) {
                    val buttonTextId = when {
                        microphonePermissionError -> R.string.webrtc_error_allow_microphone
                        error is WebRtcError.AudioStartBlocked -> R.string.webrtc_error_start_with_audio
                        startupPolicyError -> R.string.webrtc_error_start_screen_sharing
                        error is WebRtcError.NotificationPermissionRequired -> R.string.webrtc_error_open_settings
                        else -> R.string.webrtc_error_recover
                    }
                    Text(text = stringResource(buttonTextId), color = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}
