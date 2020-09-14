package info.dvkr.screenstream.data.state.helper

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.NetInterface
import info.dvkr.screenstream.data.other.getLog
import java.net.*
import java.util.*

@SuppressLint("WifiManagerPotentialLeak")
class NetworkHelper(context: Context) {

    private val networkInterfaceCommonNameArray =
        arrayOf("lo", "eth", "lan", "wlan", "en", "p2p", "net", "ppp", "wigig", "ap", "rmnet", "rmnet_data")

    private val defaultWifiRegexArray: Array<Regex> = arrayOf(
        Regex("wlan\\d"),
        Regex("ap\\d"),
        Regex("wigig\\d"),
        Regex("softap\\.?\\d")
    )

    private val wifiRegexArray: Array<Regex> =
        Resources.getSystem().getIdentifier("config_tether_wifi_regexs", "array", "android").let { tetherId ->
            context.applicationContext.resources.getStringArray(tetherId).map { it.toRegex() }.toTypedArray()
        }

    private val wifiManager: WifiManager = ContextCompat.getSystemService(context, WifiManager::class.java)!!

    private fun getNetworkInterfacesWithFallBack(): Enumeration<NetworkInterface> {
        try {
            return NetworkInterface.getNetworkInterfaces()
        } catch (ex: SocketException) {
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
            } catch (ex: SocketException) {
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

            } catch (ex: SocketException) {
                XLog.e(getLog("getNetworkInterfacesWithFallBack.commonName#$commonName:", ex.toString()))
            }
        }

        return Collections.enumeration(netList)
    }

    fun getNetInterfaces(
        useWiFiOnly: Boolean, enableIPv6: Boolean, enableLocalHost: Boolean, localHostOnly: Boolean
    ): List<NetInterface> {
        XLog.d(getLog("getNetInterfaces", "Invoked"))

        val netInterfaceList = mutableListOf<NetInterface>()

        getNetworkInterfacesWithFallBack().asSequence().filterNotNull()
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.asSequence().filterNotNull()
                    .filter { !it.isLinkLocalAddress && !it.isMulticastAddress }
                    .filter { enableLocalHost || it.isLoopbackAddress.not() }
                    .filter { localHostOnly.not() || it.isLoopbackAddress}
                    .filter { (it is Inet4Address) || (enableIPv6 && (it is Inet6Address)) }
                    .map { if (it is Inet6Address) Inet6Address.getByAddress(it.address) else it }
                    .map { NetInterface(networkInterface.displayName, it) }
            }
            .filter { netInterface ->
                (enableLocalHost && netInterface.address.isLoopbackAddress) || useWiFiOnly.not() || (
                        defaultWifiRegexArray.any { it.matches(netInterface.name) } ||
                                wifiRegexArray.any { it.matches(netInterface.name) }
                        )
            }
            .toCollection(netInterfaceList)

        if (netInterfaceList.isEmpty() && wifiConnected()) netInterfaceList.add(getWiFiNetAddress())

        return netInterfaceList
    }

    private fun wifiConnected() = wifiManager.connectionInfo.ipAddress != 0

    private fun getWiFiNetAddress(): NetInterface {
        XLog.d(getLog("getWiFiNetAddress", "Invoked"))

        val ipInt = wifiManager.connectionInfo.ipAddress
        val ipByteArray = ByteArray(4) { i -> (ipInt.shr(i * 8).and(255)).toByte() }
        val inet4Address = InetAddress.getByAddress(ipByteArray) as Inet4Address
        return NetInterface("wlan0", inet4Address)
    }
}