package info.dvkr.screenstream.data.state.helper

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.NetInterface
import info.dvkr.screenstream.data.other.getLog
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

@SuppressLint("WifiManagerPotentialLeak")
class NetworkHelper(context: Context, private val onError: (AppError) -> Unit) {

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

    fun getNetInterfaces(useWiFiOnly: Boolean, enableIPv6: Boolean): List<NetInterface> {
        XLog.d(getLog("getNetInterfaces", "Invoked"))

        val netInterfaceList = mutableListOf<NetInterface>()
        try {
            try {
                NetworkInterface.getNetworkInterfaces().asSequence().filterNotNull()
                    .flatMap { networkInterface ->
                        networkInterface.inetAddresses.asSequence().filterNotNull()
                            .filter { inetAddress ->
                                !inetAddress.isLinkLocalAddress && !inetAddress.isLoopbackAddress && !inetAddress.isMulticastAddress
                            }
                            .filter { inetAddress ->
                                (inetAddress is Inet4Address) || (enableIPv6 && (inetAddress is Inet6Address))
                            }
                            .map { inetAddress ->
                                val address =
                                    if (inetAddress is Inet6Address) Inet6Address.getByAddress(inetAddress.address)
                                    else inetAddress
                                NetInterface(networkInterface.displayName, address)
                            }
                    }
                    .filter { netInterface ->
                        useWiFiOnly.not() || (
                                defaultWifiRegexArray.any { it.matches(netInterface.name) } ||
                                        wifiRegexArray.any { it.matches(netInterface.name) }
                                )
                    }
                    .toCollection(netInterfaceList)
            } catch (throwable: Throwable) {
                XLog.e(getLog("getNetInterfaces"), throwable)
                if (wifiConnected()) netInterfaceList.add(getWiFiNetAddress()) else throw throwable
            }
        } catch (throwable: Throwable) {
            XLog.e(getLog("getNetInterfaces"), throwable)
            onError(FatalError.NetInterfaceException)
        }
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