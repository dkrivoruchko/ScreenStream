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
import kotlinx.coroutines.launch

/**
 * Copy URL rereads the current discovery row and planned UUID after the click so a remembered
 * credential cannot be copied after its server is replaced or stops listening.
 */
@Composable
internal fun MjpegContent(
    uiController: UiController,
    settings: MjpegSettings,
    modifier: Modifier = Modifier,
) {
    val state by uiController.state.collectAsStateWithLifecycle()
    val action = state.action
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        LocalNetworkPermission(
            request = state.permissionRequest,
            onPermissionCheck = { uiController.send(UiController.Command.CheckLocalNetworkPermission) },
        )
        Button(
            onClick = {
                when (action) {
                    is UiController.Action.Start -> uiController.send(UiController.Command.Start)
                    UiController.Action.Busy -> Unit
                    is UiController.Action.Stop -> uiController.send(UiController.Command.Stop(action.attempt))
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
        MjpegNetworkFilters(settings = settings, editPolicy = state.editPolicy)
        state.addressServers.forEach { server ->
            key(server.addressId) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(server.interfaceName)
                    val host = if (':' in server.address) "[${server.address}]" else server.address
                    Text("$host:${server.port}")
                    if (server.isLoopback) Text(stringResource(R.string.mjpeg_same_device))
                    Text(stringResource(server.status.messageResource()))
                    if (server.serverId != null && server.status is UiController.ServerStatus.Failed) {
                        TextButton(onClick = { uiController.send(UiController.Command.RetryServer(server.serverId)) }) {
                            Text(stringResource(R.string.mjpeg_retry_server))
                        }
                    }
                    if (server.copyUrl != null) {
                        val label = stringResource(R.string.mjpeg_copy_url)
                        TextButton(onClick = {
                            scope.launch {
                                val url = uiController.state.value.addressServers
                                    .firstOrNull { it.addressId == server.addressId && it.serverId == server.serverId }
                                    ?.takeIf { it.status == UiController.ServerStatus.Listening }?.copyUrl
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

@StringRes
private fun UiController.ServerStatus.messageResource(): Int = when (this) {
    UiController.ServerStatus.Pending -> R.string.mjpeg_server_pending
    UiController.ServerStatus.Listening -> R.string.mjpeg_server_listening
    is UiController.ServerStatus.Failed -> when (reason) {
        UiController.ServerFailureReason.AddressInUse -> R.string.mjpeg_server_address_in_use
        UiController.ServerFailureReason.AddressUnavailable -> R.string.mjpeg_server_address_unavailable
        UiController.ServerFailureReason.PermissionDenied -> R.string.mjpeg_server_permission_denied
        UiController.ServerFailureReason.IoFailure -> R.string.mjpeg_server_io_failure
        UiController.ServerFailureReason.Unknown -> R.string.mjpeg_server_unknown_failure
    }
}
