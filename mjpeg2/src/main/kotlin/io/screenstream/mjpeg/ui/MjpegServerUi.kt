package io.screenstream.mjpeg.ui

import android.net.Uri
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.settings.SecretValue
import java.net.Inet6Address

/** Map authoritative HTTP facts into display models; the identity is only an opaque Retry handle. */
internal fun mapAddressServer(
    server: HttpDelivery.AddressInfo,
    allowed: Boolean,
    accessApplied: Boolean,
    appliedToken: SecretValue?,
): UiController.AddressServer {
    val address = server.address.ip
    val host = address.hostAddress.orEmpty()
    val status = when (val state = server.state) {
        HttpDelivery.ServerState.Pending -> UiController.ServerStatus.Pending
        HttpDelivery.ServerState.Listening -> UiController.ServerStatus.Listening
        HttpDelivery.ServerState.PermissionRequired -> UiController.ServerStatus.PermissionRequired
        is HttpDelivery.ServerState.Failed -> UiController.ServerStatus.Failed(when (state.reason) {
            HttpDelivery.ServerFailure.AddressInUse -> UiController.FailureReason.AddressInUse
            HttpDelivery.ServerFailure.AddressUnavailable -> UiController.FailureReason.AddressUnavailable
            HttpDelivery.ServerFailure.PermissionDenied -> UiController.FailureReason.PermissionDenied
            HttpDelivery.ServerFailure.IoFailure -> UiController.FailureReason.IoFailure
            HttpDelivery.ServerFailure.Unknown -> UiController.FailureReason.Unknown
        })
    }
    val portableHost = host.substringBefore('%')
    val portable = portableHost.isNotBlank() && server.port in MIN_PORT..MAX_PORT &&
            !address.isAnyLocalAddress && !address.isMulticastAddress &&
            !(address is Inet6Address && (address.isLinkLocalAddress || address.isSiteLocalAddress))
    val copyUrl = if (status == UiController.ServerStatus.Listening && allowed && accessApplied && portable) {
        val authority = if (address is Inet6Address) "[$portableHost]:${server.port}" else "$portableHost:${server.port}"
        val builder = Uri.Builder().scheme("http").encodedAuthority(authority).path("/")
        appliedToken?.let { builder.appendQueryParameter("t", it.value) }
        SecretValue(builder.build().toString())
    } else null
    return UiController.AddressServer(
        id = UiController.ServerId(server.id),
        interfaceName = server.address.interfaceName,
        interfaceType = server.address.interfaceType,
        address = host,
        port = server.port,
        status = status,
        copyUrl = copyUrl,
        sameDevice = address.isLoopbackAddress,
    )
}

private const val MIN_PORT: Int = 1
private const val MAX_PORT: Int = 65535
