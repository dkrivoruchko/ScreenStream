package info.dvkr.screenstream.mjpeg.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Enumeration

@SuppressLint("WifiManagerPotentialLeak")
internal class NetworkHelper(context: Context) {

    private val networkInterfaceCommonNameArray =
        arrayOf("lo", "eth", "lan", "wlan", "en", "p2p", "net", "ppp", "wigig", "ap", "rmnet", "rmnet_data")

    private val defaultWifiRegexArray: Array<Regex> = arrayOf(
        Regex("wlan\\d"),
        Regex("ap\\d"),
        Regex("wigig\\d"),
        Regex("softap\\.?\\d")
    )

    @SuppressLint("DiscouragedApi")
    private val wifiRegexArray: Array<Regex> =
        Resources.getSystem().getIdentifier("config_tether_wifi_regexs", "array", "android").let { tetherId ->
            context.applicationContext.resources.getStringArray(tetherId).map { it.toRegex() }.toTypedArray()
        }

    private val wifiManager: WifiManager = ContextCompat.getSystemService(context, WifiManager::class.java)!!

    private fun getNetworkInterfacesWithFallBack(): Enumeration<NetworkInterface> {
        try {
            return NetworkInterface.getNetworkInterfaces()
        } catch (ex: Exception) {
            XLog.w(getLog("getNetworkInterfacesWithFallBack.getNetworkInterfaces", ex.toString()))
        }

        val netList = mutableListOf<NetworkInterface>()
        (0..7).forEach { index ->
            try {
                val networkInterface: NetworkInterface? = NetworkInterface.getByIndex(index)
                if (networkInterface != null) netList.add(networkInterface)
                else if (index > 3) {
                    if (netList.isNotEmpty()) return Collections.enumeration(netList)
                    return@forEach
                }
            } catch (ex: Exception) {
                XLog.e(getLog("getNetworkInterfacesWithFallBack.getByIndex#$index:", ex.toString()))
            }
        }

        networkInterfaceCommonNameArray.forEach { commonName ->
            try {
                val networkInterface: NetworkInterface? = NetworkInterface.getByName(commonName)
                if (networkInterface != null) netList.add(networkInterface)

                (0..15).forEach { index ->
                    val networkInterfaceIndexed: NetworkInterface? = NetworkInterface.getByName("$commonName$index")
                    if (networkInterfaceIndexed != null) netList.add(networkInterfaceIndexed)
                }

            } catch (ex: Exception) {
                XLog.e(getLog("getNetworkInterfacesWithFallBack.commonName#$commonName:", ex.toString()))
            }
        }

        return Collections.enumeration(netList)
    }

    fun getNetInterfaces(
        interfaceFilter: Int,
        addressFilter: Int,
        enableIpv4: Boolean,
        enableIpv6: Boolean,
    ): List<MjpegNetInterface> {
        XLog.d(getLog("getNetInterfaces", "Invoked"))

        val netInterfaceList = mutableListOf<MjpegNetInterface>()

        val wifiPattern = defaultWifiRegexArray + wifiRegexArray
        val mobilePattern = arrayOf(Regex("rmnet.*"), Regex("ccmni.*"), Regex("usb\\d"))
        val ethernetPattern = arrayOf(Regex("eth\\d"), Regex("en\\d"), Regex("lan\\d"))
        val vpnPattern = arrayOf(Regex("tun\\d"), Regex("tap\\d"), Regex("ppp\\d"), Regex("vpn"))

        getNetworkInterfacesWithFallBack().asSequence().forEach { networkInterface ->
            val name = networkInterface.displayName

            val isWifi = wifiPattern.any { it.matches(name) }
            val isMobile = mobilePattern.any { it.matches(name) }
            val isEthernet = ethernetPattern.any { it.matches(name) }
            val isVpn = vpnPattern.any { it.matches(name) }

            val includeInterface =
                interfaceFilter == 0 ||
                        (isWifi && interfaceFilter and MjpegSettings.Values.INTERFACE_WIFI != 0) ||
                        (isMobile && interfaceFilter and MjpegSettings.Values.INTERFACE_MOBILE != 0) ||
                        (isEthernet && interfaceFilter and MjpegSettings.Values.INTERFACE_ETHERNET != 0) ||
                        (isVpn && interfaceFilter and MjpegSettings.Values.INTERFACE_VPN != 0)

            if (!includeInterface) return@forEach

            networkInterface.inetAddresses.asSequence().filterNotNull()
                .filter { !it.isLinkLocalAddress && !it.isMulticastAddress }
                .filter { (it is Inet4Address && enableIpv4) || (it is Inet6Address && enableIpv6) }
                .filter { address ->
                    if (addressFilter == 0) true
                    else {
                        val isLocalhost = address.isLoopbackAddress
                        val isPrivate = address.isSiteLocalAddress
                        val isPublic = !isLocalhost && !isPrivate
                        (isLocalhost && addressFilter and MjpegSettings.Values.ADDRESS_LOCALHOST != 0) ||
                                (isPrivate && addressFilter and MjpegSettings.Values.ADDRESS_PRIVATE != 0) ||
                                (isPublic && addressFilter and MjpegSettings.Values.ADDRESS_PUBLIC != 0)
                    }
                }
                .map { if (it is Inet6Address) Inet6Address.getByAddress(it.address) else it }
                .map { address ->
                    val interfaceLabel = when {
                        isWifi -> context.getString(R.string.mjpeg_pref_filter_wifi)
                        isMobile -> context.getString(R.string.mjpeg_pref_filter_mobile)
                        isEthernet -> context.getString(R.string.mjpeg_pref_filter_ethernet)
                        isVpn -> context.getString(R.string.mjpeg_pref_filter_vpn)
                        else -> name
                    }
                    val addressLabel = when {
                        address.isLoopbackAddress -> context.getString(R.string.mjpeg_label_loopback)
                        address.isSiteLocalAddress && address is Inet6Address -> context.getString(R.string.mjpeg_label_ipv6_ula)
                        address.isSiteLocalAddress -> context.getString(R.string.mjpeg_label_ipv4_lan)
                        address is Inet6Address -> context.getString(R.string.mjpeg_label_public_ipv6)
                        else -> context.getString(R.string.mjpeg_label_public_ipv4)
                    }
                    val label = if (address.isLoopbackAddress) addressLabel
                    else context.getString(R.string.mjpeg_label_interface, interfaceLabel, addressLabel)
                    MjpegNetInterface(label, address)
                }
                .toCollection(netInterfaceList)
        }

        if (netInterfaceList.isEmpty() && wifiConnected()) netInterfaceList.add(getWiFiNetAddress())

        return netInterfaceList
    }

    @Suppress("DEPRECATION")
    private fun wifiConnected() = wifiManager.connectionInfo.ipAddress != 0

    @Suppress("DEPRECATION")
    private fun getWiFiNetAddress(): MjpegNetInterface {
        XLog.d(getLog("getWiFiNetAddress", "Invoked"))

        val ipInt = wifiManager.connectionInfo.ipAddress
        val ipByteArray = ByteArray(4) { i -> (ipInt.shr(i * 8).and(255)).toByte() }
        val inet4Address = InetAddress.getByAddress(ipByteArray) as Inet4Address
        val interfaceLabel = context.getString(R.string.mjpeg_pref_filter_wifi)
        val addressLabel = context.getString(R.string.mjpeg_label_ipv4_lan)
        val label = context.getString(R.string.mjpeg_label_interface, interfaceLabel, addressLabel)
        return MjpegNetInterface(label, inet4Address)
    }
}