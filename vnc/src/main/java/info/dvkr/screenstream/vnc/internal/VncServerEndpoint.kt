package info.dvkr.screenstream.vnc.internal

import java.net.InetAddress

internal data class VncServerEndpoint(val label: String, val address: InetAddress) {
    val fullAddress: String get() = address.hostAddress ?: ""
    val bindHost: String get() = address.hostAddress?.substringBefore('%') ?: ""
}
