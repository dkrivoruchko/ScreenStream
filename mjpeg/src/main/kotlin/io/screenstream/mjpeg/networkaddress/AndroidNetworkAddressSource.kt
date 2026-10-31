package io.screenstream.mjpeg.networkaddress

import io.screenstream.core.logW
import java.net.InetAddress
import java.util.Collections

/** Reads native interface evidence on IO; observer resources and lifecycle belong to the monitor. */
internal class AndroidNetworkAddressSource(
    private val nativeInterfaces: NativeInterfaceAccess = SystemNativeInterfaceAccess,
) {
    /** At most one scan may run at a time. Failed reads leave earlier addresses uncertain. */
    fun scan(platformSnapshot: Snapshot): ScanResult {
        val found = mutableListOf<InterfaceAddresses>()
        val successfulInterfaces = mutableSetOf<String>()
        val failedInterfaces = mutableSetOf<String>()
        var enumerationComplete = true
        fun readInterface(item: NativeInterfaceAccess.Handle) {
            val name = item.name
            try {
                val addresses = if (item.isUp) item.inetAddresses.toList() else emptyList()
                found.add(InterfaceAddresses(interfaceName = name, addresses = Collections.unmodifiableList(addresses)))
                successfulInterfaces.add(name)
                failedInterfaces.remove(name)
            } catch (error: Exception) {
                if (failedInterfaces.add(name)) {
                    this@AndroidNetworkAddressSource.logW("readInterface", "Interface address read failed; interface=$name", error)
                }
            }
        }

        try {
            val interfaces = nativeInterfaces.enumerate()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    readInterface(interfaces.nextElement())
                }
            }
        } catch (error: Exception) {
            logW("scan", "Interface enumeration failed; using known interfaces and callback addresses", error)
            // Pre-31 Android can throw NPE for virtual interfaces without a parent (also OEM bugs).
            enumerationComplete = false
        }

        val callbackInterfaces = platformSnapshot.callbackInterfaces
        val retryNames = if (enumerationComplete) {
            failedInterfaces.toList()
        } else {
            platformSnapshot.interfaceTypes.keys.toList()
        }
        for (name in retryNames.distinct()) {
            if (name in successfulInterfaces) continue
            try {
                nativeInterfaces.getByName(name)?.let(::readInterface)
            } catch (error: Exception) {
                if (failedInterfaces.add(name)) {
                    logW("scan", "Interface lookup failed on retry; interface=$name", error)
                }
            }
        }

        // Callback IPs supplement unknown reads; successful native absence remains authoritative.
        for (item in callbackInterfaces) {
            val nativeReadUnknown = item.interfaceName !in successfulInterfaces
            val callbackFallbackNeeded = !enumerationComplete || item.interfaceName in failedInterfaces
            if (nativeReadUnknown && callbackFallbackNeeded) {
                found.add(item)
            }
        }
        return ScanResult(
            platformSnapshot = platformSnapshot,
            interfaces = Collections.unmodifiableList(found),
            successfulInterfaces = Collections.unmodifiableSet(successfulInterfaces),
            failedInterfaces = Collections.unmodifiableSet(failedInterfaces),
            enumerationComplete = enumerationComplete,
        )
    }

    /** Addresses reported for one interface. An empty list can mean the interface currently has no addresses. */
    data class InterfaceAddresses(val interfaceName: String, val addresses: List<InetAddress>)

    /**
     * The network information known at one point. [ready] means the observers and interface details
     * are available; it does not mean every interface address has been checked.
     */
    data class Snapshot(
        val revision: Long,
        val callbackInterfaces: List<InterfaceAddresses>,
        val interfaceTypes: Map<String, InterfaceType>,
        val ready: Boolean,
    )

    /**
     * An interface-address read, including which interfaces were checked successfully or could not
     * be read. Failed reads leave previous addresses uncertain rather than confirming they are gone.
     */
    data class ScanResult(
        val platformSnapshot: Snapshot,
        val interfaces: List<InterfaceAddresses>,
        val successfulInterfaces: Set<String>,
        val failedInterfaces: Set<String>,
        val enumerationComplete: Boolean,
    ) {
        /** Whether the read and supporting network information are complete enough to use normal refresh intervals. */
        val complete: Boolean get() = enumerationComplete && failedInterfaces.isEmpty() && platformSnapshot.ready

        /**
         * Whether this read checked [interfaceName] sufficiently to remove an address missing from its result.
         * The caller must also check that the result is current and the address is actually missing.
         */
        fun canConfirmAbsence(interfaceName: String): Boolean =
            interfaceName in successfulInterfaces || enumerationComplete && interfaceName !in failedInterfaces
    }
}

/** Infers a connection type from common Android interface names, returning Other when unrecognized. */
internal fun inferInterfaceType(interfaceName: String): InterfaceType = when {
    interfaceName.startsWith("tun") || interfaceName.startsWith("tap") || interfaceName.startsWith("ppp") -> InterfaceType.Vpn

    interfaceName.startsWith("wlan") || interfaceName.startsWith("wifi") ||
            interfaceName.startsWith("p2p") || interfaceName.matches(Regex("ap\\d+")) -> InterfaceType.Wifi

    interfaceName.startsWith("eth") -> InterfaceType.Ethernet

    interfaceName.startsWith("rmnet") || interfaceName.startsWith("ccmni") || interfaceName.startsWith("pdp") -> InterfaceType.Mobile

    else -> InterfaceType.Other
}
