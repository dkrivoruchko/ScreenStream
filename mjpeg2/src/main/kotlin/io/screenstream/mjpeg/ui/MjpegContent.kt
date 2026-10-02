package io.screenstream.mjpeg.ui

import android.content.ClipData
import android.os.PersistableBundle
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.launch

/**
 * Capture controls and planned servers; only an explicit Copy URL reveals an access token.
 * @param uiController Installed controller's local display and server commands.
 * @param settings Saved preferences used by the network filter controls.
 * @param onStart Universal capture Start supplied by the core controller.
 * @param onStop Universal Stop carrying the displayed capture identity.
 * @param modifier Host layout modifier.
 */
@Composable
internal fun MjpegContent(
    uiController: UiController,
    settings: MjpegSettings,
    onStart: () -> Unit,
    onStop: (StreamingModule.CaptureAttemptId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by uiController.state.collectAsStateWithLifecycle()
    val action = state.action
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        LocalNetworkPermission(
            requestKey = state.permissionRequest,
            onPermissionChange = uiController::refreshLocalNetworkPermission,
            claimPermissionRequest = uiController::claimLocalNetworkPermissionRequest,
        )
        Button(
            onClick = {
                when (action) {
                    is UiController.Action.Start -> onStart()
                    UiController.Action.Busy -> Unit
                    is UiController.Action.Stop -> onStop(action.attempt)
                }
            },
            enabled = when (action) {
                is UiController.Action.Start -> action.enabled
                UiController.Action.Busy -> false
                is UiController.Action.Stop -> true
            },
        ) {
            when (action) {
                is UiController.Action.Start -> Text(stringResource(R.string.mjpeg_start_stream))
                UiController.Action.Busy -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                is UiController.Action.Stop -> Text(stringResource(R.string.mjpeg_stop_stream))
            }
        }
        MjpegNetworkFilters(settings = settings)
        state.addressServers.forEach { server ->
            key(server.id) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(server.interfaceName)
                    val host = if (':' in server.address) "[${server.address}]" else server.address
                    Text("$host:${server.port}")
                    if (server.sameDevice) Text(stringResource(R.string.mjpeg_same_device))
                    Text(stringResource(server.status.messageResource()))
                    if (server.status is UiController.ServerStatus.Failed) {
                        TextButton(onClick = { uiController.retryServer(server.id) }) {
                            Text(stringResource(R.string.mjpeg_retry_server))
                        }
                    }
                    if (server.copyUrl != null) {
                        val label = stringResource(R.string.mjpeg_copy_url)
                        TextButton(onClick = {
                            scope.launch {
                                val current = uiController.state.value.addressServers.firstOrNull { it.id == server.id }
                                val url = current?.takeIf { it.status == UiController.ServerStatus.Listening }?.copyUrl
                                    ?: return@launch
                                val clip = ClipData.newPlainText(label, url.value)
                                clip.description.extras = PersistableBundle().apply {
                                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                                }
                                clipboard.setClipEntry(ClipEntry(clip))
                            }
                        }) { Text(label) }
                    }
                }
            }
        }
    }
}

/** Map local availability to English-source localized resources without exposing implementation errors. */
@StringRes
private fun UiController.ServerStatus.messageResource(): Int = when (this) {
    UiController.ServerStatus.Pending -> R.string.mjpeg_server_pending
    UiController.ServerStatus.Listening -> R.string.mjpeg_server_listening
    UiController.ServerStatus.PermissionRequired -> R.string.mjpeg_server_permission_required
    is UiController.ServerStatus.Failed -> when (reason) {
        UiController.FailureReason.AddressInUse -> R.string.mjpeg_server_address_in_use
        UiController.FailureReason.AddressUnavailable -> R.string.mjpeg_server_address_unavailable
        UiController.FailureReason.PermissionDenied -> R.string.mjpeg_server_permission_denied
        UiController.FailureReason.IoFailure -> R.string.mjpeg_server_io_failure
        UiController.FailureReason.Unknown -> R.string.mjpeg_server_unknown_failure
    }
}
