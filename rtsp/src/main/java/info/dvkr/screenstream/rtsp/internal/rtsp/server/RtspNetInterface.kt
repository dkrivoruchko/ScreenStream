package info.dvkr.screenstream.rtsp.internal.rtsp.server

import java.net.Inet6Address
import java.net.InetAddress

internal data class RtspNetInterface(val label: String, val address: InetAddress) {

    internal fun buildUrl(port: Int): String = if (address is Inet6Address) {
        "rtsp://[${address.hostAddress!!.substringBefore('%')}]:$port"
    } else {
        "rtsp://${address.hostAddress}:$port"
    }
}