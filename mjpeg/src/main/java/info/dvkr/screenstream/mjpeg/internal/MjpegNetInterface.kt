package info.dvkr.screenstream.mjpeg.internal

import java.net.Inet6Address
import java.net.InetAddress

internal data class MjpegNetInterface(val label: String, val address: InetAddress) {

    internal fun buildUrl(port: Int, https: Boolean = false): String {
        val scheme = if (https) "https" else "http"
        return if (address is Inet6Address) {
            "$scheme://[${address.hostAddress!!.substringBefore('%')}]:$port"
        } else {
            "$scheme://${address.hostAddress}:$port"
        }
    }
}