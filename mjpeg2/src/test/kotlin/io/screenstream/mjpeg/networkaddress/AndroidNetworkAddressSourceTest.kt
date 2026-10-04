package io.screenstream.mjpeg.networkaddress

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketException
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [CallbackLinkAddressShadow::class, SourceConnectivityShadow::class])
internal class AndroidNetworkAddressSourceTest {
    @Before
    fun initializeLogging() {
        XLog.init(LogLevel.NONE)
    }

    // Cases: MJPEG-NET-09
    @Test
    @Config(sdk = [29, 30, 35])
    fun registeredRequestAdmitsUntrustedLanAndVpnWithoutInternetRequirements() {
        val context: Context = RuntimeEnvironment.getApplication()
        val platform = Shadow.extract<SourceConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val source = AndroidNetworkAddressSource(context)
        try {
            source.scan()
            val request = checkNotNull(platform.registeredRequest)
            assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
            for (excluded in listOf(
                NetworkCapabilities.NET_CAPABILITY_TRUSTED,
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            )) {
                assertFalse(request.hasCapability(excluded))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN)) {
                    val input = capabilities(transport)
                    val shadow = shadowOf(input)
                    shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    assertTrue(request.canBeSatisfiedBy(input))
                    shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    assertFalse(request.canBeSatisfiedBy(input))
                }
            }
        } finally {
            source.unregister(source.beginClose())
            assertNull(source.finishClose())
            assertTrue(platform.networkCallbacks.isEmpty())
        }
    }

    // Cases: MJPEG-NET-09
    @Test
    fun callbackCopyRetainsIpv6BytesAndScopeAfterPlatformInputChanges() {
        val context: Context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val source = AndroidNetworkAddressSource(context)
        try {
            source.scan()
            val callback = platform.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(102)
            val expectedBytes = byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7)
            val linkAddress = Shadow.newInstanceOf(LinkAddress::class.java)
            val input = Shadow.extract<CallbackLinkAddressShadow>(linkAddress)
            input.callbackAddress = Inet6Address.getByAddress(null, expectedBytes, 23)
            val properties = properties("scoped-fixture").apply { setLinkAddresses(listOf(linkAddress)) }
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            callback.onLinkPropertiesChanged(network, properties)
            val retained = source.snapshot()
            val address = retained.networks.single().interfaces!!.single().addresses.single() as Inet6Address
            input.callbackAddress = Inet6Address.getByAddress(null, expectedBytes, 99)
            properties.clear()
            assertTrue(retained.ready)
            assertArrayEquals(expectedBytes, address.address)
            assertEquals(23, address.scopeId)
            callback.onLinkPropertiesChanged(network, properties("replacement"))
            assertEquals("replacement", source.snapshot().networks.single().interfaces!!.single().interfaceName)
            assertEquals("scoped-fixture", retained.networks.single().interfaces!!.single().interfaceName)
            assertEquals(23, address.scopeId)
        } finally {
            source.unregister(source.beginClose())
            assertNull(source.finishClose())
        }
    }

    // Cases: MJPEG-NET-09
    @Test
    fun failedRegistrationRetriesOnLaterScanAndThenAdmitsMetadata() {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val platform = Shadow.extract<SourceConnectivityShadow>(connectivity)
        platform.clearAllNetworks()
        platform.beforeRegistration = {
            platform.beforeRegistration = null
            throw IllegalStateException("registration refused")
        }
        val nativeIp = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 43, 9))
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(listOf(nativeInterface("retry-fixture", listOf(nativeIp))))
        })
        val expectedInterfaces = listOf(AndroidNetworkAddressSource.InterfaceAddresses("retry-fixture", listOf(nativeIp)))
        val source = AndroidNetworkAddressSource(context, native)
        try {
            val refusedRegistration = source.scan()
            assertEquals(expectedInterfaces, refusedRegistration.interfaces)
            assertFalse(refusedRegistration.complete)
            assertFalse(source.snapshot().ready)
            assertTrue(platform.networkCallbacks.isEmpty())
            val registered = source.scan()
            assertEquals(expectedInterfaces, registered.interfaces)
            val callback = platform.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(103)
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_ETHERNET))
            callback.onLinkPropertiesChanged(network, properties("retry-fixture"))
            assertTrue(source.snapshot().ready)
            assertEquals(mapOf("retry-fixture" to InterfaceType.Ethernet), source.snapshot().interfaceTypes())
            source.unregister(source.beginClose())
            assertTrue(platform.networkCallbacks.isEmpty())
            assertNull(source.finishClose())
        } finally {
            source.unregister(source.beginClose())
            source.finishClose()
        }
    }

    // Cases: MJPEG-NET-11
    @Test
    fun registrationReturningAfterCloseRetiresItsObserverWithoutPublishing() {
        val context: Context = RuntimeEnvironment.getApplication()
        val platform = Shadow.extract<SourceConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        platform.setStrictUnregistration(true)
        val source = AndroidNetworkAddressSource(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        platform.afterRegistration = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Registration release timed out" }
        }
        try {
            val scan = worker.submit<AndroidNetworkAddressSource.ScanResult> { source.scan() }
            assertTrue("Android registration was not reached", entered.await(5, TimeUnit.SECONDS))
            val callback = platform.networkCallbacks.single()
            assertTrue(source.beginClose().isEmpty())
            val closing = source.snapshot()
            release.countDown()
            val result = scan.get(5, TimeUnit.SECONDS)
            assertFalse(result.complete)
            assertTrue(result.interfaces.isEmpty())
            assertFalse(result.canConfirmAbsence("late-fixture"))
            assertTrue(platform.networkCallbacks.isEmpty())
            assertEquals(closing, source.snapshot())
            assertNull(source.finishClose())
            callback.onLinkPropertiesChanged(ShadowNetwork.newInstance(104), properties("late-fixture"))
            assertEquals(closing, source.snapshot())
            assertTrue(source.changes.tryReceive().isClosed)
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue("Scan worker did not finish", worker.awaitTermination(5, TimeUnit.SECONDS))
            source.unregister(source.beginClose())
            source.finishClose()
        }
    }

    // Cases: MJPEG-NET-09
    @Test
    @Config(manifest = Config.NONE, sdk = [24, 25])
    fun pre26LostCallbackDuringCapturedFallbackReadRejectsTheStaleNetwork() {
        val context: Context = RuntimeEnvironment.getApplication()
        val platform = Shadow.extract<SourceConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val network = ShadowNetwork.newInstance(202)
        platform.addNetwork(network, pre26ConnectedWifiInfo())
        platform.setNetworkCapabilities(
            network,
            capabilities(NetworkCapabilities.TRANSPORT_WIFI).also {
                shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            },
        )
        val staleProperties = properties("stale-fallback")
        platform.setLinkProperties(network, staleProperties)
        val source = AndroidNetworkAddressSource(context)
        try {
            source.scan()
            assertEquals(mapOf("stale-fallback" to InterfaceType.Wifi), source.snapshot().interfaceTypes())
            val callback = platform.networkCallbacks.single()
            platform.afterLinkPropertiesRead = { capturedNetwork, capturedProperties ->
                platform.afterLinkPropertiesRead = null
                assertEquals(network, capturedNetwork)
                assertSame(staleProperties, capturedProperties)
                // The getter still returns this captured value after onLost removes the network.
                callback.onLost(network)
                platform.removeNetwork(network)
            }
            source.scan()
            assertTrue(source.snapshot().networks.isEmpty())
            source.scan()
            assertTrue(source.snapshot().networks.isEmpty())
        } finally {
            source.unregister(source.beginClose())
            assertNull(source.finishClose())
            assertTrue(platform.networkCallbacks.isEmpty())
        }
    }

    // Cases: MJPEG-NET-09
    @Test
    @Config(manifest = Config.NONE, sdk = [36, 37], shadows = [SourceTetheringShadow::class])
    fun tetheringEventsSupplyReadinessAndTypesThenClosingRejectsRetiredEvents() {
        val application: Context = RuntimeEnvironment.getApplication()
        val manager = Shadow.newInstanceOf(TetheringManager::class.java)
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSystemServiceName(serviceClass: Class<*>): String? =
                if (serviceClass == TetheringManager::class.java) TETHERING_SERVICE else super.getSystemServiceName(serviceClass)

            override fun getSystemService(name: String): Any? =
                if (name == TETHERING_SERVICE) manager else super.getSystemService(name)
        }
        val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        connectivity.clearAllNetworks()
        val platform = Shadow.extract<SourceTetheringShadow>(manager)
        val source = AndroidNetworkAddressSource(context)
        try {
            source.scan()
            assertFalse(source.snapshot().ready)
            val callback = checkNotNull(platform.registeredCallback)
            val executor = checkNotNull(platform.registeredExecutor)
            executor.execute { callback.onTetheredInterfacesChanged(emptySet()) }
            assertTrue(source.snapshot().ready)
            val interfaces = linkedSetOf(
                TetheringInterface(TetheringManager.TETHERING_WIFI, "tether-fixture-first"),
                TetheringInterface(TetheringManager.TETHERING_WIFI, "tether-fixture-second"),
            )
            executor.execute { callback.onTetheredInterfacesChanged(interfaces) }
            val retained = source.snapshot()
            val expected = mapOf("tether-fixture-first" to InterfaceType.Wifi, "tether-fixture-second" to InterfaceType.Wifi)
            assertEquals(expected, retained.tethered)
            assertEquals(expected, retained.interfaceTypes())
            interfaces.clear()
            assertEquals(expected, retained.tethered)
            assertThrows(UnsupportedOperationException::class.java) { (retained.tethered as MutableMap<*, *>).clear() }
            executor.execute { callback.onTetheredInterfacesChanged(emptySet()) }
            assertTrue(source.snapshot().tethered.isEmpty())
            assertEquals(expected, retained.tethered)
            source.unregister(source.beginClose())
            assertNull(platform.registeredCallback)
            assertTrue(connectivity.networkCallbacks.isEmpty())
            assertNull(source.finishClose())
            val closed = source.snapshot()
            executor.execute {
                callback.onTetheredInterfacesChanged(setOf(TetheringInterface(TetheringManager.TETHERING_WIFI, "retired-fixture")))
            }
            assertEquals(closed, source.snapshot())
            while (source.changes.tryReceive().isSuccess) { /* Drain admitted snapshots before channel closure. */
            }
            assertTrue(source.changes.tryReceive().isClosed)
        } finally {
            source.unregister(source.beginClose())
            source.finishClose()
        }
    }

    // Cases: MJPEG-NET-09, MJPEG-NET-11
    @Test
    @Config(sdk = [26, 29, 30, 35])
    fun callbackPayloadsSupplyMetadataAndClosingRetiresTheExactObserver() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val platform = shadowOf(connectivity)
        platform.clearAllNetworks()
        val source = AndroidNetworkAddressSource(context)
        assertFalse(source.snapshot().ready)
        try {
            // scan registers the real source callback; its incidental host-native addresses are not an oracle.
            source.scan()
            val callback = platform.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(101)
            callback.onAvailable(network)
            assertFalse(source.snapshot().ready)
            callback.onCapabilitiesChanged(
                network,
                capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN),
            )
            val suppliedBytes = byteArrayOf(192.toByte(), 168.toByte(), 43, 7)
            val linkAddress = Shadow.newInstanceOf(LinkAddress::class.java)
            val linkInput = Shadow.extract<CallbackLinkAddressShadow>(linkAddress)
            linkInput.callbackAddress = InetAddress.getByAddress(suppliedBytes)
            val properties = properties("wlan-fixture").apply { setLinkAddresses(listOf(linkAddress)) }
            callback.onLinkPropertiesChanged(network, properties)
            val ready = source.snapshot()
            assertTrue(ready.ready)
            assertEquals(mapOf("wlan-fixture" to InterfaceType.Vpn), ready.interfaceTypes())
            val copiedInterfaces = ready.networks.single().interfaces!!
            val copiedAddresses = copiedInterfaces.single().addresses
            assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 43, 7), copiedAddresses.single().address)
            linkInput.callbackAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
            properties.clear()
            assertEquals("wlan-fixture", ready.networks.single().interfaces!!.single().interfaceName)
            assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 43, 7), copiedAddresses.single().address)
            assertThrows(UnsupportedOperationException::class.java) { (copiedAddresses as MutableList<*>).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (copiedInterfaces as MutableList<*>).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (ready.networks as MutableList<*>).clear() }
            callback.onLost(network)
            assertTrue(source.snapshot().networks.isEmpty())
            assertTrue(ready.networks.isNotEmpty())
            val handles = source.beginClose()
            source.unregister(handles)
            assertTrue(platform.networkCallbacks.isEmpty())
            assertNull(source.finishClose())
            val closed = source.snapshot()
            callback.onAvailable(network)
            callback.onLinkPropertiesChanged(network, properties("late0"))
            assertEquals(closed, source.snapshot())
            while (source.changes.tryReceive().isSuccess) { /* Drain the last admitted conflated snapshot. */
            }
            assertTrue(source.changes.receiveCatching().isClosed)
        } finally {
            source.unregister(source.beginClose())
            source.finishClose()
        }
    }

    // Cases: MJPEG-NET-09
    @Test
    @Config(manifest = Config.NONE, sdk = [24, 25])
    fun pre26FallbackSuppliesMetadataButCannotOverwriteCallbackPayloads() {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val platform = shadowOf(connectivity)
        platform.clearAllNetworks()
        val network = ShadowNetwork.newInstance(201)
        platform.addNetwork(network, pre26ConnectedWifiInfo())
        platform.setNetworkCapabilities(
            network,
            capabilities(NetworkCapabilities.TRANSPORT_WIFI).also {
                shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            },
        )
        platform.setLinkProperties(network, properties("fallback0"))
        val source = AndroidNetworkAddressSource(context)
        try {
            source.scan()
            assertTrue(source.snapshot().ready)
            assertEquals(mapOf("fallback0" to InterfaceType.Wifi), source.snapshot().interfaceTypes())
            val callback = platform.networkCallbacks.single()
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            callback.onLinkPropertiesChanged(network, properties("callback0"))
            source.scan()
            assertEquals(mapOf("callback0" to InterfaceType.Vpn), source.snapshot().interfaceTypes())
            callback.onLost(network)
            platform.removeNetwork(network)
            source.scan()
            assertTrue(source.snapshot().networks.isEmpty())
        } finally {
            source.unregister(source.beginClose())
            assertNull(source.finishClose())
            assertTrue(platform.networkCallbacks.isEmpty())
        }
    }

    // Cases: MJPEG-NET-12
    @Test
    fun unregisterFailuresDoNotSkipRemainingObligationsAndPreservePrimaryCause() {
        val source = AndroidNetworkAddressSource(RuntimeEnvironment.getApplication())
        val primary = IllegalStateException("first release")
        val secondary = IllegalArgumentException("second release")
        val released = mutableListOf<String>()
        source.beginClose()
        source.unregister(
            listOf(
                { released += "first"; throw primary },
                { released += "same-primary"; throw primary },
                { released += "second"; throw secondary },
                { released += "repeated-second"; throw secondary },
                { released += "last" },
            )
        )
        assertEquals(listOf("first", "same-primary", "second", "repeated-second", "last"), released)
        val failure = source.finishClose()
        assertSame(primary, failure)
        assertEquals(listOf(secondary, secondary), failure!!.suppressed.toList())
    }

    // Cases: MJPEG-NET-11
    @Test
    fun closeBeforeScanAdmitsNoObserverAndClosedScanHasNoAbsenceAuthority() {
        val context: Context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        val source = AndroidNetworkAddressSource(context)
        assertTrue(source.beginClose().isEmpty())
        val result = source.scan()
        assertTrue(platform.networkCallbacks.isEmpty())
        assertTrue(result.interfaces.isEmpty())
        assertFalse(result.complete)
        assertFalse(result.canConfirmAbsence("wlan0"))
        assertNull(source.finishClose())
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-07
    @Test
    fun nativeEmptyAndDownReadsOverrideConflictingCallbacksWhileFailedReadsUseFallback() {
        val context: Context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val native = NativeInterfaceFixture(enumeration = { null })
        val source = AndroidNetworkAddressSource(context, native)
        val callbackIp = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 43, 8))
        try {
            // Native null is a complete empty enumeration; registration still belongs to the real source.
            assertTrue(source.scan().complete)
            val callback = platform.networkCallbacks.single()
            for ((index, name) in listOf("empty-fixture", "down-fixture", "failed-fixture").withIndex()) {
                val network = ShadowNetwork.newInstance(301 + index)
                callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
                callback.onLinkPropertiesChanged(network, properties(name, callbackIp))
            }
            native.enumeration = {
                Collections.enumeration(
                    listOf(
                        nativeInterface("empty-fixture"),
                        nativeInterface("down-fixture", readIsUp = { false }, readAddresses = { error("Down addresses must not be read") }),
                        nativeInterface("failed-fixture", readIsUp = { throw SocketException("Interface status unavailable") }),
                    ),
                )
            }
            native.lookup = { throw SocketException("Retry unavailable") }

            val result = source.scan()
            assertEquals(3, result.interfaces.size)
            assertEquals(
                mapOf("empty-fixture" to emptyList(), "down-fixture" to emptyList(), "failed-fixture" to listOf(callbackIp)),
                result.interfaces.associate { it.interfaceName to it.addresses },
            )
            assertEquals(setOf("empty-fixture", "down-fixture"), result.successfulInterfaces)
            assertEquals(setOf("failed-fixture"), result.failedInterfaces)
            assertTrue(result.enumerationComplete)
            assertFalse(result.complete)
            assertTrue(result.canConfirmAbsence("empty-fixture"))
            assertTrue(result.canConfirmAbsence("down-fixture"))
            assertFalse(result.canConfirmAbsence("failed-fixture"))

            native.enumeration = { Collections.enumeration(listOf(nativeInterface("failed-fixture"))) }
            val recovered = source.scan()
            assertEquals(emptyList<InetAddress>(), recovered.interfaces.single().addresses)
            assertTrue(recovered.complete)
            assertTrue(recovered.canConfirmAbsence("failed-fixture"))
            // Recovery does not alter the previously returned uncertain result or its copied callback IP.
            assertEquals(listOf(callbackIp), result.interfaces.single { it.interfaceName == "failed-fixture" }.addresses)
        } finally {
            source.unregister(source.beginClose())
            assertNull(source.finishClose())
            assertTrue(platform.networkCallbacks.isEmpty())
        }
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-07
    @Test
    fun partialNativeEnumerationKeepsPriorSuccessAndRetriesKnownInterfacesWithoutDeclaringGlobalAbsence() {
        val context: Context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val native = NativeInterfaceFixture()
        val source = AndroidNetworkAddressSource(context, native)
        val callbackIp = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 8))
        val firstIp = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 9))
        val retryIp = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 10))
        try {
            source.scan()
            val callback = platform.networkCallbacks.single()
            for ((index, name) in listOf("first-fixture", "retry-fixture", "failed-fixture", "missing-fixture").withIndex()) {
                val network = ShadowNetwork.newInstance(401 + index)
                callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
                callback.onLinkPropertiesChanged(network, properties(name, callbackIp))
            }
            native.enumeration = {
                object : Enumeration<NativeInterfaceAccess.Handle> {
                    private var delivered = false
                    override fun hasMoreElements(): Boolean = if (delivered) throw SocketException("Enumeration interrupted") else true
                    override fun nextElement(): NativeInterfaceAccess.Handle {
                        delivered = true
                        return nativeInterface("first-fixture", listOf(firstIp))
                    }
                }
            }
            native.lookup = { name ->
                when (name) {
                    "retry-fixture" -> nativeInterface(name, listOf(retryIp))
                    "failed-fixture" -> throw SocketException("Lookup failed")
                    "missing-fixture" -> null
                    else -> error("A completed native read must not be replaced by a retry")
                }
            }

            val result = source.scan()
            assertEquals(4, result.interfaces.size)
            assertEquals(
                mapOf(
                    "first-fixture" to listOf(firstIp),
                    "retry-fixture" to listOf(retryIp),
                    "failed-fixture" to listOf(callbackIp),
                    "missing-fixture" to listOf(callbackIp),
                ),
                result.interfaces.associate { it.interfaceName to it.addresses },
            )
            assertEquals(setOf("first-fixture", "retry-fixture"), result.successfulInterfaces)
            assertEquals(setOf("failed-fixture"), result.failedInterfaces)
            assertFalse(result.enumerationComplete)
            assertFalse(result.complete)
            assertTrue(result.canConfirmAbsence("first-fixture"))
            assertTrue(result.canConfirmAbsence("retry-fixture"))
            assertFalse(result.canConfirmAbsence("failed-fixture"))
            assertFalse(result.canConfirmAbsence("missing-fixture"))
            assertFalse(result.canConfirmAbsence("unseen-fixture"))
        } finally {
            source.unregister(source.beginClose())
            assertNull(source.finishClose())
            assertTrue(platform.networkCallbacks.isEmpty())
        }
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-07
    @Test
    fun partialAddressReadPublishesOnlyCallbackFallbackUntilNamedRetryCompletes() {
        val context: Context = RuntimeEnvironment.getApplication()
        val platform = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        platform.clearAllNetworks()
        val native = NativeInterfaceFixture()
        val source = AndroidNetworkAddressSource(context, native)
        val partialIp = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 11))
        val callbackIp = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 12))
        try {
            source.scan()
            val callback = platform.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(501)
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            callback.onLinkPropertiesChanged(network, properties("partial-fixture", callbackIp))
            native.enumeration = {
                Collections.enumeration(
                    listOf(
                        nativeInterface("partial-fixture", readAddresses = {
                            object : Enumeration<InetAddress> {
                                private var delivered = false
                                override fun hasMoreElements(): Boolean = true
                                override fun nextElement(): InetAddress {
                                    if (delivered) throw SocketException("Address read interrupted")
                                    delivered = true
                                    return partialIp
                                }
                            }
                        }),
                    ),
                )
            }

            val uncertain = source.scan()
            assertEquals(listOf(callbackIp), uncertain.interfaces.single().addresses)
            assertEquals(setOf("partial-fixture"), uncertain.failedInterfaces)
            assertTrue(uncertain.successfulInterfaces.isEmpty())
            assertTrue(uncertain.enumerationComplete)
            assertFalse(uncertain.complete)
            assertFalse(uncertain.canConfirmAbsence("partial-fixture"))

            // The original address reader still fails. Only this successful named retry confirms absence.
            native.lookup = { name -> nativeInterface(name) }
            val recovered = source.scan()
            assertEquals(emptyList<InetAddress>(), recovered.interfaces.single().addresses)
            assertEquals(setOf("partial-fixture"), recovered.successfulInterfaces)
            assertTrue(recovered.failedInterfaces.isEmpty())
            assertTrue(recovered.complete)
            assertTrue(recovered.canConfirmAbsence("partial-fixture"))
            assertEquals(listOf(callbackIp), uncertain.interfaces.single().addresses)
        } finally {
            source.unregister(source.beginClose())
            assertNull(source.finishClose())
            assertTrue(platform.networkCallbacks.isEmpty())
        }
    }

    private fun properties(name: String, address: InetAddress? = null): LinkProperties = LinkProperties().apply {
        interfaceName = name
        if (address != null) {
            val linkAddress = Shadow.newInstanceOf(LinkAddress::class.java)
            Shadow.extract<CallbackLinkAddressShadow>(linkAddress).callbackAddress = address
            setLinkAddresses(listOf(linkAddress))
        }
    }

    // Stock addNetwork pairs NetworkInfo with Network; this fixture is used only for API 24/25 fallback.
    @Suppress("DEPRECATION")
    private fun pre26ConnectedWifiInfo(): NetworkInfo = ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED,
    )

    private fun capabilities(vararg transports: Int): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also { capabilities ->
        for (transport in transports) shadowOf(capabilities).addTransportType(transport)
    }
}
