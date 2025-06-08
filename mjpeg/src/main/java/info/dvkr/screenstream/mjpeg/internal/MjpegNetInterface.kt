package info.dvkr.screenstream.mjpeg.internal

import java.net.Inet6Address
import java.net.InetAddress

internal data class MjpegNetInterface(val label: String, val address: InetAddress) {

    internal fun buildUrl(port: Int): String = if (address is Inet6Address) {
        "http://[${address.hostAddress!!.substringBefore('%')}]:$port"
    } else {
        "http://${address.hostAddress}:$port"
    }
}