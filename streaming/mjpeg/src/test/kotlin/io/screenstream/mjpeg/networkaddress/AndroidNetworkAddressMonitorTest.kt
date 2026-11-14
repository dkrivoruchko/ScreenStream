package io.screenstream.mjpeg.networkaddress

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import io.screenstream.mjpeg.MjpegKoinTestRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class AndroidNetworkAddressMonitorTest {
    @get:Rule
    val koin = MjpegKoinTestRule()

    // Cases: MJPEG-NET-10, MJPEG-NET-11
    @Test
    fun discoveryStartsWithoutSubscribersAndSelectionsReuseObserverUntilClose() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivityShadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val monitor = AndroidNetworkAddressMonitor(context, NativeInterfaceFixture())
        val noFamilies = NetworkAddressFilter(emptySet(), InterfaceType.entries.toSet(), AddressCategory.entries.toSet())
        val noCategories = NetworkAddressFilter(AddressFamily.entries.toSet(), InterfaceType.entries.toSet(), emptySet())
        try {
            assertEquals(NetworkAddressMonitor.State.NotStarted, monitor.state.value)
            monitor.start()
            // Empty family criteria select no addresses.
            awaitSelectedAddresses(monitor, noFamilies)
            val observer = connectivityShadow.networkCallbacks.single()
            monitor.start()
            awaitSelectedAddresses(monitor, noCategories)
            assertSame(observer, connectivityShadow.networkCallbacks.single())
            withTimeout(5.seconds) { monitor.close() }
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertTrue(connectivityShadow.networkCallbacks.isEmpty())
            monitor.start()
            withTimeout(5.seconds) { monitor.close() }
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertTrue(connectivityShadow.networkCallbacks.isEmpty())
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-11
    @Test
    fun inertMonitorClosesWithoutScanAndLaterStartsCannotReopenIt() = runBlocking {
        val nativeScanEntries = AtomicInteger()
        val native = NativeInterfaceFixture(enumeration = {
            nativeScanEntries.incrementAndGet()
            Collections.emptyEnumeration()
        })
        val monitor = AndroidNetworkAddressMonitor(RuntimeEnvironment.getApplication(), native)
        val emissions = java.util.concurrent.CopyOnWriteArrayList<NetworkAddressMonitor.State>()
        val collector = launch(Dispatchers.Unconfined) { monitor.state.collect { emissions += it } }
        try {
            withTimeout(5.seconds) {
                assertEquals(NetworkAddressMonitor.State.NotStarted, monitor.state.value)
                val first = async { monitor.close() }
                val second = async { monitor.close() }
                first.await()
                second.await()
                assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
                assertEquals(0, nativeScanEntries.get())
                assertFalse(emissions.contains(NetworkAddressMonitor.State.Loading))
                monitor.start()
                monitor.close()
                assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
                assertEquals(0, nativeScanEntries.get())
            }
        } finally {
            withContext(NonCancellable) {
                withTimeout(5.seconds) {
                    monitor.close()
                    collector.cancelAndJoin()
                }
            }
        }
    }

    // Cases: MJPEG-NET-02, MJPEG-NET-04, MJPEG-NET-10
    @Test
    fun nativeSelectionKeepsScopedIdentitiesWhenFamilyCategoryAndInterfaceFiltersChange(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivityShadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
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
                            Inet6Address.getByAddress(null, ip("fe80::7").address, 11),
                            Inet6Address.getByAddress(null, ip("ff02::1").address, 12),
                        ),
                    ),
                ),
            )
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val allAddressesFilter = filter()
        try {
            monitor.start()
            // No collector starts discovery: this retained nonempty state is the first receipt.
            withTimeout(5.seconds) {
                while (monitor.state.value !is NetworkAddressMonitor.State.Observed) yield()
            }
            val initial = assertIs<NetworkAddressMonitor.State.Observed>(monitor.state.value).inventory.select(allAddressesFilter)
            assertEquals(6, initial.size)
            val ids = initial.map { it.id }
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
                initial,
            )

            val wifiPrivate = filter(
                families = setOf(AddressFamily.Ipv4), interfaceTypes = setOf(InterfaceType.Wifi), categories = setOf(AddressCategory.Private),
            )
            assertEquals(
                listOf(NetworkAddress(ids[3], shared, "wlan0", InterfaceType.Wifi)),
                awaitSelectedAddresses(monitor, wifiPrivate) { it.isNotEmpty() },
            )
            val ipv6Private = filter(families = setOf(AddressFamily.Ipv6), categories = setOf(AddressCategory.Private))
            assertEquals(
                listOf(
                    NetworkAddress(ids[4], scope11, "eth0", InterfaceType.Ethernet),
                    NetworkAddress(ids[5], scope12, "eth0", InterfaceType.Ethernet),
                ),
                awaitSelectedAddresses(monitor, ipv6Private) { it.isNotEmpty() },
            )
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-02
    @Test
    fun nativeSelectionClassifiesPrivateRangeBoundariesAndOtherSupportedCategories() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivityShadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val expectedByCategory = mapOf(
            AddressCategory.Private to listOf(
                "10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255",
                "192.168.0.0", "192.168.255.255", "fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            ),
            AddressCategory.NonPrivate to listOf(
                "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0",
                "192.167.255.255", "192.169.0.0", "100.64.0.1", "fbff::1", "fe00::1",
                "169.253.255.255", "169.255.0.0",
                "fec0::1", "2001:db8::1",
            ),
            AddressCategory.Loopback to listOf("127.0.0.0", "127.255.255.255", "::1"),
            AddressCategory.LinkLocal to listOf("169.254.0.0", "169.254.255.255"),
        )
        val suppliedAddresses = expectedByCategory.values.flatten().map(::ip)
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(listOf(nativeInterface("eth0", suppliedAddresses)))
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        try {
            for ((category, expectedAddresses) in expectedByCategory) {
                val categoryFilter = filter(categories = setOf(category))
                monitor.start()
                val observed = awaitSelectedAddresses(monitor, categoryFilter) { true }
                assertEquals(expectedAddresses.map(::ip).toSet(), observed.map { it.ip }.toSet(), category.name)
            }
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-04, MJPEG-NET-07
    @Test
    fun freshExcludedDuplicateDoesNotHideRetainedMatchingInterface() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivityShadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val shared = ip("192.168.10.7")
        val witness = ip("192.168.10.8")
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(listOf(nativeInterface("eth0", listOf(shared)), nativeInterface("wlan0", listOf(shared))))
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val wifi = filter(interfaceTypes = setOf(InterfaceType.Wifi))
        try {
            monitor.start()
            val initial = awaitSelectedAddresses(monitor, wifi) { it.isNotEmpty() }
            val id = initial.single().id
            assertEquals(listOf(NetworkAddress(id, shared, "wlan0", InterfaceType.Wifi)), initial)
            val callback = connectivityShadow.networkCallbacks.single()

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
            val retained = awaitSelectedAddresses(monitor, wifi) { observation -> observation.any { it.ip == witness } }
            val witnessId = retained.single { it.ip == witness }.id
            assertNotEquals(id, witnessId)
            assertEquals(
                listOf(
                    NetworkAddress(id, shared, "wlan0", InterfaceType.Wifi),
                    NetworkAddress(witnessId, witness, "wlan1", InterfaceType.Wifi),
                ),
                retained,
            )
            val ethernet = filter(interfaceTypes = setOf(InterfaceType.Ethernet))
            assertEquals(
                listOf(NetworkAddress(id, shared, "eth0", InterfaceType.Ethernet)),
                awaitSelectedAddresses(monitor, ethernet) { it.isNotEmpty() },
            )
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-05, MJPEG-NET-07, MJPEG-NET-08
    @Test
    fun identitySurvivesMetadataFilterAndUncertaintyUntilCurrentNativeAbsence() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivityShadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val address = ip("192.168.10.7")
        val initialWitness = ip("192.168.50.1")
        val uncertainWitness = ip("192.168.50.2")
        val absenceWitness = ip("192.168.50.3")
        val returnWitness = ip("192.168.50.4")
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(listOf(nativeInterface("wlan0", listOf(address)), nativeInterface("eth0", listOf(initialWitness))))
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val allAddressesFilter = filter()
        try {
            monitor.start()
            val initial = awaitSelectedAddresses(monitor, allAddressesFilter) { it.size == 2 }
            val originalId = initial.single { it.ip == address }.id
            val initialWitnessId = initial.single { it.ip == initialWitness }.id
            assertEquals(
                listOf(
                    NetworkAddress(initialWitnessId, initialWitness, "eth0", InterfaceType.Ethernet),
                    NetworkAddress(originalId, address, "wlan0", InterfaceType.Wifi),
                ),
                initial,
            )
            val callback = connectivityShadow.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(202)
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            // Empty callback addresses leave native uncertainty visible rather than reintroducing the IP.
            callback.onLinkPropertiesChanged(network, LinkProperties().apply { interfaceName = "wlan0" })
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            val vpn = awaitSelectedAddresses(monitor, allAddressesFilter) { observation ->
                observation.any { it.ip == address && it.interfaceType == InterfaceType.Vpn }
            }
            assertEquals(NetworkAddress(originalId, address, "wlan0", InterfaceType.Vpn), vpn.single { it.ip == address })

            val ethernet = filter(interfaceTypes = setOf(InterfaceType.Ethernet))
            assertEquals(
                listOf(NetworkAddress(initialWitnessId, initialWitness, "eth0", InterfaceType.Ethernet)),
                awaitSelectedAddresses(monitor, ethernet) { it.isNotEmpty() },
            )
            assertEquals(
                NetworkAddress(originalId, address, "wlan0", InterfaceType.Vpn),
                awaitSelectedAddresses(monitor, allAddressesFilter) { it.size == 2 }.single { it.ip == address },
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
            val uncertain = awaitSelectedAddresses(monitor, allAddressesFilter) { it.size == 2 && it.any { item -> item.ip == uncertainWitness } }
            val uncertainWitnessId = uncertain.single { it.ip == uncertainWitness }.id
            assertEquals(
                listOf(
                    NetworkAddress(uncertainWitnessId, uncertainWitness, "eth0", InterfaceType.Ethernet),
                    NetworkAddress(originalId, address, "wlan0", InterfaceType.Vpn),
                ),
                uncertain,
            )

            native.enumeration = {
                Collections.enumeration(listOf(nativeInterface("wlan0"), nativeInterface("eth0", listOf(absenceWitness))))
            }
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            val absent = awaitSelectedAddresses(monitor, allAddressesFilter) { it.size == 1 && it.single().ip == absenceWitness }
            assertEquals(
                listOf(NetworkAddress(absent.single().id, absenceWitness, "eth0", InterfaceType.Ethernet)),
                absent,
            )

            native.enumeration = {
                Collections.enumeration(listOf(nativeInterface("wlan0", listOf(address)), nativeInterface("eth0", listOf(returnWitness))))
            }
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            val returned = awaitSelectedAddresses(monitor, allAddressesFilter) { it.size == 2 && it.any { item -> item.ip == returnWitness } }
            val returnedId = returned.single { it.ip == address }.id
            assertFalse(returnedId in setOf(originalId, initialWitnessId, uncertainWitnessId, absent.single().id))
            assertTrue(returnedId > maxOf(originalId, initialWitnessId, uncertainWitnessId, absent.single().id))
            assertEquals(
                listOf(
                    NetworkAddress(returned.single { it.ip == returnWitness }.id, returnWitness, "eth0", InterfaceType.Ethernet),
                    NetworkAddress(returnedId, address, "wlan0", InterfaceType.Vpn),
                ),
                returned,
            )
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-08
    @Test
    fun staleScanAddsNewCandidatesAndRetainsKnownIdentityUntilCurrentAbsence(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivityShadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val address = ip("192.168.10.7")
        val staleNewAddress = ip("192.168.10.8")
        val native = NativeInterfaceFixture(enumeration = { Collections.enumeration(listOf(nativeInterface("wlan0", listOf(address)))) })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val staleScan = NativeScanGate(listOf(nativeInterface("wlan0"), nativeInterface("wlan1", listOf(staleNewAddress))))
        val currentScan = NativeScanGate(listOf(nativeInterface("wlan0")))
        val allAddressesFilter = filter()
        try {
            monitor.start()
            val initial = awaitSelectedAddresses(monitor, allAddressesFilter) { it.isNotEmpty() }
            val id = initial.single().id
            assertEquals(listOf(NetworkAddress(id, address, "wlan0", InterfaceType.Wifi)), initial)
            val callback = connectivityShadow.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(203)
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            callback.onLinkPropertiesChanged(network, LinkProperties().apply { interfaceName = "wlan0" })

            native.enumeration = staleScan::enumerate
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            staleScan.awaitEntry() // The monitor captures its snapshot before entering native enumeration.
            native.enumeration = currentScan::enumerate
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            val vpnOnly = filter(interfaceTypes = setOf(InterfaceType.Vpn))
            assertEquals(
                listOf(NetworkAddress(id, address, "wlan0", InterfaceType.Vpn)),
                awaitSelectedAddresses(monitor, vpnOnly) { it.isNotEmpty() },
            )
            assertFalse(currentScan.hasEntered)

            staleScan.release()
            // Entering another native scan proves the old result was processed and requested fresh evidence.
            currentScan.awaitEntry()
            assertEquals(
                listOf(NetworkAddress(id, address, "wlan0", InterfaceType.Vpn)),
                assertIs<NetworkAddressMonitor.State.Observed>(monitor.state.value).inventory.select(vpnOnly),
            )
            val staleAddresses = assertIs<NetworkAddressMonitor.State.Observed>(monitor.state.value).inventory.select(allAddressesFilter)
            assertEquals(setOf(address, staleNewAddress), staleAddresses.map { it.ip }.toSet())
            assertEquals(id, staleAddresses.single { it.ip == address }.id)
            assertEquals(InterfaceType.Wifi, staleAddresses.single { it.ip == staleNewAddress }.interfaceType)
            currentScan.release()
            assertEquals(emptyList(), awaitSelectedAddresses(monitor, allAddressesFilter))
        } finally {
            staleScan.release()
            currentScan.release()
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-11
    @Test
    fun cancelledCloseWaiterCannotAbandonHeldScanSettlementOrReopenDiscovery() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivityShadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val gate = NativeScanGate(listOf(nativeInterface("wlan0", listOf(ip("192.168.10.7")))))
        val native = NativeInterfaceFixture(enumeration = gate::enumerate)
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val waiters = mutableListOf<Job>()
        try {
            monitor.start()
            gate.awaitEntry()
            assertEquals(NetworkAddressMonitor.State.Loading, monitor.state.value)
            val callback = connectivityShadow.networkCallbacks.single()
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { monitor.close() }.also { waiters += it }
            val surviving = async(start = CoroutineStart.UNDISPATCHED) { monitor.close() }.also { waiters += it }
            withTimeout(5.seconds) {
                while (connectivityShadow.networkCallbacks.isNotEmpty()) yield()
            }
            assertFalse(cancelled.isCompleted)
            assertFalse(surviving.isCompleted)
            cancelled.cancelAndJoin()
            assertFalse(surviving.isCompleted)
            assertEquals(NetworkAddressMonitor.State.Loading, monitor.state.value)
            monitor.start()
            callback.onCapabilitiesChanged(ShadowNetwork.newInstance(204), capabilities(NetworkCapabilities.TRANSPORT_VPN))

            gate.release()
            withTimeout(5.seconds) { surviving.await() }
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertTrue(connectivityShadow.networkCallbacks.isEmpty())
            monitor.start()
            callback.onAvailable(ShadowNetwork.newInstance(205))
            withTimeout(5.seconds) { monitor.close() }
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertTrue(connectivityShadow.networkCallbacks.isEmpty())
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

    // Cases: MJPEG-NET-05, MJPEG-NET-06, MJPEG-NET-07
    @Test
    fun incompleteEnumerationCanRetireEveryCandidateAfterSuccessfulNamedReads() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivityShadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val address = ip("192.168.10.7")
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(listOf(nativeInterface("wlan0", listOf(address))))
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val allAddressesFilter = filter()
        try {
            monitor.start()
            val initial = awaitSelectedAddresses(monitor, allAddressesFilter) { it.isNotEmpty() }.single()
            val callback = connectivityShadow.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(206)
            native.enumeration = { throw SocketException("Incomplete enumeration") }
            native.lookup = { name -> nativeInterface(name) }
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            callback.onLinkPropertiesChanged(network, LinkProperties().apply { interfaceName = "wlan0" })
            assertTrue(awaitSelectedAddresses(monitor, allAddressesFilter).isEmpty())
            native.lookup = { name -> nativeInterface(name, listOf(address)) }
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            val returned = awaitSelectedAddresses(monitor, allAddressesFilter) { it.isNotEmpty() }.single()
            assertEquals(initial.ipKey, returned.ipKey)
            assertTrue(returned.id > initial.id)
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-01, MJPEG-NET-08
    @Test
    fun equalInventoriesDoNotEmitWhileAStaleScanStillRequestsCurrentEvidence() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivityShadow = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val interfaces = listOf(nativeInterface("wlan0", listOf(ip("192.168.10.7"))))
        val native = NativeInterfaceFixture(enumeration = { Collections.enumeration(interfaces) })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val stale = NativeScanGate(interfaces)
        val current = NativeScanGate(interfaces)
        val observedEmissionCount = AtomicInteger()
        val collector = launch(Dispatchers.Unconfined) {
            monitor.state.collect { if (it is NetworkAddressMonitor.State.Observed) observedEmissionCount.incrementAndGet() }
        }
        try {
            monitor.start()
            awaitSelectedAddresses(monitor, filter()) { it.isNotEmpty() }
            withTimeout(5.seconds) {
                while (observedEmissionCount.get() == 0) yield()
            }
            val emissionsBeforeScan = observedEmissionCount.get()
            val callback = connectivityShadow.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(207)
            native.enumeration = stale::enumerate
            callback.onAvailable(network)
            stale.awaitEntry()
            native.enumeration = current::enumerate
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            stale.release()
            current.awaitEntry()
            assertEquals(emissionsBeforeScan, observedEmissionCount.get())
        } finally {
            stale.release()
            current.release()
            withContext(NonCancellable) {
                withTimeout(5.seconds) {
                    monitor.close()
                    collector.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun awaitSelectedAddresses(
        monitor: AndroidNetworkAddressMonitor,
        filter: NetworkAddressFilter,
        matches: (List<NetworkAddress>) -> Boolean = { it.isEmpty() },
    ): List<NetworkAddress> = withTimeout(5.seconds) {
        val observed = assertIs<NetworkAddressMonitor.State.Observed>(monitor.state.first {
            it is NetworkAddressMonitor.State.Observed && matches(it.inventory.select(filter))
        })
        observed.inventory.select(filter)
    }

    private fun filter(
        families: Set<AddressFamily> = AddressFamily.entries.toSet(),
        interfaceTypes: Set<InterfaceType> = InterfaceType.entries.toSet(),
        categories: Set<AddressCategory> = AddressCategory.entries.toSet(),
    ): NetworkAddressFilter = NetworkAddressFilter(families, interfaceTypes, categories)

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    private fun capabilities(transport: Int): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addTransportType(transport)
    }

    /** Holds only the native input operation; production owns snapshot capture, merging and settlement. */
    private class NativeScanGate(private val interfaces: List<NativeInterfaceAccess.Handle>) {
        private val entered = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)
        val hasEntered: Boolean get() = entered.isCompleted

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
