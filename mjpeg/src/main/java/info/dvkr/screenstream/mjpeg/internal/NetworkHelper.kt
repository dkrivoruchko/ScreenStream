package info.dvkr.screenstream.mjpeg.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings.Values.AddressMask
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings.Values.InterfaceMask
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

internal class NetworkHelper(private val context: Context) {

    private companion object {
        private val INTERFACE_PATTERNS = mapOf(
            MjpegSettings.Values.INTERFACE_WIFI to listOf("wlan\\d", "ap\\d", "wigig\\d", "softap\\.?\\d").map { it.toRegex() },
            MjpegSettings.Values.INTERFACE_MOBILE to listOf("rmnet.*", "ccmni.*", "usb\\d").map { it.toRegex() },
            MjpegSettings.Values.INTERFACE_ETHERNET to listOf("eth\\d", "en\\d", "lan\\d").map { it.toRegex() },
            MjpegSettings.Values.INTERFACE_VPN to listOf("tun\\d", "tap\\d", "ppp\\d", "vpn").map { it.toRegex() }
        )

        private val COMMON_IFACE_NAMES = listOf(
            "lo", "eth", "lan", "wlan", "en", "p2p", "net", "ppp", "wigig", "ap", "rmnet", "rmnet_data"
        )

        private val LOOPBACK_IFACE_NAMES = listOf("lo", "lo0", "loopback")
    }

    private val connectivityManager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    private val enhancedInterfacePatterns: Map<Int, List<Regex>> by lazy {
        INTERFACE_PATTERNS.mapValues { (type, patterns) ->
            if (type == MjpegSettings.Values.INTERFACE_WIFI) {
                patterns + getOemWifiPatterns()
            } else {
                patterns
            }
        }
    }

    fun getNetInterfaces(
        @InterfaceMask interfaceFilter: Int,
        @AddressMask addressFilter: Int,
        enableIpv4: Boolean,
        enableIpv6: Boolean,
    ): List<MjpegNetInterface> {
        XLog.d(
            getLog(
                "getNetInterfaces",
                "interfaceFilter=$interfaceFilter, addressFilter=$addressFilter, ipv4=$enableIpv4, ipv6=$enableIpv6"
            )
        )

        val netInterfaces = mutableListOf<MjpegNetInterface>()

        if (addressFilter != MjpegSettings.Values.ADDRESS_LOCALHOST) {
            connectivityManager?.runCatching {
                discoverViaConnectivityManager(interfaceFilter, addressFilter, enableIpv4, enableIpv6, netInterfaces)
            }?.onFailure { XLog.w(getLog("CM discovery failed", it.toString())) }
        }

        discoverViaNetworkInterface(
            interfaceFilter = interfaceFilter,
            addressFilter = addressFilter,
            enableIpv4 = enableIpv4,
            enableIpv6 = enableIpv6,
            sink = netInterfaces,
            useStrictFiltering = true
        )

        val needsLocalhost = addressFilter == 0 || (addressFilter and MjpegSettings.Values.ADDRESS_LOCALHOST) != 0
        if (needsLocalhost && netInterfaces.none { it.address.isLoopbackAddress }) {
            XLog.d(getLog("getNetInterfaces", "No loopback found, attempting manual discovery"))
            discoverLoopbackManually(enableIpv4, enableIpv6, netInterfaces)
        }

        if (netInterfaces.isEmpty() && (enableIpv4 || enableIpv6) && addressFilter != MjpegSettings.Values.ADDRESS_LOCALHOST) {
            XLog.d(getLog("getNetInterfaces", "Attempting discovery with relaxed filters"))

            discoverViaNetworkInterface(
                interfaceFilter = interfaceFilter,
                addressFilter = addressFilter,
                enableIpv4 = enableIpv4,
                enableIpv6 = enableIpv6,
                sink = netInterfaces,
                useStrictFiltering = false
            )
        }

        return netInterfaces
            .filter { it.address.hostAddress != null }
            .distinctBy { it.address.hostAddress!!.substringBefore('%') }
    }

    @Suppress("DEPRECATION")
    private fun discoverViaConnectivityManager(
        @InterfaceMask interfaceFilter: Int,
        @AddressMask addressFilter: Int,
        enableIpv4: Boolean,
        enableIpv6: Boolean,
        sink: MutableList<MjpegNetInterface>
    ) {
        connectivityManager?.allNetworks?.forEach { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@forEach
            val linkProps = connectivityManager.getLinkProperties(network) ?: return@forEach

            val transportMask = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> MjpegSettings.Values.INTERFACE_WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> MjpegSettings.Values.INTERFACE_MOBILE
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> MjpegSettings.Values.INTERFACE_ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> MjpegSettings.Values.INTERFACE_VPN
                else -> 0
            }

            if (interfaceFilter != 0 && (transportMask and interfaceFilter) == 0) return@forEach

            linkProps.linkAddresses
                .mapNotNull { it.address }
                .filter { addr -> !addr.isLinkLocalAddress && !addr.isMulticastAddress }
                .filter { isAddressTypeEnabled(it, enableIpv4, enableIpv6) }
                .filter { passesAddressFilter(it, addressFilter) }
                .mapTo(sink) { toMjpegNetInterface(it, transportMask) }
        }
    }

    private fun discoverViaNetworkInterface(
        @InterfaceMask interfaceFilter: Int,
        @AddressMask addressFilter: Int,
        enableIpv4: Boolean,
        enableIpv6: Boolean,
        sink: MutableList<MjpegNetInterface>,
        useStrictFiltering: Boolean
    ) {
        val allIfaceNames = COMMON_IFACE_NAMES + LOOPBACK_IFACE_NAMES

        runCatching { NetworkInterface.getNetworkInterfaces() }.getOrElse {
            val interfaces = mutableSetOf<NetworkInterface>()
            (0..10).forEach { index ->
                runCatching { NetworkInterface.getByIndex(index) }.getOrNull()?.let(interfaces::add)
            }
            allIfaceNames.forEach { base ->
                runCatching { NetworkInterface.getByName(base) }.getOrNull()?.let(interfaces::add)
                (0..15).forEach { i -> runCatching { NetworkInterface.getByName("$base$i") }.getOrNull()?.let(interfaces::add) }
            }
            Collections.enumeration(interfaces.toList())
        }
            .asSequence()
            .filter { nif ->
                if (!runCatching { nif.isUp }.getOrDefault(false)) return@filter false
                val isLoopback = LOOPBACK_IFACE_NAMES.any {
                    nif.name.equals(it, ignoreCase = true) || nif.displayName.equals(it, ignoreCase = true)
                }
                val needsLocalhost = addressFilter == 0 || (addressFilter and MjpegSettings.Values.ADDRESS_LOCALHOST) != 0
                if (isLoopback && needsLocalhost) return@filter true

                !useStrictFiltering || Build.VERSION.SDK_INT < 24 || !nif.isVirtual
            }
            .forEach { nif ->
                val transportMask = when {
                    LOOPBACK_IFACE_NAMES.any {
                        nif.name.equals(it, ignoreCase = true) || nif.displayName.equals(it, ignoreCase = true)
                    } -> 0

                    else -> enhancedInterfacePatterns.entries.firstOrNull { (_, patterns) ->
                        patterns.any { regex -> regex.matches(nif.displayName) || regex.matches(nif.name) }
                    }?.key ?: 0
                }

                val isLoopback = transportMask == 0 && LOOPBACK_IFACE_NAMES.any { nif.name.equals(it, ignoreCase = true) }

                if (!isLoopback && interfaceFilter != 0 && transportMask != 0 && (transportMask and interfaceFilter) == 0) {
                    return@forEach
                }

                nif.inetAddresses
                    .asSequence()
                    .filter { addr -> !addr.isLinkLocalAddress && !addr.isMulticastAddress }
                    .filter { addr -> isAddressTypeEnabled(addr, enableIpv4, enableIpv6) }
                    .filter { addr -> passesAddressFilter(addr, addressFilter) }
                    .mapTo(sink) { toMjpegNetInterface(it, transportMask) }
            }
    }

    private fun discoverLoopbackManually(
        enableIpv4: Boolean,
        enableIpv6: Boolean,
        sink: MutableList<MjpegNetInterface>
    ) {
        LOOPBACK_IFACE_NAMES.forEach { name ->
            runCatching {
                NetworkInterface.getByName(name)?.let { nif ->
                    if (runCatching { nif.isUp }.getOrDefault(false)) {
                        nif.inetAddresses
                            .asSequence()
                            .filter { it.isLoopbackAddress }
                            .filter { isAddressTypeEnabled(it, enableIpv4, enableIpv6) }
                            .mapTo(sink) { toMjpegNetInterface(it, 0) }
                    }
                }
            }.onFailure {
                XLog.d(getLog("discoverLoopbackManually", "Failed to get interface $name: ${it.message}"))
            }
        }

        if (sink.none { it.address.isLoopbackAddress }) {
            if (enableIpv4) {
                runCatching {
                    InetAddress.getByName("127.0.0.1").let { addr -> if (addr.isLoopbackAddress) sink.add(toMjpegNetInterface(addr, 0)) }
                }.onFailure {
                    XLog.d(getLog("discoverLoopbackManually", "Failed to add IPv4 loopback: ${it.message}"))
                }
            }

            if (enableIpv6) {
                runCatching {
                    InetAddress.getByName("::1").let { addr -> if (addr.isLoopbackAddress) sink.add(toMjpegNetInterface(addr, 0)) }
                }.onFailure {
                    XLog.d(getLog("discoverLoopbackManually", "Failed to add IPv6 loopback: ${it.message}"))
                }
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun getOemWifiPatterns(): List<Regex> {
        val id = Resources.getSystem().getIdentifier("config_tether_wifi_regexs", "array", "android")
        return if (id != 0) {
            runCatching { context.resources.getStringArray(id).map { it.toRegex() } }.getOrElse {
                XLog.w(getLog("getOemWifiPatterns", "Failed to load OEM patterns: $it"))
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    private fun isAddressTypeEnabled(addr: InetAddress, ipv4: Boolean, ipv6: Boolean): Boolean =
        (addr is Inet4Address && ipv4) || (addr is Inet6Address && ipv6)

    private fun isULA(addr: Inet6Address): Boolean = (addr.address[0].toInt() and 0xFE) == 0xFC

    private fun passesAddressFilter(addr: InetAddress, @AddressMask filter: Int): Boolean {
        if (filter == MjpegSettings.Values.ADDRESS_ALL) return true

        val isPrivate = when {
            addr.isLoopbackAddress -> false
            addr is Inet6Address && isULA(addr) -> true
            addr.isSiteLocalAddress -> true
            else -> false
        }

        return when {
            addr.isLoopbackAddress -> (filter and MjpegSettings.Values.ADDRESS_LOCALHOST) != 0
            isPrivate -> (filter and MjpegSettings.Values.ADDRESS_PRIVATE) != 0
            else -> (filter and MjpegSettings.Values.ADDRESS_PUBLIC) != 0
        }
    }

    private fun toMjpegNetInterface(address: InetAddress, transportMask: Int): MjpegNetInterface {
        val interfaceLabel = when (transportMask) {
            MjpegSettings.Values.INTERFACE_WIFI -> context.getString(R.string.mjpeg_pref_filter_wifi)
            MjpegSettings.Values.INTERFACE_MOBILE -> context.getString(R.string.mjpeg_pref_filter_mobile)
            MjpegSettings.Values.INTERFACE_ETHERNET -> context.getString(R.string.mjpeg_pref_filter_ethernet)
            MjpegSettings.Values.INTERFACE_VPN -> context.getString(R.string.mjpeg_pref_filter_vpn)
            else -> context.getString(R.string.mjpeg_pref_filter_unknown)
        }

        val addressLabel = when {
            address.isLoopbackAddress -> context.getString(R.string.mjpeg_label_loopback)
            address is Inet6Address && isULA(address) -> context.getString(R.string.mjpeg_label_ipv6_ula)
            address.isSiteLocalAddress -> context.getString(R.string.mjpeg_label_ipv4_lan)
            address is Inet6Address -> context.getString(R.string.mjpeg_label_public_ipv6)
            else -> context.getString(R.string.mjpeg_label_public_ipv4)
        }

        val label = if (address.isLoopbackAddress) {
            context.getString(R.string.mjpeg_label_interface, addressLabel, if (address is Inet6Address) "IPv6" else "IPv4")
        } else {
            context.getString(R.string.mjpeg_label_interface, interfaceLabel, addressLabel)
        }

        return MjpegNetInterface(label, address)
    }
}