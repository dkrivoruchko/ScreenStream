package info.dvkr.screenstream.rtmp.ui.main.cards

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.rtmp.R
import info.dvkr.screenstream.rtmp.internal.RtmpEvent
import info.dvkr.screenstream.rtmp.ui.RtmpError
import info.dvkr.screenstream.rtmp.ui.isStartupPolicyError
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission

@Composable
internal fun ErrorCard(
    error: RtmpError,
    sendEvent: (event: RtmpEvent) -> Unit,
    hasEnabledAudioSource: Boolean,
    retryStartupPolicyError: () -> Unit,
    requestAudioPermission: () -> Unit,
    requestNotificationPermission: () -> Unit,
    requestLocalNetworkPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val startupPolicyError = error.isStartupPolicyError()
    val isAudioPermissionError = error is RtmpError.AudioPermissionRequired && hasEnabledAudioSource
    val permission = when {
        isAudioPermissionError -> RuntimePermission.RecordAudio
        error is RtmpError.NotificationPermissionRequired -> RuntimePermission.Notifications
        error is RtmpError.LocalNetworkPermissionRequired -> RuntimePermission.LocalNetwork
        else -> null
    }
    val permissionState = permission?.let { LocalRuntimePermissions.current.state(it).collectAsStateWithLifecycle().value }

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
                        isAudioPermissionError -> requestAudioPermission()
                        startupPolicyError -> retryStartupPolicyError()
                        error is RtmpError.NotificationPermissionRequired -> requestNotificationPermission()
                        error is RtmpError.LocalNetworkPermissionRequired -> requestLocalNetworkPermission()

                        else -> sendEvent(RtmpEvent.RecoverError())
                    }
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.End),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(MaterialTheme.colorScheme.onError))
            ) {
                val buttonTextId = when {
                    permissionState == PermissionState.SettingsSuggested -> R.string.rtmp_error_open_settings
                    isAudioPermissionError -> R.string.rtmp_error_allow_microphone
                    error is RtmpError.AudioStartBlocked -> R.string.rtmp_error_start_with_audio
                    startupPolicyError -> R.string.rtmp_error_start_screen_sharing
                    error is RtmpError.NotificationPermissionRequired && permissionState == PermissionState.Granted -> R.string.rtmp_error_open_settings
                    else -> R.string.rtmp_error_recover
                }
                Text(text = stringResource(buttonTextId), color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}
