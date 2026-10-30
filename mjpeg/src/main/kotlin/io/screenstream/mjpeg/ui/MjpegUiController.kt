package io.screenstream.mjpeg.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.screenstream.mjpeg.MjpegCaptureSession.State.SuspensionProblem
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.ui.UiController.Action
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.Inet6Address

/** Synchronously projects local screen facts and routes actions to their original owners. */
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
        appliedPin: SecretValue?,
        hidePinOnCapture: Boolean,
        editPolicy: MjpegSettings.EditPolicy,
        statistics: UiController.Statistics,
    ) {
        val addressServers = if (port == null) emptyList() else selectedAddresses.map { address ->
            address.mapAddressServer(
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
            suspensionProblem = suspensionProblem,
            editPolicy = editPolicy,
            appliedPin = appliedPin,
            hidePinOnCapture = hidePinOnCapture,
            statistics = statistics,
        )
    }

    override fun send(uiCommand: UiController.Command) = onUiCommand(uiCommand)

    @Composable
    fun Content(modifier: Modifier = Modifier) {
        MjpegContent(
            uiController = this,
            mjpegSettings = settings.value,
            modifier = modifier,
        )
    }
}

/** Advertise portable URLs from applied access only; saved preferences may still be unapplied. */
internal fun NetworkAddress.mapAddressServer(
    port: Int,
    server: HttpDelivery.ServerInfo?,
    allowed: Boolean,
    accessApplied: Boolean,
    appliedAccessToken: SecretValue?,
): UiController.AddressServer {
    val host = ip.hostAddress.orEmpty()
    val status = when {
        !allowed -> UiController.ServerStatus.Failed(UiController.ServerFailureReason.PermissionDenied)
        server == null -> UiController.ServerStatus.Pending
        else -> when (val state = server.state) {
            HttpDelivery.ServerState.Pending -> UiController.ServerStatus.Pending
            HttpDelivery.ServerState.Listening -> UiController.ServerStatus.Listening
            is HttpDelivery.ServerState.Failed -> UiController.ServerStatus.Failed(
                when (state.reason) {
                    HttpDelivery.ServerFailure.AddressInUse -> UiController.ServerFailureReason.AddressInUse
                    HttpDelivery.ServerFailure.AddressUnavailable -> UiController.ServerFailureReason.AddressUnavailable
                    HttpDelivery.ServerFailure.PermissionDenied -> UiController.ServerFailureReason.PermissionDenied
                    HttpDelivery.ServerFailure.IoFailure -> UiController.ServerFailureReason.IoFailure
                    HttpDelivery.ServerFailure.Unknown -> UiController.ServerFailureReason.Unknown
                }
            )
        }
    }
    val portableHost = host.substringBefore('%')
    val portable = portableHost.isNotBlank() && port in 1..65535 &&
            !ip.isAnyLocalAddress && !ip.isMulticastAddress &&
            !(ip is Inet6Address && (ip.isLinkLocalAddress || ip.isSiteLocalAddress))
    val accessUrl = if (status == UiController.ServerStatus.Listening && allowed && accessApplied && portable) {
        val builder = Uri.Builder()
            .scheme("http")
            .encodedAuthority(if (ip is Inet6Address) "[$portableHost]:$port" else "$portableHost:$port")
            .path("/")
        appliedAccessToken?.let { builder.appendQueryParameter("accessToken", it.value) }
        SecretValue(builder.build().toString())
    } else {
        null
    }

    return UiController.AddressServer(
        addressId = id,
        serverId = if (allowed) server?.id else null,
        interfaceName = interfaceName,
        interfaceType = interfaceType,
        family = if (ip is Inet4Address) AddressFamily.Ipv4 else AddressFamily.Ipv6,
        isPrivate = if (ip is Inet4Address) ip.isSiteLocalAddress else ip.address[0].toInt() and 0xfe == 0xfc,
        address = host,
        port = port,
        status = status,
        accessUrl = accessUrl,
        isLoopback = ip.isLoopbackAddress,
        isLinkLocal = ip.isLinkLocalAddress,
    )
}
