package io.screenstream.mjpeg.networkaddress

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketException
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class AndroidNetworkAddressMonitorTest {
    @Before
    fun initializeLogging() {
        XLog.init(LogLevel.NONE)
    }

    // Cases: MJPEG-NET-10, MJPEG-NET-11
    @Test
    fun discoveryStartsWithoutSubscribersAndEmptyFilterChangesReuseObserverUntilClose() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val monitor = AndroidNetworkAddressMonitor(context)
        val noFamilies = NetworkAddressFilter(emptySet(), InterfaceType.entries.toSet(), AddressCategory.entries.toSet())
        val noCategories = NetworkAddressFilter(AddressFamily.entries.toSet(), InterfaceType.entries.toSet(), emptySet())
        try {
            assertEquals(NetworkAddressMonitor.State.NotStarted, monitor.state.value)
            monitor.updateFilter(noFamilies)
            // Host-native IPs cannot match this empty filter.
            awaitObserved(monitor, noFamilies)
            val observer = platform.networkCallbacks.single()
            monitor.updateFilter(noCategories)
            awaitObserved(monitor, noCategories)
            assertSame(observer, platform.networkCallbacks.single())
            withTimeout(5.seconds) { monitor.close() }
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertTrue(platform.networkCallbacks.isEmpty())
            monitor.updateFilter(noFamilies)
            withTimeout(5.seconds) { monitor.close() }
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertTrue(platform.networkCallbacks.isEmpty())
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-11
    @Test
    fun inertMonitorClosesWithoutScanAndLaterFiltersCannotReopenIt() = runBlocking {
        val nativeScanEntries = AtomicInteger()
        val native = NativeInterfaceFixture(enumeration = {
            nativeScanEntries.incrementAndGet()
            Collections.emptyEnumeration()
        })
        val monitor = AndroidNetworkAddressMonitor(RuntimeEnvironment.getApplication(), native)
        try {
            withTimeout(5.seconds) {
                assertEquals(NetworkAddressMonitor.State.NotStarted, monitor.state.value)
                val first = async { monitor.close() }
                val second = async { monitor.close() }
                first.await()
                second.await()
                assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
                assertEquals(0, nativeScanEntries.get())
                monitor.updateFilter(NetworkAddressFilter(setOf(AddressFamily.Ipv4), setOf(InterfaceType.Wifi), setOf(AddressCategory.Private)))
                monitor.close()
                assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
                assertEquals(0, nativeScanEntries.get())
            }
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-02, MJPEG-NET-04, MJPEG-NET-10
    @Test
    fun nativeSelectionKeepsScopedIdentitiesWhenFamilyCategoryAndInterfaceFiltersChange() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val shared = ip("192.168.10.7")
        val scopedBytes = byteArrayOf(0xfc.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7)
        val scope11 = Inet6Address.getByAddress(null, scopedBytes, 11)
        val scope12 = Inet6Address.getByAddress(null, scopedBytes, 12)
        val unscoped = Inet6Address.getByAddress(null, scopedBytes, 0)
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(
                listOf(
                    nativeInterface("wlan0", listOf(shared)),
                    nativeInterface(
                        "eth0",
                        listOf(
                            scope12, ip("ff02::1"), shared, ip("169.254.1.7"), unscoped,
                            ip("127.0.0.1"), ip("0.0.0.0"), scope11, ip("8.8.8.8"),
                            ip("224.0.0.1"), ip("::"), ip("fe80::7"),
                        ),
                    ),
                ),
            )
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val all = filter()
        try {
            monitor.updateFilter(all)
            // No collector starts discovery: this retained nonempty state is the first receipt.
            withTimeout(5.seconds) {
                while (monitor.state.value !is NetworkAddressMonitor.State.Observed) yield()
            }
            val initial = monitor.state.value as NetworkAddressMonitor.State.Observed
            assertEquals(all, initial.filter)
            assertEquals(6, initial.addresses.size)
            val ids = initial.addresses.map { it.id }
            assertEquals(6, ids.toSet().size)
            assertEquals(
                listOf(
                    NetworkAddress(ids[0], ip("8.8.8.8"), "eth0", InterfaceType.Ethernet),
                    NetworkAddress(ids[1], ip("127.0.0.1"), "eth0", InterfaceType.Ethernet),
                    NetworkAddress(ids[2], ip("169.254.1.7"), "eth0", InterfaceType.Ethernet),
                    NetworkAddress(ids[3], shared, "eth0", InterfaceType.Ethernet),
                    NetworkAddress(ids[4], scope11, "eth0", InterfaceType.Ethernet),
                    NetworkAddress(ids[5], scope12, "eth0", InterfaceType.Ethernet),
                ),
                initial.addresses,
            )

            val wifiPrivate = filter(
                families = setOf(AddressFamily.Ipv4), types = setOf(InterfaceType.Wifi), categories = setOf(AddressCategory.Private),
            )
            monitor.updateFilter(wifiPrivate)
            assertEquals(
                listOf(NetworkAddress(ids[3], shared, "wlan0", InterfaceType.Wifi)),
                awaitObserved(monitor, wifiPrivate) { it.addresses.isNotEmpty() }.addresses,
            )
            val ipv6Private = filter(families = setOf(AddressFamily.Ipv6), categories = setOf(AddressCategory.Private))
            monitor.updateFilter(ipv6Private)
            assertEquals(
                listOf(
                    NetworkAddress(ids[4], scope11, "eth0", InterfaceType.Ethernet),
                    NetworkAddress(ids[5], scope12, "eth0", InterfaceType.Ethernet),
                ),
                awaitObserved(monitor, ipv6Private) { it.addresses.isNotEmpty() }.addresses,
            )
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-02
    @Test
    fun nativeSelectionClassifiesPrivateRangeBoundariesAndOtherSupportedCategories() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val expectedByCategory = mapOf(
            AddressCategory.Private to listOf(
                "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254",
                "192.168.0.1", "192.168.255.254", "fc00::1", "fdff::1",
            ),
            AddressCategory.NonPrivate to listOf(
                "9.255.255.254", "11.0.0.1", "172.15.255.254", "172.32.0.1",
                "192.167.255.254", "192.169.0.1", "100.64.0.1", "fbff::1", "fe00::1",
                "fec0::1", "2001:db8::1",
            ),
            AddressCategory.Loopback to listOf("127.0.0.1", "::1"),
            AddressCategory.LinkLocal to listOf("169.254.1.7"),
        )
        val suppliedAddresses = expectedByCategory.values.flatten().map(::ip)
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(listOf(nativeInterface("eth0", suppliedAddresses)))
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        try {
            for ((category, expectedAddresses) in expectedByCategory) {
                val categoryFilter = filter(categories = setOf(category))
                monitor.updateFilter(categoryFilter)
                val observed = awaitObserved(monitor, categoryFilter) { true }
                assertEquals(category.name, expectedAddresses.map(::ip).toSet(), observed.addresses.map { it.ip }.toSet())
            }
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-04, MJPEG-NET-07
    @Test
    fun freshExcludedDuplicateDoesNotHideRetainedMatchingInterface() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val shared = ip("192.168.10.7")
        val witness = ip("192.168.10.8")
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(listOf(nativeInterface("eth0", listOf(shared)), nativeInterface("wlan0", listOf(shared))))
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val wifi = filter(types = setOf(InterfaceType.Wifi))
        try {
            monitor.updateFilter(wifi)
            val initial = awaitObserved(monitor, wifi) { it.addresses.isNotEmpty() }
            val id = initial.addresses.single().id
            assertEquals(listOf(NetworkAddress(id, shared, "wlan0", InterfaceType.Wifi)), initial.addresses)
            val callback = platform.networkCallbacks.single()

            native.enumeration = {
                Collections.enumeration(
                    listOf(
                        nativeInterface("eth0", listOf(shared)),
                        nativeInterface("wlan0", readAddresses = { throw SocketException("Wifi temporarily unreadable") }),
                        nativeInterface("wlan1", listOf(witness)),
                    ),
                )
            }
            callback.onAvailable(ShadowNetwork.newInstance(201))
            // The new Wifi address proves this failed-read result reached the real observation owner.
            val retained = awaitObserved(monitor, wifi) { observation -> observation.addresses.any { it.ip == witness } }
            val witnessId = retained.addresses.single { it.ip == witness }.id
            assertNotEquals(id, witnessId)
            assertEquals(
                listOf(
                    NetworkAddress(id, shared, "wlan0", InterfaceType.Wifi),
                    NetworkAddress(witnessId, witness, "wlan1", InterfaceType.Wifi),
                ),
                retained.addresses,
            )
            val ethernet = filter(types = setOf(InterfaceType.Ethernet))
            monitor.updateFilter(ethernet)
            assertEquals(
                listOf(NetworkAddress(id, shared, "eth0", InterfaceType.Ethernet)),
                awaitObserved(monitor, ethernet) { it.addresses.isNotEmpty() }.addresses,
            )
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-05, MJPEG-NET-07, MJPEG-NET-08
    @Test
    fun identitySurvivesMetadataFilterAndUncertaintyUntilCurrentNativeAbsence() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val address = ip("192.168.10.7")
        val initialWitness = ip("192.168.50.1")
        val uncertainWitness = ip("192.168.50.2")
        val absenceWitness = ip("192.168.50.3")
        val returnWitness = ip("192.168.50.4")
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(listOf(nativeInterface("wlan0", listOf(address)), nativeInterface("eth0", listOf(initialWitness))))
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val all = filter()
        try {
            monitor.updateFilter(all)
            val initial = awaitObserved(monitor, all) { it.addresses.size == 2 }
            val originalId = initial.addresses.single { it.ip == address }.id
            val initialWitnessId = initial.addresses.single { it.ip == initialWitness }.id
            assertEquals(
                listOf(
                    NetworkAddress(initialWitnessId, initialWitness, "eth0", InterfaceType.Ethernet),
                    NetworkAddress(originalId, address, "wlan0", InterfaceType.Wifi),
                ),
                initial.addresses,
            )
            val callback = platform.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(202)
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            // Empty callback addresses leave native uncertainty visible rather than reintroducing the IP.
            callback.onLinkPropertiesChanged(network, LinkProperties().apply { interfaceName = "wlan0" })
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            val vpn = awaitObserved(monitor, all) { observation ->
                observation.addresses.any { it.ip == address && it.interfaceType == InterfaceType.Vpn }
            }
            assertEquals(NetworkAddress(originalId, address, "wlan0", InterfaceType.Vpn), vpn.addresses.single { it.ip == address })

            val ethernet = filter(types = setOf(InterfaceType.Ethernet))
            monitor.updateFilter(ethernet)
            assertEquals(
                listOf(NetworkAddress(initialWitnessId, initialWitness, "eth0", InterfaceType.Ethernet)),
                awaitObserved(monitor, ethernet) { it.addresses.isNotEmpty() }.addresses,
            )
            monitor.updateFilter(all)
            assertEquals(
                NetworkAddress(originalId, address, "wlan0", InterfaceType.Vpn),
                awaitObserved(monitor, all) { it.addresses.size == 2 }.addresses.single { it.ip == address },
            )

            native.enumeration = {
                Collections.enumeration(
                    listOf(
                        nativeInterface("wlan0", readAddresses = { throw SocketException("Address read temporarily unavailable") }),
                        nativeInterface("eth0", listOf(uncertainWitness)),
                    ),
                )
            }
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            val uncertain = awaitObserved(monitor, all) { it.addresses.size == 2 && it.addresses.any { item -> item.ip == uncertainWitness } }
            val uncertainWitnessId = uncertain.addresses.single { it.ip == uncertainWitness }.id
            assertEquals(
                listOf(
                    NetworkAddress(uncertainWitnessId, uncertainWitness, "eth0", InterfaceType.Ethernet),
                    NetworkAddress(originalId, address, "wlan0", InterfaceType.Vpn),
                ),
                uncertain.addresses,
            )

            native.enumeration = {
                Collections.enumeration(listOf(nativeInterface("wlan0"), nativeInterface("eth0", listOf(absenceWitness))))
            }
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            val absent = awaitObserved(monitor, all) { it.addresses.size == 1 && it.addresses.single().ip == absenceWitness }
            assertEquals(
                listOf(NetworkAddress(absent.addresses.single().id, absenceWitness, "eth0", InterfaceType.Ethernet)),
                absent.addresses,
            )

            native.enumeration = {
                Collections.enumeration(listOf(nativeInterface("wlan0", listOf(address)), nativeInterface("eth0", listOf(returnWitness))))
            }
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            val returned = awaitObserved(monitor, all) { it.addresses.size == 2 && it.addresses.any { item -> item.ip == returnWitness } }
            val returnedId = returned.addresses.single { it.ip == address }.id
            assertFalse(returnedId in setOf(originalId, initialWitnessId, uncertainWitnessId, absent.addresses.single().id))
            assertEquals(
                listOf(
                    NetworkAddress(returned.addresses.single { it.ip == returnWitness }.id, returnWitness, "eth0", InterfaceType.Ethernet),
                    NetworkAddress(returnedId, address, "wlan0", InterfaceType.Vpn),
                ),
                returned.addresses,
            )
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-08
    @Test
    fun staleEmptyScanKeepsKnownIdentityUnderLatestMetadataAndFilterUntilCurrentAbsence() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val address = ip("192.168.10.7")
        val native = NativeInterfaceFixture(enumeration = { Collections.enumeration(listOf(nativeInterface("wlan0", listOf(address)))) })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val oldScan = NativeScanGate(listOf(nativeInterface("wlan0")))
        val currentScan = NativeScanGate(listOf(nativeInterface("wlan0")))
        val all = filter()
        try {
            monitor.updateFilter(all)
            val initial = awaitObserved(monitor, all) { it.addresses.isNotEmpty() }
            val id = initial.addresses.single().id
            assertEquals(listOf(NetworkAddress(id, address, "wlan0", InterfaceType.Wifi)), initial.addresses)
            val callback = platform.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(203)
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            callback.onLinkPropertiesChanged(network, LinkProperties().apply { interfaceName = "wlan0" })

            native.enumeration = oldScan::enumerate
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            oldScan.awaitEntry() // Source captures its snapshot before entering native enumeration.
            native.enumeration = currentScan::enumerate
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            val vpnOnly = filter(types = setOf(InterfaceType.Vpn))
            monitor.updateFilter(vpnOnly)
            assertEquals(
                listOf(NetworkAddress(id, address, "wlan0", InterfaceType.Vpn)),
                awaitObserved(monitor, vpnOnly) { it.addresses.isNotEmpty() }.addresses,
            )

            oldScan.release()
            // Entering another native scan proves the old result was processed and requested fresh evidence.
            currentScan.awaitEntry()
            assertEquals(
                NetworkAddressMonitor.State.Observed(vpnOnly, listOf(NetworkAddress(id, address, "wlan0", InterfaceType.Vpn))),
                monitor.state.value,
            )
            currentScan.release()
            assertEquals(emptyList<NetworkAddress>(), awaitObserved(monitor, vpnOnly).addresses)
        } finally {
            oldScan.release()
            currentScan.release()
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-11
    @Test
    fun cancelledCloseWaiterCannotAbandonHeldScanSettlementOrReopenDiscovery() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val gate = NativeScanGate(listOf(nativeInterface("wlan0", listOf(ip("192.168.10.7")))))
        val native = NativeInterfaceFixture(enumeration = gate::enumerate)
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val waiters = mutableListOf<Job>()
        val all = filter()
        try {
            monitor.updateFilter(all)
            gate.awaitEntry()
            assertEquals(NetworkAddressMonitor.State.Loading(all), monitor.state.value)
            val callback = platform.networkCallbacks.single()
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { monitor.close() }.also { waiters += it }
            val surviving = async(start = CoroutineStart.UNDISPATCHED) { monitor.close() }.also { waiters += it }
            withTimeout(5.seconds) {
                while (platform.networkCallbacks.isNotEmpty()) yield()
            }
            assertFalse(cancelled.isCompleted)
            assertFalse(surviving.isCompleted)
            cancelled.cancelAndJoin()
            assertFalse(surviving.isCompleted)
            assertEquals(NetworkAddressMonitor.State.Loading(all), monitor.state.value)
            val laterFilter = filter(types = setOf(InterfaceType.Vpn))
            monitor.updateFilter(laterFilter)
            callback.onCapabilitiesChanged(ShadowNetwork.newInstance(204), capabilities(NetworkCapabilities.TRANSPORT_VPN))

            gate.release()
            withTimeout(5.seconds) { surviving.await() }
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertTrue(platform.networkCallbacks.isEmpty())
            monitor.updateFilter(laterFilter)
            callback.onAvailable(ShadowNetwork.newInstance(205))
            withTimeout(5.seconds) { monitor.close() }
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertTrue(platform.networkCallbacks.isEmpty())
        } finally {
            gate.release()
            withContext(NonCancellable) {
                withTimeout(5.seconds) {
                    monitor.close()
                    waiters.joinAll()
                }
            }
        }
    }

    private suspend fun awaitObserved(
        monitor: AndroidNetworkAddressMonitor,
        filter: NetworkAddressFilter,
        matches: (NetworkAddressMonitor.State.Observed) -> Boolean = { it.addresses.isEmpty() },
    ): NetworkAddressMonitor.State.Observed = withTimeout(5.seconds) {
        monitor.state.first {
            it is NetworkAddressMonitor.State.Observed && it.filter == filter && matches(it)
        } as NetworkAddressMonitor.State.Observed
    }

    private fun filter(
        families: Set<AddressFamily> = AddressFamily.entries.toSet(),
        types: Set<InterfaceType> = InterfaceType.entries.toSet(),
        categories: Set<AddressCategory> = AddressCategory.entries.toSet(),
    ): NetworkAddressFilter = NetworkAddressFilter(families, types, categories)

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    private fun capabilities(transport: Int): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addTransportType(transport)
    }

    /** Holds only the native input operation; production owns snapshot capture, merging and settlement. */
    private class NativeScanGate(private val interfaces: List<NativeInterfaceAccess.Handle>) {
        private val entered = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)

        fun enumerate(): Enumeration<NativeInterfaceAccess.Handle> {
            entered.complete(Unit)
            check(released.await(10, TimeUnit.SECONDS)) { "Native scan was not released" }
            return Collections.enumeration(interfaces)
        }

        suspend fun awaitEntry() {
            withTimeout(5.seconds) { entered.await() }
        }

        fun release() {
            released.countDown()
        }
    }
}
