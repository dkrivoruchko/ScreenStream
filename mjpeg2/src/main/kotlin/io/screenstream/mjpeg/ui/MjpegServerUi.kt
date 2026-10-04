package io.screenstream.mjpeg.ui

import android.net.Uri
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.SecretValue
import java.net.Inet6Address

/** Advertise portable URLs from applied access only; saved preferences may still be unapplied. */
internal fun mapAddressServer(
    address: NetworkAddress,
    port: Int,
    server: HttpDelivery.ServerInfo?,
    allowed: Boolean,
    accessApplied: Boolean,
    appliedAccessToken: SecretValue?,
): UiController.AddressServer {
    val ip = address.ip
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
    val portable = portableHost.isNotBlank() && port in MIN_PORT..MAX_PORT &&
            !ip.isAnyLocalAddress && !ip.isMulticastAddress &&
            !(ip is Inet6Address && (ip.isLinkLocalAddress || ip.isSiteLocalAddress))
    val copyUrl = if (status == UiController.ServerStatus.Listening && allowed && accessApplied && portable) {
        val authority = if (ip is Inet6Address) "[$portableHost]:$port" else "$portableHost:$port"
        val builder = Uri.Builder().scheme("http").encodedAuthority(authority).path("/")
        appliedAccessToken?.let { builder.appendQueryParameter("accessToken", it.value) }
        SecretValue(builder.build().toString())
    } else null
    return UiController.AddressServer(
        addressId = address.id,
        serverId = server?.id.takeIf { allowed },
        interfaceName = address.interfaceName,
        interfaceType = address.interfaceType,
        address = host,
        port = port,
        status = status,
        copyUrl = copyUrl,
        isLoopback = ip.isLoopbackAddress,
    )
}

private const val MIN_PORT: Int = 1
private const val MAX_PORT: Int = 65535
