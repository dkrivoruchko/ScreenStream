package info.dvkr.screenstream.vnc.internal

import android.content.Context
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.vnc.R
import info.dvkr.screenstream.vnc.settings.VncSettings
import info.dvkr.screenstream.vnc.settings.VncSettings.Values.AddressMask
import info.dvkr.screenstream.vnc.settings.VncSettings.Values.InterfaceMask
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

internal class VncNetworkHelper(private val context: Context) {

    private companion object {
        private val INTERFACE_PATTERNS = mapOf(
            VncSettings.Values.INTERFACE_WIFI to listOf("wlan\\d", "ap\\d", "wigig\\d", "softap\\.?\\d").map { it.toRegex() },
            VncSettings.Values.INTERFACE_MOBILE to listOf("rmnet.*", "ccmni.*", "usb\\d").map { it.toRegex() },
            VncSettings.Values.INTERFACE_ETHERNET to listOf("eth\\d", "en\\d", "lan\\d").map { it.toRegex() },
            VncSettings.Values.INTERFACE_VPN to listOf("tun\\d", "tap\\d", "ppp\\d", "vpn").map { it.toRegex() }
        )
    }

    fun getNetInterfaces(
        @InterfaceMask interfaceFilter: Int,
        @AddressMask addressFilter: Int,
        enableIpv4: Boolean,
        enableIpv6: Boolean,
    ): List<VncServerEndpoint> {
        XLog.d(
            getLog(
                "getNetInterfaces",
                "interfaceFilter=$interfaceFilter, addressFilter=$addressFilter, ipv4=$enableIpv4, ipv6=$enableIpv6"
            )
        )

        val netInterfaces = mutableListOf<VncServerEndpoint>()

        runCatching { NetworkInterface.getNetworkInterfaces() }
            .getOrElse { Collections.enumeration(emptyList()) }
            .asSequence()
            .filter { nif -> runCatching { nif.isUp }.getOrDefault(false) }
            .forEach { nif ->
                val transportMask = INTERFACE_PATTERNS.entries.firstOrNull { (_, patterns) ->
                    patterns.any { regex -> regex.matches(nif.displayName) || regex.matches(nif.name) }
                }?.key ?: 0

                val isLoopback = runCatching { nif.isLoopback }.getOrDefault(false)

                if (!isLoopback && interfaceFilter != VncSettings.Values.INTERFACE_ALL && transportMask != 0 &&
                    (transportMask and interfaceFilter) == 0
                ) {
                    return@forEach
                }

                nif.inetAddresses
                    .asSequence()
                    .filter { addr -> !addr.isLinkLocalAddress && !addr.isMulticastAddress }
                    .filter { addr -> isAddressTypeEnabled(addr, enableIpv4, enableIpv6) }
                    .filter { addr -> passesAddressFilter(addr, addressFilter) }
                    .mapTo(netInterfaces) { toNetInterface(it, transportMask, isLoopback) }
            }

        if (netInterfaces.isEmpty() && enableIpv4 && (addressFilter == 0 || (addressFilter and VncSettings.Values.ADDRESS_LOCALHOST) != 0)) {
            runCatching {
                InetAddress.getByName("127.0.0.1").let { addr ->
                    if (addr.isLoopbackAddress) netInterfaces.add(toNetInterface(addr, 0, isLoopback = true))
                }
            }.onFailure { XLog.d(getLog("getNetInterfaces", "Failed to add IPv4 loopback: ${it.message}")) }
        }

        return netInterfaces
            .filter { it.address.hostAddress != null }
            .distinctBy { it.address.hostAddress!!.substringBefore('%') }
    }

    private fun isAddressTypeEnabled(addr: InetAddress, ipv4: Boolean, ipv6: Boolean): Boolean =
        (addr is Inet4Address && ipv4) || (addr is Inet6Address && ipv6)

    private fun isULA(addr: Inet6Address): Boolean = (addr.address[0].toInt() and 0xFE) == 0xFC

    private fun passesAddressFilter(addr: InetAddress, @AddressMask filter: Int): Boolean {
        if (filter == VncSettings.Values.ADDRESS_ALL) return true

        val isPrivate = when {
            addr.isLoopbackAddress -> false
            addr is Inet6Address && isULA(addr) -> true
            addr.isSiteLocalAddress -> true
            else -> false
        }

        return when {
            addr.isLoopbackAddress -> (filter and VncSettings.Values.ADDRESS_LOCALHOST) != 0
            isPrivate -> (filter and VncSettings.Values.ADDRESS_PRIVATE) != 0
            else -> (filter and VncSettings.Values.ADDRESS_PUBLIC) != 0
        }
    }

    private fun toNetInterface(address: InetAddress, transportMask: Int, isLoopback: Boolean): VncServerEndpoint {
        val interfaceLabel = when (transportMask) {
            VncSettings.Values.INTERFACE_WIFI -> context.getString(R.string.vnc_pref_filter_wifi)
            VncSettings.Values.INTERFACE_MOBILE -> context.getString(R.string.vnc_pref_filter_mobile)
            VncSettings.Values.INTERFACE_ETHERNET -> context.getString(R.string.vnc_pref_filter_ethernet)
            VncSettings.Values.INTERFACE_VPN -> context.getString(R.string.vnc_pref_filter_vpn)
            else -> context.getString(R.string.vnc_pref_filter_unknown)
        }

        val addressLabel = when {
            address.isLoopbackAddress -> context.getString(R.string.vnc_label_loopback)
            address is Inet6Address && isULA(address) -> context.getString(R.string.vnc_label_ipv6_ula)
            address.isSiteLocalAddress -> context.getString(R.string.vnc_label_ipv4_lan)
            address is Inet6Address -> context.getString(R.string.vnc_label_public_ipv6)
            else -> context.getString(R.string.vnc_label_public_ipv4)
        }

        val label = if (isLoopback) {
            context.getString(R.string.vnc_label_interface, addressLabel, if (address is Inet6Address) "IPv6" else "IPv4")
        } else {
            context.getString(R.string.vnc_label_interface, interfaceLabel, addressLabel)
        }

        return VncServerEndpoint(label, address)
    }
}
