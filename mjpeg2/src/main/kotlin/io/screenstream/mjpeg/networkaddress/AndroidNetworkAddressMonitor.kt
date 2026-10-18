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
import io.screenstream.mjpeg.networkaddress.AndroidNetworkAddressSource.InterfaceAddresses
import io.screenstream.mjpeg.networkaddress.AndroidNetworkAddressSource.ScanResult
import io.screenstream.mjpeg.networkaddress.AndroidNetworkAddressSource.Snapshot
import io.screenstream.streaming.logD
import io.screenstream.streaming.logE
import io.screenstream.streaming.logV
import io.screenstream.streaming.logW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Serializes native scans while callback metadata changes. The controller, not discovery,
 * decides when an empty selection must stop capture.
 */
@Factory(binds = [NetworkAddressMonitor::class])
internal class AndroidNetworkAddressMonitor(
    context: Context,
    nativeInterfaces: NativeInterfaceAccess = SystemNativeInterfaceAccess,
) : NetworkAddressMonitor {
    // Admission, observer handles and platform metadata share one gate; scan and cleanup work run outside it.
    private val admissionGate = Any()
    private var terminalFailure: Throwable? = null
    private val registrations = linkedMapOf<Observer, () -> Unit>()
    private val cleanupFailures = mutableListOf<Exception>()
    private val platformChanges = Channel<Unit>(Channel.CONFLATED)
    private val closeRequested = CompletableDeferred<Unit>()
    private val closeCompletion = CompletableDeferred<Unit>()
    private val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val addressSource = AndroidNetworkAddressSource(nativeInterfaces)
    private val platform = PlatformObservation(context)
    override val state: StateFlow<NetworkAddressMonitor.State>
        field = MutableStateFlow<NetworkAddressMonitor.State>(NetworkAddressMonitor.State.NotStarted)
    private val observationJob = ownedScope.launch(start = CoroutineStart.LAZY) { Observation().run() }

    override fun start() {
        val admitted = synchronized(admissionGate) { !closeRequested.isCompleted }
        if (admitted) observationJob.start()
    }

    override suspend fun close() {
        beginClose()
        closeCompletion.await()
    }

    /** Fences all admission before independently retiring observers and joining observation. */
    private fun beginClose(failure: Throwable? = null) {
        var reportFailure = false
        val handles = synchronized(admissionGate) {
            if (failure != null && terminalFailure == null) {
                terminalFailure = failure
                state.value = NetworkAddressMonitor.State.Failed
                reportFailure = failure !is CancellationException
            }
            if (closeRequested.isCompleted) {
                null
            } else {
                closeRequested.complete(Unit)
                registrations.values.toList().also { registrations.clear() }
            }
        }
        if (reportFailure && failure != null) logE("beginClose", "Address discovery failed; state=Failed", failure)
        if (handles == null) return
        logD("beginClose", "Close requested; observersToClose=${handles.size}")
        observationJob.start() // Also permits close before discovery starts.
        ownedScope.launch(Dispatchers.IO) {
            unregisterObservers(handles)
            observationJob.join() // Includes the native scan and retirement of late registrations.
            val outcome = synchronized(admissionGate) {
                val cleanupFailure = cleanupFailures.firstOrNull()
                if (cleanupFailure != null) {
                    for (error in cleanupFailures.drop(1)) {
                        if (error !== cleanupFailure) cleanupFailure.addSuppressed(error)
                    }
                }
                val cause = terminalFailure ?: cleanupFailure
                if (cause != null && cleanupFailure != null && cleanupFailure !== cause) {
                    cause.addSuppressed(cleanupFailure)
                }
                state.value = if (cause == null) NetworkAddressMonitor.State.Closed else NetworkAddressMonitor.State.Failed
                cause
            }
            platformChanges.close()
            if (outcome == null) {
                this@AndroidNetworkAddressMonitor.logD("beginClose", "Address discovery closed")
                closeCompletion.complete(Unit)
            } else {
                closeCompletion.completeExceptionally(outcome)
            }
            ownedScope.cancel()
        }
    }

    /** A registration completing after close must retire before its scan can finish. */
    private fun retainRegistration(observer: Observer, unregister: () -> Unit, registered: () -> Unit = {}) {
        val accepted = synchronized(admissionGate) {
            if (closeRequested.isCompleted) {
                false
            } else {
                registrations[observer] = unregister
                registered()
                platform.changedLocked()
                true
            }
        }
        if (!accepted) unregisterObservers(listOf(unregister))
    }

    private fun unregisterObservers(handles: List<() -> Unit>) {
        for (unregister in handles) {
            try {
                unregister()
            } catch (error: Exception) {
                logE("unregisterObservers", "Network observer cleanup failed; observer could not be unregistered", error)
                synchronized(admissionGate) { cleanupFailures.add(error) }
            }
        }
    }

    private fun scan(): ScanResult {
        platform.registerObservers()
        platform.refreshFallback()
        val snapshot = platform.snapshot()
        if (closeRequested.isCompleted) {
            return ScanResult(
                platformSnapshot = snapshot,
                interfaces = emptyList(),
                successfulInterfaces = emptySet(),
                failedInterfaces = emptySet(),
                enumerationComplete = false,
            )
        }
        return addressSource.scan(snapshot)
    }

    private enum class Observer { Network, Tethering }

    private inner class Observation {
        private var addressesByIp = emptyMap<IpAddressKey, TrackedAddress>()
        private var nextAddressId = 0L
        private var hasScanned = false
        private var platformSnapshot = platform.snapshot()
        private var activeScan: Deferred<ScanResult>? = null
        private var scanAtNanos: Long? = System.nanoTime()
        private var retryIndex = 0

        suspend fun run() {
            try {
                publishInventory()
                while (!closeRequested.isCompleted) {
                    startScanIfNeeded()
                    awaitChange()
                }
            } catch (failure: Throwable) {
                // Publish failure and unregister existing callbacks before a stuck native scan join.
                beginClose(failure)
            } finally {
                withContext(NonCancellable) {
                    activeScan?.cancel()
                    activeScan?.join()
                }
            }
        }

        /** Waits for a change, giving stop requests and current platform evidence priority. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun awaitChange(): Unit = select {
            closeRequested.onAwait { }
            platformChanges.onReceive { onPlatformChanged() }
            activeScan?.onAwait { onScanFinished(it) }
            if (activeScan == null) {
                scanAtNanos?.let { deadline ->
                    val remaining = (deadline - System.nanoTime()).coerceAtLeast(0).nanoseconds
                    onTimeout(remaining) { }
                }
            }
        }

        private fun startScanIfNeeded() {
            val deadline = scanAtNanos ?: return
            if (activeScan != null || deadline - System.nanoTime() > 0L) return
            activeScan = ownedScope.async(Dispatchers.IO) { scan() }
            scanAtNanos = null
        }

        /** Updates interface details while keeping known addresses and their ids. */
        private fun onPlatformChanged() {
            val snapshot = platform.snapshot()
            if (snapshot.revision <= platformSnapshot.revision) return
            platformSnapshot = snapshot
            val types = snapshot.interfaceTypes
            addressesByIp = addressesByIp.mapValues { (_, tracked) ->
                tracked.copy(
                    candidates = tracked.candidates.map { candidate ->
                        candidate.copy(interfaceType = types[candidate.interfaceName] ?: candidate.interfaceType)
                    },
                )
            }
            publishInventory()
            scanAtNanos = System.nanoTime()
            if (activeScan == null) retryIndex = 0
        }

        private fun onScanFinished(result: ScanResult) {
            activeScan = null
            // One coherent snapshot includes callbacks whose notification has not been consumed yet.
            platformSnapshot = platform.snapshot()
            val currentEvidence = result.platformSnapshot.revision == platformSnapshot.revision
            this@AndroidNetworkAddressMonitor.logV(
                "onScanFinished",
                "Interface scan completed; snapshotRevision=${result.platformSnapshot.revision}, " +
                        "currentSnapshot=$currentEvidence, complete=${result.complete}, unreadableInterfaces=${result.failedInterfaces}",
            )
            applyScan(result = result, currentEvidence = currentEvidence)
            if (currentEvidence) {
                val refreshDelay = when {
                    result.complete -> {
                        retryIndex = 0
                        PERIODIC_SCAN_MS
                    }

                    retryIndex < RETRY_DELAYS_MS.size -> RETRY_DELAYS_MS[retryIndex++]
                    else -> PERIODIC_SCAN_MS
                }
                scanAtNanos = System.nanoTime() + refreshDelay.milliseconds.inWholeNanoseconds
            } else {
                scanAtNanos = System.nanoTime()
            }
        }

        /**
         * Retain previous addresses until current evidence confirms absence; temporary unreadability must
         * not replace their identities or tear down listeners.
         */
        private fun applyScan(result: ScanResult, currentEvidence: Boolean) {
            val types = platformSnapshot.interfaceTypes
            val freshCandidates = result.interfaces.flatMap { item ->
                val type = types[item.interfaceName] ?: inferInterfaceType(item.interfaceName)
                item.addresses.map { ip -> AddressCandidate(ip = ip, interfaceName = item.interfaceName, interfaceType = type) }
            }.filter { it.isSupported() }.sortedWith(CANDIDATE_ORDER)
            val freshInterfaceIps = freshCandidates.mapTo(HashSet()) { it.interfaceName to it.ipKey }

            val retainedCandidates = addressesByIp.values.flatMap { it.candidates }.filter { candidate ->
                val replaced = (candidate.interfaceName to candidate.ipKey) in freshInterfaceIps
                val confirmedAbsent = currentEvidence && result.canConfirmAbsence(interfaceName = candidate.interfaceName)
                !replaced && !confirmedAbsent
            }.map { candidate ->
                candidate.copy(interfaceType = types[candidate.interfaceName] ?: candidate.interfaceType)
            }

            // Preserve fresh-before-retained order while removing duplicates and unscoped aliases.
            val mergedCandidates = (freshCandidates + retainedCandidates).distinctBy {
                it.interfaceName to it.ipKey
            }
            val scopedInterfaceIps = mergedCandidates.filter { it.hasIpv6Scope() }
                .mapTo(HashSet()) { it.interfaceName to it.ipKey.bytes }
            val normalizedCandidates = mergedCandidates.filter { candidate ->
                val scopedAliasExists = (candidate.interfaceName to candidate.ipKey.bytes) in scopedInterfaceIps
                candidate.ip !is Inet6Address || candidate.hasIpv6Scope() || !scopedAliasExists
            }

            addressesByIp = normalizedCandidates.groupBy { it.ipKey }.mapValues { (ipKey, candidates) ->
                val id = addressesByIp[ipKey]?.id ?: ++nextAddressId
                TrackedAddress(id = id, candidates = candidates)
            }
            hasScanned = true
            publishInventory()
        }

        /** Publishes every supported scoped IP and its ordered interface alternatives. */
        private fun publishInventory() {
            val nextState = if (hasScanned) {
                val inventory = NetworkAddressInventory(addressesByIp.mapValues { (_, tracked) ->
                    tracked.candidates.map { candidate ->
                        NetworkAddress(
                            id = tracked.id,
                            ip = candidate.ip,
                            interfaceName = candidate.interfaceName,
                            interfaceType = candidate.interfaceType,
                        )
                    }
                })
                NetworkAddressMonitor.State.Observed(inventory)
            } else {
                NetworkAddressMonitor.State.Loading
            }
            synchronized(admissionGate) {
                if (!closeRequested.isCompleted) state.value = nextState
            }
        }
    }

    private inner class PlatformObservation(context: Context) {
        private val applicationContext = context.applicationContext
        private val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)

        private var sourceRevision = 0L
        private val callbackNetworks = mutableMapOf<Network, NetworkMetadata>()

        // Pre-26 fallback reads never overwrite callback payloads; registered modern callbacks are authoritative.
        private var fallbackNetworks = emptyMap<Network, NetworkMetadata>()
        private var fallbackReady = false
        private var tethered = emptyMap<String, InterfaceType>()
        private var tetherReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA

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

        fun snapshot(): Snapshot = synchronized(admissionGate) { snapshotLocked() }

        /** Returns the network information currently known to this source; requires [admissionGate]. */
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
            return Snapshot(
                revision = sourceRevision,
                callbackInterfaces = Collections.unmodifiableList(networks.flatMap { it.interfaces.orEmpty() }),
                interfaceTypes = Collections.unmodifiableMap(types.mapValues { (name, type) ->
                    if (type == InterfaceType.Other) inferInterfaceType(name) else type
                }),
                ready = Observer.Network in registrations && metadataReady && fallbackReadyIfNeeded && tetherReady,
            )
        }

        private fun updateCallbackState(update: () -> Unit) {
            synchronized(admissionGate) {
                if (closeRequested.isCompleted) return
                update()
                changedLocked()
            }
        }

        fun registerObservers() {
            registerNetworkObserver()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                registerTetheringObserver()
            }
        }

        /** Requires the monitor's admission gate. */
        fun changedLocked() {
            sourceRevision++
            platformChanges.trySend(Unit)
        }

        /**
         * Retry observer registration on later scans; failure leaves native interface evidence usable.
         */
        private fun registerNetworkObserver() {
            val registrationNeeded = synchronized(admissionGate) { !closeRequested.isCompleted && Observer.Network !in registrations }
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
                retainRegistration(Observer.Network, unregister = { connectivity.unregisterNetworkCallback(networkCallback) }) {
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
            val registrationNeeded = synchronized(admissionGate) { !closeRequested.isCompleted && Observer.Tethering !in registrations }
            if (!registrationNeeded) return
            try {
                val observer = TetheringObservation(applicationContext) { interfaces ->
                    updateCallbackState {
                        tethered = interfaces
                        tetherReady = true
                    }
                }
                observer.start()
                retainRegistration(Observer.Tethering, unregister = observer::close)
            } catch (error: Exception) {
                logW("registerTetheringObserver", "Tethering observer registration failed; interface scans remain available", error)
            }
        }

        /** Gets network information when callbacks do not provide it, without replacing newer Android updates. */
        fun refreshFallback() {
            val beforeReadRevision = synchronized(admissionGate) {
                val modernCallbackRegistered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Observer.Network in registrations
                if (closeRequested.isCompleted || modernCallbackRegistered) return
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
            synchronized(admissionGate) {
                if (closeRequested.isCompleted || sourceRevision != beforeReadRevision) return
                val mergedFallback = if (complete) fallbackRead else fallbackNetworks + fallbackRead
                fallbackNetworks = Collections.unmodifiableMap(mergedFallback)
                fallbackReady = complete
                changedLocked()
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
    }

    /** Known interface details for an Android network. Null means those details are not available yet. */
    private data class NetworkMetadata(val interfaces: List<InterfaceAddresses>? = null, val interfaceType: InterfaceType? = null)

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

    private data class AddressCandidate(val ip: InetAddress, val interfaceName: String, val interfaceType: InterfaceType) {
        val ipKey: IpAddressKey = IpAddressKey(ip)

        fun isSupported(): Boolean = !ip.isAnyLocalAddress && !ip.isMulticastAddress && !(ip is Inet6Address && ip.isLinkLocalAddress)

        fun hasIpv6Scope(): Boolean = ip is Inet6Address && (ip.scopeId != 0 || ip.scopedInterface != null)
    }

    private data class TrackedAddress(val id: Long, val candidates: List<AddressCandidate>)

    private companion object {
        const val PERIODIC_SCAN_MS = 30_000L
        val RETRY_DELAYS_MS = longArrayOf(250, 1_000, 3_000)
        val CANDIDATE_ORDER: Comparator<AddressCandidate> = compareBy<AddressCandidate> { it.interfaceName }
            .thenBy(IpAddressKey.ORDER) { it.ipKey }
            .thenBy { it.interfaceType.ordinal }
    }
}
