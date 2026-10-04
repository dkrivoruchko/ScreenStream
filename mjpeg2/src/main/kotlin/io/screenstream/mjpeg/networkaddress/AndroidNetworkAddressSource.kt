package io.screenstream.mjpeg.networkaddress

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import androidx.annotation.RequiresApi
import io.screenstream.streaming.logE
import io.screenstream.streaming.logW
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Provides Android network information and reads interface addresses for the monitor. Reported
 * information may be incomplete while Android is delivering updates or an interface cannot be read.
 * The caller must use [scan] on IO and complete the close sequence when finished.
 */
internal class AndroidNetworkAddressSource(context: Context) {
    private val applicationContext = context.applicationContext
    private val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val platformLock = Any()

    private var sourceRevision = 0L
    private val callbackNetworks = mutableMapOf<Network, NetworkMetadata>()

    // Pre-26 fallback reads never overwrite callback payloads; registered modern callbacks are authoritative.
    private var fallbackNetworks = emptyMap<Network, NetworkMetadata>()
    private var fallbackReady = false
    private var tethered = emptyMap<String, InterfaceType>()
    private var tetherReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA

    private var networkRegistered = false
    private var tetherRegistered = false
    private var isClosing = false
    private val registrations = mutableListOf<() -> Unit>()
    private val cleanupFailures = mutableListOf<Exception>()

    private val snapshotChanges = Channel<Snapshot>(capacity = Channel.CONFLATED)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateCallbackState {
            callbackNetworks.putIfAbsent(network, NetworkMetadata())
        }

        override fun onLost(network: Network) = updateCallbackState {
            callbackNetworks.remove(network)
            fallbackNetworks = fallbackNetworks - network
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val type = networkCapabilities.interfaceType()
            updateCallbackState {
                val previous = callbackNetworks[network] ?: NetworkMetadata()
                callbackNetworks[network] = previous.copy(interfaceType = type)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val interfaces = linkProperties.copyInterfaceAddresses()
            updateCallbackState {
                val previous = callbackNetworks[network] ?: NetworkMetadata()
                callbackNetworks[network] = previous.copy(interfaces = interfaces)
            }
        }
    }

    /**
     * Network-information updates for the monitor. A slow receiver gets the latest snapshot rather than
     * every intermediate update. The channel closes when source cleanup is finished.
     */
    val changes: ReceiveChannel<Snapshot> = snapshotChanges

    fun snapshot(): Snapshot = synchronized(platformLock) { snapshotLocked() }

    /**
     * Reads current interface addresses and reports which interfaces could be checked. Call on IO,
     * with at most one scan in progress. A failed interface read leaves its previous addresses uncertain;
     * a successful read can show that its addresses are gone.
     */
    fun scan(): ScanResult {
        registerObservers()
        refreshFallback()
        val platformSnapshot = snapshot()
        if (synchronized(platformLock) { isClosing }) {
            return ScanResult(
                platformSnapshot = platformSnapshot,
                interfaces = emptyList(),
                successfulInterfaces = emptySet(),
                failedInterfaces = emptySet(),
                enumerationComplete = false,
            )
        }

        val found = mutableListOf<InterfaceAddresses>()
        val successfulInterfaces = mutableSetOf<String>()
        val failedInterfaces = mutableSetOf<String>()
        var enumerationComplete = true
        fun readInterface(item: NetworkInterface) {
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
            val interfaces = NetworkInterface.getNetworkInterfaces()
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

        val callbackInterfaces = platformSnapshot.networks.flatMap { it.interfaces.orEmpty() }
        val retryNames = if (enumerationComplete) {
            failedInterfaces.toList()
        } else {
            callbackInterfaces.map { it.interfaceName } + platformSnapshot.tethered.keys
        }
        for (name in retryNames.distinct()) {
            if (name in successfulInterfaces) continue
            try {
                NetworkInterface.getByName(name)?.let(::readInterface)
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

    /**
     * Begins closing and returns actions for unregistering current observers. Call [unregister] with
     * these actions, wait for any active [scan], then call [finishClose].
     */
    fun beginClose(): List<() -> Unit> = synchronized(platformLock) {
        isClosing = true
        val handles = registrations.toList()
        registrations.clear()
        handles
    }

    /** Runs all supplied unregister actions. Failures are collected and returned by [finishClose]. */
    fun unregister(handles: List<() -> Unit>) {
        for (unregister in handles) {
            try {
                unregister()
            } catch (error: Exception) {
                logE("unregister", "Network observer cleanup failed; observer could not be unregistered", error)
                synchronized(platformLock) { cleanupFailures.add(error) }
            }
        }
    }

    /**
     * Completes closing and returns a cleanup failure, or null on success. Call after [unregister] and
     * after any active scan finishes.
     */
    fun finishClose(): Exception? = synchronized(platformLock) {
        snapshotChanges.close()
        val firstFailure = cleanupFailures.firstOrNull() ?: return@synchronized null
        for (failure in cleanupFailures.drop(1)) {
            if (failure !== firstFailure) firstFailure.addSuppressed(failure)
        }
        firstFailure
    }

    /** Returns the network information currently known to this source; requires [platformLock]. */
    private fun snapshotLocked(): Snapshot {
        val networks = (callbackNetworks.keys + fallbackNetworks.keys).map { network ->
            val callback = callbackNetworks[network]
            val fallback = fallbackNetworks[network]
            NetworkMetadata(
                interfaces = callback?.interfaces ?: fallback?.interfaces,
                interfaceType = callback?.interfaceType ?: fallback?.interfaceType,
            )
        }
        val metadataReady = networks.all { it.interfaces != null && it.interfaceType != null }
        val fallbackReadyIfNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || fallbackReady
        return Snapshot(
            revision = sourceRevision,
            networks = Collections.unmodifiableList(networks),
            tethered = tethered,
            ready = networkRegistered && metadataReady && fallbackReadyIfNeeded && tetherReady,
        )
    }

    private fun updateCallbackState(update: () -> Unit) {
        synchronized(platformLock) {
            if (isClosing) return
            update()
            sourceRevision++
            snapshotChanges.trySend(snapshotLocked())
        }
    }

    private fun registerObservers() {
        registerNetworkObserver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            registerTetheringObserver()
        }
    }

    /**
     * Retry observer registration on later scans; failure leaves native interface evidence usable.
     */
    private fun registerNetworkObserver() {
        val registrationNeeded = synchronized(platformLock) { !isClosing && !networkRegistered }
        if (!registrationNeeded) return
        try {
            val request = NetworkRequest.Builder().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    clearCapabilities()
                } else {
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                }
                // No INTERNET or VALIDATED requirement: LAN/P2P networks are useful too.
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            }.build()
            connectivity.registerNetworkCallback(request, networkCallback)
            retainRegistration(unregister = { connectivity.unregisterNetworkCallback(networkCallback) }) {
                networkRegistered = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // A network gone before registration produces no initial callback or onLost.
                    fallbackNetworks = emptyMap()
                    fallbackReady = false
                }
            }
        } catch (error: Exception) {
            logW("registerNetworkObserver", "Network observer registration failed; native interface scans remain available", error)
        }
    }

    /** Watches tethered interface types where Android supports it; address reads can proceed without it. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun registerTetheringObserver() {
        val registrationNeeded = synchronized(platformLock) { !isClosing && !tetherRegistered }
        if (!registrationNeeded) return
        try {
            val observer = TetheringObservation(applicationContext) { interfaces ->
                updateCallbackState {
                    tethered = interfaces
                    tetherReady = true
                }
            }
            observer.start()
            retainRegistration(unregister = observer::close) { tetherRegistered = true }
        } catch (error: Exception) {
            logW("registerTetheringObserver", "Tethering observer registration failed; interface scans remain available", error)
        }
    }

    /** Keeps an observer available for later cleanup, or unregisters it if closing has already begun. */
    private fun retainRegistration(unregister: () -> Unit, registered: () -> Unit) {
        val accepted = synchronized(platformLock) {
            if (isClosing) {
                false
            } else {
                registrations.add(unregister)
                registered()
                sourceRevision++
                snapshotChanges.trySend(snapshotLocked())
                true
            }
        }
        // Close may win during registration; retire this late handle before the scan ends.
        if (!accepted) unregister(listOf(unregister))
    }

    /** Gets network information when callbacks do not provide it, without replacing newer Android updates. */
    private fun refreshFallback() {
        val beforeReadRevision = synchronized(platformLock) {
            val modernCallbackRegistered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && networkRegistered
            if (isClosing || modernCallbackRegistered) return
            sourceRevision
        }
        val fallbackRead = mutableMapOf<Network, NetworkMetadata>()
        var complete = true
        try {
            @Suppress("DEPRECATION")
            val networks = connectivity.allNetworks
            for (network in networks) {
                try {
                    val capabilities = connectivity.getNetworkCapabilities(network)
                    val properties = connectivity.getLinkProperties(network)
                    when {
                        capabilities == null || properties == null -> complete = false
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) -> {
                            fallbackRead[network] = NetworkMetadata(
                                interfaces = properties.copyInterfaceAddresses(),
                                interfaceType = capabilities.interfaceType(),
                            )
                        }
                    }
                } catch (error: Exception) {
                    logW("refreshFallback", "Network metadata read failed; network=$network", error)
                    complete = false
                }
            }
        } catch (error: Exception) {
            logW("refreshFallback", "Network enumeration failed; retaining previous network information", error)
            complete = false
        }
        synchronized(platformLock) {
            if (isClosing || sourceRevision != beforeReadRevision) return
            val mergedFallback = if (complete) fallbackRead else fallbackNetworks + fallbackRead
            fallbackNetworks = Collections.unmodifiableMap(mergedFallback)
            fallbackReady = complete
            sourceRevision++
            snapshotChanges.trySend(snapshotLocked())
        }
    }

    /** VPN transport takes precedence when Android reports multiple transports. */
    private fun NetworkCapabilities.interfaceType(): InterfaceType = when {
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> InterfaceType.Vpn
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> InterfaceType.Wifi
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> InterfaceType.Ethernet
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> InterfaceType.Mobile
        else -> InterfaceType.Other
    }

    private fun LinkProperties.copyInterfaceAddresses(): List<InterfaceAddresses> {
        val name = interfaceName ?: return emptyList()
        val addresses = Collections.unmodifiableList(linkAddresses.map { it.address })
        return Collections.unmodifiableList(listOf(InterfaceAddresses(interfaceName = name, addresses = addresses)))
    }

    /** Addresses reported for one interface. An empty list can mean the interface currently has no addresses. */
    data class InterfaceAddresses(val interfaceName: String, val addresses: List<InetAddress>)

    /** Known interface details for an Android network. Null means those details are not available yet. */
    data class NetworkMetadata(val interfaces: List<InterfaceAddresses>? = null, val interfaceType: InterfaceType? = null)

    /**
     * The network information known at one point. [ready] means the observers and interface details
     * are available; it does not mean every interface address has been checked.
     */
    data class Snapshot(
        val revision: Long,
        val networks: List<NetworkMetadata>,
        val tethered: Map<String, InterfaceType>,
        val ready: Boolean,
    ) {
        /**
         * Returns known interface types, using interface names when Android has not supplied a type.
         * VPN takes precedence over other reported types.
         */
        fun interfaceTypes(): Map<String, InterfaceType> {
            val types = mutableMapOf<String, InterfaceType>()
            for ((interfaces, observedType) in networks) {
                val type = observedType ?: InterfaceType.Other
                for ((name) in interfaces.orEmpty()) {
                    val currentType = types[name]
                    if (currentType == null || currentType == InterfaceType.Other || type == InterfaceType.Vpn) {
                        types[name] = type
                    }
                }
            }
            for ((name, type) in tethered) {
                val currentType = types[name]
                if (currentType == null || currentType == InterfaceType.Other) {
                    types[name] = type
                }
            }
            return types.mapValues { (name, type) -> if (type == InterfaceType.Other) inferInterfaceType(name) else type }
        }
    }

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

    /** Reports tethered interface types using the public API available on Android 16 and later. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private class TetheringObservation(context: Context, onInterfaces: (Map<String, InterfaceType>) -> Unit) {
        private val manager = context.getSystemService(TetheringManager::class.java)
        private val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                val interfaceTypes = interfaces.associate { item ->
                    val type = if (item.type == TetheringManager.TETHERING_WIFI) InterfaceType.Wifi else InterfaceType.Other
                    item.`interface` to type
                }
                onInterfaces(Collections.unmodifiableMap(interfaceTypes))
            }
        }

        /** Starts receiving tethered interface updates; pair a successful call with [close]. */
        fun start() = manager.registerTetheringEventCallback({ it.run() }, callback)

        fun close() = manager.unregisterTetheringEventCallback(callback)
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
