package io.screenstream.mjpeg.ipaddress

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
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor.Address
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor.AddressCategory
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor.AddressFamily
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor.Filter
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor.InterfaceType
import io.screenstream.mjpeg.ipaddress.NetworkInterfaceMonitor.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Factory
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds

/**
 * Inert Android monitoring created per controller; the first filter starts discovery independently
 * of UI subscriptions. The controller explicitly owns close.
 *
 * @param context Context whose application context provides network observation.
 */
@Factory(binds = [NetworkInterfaceMonitor::class])
internal class AndroidNetworkInterfaceMonitor(
    context: Context,
) : NetworkInterfaceMonitor {
    private data class Link(val name: String, val addresses: List<InetAddress>)
    private data class NetworkInfo(
        val links: List<Link>? = null,
        val type: InterfaceType? = null,
        val callbackLinks: Boolean = false,
        val callbackType: Boolean = false,
    )

    private data class ScanResult(val addresses: List<Address>, val complete: Boolean)

    private val applicationContext = context.applicationContext
    private val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = CompletableDeferred<Unit>()
    private val observers = mutableListOf<() -> Unit>()
    private val cleanupFailures = mutableListOf<Exception>()
    private val networks = mutableMapOf<Network, NetworkInfo>()
    private val lost = mutableSetOf<Network>()
    private var tethered = emptyMap<String, InterfaceType>()
    private var networkRegistered = false
    private var tetherRegistered = false
    private var seeded = false
    private var seedComplete = true
    private var closing = false
    private var revision = 0L
    private var filter: Filter? = null
    private var discovery: ScanResult? = null
    private val mutableState = MutableStateFlow<State>(State.NotStarted)
    override val state: StateFlow<State> = mutableState.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = change {
            lost.remove(network)
            networks.putIfAbsent(network, NetworkInfo())
        }

        override fun onLost(network: Network) = change {
            networks.remove(network)
            if (!seeded) lost.add(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = change {
            if (network !in lost) networks[network] = (networks[network] ?: NetworkInfo()).copy(
                type = networkCapabilities.interfaceType(), callbackType = true,
            )
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = change {
            if (network !in lost) networks[network] = (networks[network] ?: NetworkInfo()).copy(
                links = linkProperties.copyLinks(), callbackLinks = true,
            )
        }
    }

    private val worker: Job = ownedScope.launch(start = CoroutineStart.LAZY) {
        try {
            var retry = 0
            var retryingFailure = false
            while (isActive) {
                registerObservers()
                val scanRevision = synchronized(lock) {
                    if (closing) return@launch
                    if (requests.tryReceive().isSuccess) retry = 0
                    revision
                }
                val result = try {
                    scan(scanRevision)
                } catch (_: Exception) {
                    ScanResult(emptyList(), complete = false)
                }
                val accepted = synchronized(lock) {
                    if (closing) return@launch
                    if (scanRevision != revision) {
                        // Callback evidence invalidated this scan; only a fresh scan can confirm removal.
                        publishIncomplete()
                        false
                    } else {
                        val previous = if (result.complete) emptyList() else discovery?.addresses.orEmpty()
                        discovery = ScanResult(normalize(previous, result.addresses), result.complete)
                        publish()
                        true
                    }
                }
                if (!accepted) continue
                if (!result.complete && !retryingFailure) retry = 0
                retryingFailure = !result.complete
                val timeout = if (retry < RETRY_DELAYS.size) RETRY_DELAYS[retry++] else PERIODIC_SCAN_MS
                val changed = withTimeoutOrNull(timeout.milliseconds) { requests.receive(); true } == true
                if (changed) retry = 0
            }
        } finally {
            beginClose()
        }
    }

    override fun updateFilter(filter: Filter) {
        val shouldStart = synchronized(lock) {
            if (closing) return
            val firstFilter = this.filter == null
            this.filter = filter.copyOwned()
            publish()
            firstFilter
        }
        if (shouldStart) worker.start()
    }

    override suspend fun close() {
        beginClose()
        closed.await()
    }

    private fun change(update: () -> Unit) {
        synchronized(lock) {
            if (closing) return
            update()
            revision++
            requests.trySend(Unit)
        }
    }

    private fun registerObservers() {
        val needsNetwork = synchronized(lock) { if (closing) return else !networkRegistered }
        if (needsNetwork) try {
            val builder = NetworkRequest.Builder()
            if (Build.VERSION.SDK_INT >= 30) builder.clearCapabilities()
            else builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            // Target >=35 sees local networks without requiring LOCAL_NETWORK, which would exclude ordinary networks.
            val request = builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).build()
            connectivity.registerNetworkCallback(request, callback)
            val unregister: () -> Unit = { connectivity.unregisterNetworkCallback(callback) }
            val accepted = synchronized(lock) {
                if (closing) false else {
                    observers.add(unregister); networkRegistered = true; true
                }
            }
            if (!accepted) {
                retire(unregister); return
            }
        } catch (_: Exception) {
            // Missing observation leaves discovery Incomplete and is retried by the worker.
        }
        val needsTethering = synchronized(lock) { if (closing) return else !tetherRegistered }
        if (Build.VERSION.SDK_INT >= 36 && needsTethering) try {
            val observer = TetheringObserver(applicationContext) { interfaces -> change { tethered = interfaces } }
            observer.start()
            val unregister: () -> Unit = observer::close
            val accepted = synchronized(lock) {
                if (closing) false else {
                    observers.add(unregister); tetherRegistered = true; true
                }
            }
            if (!accepted) retire(unregister)
        } catch (_: Exception) {
        }
    }

    private fun beginClose() {
        val obtained = synchronized(lock) {
            if (closing) return
            closing = true
            observers.toList().also { observers.clear() }
        }
        worker.cancel()
        // The independent cleanup retires obtained callbacks before waiting for a registration or native scan.
        ownedScope.launch {
            obtained.forEach(::retire)
            worker.join() // A late successful registration retires itself before the worker finishes.
            requests.close()
            val failure = synchronized(lock) {
                val first = cleanupFailures.firstOrNull()
                if (first == null) mutableState.value = State.Closed
                else {
                    cleanupFailures.drop(1).forEach { if (it !== first && it !in first.suppressed) first.addSuppressed(it) }
                    publishIncomplete()
                }
                first
            }
            if (failure == null) closed.complete(Unit) else closed.completeExceptionally(failure)
            ownedScope.cancel()
        }
    }

    private fun retire(unregister: () -> Unit) {
        try {
            unregister()
        } catch (error: Exception) {
            synchronized(lock) { cleanupFailures.add(error) }
        }
    }

    private fun scan(scanRevision: Long): ScanResult {
        seedNetworks(scanRevision)
        val evidence = synchronized(lock) { networks.values.toList() to tethered.toMap() }
        val links = evidence.first.flatMap { info -> info.links.orEmpty().map { it to (info.type ?: InterfaceType.Unknown) } }
        val types = mutableMapOf<String, InterfaceType>()
        for ((link, type) in links) {
            val previous = types[link.name]
            if (previous == null || previous == InterfaceType.Unknown || type == InterfaceType.Vpn) types[link.name] = type
        }
        for ((name, type) in evidence.second) {
            if (types[name] == null || types[name] == InterfaceType.Unknown) types[name] = type
        }
        fun type(name: String): InterfaceType = types[name]?.takeUnless { it == InterfaceType.Unknown }
            ?: interfaceTypeFromName(name)

        val addresses = mutableListOf<Address>()
        val readNames = mutableSetOf<String>()
        val failedNames = mutableSetOf<String>()
        var complete = synchronized(lock) { networkRegistered && (Build.VERSION.SDK_INT < 36 || tetherRegistered) && seedComplete }
        var enumerationComplete = true
        fun read(networkInterface: NetworkInterface) {
            try {
                if (networkInterface.isUp) {
                    val found = networkInterface.inetAddresses.toList()
                    for (address in found) addresses.add(Address(address, networkInterface.name, type(networkInterface.name)))
                }
                readNames.add(networkInterface.name)
            } catch (_: Exception) {
                failedNames.add(networkInterface.name)
                complete = false
            }
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) while (interfaces.hasMoreElements()) read(interfaces.nextElement())
        } catch (_: Exception) {
            enumerationComplete = false
            complete = false
        }
        // Older Android/OEM failures can still expose interfaces identified by public callbacks.
        for (name in (if (!enumerationComplete) types.keys + links.map { it.first.name } else failedNames).distinct()) {
            if (name in readNames) continue
            try {
                NetworkInterface.getByName(name)?.let(::read)
            } catch (_: Exception) {
                complete = false
            }
        }
        for ((link, _) in links) for (address in link.addresses) addresses.add(Address(address, link.name, type(link.name)))
        return ScanResult(addresses, complete)
    }

    private fun seedNetworks(scanRevision: Long) {
        if (synchronized(lock) {
                seeded && networkRegistered && networks.values.all { it.callbackLinks && it.callbackType }
            }) return
        val snapshot = mutableMapOf<Network, NetworkInfo>()
        var complete = true
        try {
            // Bootstrap on API24-25, refresh fields not covered by callbacks, and fall back when observation failed.
            @Suppress("DEPRECATION")
            val availableNetworks = connectivity.allNetworks
            for (network in availableNetworks) {
                try {
                    val capabilities = connectivity.getNetworkCapabilities(network)
                    if (capabilities == null) {
                        complete = false; continue
                    }
                    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) continue
                    val links = connectivity.getLinkProperties(network)
                    if (links == null) {
                        complete = false; continue
                    }
                    snapshot[network] = NetworkInfo(links.copyLinks(), capabilities.interfaceType())
                } catch (_: Exception) {
                    complete = false
                }
            }
        } catch (_: Exception) {
            complete = false
        }
        synchronized(lock) {
            if (scanRevision != revision || closing) return
            for ((network, info) in snapshot) {
                if (network !in lost) {
                    val current = networks[network]
                    networks[network] = NetworkInfo(
                        if (current?.callbackLinks == true) current.links else info.links,
                        if (current?.callbackType == true) current.type else info.type,
                        current?.callbackLinks == true, current?.callbackType == true,
                    )
                }
            }
            if (complete) networks.entries.removeAll { (network, info) ->
                network !in snapshot && !info.callbackLinks && !info.callbackType
            }
            seedComplete = complete
            seeded = complete
            if (seeded) lost.clear()
        }
    }

    private fun NetworkCapabilities.interfaceType(): InterfaceType = when {
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> InterfaceType.Vpn
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> InterfaceType.Wifi
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> InterfaceType.Ethernet
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> InterfaceType.Mobile
        else -> InterfaceType.Unknown
    }

    private fun LinkProperties.copyLinks(): List<Link> = buildList {
        interfaceName?.let { add(Link(it, linkAddresses.map { address -> address.address })) }
    }

    private fun interfaceTypeFromName(name: String): InterfaceType = when {
        name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") -> InterfaceType.Vpn
        name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("p2p") || name.matches(Regex("ap\\d+")) -> InterfaceType.Wifi
        name.startsWith("eth") -> InterfaceType.Ethernet
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> InterfaceType.Mobile
        else -> InterfaceType.Unknown
    }

    private fun normalize(previous: List<Address>, current: List<Address>): List<Address> {
        val merged = previous.filter { old ->
            current.none { fresh ->
                old.interfaceName == fresh.interfaceName && old.address == fresh.address &&
                        (old.address as? Inet6Address)?.scopeId == (fresh.address as? Inet6Address)?.scopeId &&
                        (old.address as? Inet6Address)?.scopedInterface?.name == (fresh.address as? Inet6Address)?.scopedInterface?.name
            }
        } + current
        val normalized = merged.distinct().filter { item ->
            val ipv6 = item.address as? Inet6Address
            ipv6 == null || ipv6.scopeId != 0 || ipv6.scopedInterface != null || merged.none { scoped ->
                scoped.interfaceName == item.interfaceName && scoped.address.address.contentEquals(item.address.address) &&
                        (scoped.address as? Inet6Address)?.let { it.scopeId != 0 || it.scopedInterface != null } == true
            }
        }.sortedWith(
            compareBy(
                { it.interfaceName }, { it.address.address.size },
                { it.address.address.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } },
                { (it.address as? Inet6Address)?.scopeId ?: 0 },
                { (it.address as? Inet6Address)?.scopedInterface?.name.orEmpty() }, { it.interfaceType.ordinal },
            )
        )
        return Collections.unmodifiableList(normalized)
    }

    private fun publishIncomplete() {
        discovery = ScanResult(discovery?.addresses.orEmpty(), complete = false)
        publish()
    }

    private fun publish() {
        val filter = filter ?: return
        val result = discovery
        val selected = result?.addresses?.filter { item ->
            val family = if (item.address is Inet4Address) AddressFamily.Ipv4 else AddressFamily.Ipv6
            family in filter.families && item.interfaceType in filter.interfaceTypes && category(item.address) in filter.categories
        }?.let { Collections.unmodifiableList(it) }
        mutableState.value = when {
            result == null -> State.Loading(filter)
            result.complete -> State.Ready(filter, selected.orEmpty())
            else -> State.Incomplete(filter, selected.orEmpty())
        }
    }

    private fun category(address: InetAddress): AddressCategory? {
        if (address.isAnyLocalAddress || address.isMulticastAddress) return null
        if (address.isLoopbackAddress) return AddressCategory.Loopback
        if (address.isLinkLocalAddress) return AddressCategory.LinkLocal
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val private = when (address) {
            is Inet4Address -> first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
            is Inet6Address -> first and 0xfe == 0xfc
            else -> false
        }
        return if (private) AddressCategory.Private else AddressCategory.NonPrivate
    }

    private fun Filter.copyOwned(): Filter = Filter(families, interfaceTypes, categories)

    @RequiresApi(36)
    private class TetheringObserver(context: Context, onInterfaces: (Map<String, InterfaceType>) -> Unit) {
        private val manager = context.getSystemService(TetheringManager::class.java)
        private val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                onInterfaces(interfaces.associate { item ->
                    item.`interface` to if (item.type == TetheringManager.TETHERING_WIFI) InterfaceType.Wifi
                    else InterfaceType.Unknown
                })
            }
        }

        fun start() = manager.registerTetheringEventCallback({ it.run() }, callback)
        fun close() = manager.unregisterTetheringEventCallback(callback)
    }

    private companion object {
        const val PERIODIC_SCAN_MS = 30_000L
        val RETRY_DELAYS = longArrayOf(250, 1_000, 3_000)
    }
}
