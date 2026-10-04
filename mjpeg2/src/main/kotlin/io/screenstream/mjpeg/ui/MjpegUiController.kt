package io.screenstream.mjpeg.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.mjpeg.MjpegCaptureSession.State.SuspensionProblem
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.ui.UiController.Action
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Synchronously projects screen state and forwards requests to its original module. */
internal class MjpegUiController(
    private val instanceId: StreamingModule.InstanceId,
    private val onUiCommand: (UiController.Command) -> Unit,
    private val settings: Lazy<MjpegSettings>,
) : UiController {
    override val state: StateFlow<UiController.State>
        field = MutableStateFlow(UiController.State(instanceId = instanceId, action = Action.Start(enabled = false)))

    fun update(
        action: Action,
        suspensionProblem: SuspensionProblem?,
        selectedAddresses: List<NetworkAddress>,
        port: Int?,
        servers: List<HttpDelivery.ServerInfo>,
        localNetworkPermissionGranted: Boolean,
        accessApplied: Boolean,
        appliedAccessToken: SecretValue?,
        permissionRequest: LocalNetworkPermissionRequest?,
        editPolicy: MjpegSettings.EditPolicy,
    ) {
        val addressServers = if (port == null) emptyList() else selectedAddresses.map { address ->
            mapAddressServer(
                address = address,
                port = port,
                server = servers.firstOrNull { it.address.id == address.id && it.port == port },
                allowed = !address.requiresLocalNetworkPermission || localNetworkPermissionGranted,
                accessApplied = accessApplied,
                appliedAccessToken = appliedAccessToken,
            )
        }
        state.value = UiController.State(
            instanceId = instanceId,
            action = action,
            addressServers = addressServers,
            permissionRequest = permissionRequest.takeIf { !localNetworkPermissionGranted },
            suspensionProblem = suspensionProblem,
            editPolicy = editPolicy,
        )
    }

    fun clear() {
        state.value = UiController.State(instanceId, Action.Busy)
    }

    override fun send(uiCommand: UiController.Command) = onUiCommand(uiCommand)

    @Composable
    fun Content(window: WindowSizeClass, modifier: Modifier = Modifier) {
        MjpegContent(
            uiController = this,
            settings = settings.value,
            modifier = modifier,
        )
    }
}
