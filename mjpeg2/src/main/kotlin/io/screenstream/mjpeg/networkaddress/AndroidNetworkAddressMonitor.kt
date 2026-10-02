package io.screenstream.mjpeg.networkaddress

import android.content.Context
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.Address
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.AddressCategory
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.AddressFamily
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.Filter
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressMonitor.State
import io.screenstream.streaming.logD
import io.screenstream.streaming.logE
import io.screenstream.streaming.logV
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Finds and selects device addresses using Android network information. Discovery starts with the
 * first filter and continues without UI subscribers. The controller must close the monitor when
 * finished and decide how long a stream may continue with an empty selection.
 */
@Factory(binds = [NetworkAddressMonitor::class])
internal class AndroidNetworkAddressMonitor(context: Context) : NetworkAddressMonitor {
    // Lifecycle admission and cleanup are independent of observation work and of each close waiter.
    private val lifecycleLock = Any()
    private var terminalFailure: Throwable? = null
    private val filterUpdates = Channel<Filter>(Channel.CONFLATED)
    private val closeRequested = CompletableDeferred<Unit>()
    private val closeCompletion = CompletableDeferred<Unit>()
    private val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val addressSource = AndroidNetworkAddressSource(context)
    private val mutableState = MutableStateFlow<State>(State.NotStarted)
    private val observationJob = ownedScope.launch(start = CoroutineStart.LAZY) { Observation().run() }

    override val state: StateFlow<State> = mutableState.asStateFlow()

    /** Requests the latest address filter without waiting for discovery. */
    override fun updateFilter(filter: Filter) {
        synchronized(lifecycleLock) {
            if (closeRequested.isCompleted) return
            filterUpdates.trySend(filter)
            observationJob.start()
        }
    }

    /** Stops discovery and waits for the shared cleanup result, even if another caller was cancelled. */
    override suspend fun close() {
        beginClose()
        closeCompletion.await()
    }

    /** Stops accepting updates and reports any discovery failure immediately. Failure remains the final status. */
    private fun beginClose(failure: Throwable? = null) {
        synchronized(lifecycleLock) {
            if (failure != null && terminalFailure == null) {
                terminalFailure = failure
                mutableState.value = State.Failed
                if (failure !is CancellationException) {
                    logE("beginClose", "Address discovery failed; state=Failed", failure)
                }
            }
            if (closeRequested.isCompleted) return
            closeRequested.complete(Unit)
            val handles = addressSource.beginClose()
            logD("beginClose", "Close requested; observersToClose=${handles.size}")
            observationJob.start() // Also permits close before the first filter.
            ownedScope.launch(Dispatchers.IO) {
                addressSource.unregister(handles)
                observationJob.join() // Includes a native scan and retirement of late registrations.
                val cleanupFailure = addressSource.finishClose()
                val outcome = synchronized(lifecycleLock) {
                    val cause = terminalFailure ?: cleanupFailure
                    if (cause != null && cleanupFailure != null && cleanupFailure !== cause) {
                        cause.addSuppressed(cleanupFailure)
                    }
                    mutableState.value = if (cause == null) State.Closed else State.Failed
                    cause
                }
                if (outcome == null) {
                    this@AndroidNetworkAddressMonitor.logD("beginClose", "Address discovery closed")
                }
                filterUpdates.close()
                if (outcome == null) {
                    closeCompletion.complete(Unit)
                } else {
                    closeCompletion.completeExceptionally(outcome)
                }
                ownedScope.cancel()
            }
        }
    }

    /** Maintains the selected addresses as filters and Android network information change. */
    private inner class Observation {
        private var currentFilter: Filter? = null
        private var addressesByIp = emptyMap<IpAddressKey, TrackedAddress>()
        private var nextAddressId = 0L
        private var hasScanned = false
        private var platformSnapshot = addressSource.snapshot()
        private var activeScan: Deferred<AndroidNetworkAddressSource.ScanResult>? = null
        private var scanRequested = false
        private var refreshAtNanos: Long? = null
        private var retryIndex = 0
        private var acceptedScanRevision = -1L

        /** Keeps the address selection current until closing or an unexpected discovery failure. */
        suspend fun run() {
            try {
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

        /** Waits for a change, giving stop requests and the latest filter priority. */
        @OptIn(ExperimentalCoroutinesApi::class) // Atomic selection cannot lose a concurrent filter to timeout cancellation.
        private suspend fun awaitChange(): Unit = select {
            closeRequested.onAwait { }
            filterUpdates.onReceive { onFilterChanged(it) }
            addressSource.changes.onReceive { onPlatformChanged(it) }
            activeScan?.onAwait { onScanFinished(it) }
            refreshAtNanos?.let { deadline ->
                val remaining = (deadline - System.nanoTime()).coerceAtLeast(0).nanoseconds
                onTimeout(remaining) { scanRequested = true }
            }
        }

        /** Refreshes interface addresses when needed, without overlapping reads. */
        private fun startScanIfNeeded() {
            if (!scanRequested || activeScan != null || currentFilter == null) return
            activeScan = ownedScope.async(Dispatchers.IO) { addressSource.scan() }
            scanRequested = false
            refreshAtNanos = null
        }

        /** Applies the requested filter to known addresses while discovery catches up. */
        private fun onFilterChanged(filter: Filter) {
            val firstFilter = currentFilter == null
            if (currentFilter != filter) {
                this@AndroidNetworkAddressMonitor.logD(
                    "onFilterChanged",
                    "Address filter applied; families=${filter.families}, " +
                            "interfaceTypes=${filter.interfaceTypes}, categories=${filter.categories}",
                )
            }
            currentFilter = filter
            publishSelection()
            if (firstFilter) scanRequested = true
        }

        /** Updates interface details while keeping known addresses and their ids. */
        private fun onPlatformChanged(snapshot: AndroidNetworkAddressSource.Snapshot) {
            if (snapshot.revision < platformSnapshot.revision) return
            platformSnapshot = snapshot
            val types = snapshot.interfaceTypes()
            addressesByIp = addressesByIp.mapValues { (_, tracked) ->
                tracked.copy(
                    candidates = tracked.candidates.map { candidate ->
                        candidate.copy(interfaceType = types[candidate.interfaceName] ?: candidate.interfaceType)
                    },
                )
            }
            publishSelection()
            if (snapshot.revision > acceptedScanRevision) {
                scanRequested = true
                if (activeScan == null) retryIndex = 0
            }
        }

        /** Uses a completed read to update selection and choose when to read again. */
        private fun onScanFinished(result: AndroidNetworkAddressSource.ScanResult) {
            activeScan = null
            // One coherent snapshot includes callbacks whose notification has not been consumed yet.
            platformSnapshot = addressSource.snapshot()
            val currentEvidence = result.platformSnapshot.revision == platformSnapshot.revision
            filterUpdates.tryReceive().getOrNull()?.let { filter ->
                if (currentFilter != filter) {
                    this@AndroidNetworkAddressMonitor.logD(
                        "onScanFinished",
                        "Pending address filter applied; families=${filter.families}, " +
                                "interfaceTypes=${filter.interfaceTypes}, categories=${filter.categories}",
                    )
                }
                currentFilter = filter
            }
            this@AndroidNetworkAddressMonitor.logV(
                "onScanFinished",
                "Interface scan completed; snapshotRevision=${result.platformSnapshot.revision}, " +
                        "currentSnapshot=$currentEvidence, complete=${result.complete}, unreadableInterfaces=${result.failedInterfaces}",
            )
            applyScan(result = result, currentEvidence = currentEvidence)
            acceptedScanRevision = result.platformSnapshot.revision
            scanRequested = !currentEvidence
            if (currentEvidence) {
                val refreshDelay = when {
                    result.complete -> {
                        retryIndex = 0
                        PERIODIC_SCAN_MS
                    }

                    retryIndex < RETRY_DELAYS_MS.size -> RETRY_DELAYS_MS[retryIndex++]
                    else -> PERIODIC_SCAN_MS
                }
                refreshAtNanos = System.nanoTime() + refreshDelay.milliseconds.inWholeNanoseconds
            }
        }

        /**
         * Updates known addresses from this read. Keeps earlier addresses when current information cannot
         * confirm they disappeared; removes them when a current read confirms they are gone.
         */
        private fun applyScan(result: AndroidNetworkAddressSource.ScanResult, currentEvidence: Boolean) {
            val types = platformSnapshot.interfaceTypes()
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
                Triple(it.ipKey, it.interfaceName, it.interfaceType)
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
            publishSelection()
        }

        /** Publishes one matching interface per IP address and IPv6 scope in a consistent order. */
        private fun publishSelection() {
            val appliedFilter = currentFilter ?: return

            // Candidate order keeps fresh evidence before retained evidence, including filter-only changes.
            val selection = addressesByIp.values.mapNotNull { tracked ->
                val candidate = tracked.candidates.firstOrNull { it.matches(appliedFilter) } ?: return@mapNotNull null
                tracked.id to candidate
            }.sortedWith(compareBy(CANDIDATE_ORDER) { it.second }).map { (id, candidate) ->
                Address(id = id, ip = candidate.ip, interfaceName = candidate.interfaceName, interfaceType = candidate.interfaceType)
            }

            synchronized(lifecycleLock) {
                if (!closeRequested.isCompleted) {
                    val nextState = if (hasScanned) {
                        State.Observed(filter = appliedFilter, addresses = Collections.unmodifiableList(selection))
                    } else {
                        State.Loading(filter = appliedFilter)
                    }
                    val selectionChanged = when (val previousState = mutableState.value) {
                        is State.Observed -> previousState.addresses != selection
                        is State.Loading -> hasScanned
                        else -> true
                    }
                    if (selectionChanged) {
                        val addresses = selection.joinToString { address ->
                            "${address.id}:${address.interfaceName}/${address.interfaceType}/${address.ip.hostAddress}"
                        }
                        this@AndroidNetworkAddressMonitor.logD(
                            "publishSelection",
                            "Address selection updated; state=${if (hasScanned) "Observed" else "Loading"}, addresses=[$addresses]",
                        )
                    }
                    mutableState.value = nextState
                }
            }
        }
    }

    /** Identifies the same IP and IPv6 scope even when interface details change. */
    private data class IpAddressKey(val bytes: List<Byte>, val scopeId: Int?, val scopeInterface: String?)

    /** An IP available through a particular interface, considered when choosing the selected address. */
    private data class AddressCandidate(val ip: InetAddress, val interfaceName: String, val interfaceType: InterfaceType) {
        val ipKey: IpAddressKey = IpAddressKey(
            bytes = ip.address.toList(),
            scopeId = (ip as? Inet6Address)?.scopeId,
            scopeInterface = (ip as? Inet6Address)?.scopedInterface?.name,
        )

        /** Loopback is usable on this device; IPv6 link-local addresses are always excluded. */
        fun isSupported(): Boolean = !ip.isAnyLocalAddress && !ip.isMulticastAddress && !(ip is Inet6Address && ip.isLinkLocalAddress)

        /** Whether this IPv6 address specifies the interface or scope needed to use it. */
        fun hasIpv6Scope(): Boolean = ip is Inet6Address && (ip.scopeId != 0 || ip.scopedInterface != null)

        /** Whether the address satisfies all three filter criteria. */
        fun matches(filter: Filter): Boolean {
            val family = if (ip is Inet4Address) AddressFamily.Ipv4 else AddressFamily.Ipv6
            return family in filter.families && interfaceType in filter.interfaceTypes && category() in filter.categories
        }

        /** The address category used by filters, including loopback and private-network addresses. */
        private fun category(): AddressCategory {
            val first = ip.address[0].toInt() and 0xff
            val second = ip.address[1].toInt() and 0xff
            val privateAddress = if (ip is Inet4Address) {
                first == 10 || first == 172 && second in 16..31 || first == 192 && second == 168
            } else {
                first and 0xfe == 0xfc
            }
            return when {
                ip.isLoopbackAddress -> AddressCategory.Loopback
                ip.isLinkLocalAddress -> AddressCategory.LinkLocal
                privateAddress -> AddressCategory.Private
                else -> AddressCategory.NonPrivate
            }
        }
    }

    /** An address id and the interfaces through which that IP may be selected. */
    private data class TrackedAddress(val id: Long, val candidates: List<AddressCandidate>)

    private companion object {
        const val PERIODIC_SCAN_MS = 30_000L
        val RETRY_DELAYS_MS = longArrayOf(250, 1_000, 3_000)
        val CANDIDATE_ORDER: Comparator<AddressCandidate> = compareBy(
            { it.interfaceName }, { it.ipKey.bytes.size },
            { it.ipKey.bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } },
            { it.ipKey.scopeId ?: 0 }, { it.ipKey.scopeInterface.orEmpty() }, { it.interfaceType.ordinal },
        )
    }
}
