package io.screenstream.mjpeg.networkaddress

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import io.screenstream.mjpeg.MjpegKoinTestRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
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
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [CallbackLinkAddressShadow::class, ObservationConnectivityShadow::class])
internal class AndroidNetworkAddressPlatformObservationTest {
    @get:Rule
    val koin = MjpegKoinTestRule()

    // Cases: MJPEG-NET-09
    @Test
    @Config(sdk = [24, 25, 26, 29, 30, 35])
    fun registeredRequestUsesSdkAppropriateCapabilitiesWithoutTransportRestrictions() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val monitor = AndroidNetworkAddressMonitor(context, NativeInterfaceFixture())
        try {
            monitor.start()
            awaitSelectedAddresses(monitor) { true }
            val request = checkNotNull(connectivityShadow.registeredRequest)
            val expected = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
            } else {
                NetworkRequest.Builder()
                    .clearCapabilities()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .build()
            }
            assertEquals(
                expected,
                request,
                "The complete request must preserve SDK-specific capabilities and require no transport",
            )
            assertEquals(1, connectivityShadow.networkCallbacks.size)
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
        assertTrue(connectivityShadow.networkCallbacks.isEmpty())
    }

    // Cases: MJPEG-NET-03, MJPEG-NET-09
    @Test
    fun frameworkTransportsOverrideNamesAndEarlierSpecificReportsYieldOnlyToVpn() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val names = listOf("wlan0", "shared0", "eth0", "mixed0")
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(names.mapIndexed { index, name -> nativeInterface(name, listOf(ip("10.0.0.${index + 1}"))) })
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        try {
            monitor.start()
            val initial = awaitSelectedAddresses(monitor) { it.size == 4 }
            val ids = initial.associate { it.interfaceName to it.id }
            val callback = connectivityShadow.networkCallbacks.single()
            fun report(id: Int, name: String, vararg transports: Int) {
                val network = ShadowNetwork.newInstance(id)
                callback.onCapabilitiesChanged(network, capabilities(*transports))
                callback.onLinkPropertiesChanged(network, linkProperties(name))
            }
            report(1, "wlan0", NetworkCapabilities.TRANSPORT_CELLULAR)
            report(2, "shared0")
            report(3, "shared0", NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_CELLULAR)
            report(4, "shared0", NetworkCapabilities.TRANSPORT_ETHERNET)
            report(5, "eth0")
            report(7, "mixed0", NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_CELLULAR)
            val types =
                mapOf("wlan0" to InterfaceType.Mobile, "shared0" to InterfaceType.Wifi, "eth0" to InterfaceType.Ethernet, "mixed0" to InterfaceType.Ethernet)
            assertEquals(types, awaitSelectedAddresses(monitor) { it.associate { address -> address.interfaceName to address.interfaceType } == types }
                .associate { it.interfaceName to it.interfaceType })
            val scanEntered = CountDownLatch(1)
            val releaseScan = CountDownLatch(1)
            native.enumeration = {
                scanEntered.countDown()
                check(releaseScan.await(5, TimeUnit.SECONDS))
                Collections.enumeration(names.mapIndexed { index, name -> nativeInterface(name, listOf(ip("10.0.0.${index + 1}"))) })
            }
            try {
                report(6, "shared0", NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI)
                assertTrue(
                    scanEntered.await(5, TimeUnit.SECONDS),
                    "Native scans must be held before network metadata is lost",
                )
                val vpn = awaitSelectedAddresses(monitor) { it.single { address -> address.interfaceName == "shared0" }.interfaceType == InterfaceType.Vpn }
                assertEquals(ids, vpn.associate { it.interfaceName to it.id })
                callback.onLost(ShadowNetwork.newInstance(1))
                callback.onCapabilitiesChanged(ShadowNetwork.newInstance(7), capabilities(NetworkCapabilities.TRANSPORT_VPN))
                val retained = awaitSelectedAddresses(monitor) { it.single { address -> address.interfaceName == "mixed0" }.interfaceType == InterfaceType.Vpn }
                assertEquals(InterfaceType.Mobile, retained.single { it.interfaceName == "wlan0" }.interfaceType)
                assertEquals(ids, retained.associate { it.interfaceName to it.id })
            } finally {
                releaseScan.countDown()
            }
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-03, MJPEG-NET-09
    @Test
    fun copiedCallbackAddressesRetainIpv6ScopeWhenPlatformInputsChange() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val native = NativeInterfaceFixture(enumeration = { throw java.net.SocketException("Unknown native state") })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        try {
            monitor.start()
            awaitSelectedAddresses(monitor) { true }
            val callback = connectivityShadow.networkCallbacks.single()
            val network = ShadowNetwork.newInstance(20)
            val link = Shadow.newInstanceOf(LinkAddress::class.java)
            val input = Shadow.extract<CallbackLinkAddressShadow>(link)
            val originalIp = java.net.Inet6Address.getByAddress(null, ip("fd00::7").address, 23)
            input.callbackAddress = originalIp
            val callbackProperties = linkProperties("callback0").apply { setLinkAddresses(listOf(link)) }
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            callback.onLinkPropertiesChanged(network, callbackProperties)
            input.callbackAddress = java.net.Inet6Address.getByAddress(null, originalIp.address, 99)
            callbackProperties.clear()
            val retained = awaitSelectedAddresses(monitor) { it.isNotEmpty() }.single()
            assertEquals(IpAddressKey(originalIp), retained.ipKey)
            assertEquals("callback0", retained.interfaceName)
            callback.onLinkPropertiesChanged(network, linkProperties("replacement0", ip("10.0.0.8")))
            awaitSelectedAddresses(monitor) { it.any { address -> address.interfaceName == "replacement0" } }
            assertEquals(IpAddressKey(originalIp), retained.ipKey)
            assertEquals("callback0", retained.interfaceName)
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-09, MJPEG-NET-11
    @Test
    fun networkRegistrationCompletingAfterCloseIsUnregisteredOnceBeforeCloseSettles() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        connectivityShadow.setStrictUnregistration(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unregistered = AtomicInteger()
        connectivityShadow.afterRegistration = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        connectivityShadow.beforeUnregistration = { unregistered.incrementAndGet() }
        val nativeEntries = AtomicInteger()
        val monitor = AndroidNetworkAddressMonitor(context, NativeInterfaceFixture(enumeration = {
            nativeEntries.incrementAndGet()
            Collections.emptyEnumeration()
        }))
        try {
            monitor.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val callback = connectivityShadow.networkCallbacks.single()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { monitor.close() }
            assertFalse(closing.isCompleted)
            release.countDown()
            withTimeout(5.seconds) { closing.await() }
            assertEquals(1, unregistered.get())
            assertEquals(0, nativeEntries.get())
            assertTrue(connectivityShadow.networkCallbacks.isEmpty())
            callback.onAvailable(ShadowNetwork.newInstance(91))
            callback.onLinkPropertiesChanged(ShadowNetwork.newInstance(91), linkProperties("late0", ip("10.0.0.9")))
            monitor.start()
            monitor.close()
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
            assertEquals(1, unregistered.get())
        } finally {
            release.countDown()
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-09
    @Test
    fun failedRegistrationRetriesAndSuccessfulRegistrationClearsPreviousGetterEvidence() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val network = ShadowNetwork.newInstance(40)
        addGetterNetwork(connectivityShadow, network, "native0", ip("10.0.0.8"))
        connectivityShadow.setNetworkCapabilities(network, capabilities(NetworkCapabilities.TRANSPORT_VPN).also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        })
        connectivityShadow.beforeRegistration = { throw IllegalStateException("Registration refused") }
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(listOf(nativeInterface("native0", listOf(ip("10.0.0.8")))))
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        try {
            monitor.start()
            val fallback = awaitSelectedAddresses(monitor) { it.singleOrNull()?.interfaceType == InterfaceType.Vpn }.single()
            assertTrue(connectivityShadow.networkCallbacks.isEmpty())
            connectivityShadow.removeNetwork(network)
            connectivityShadow.beforeRegistration = null
            val registered = awaitSelectedAddresses(monitor) { it.singleOrNull()?.interfaceType == InterfaceType.Other }.single()
            assertEquals(fallback.id, registered.id)
            assertEquals(1, connectivityShadow.networkCallbacks.size)
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-09
    @Test
    @Config(sdk = [26, 37], shadows = [ObservationTetheringShadow::class])
    fun registeredModernObserverUsesCallbackAddressesWithoutReadingStaleGettersWhenNativeStateIsUnknown() = runBlocking {
        val context: Context = if (Build.VERSION.SDK_INT >= 36) tetheringTestContext() else RuntimeEnvironment.getApplication()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val network = ShadowNetwork.newInstance(45)
        addGetterNetwork(connectivityShadow, network, "getter0", ip("10.0.0.8"))
        val getterReads = AtomicInteger()
        connectivityShadow.afterLinkPropertiesRead = { _, _ -> getterReads.incrementAndGet() }
        val native = NativeInterfaceFixture(enumeration = { throw java.net.SocketException("Unknown native state") })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        try {
            monitor.start()
            assertTrue(awaitSelectedAddresses(monitor) { true }.isEmpty())
            assertEquals(0, getterReads.get())
            val callback = connectivityShadow.networkCallbacks.single()
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            callback.onLinkPropertiesChanged(network, linkProperties("callback0", ip("10.0.0.9")))
            val selected = awaitSelectedAddresses(monitor) { it.any { address -> address.interfaceName == "callback0" } }
            assertEquals(1, selected.size)
            assertEquals(ip("10.0.0.9"), selected.single().ip)
            assertEquals(InterfaceType.Wifi, selected.single().interfaceType)
            assertEquals(0, getterReads.get())
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-09
    @Test
    @Config(sdk = [24, 25])
    fun pre26GetterFieldsYieldToCallbacksAndRacingReadCannotAddStaleAddresses() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val network = ShadowNetwork.newInstance(50)
        addGetterNetwork(connectivityShadow, network, "getter0", ip("10.0.0.8"))
        val native = NativeInterfaceFixture(enumeration = { throw java.net.SocketException("Unknown native state") })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        try {
            monitor.start()
            awaitSelectedAddresses(monitor) { it.singleOrNull()?.interfaceName == "getter0" }
            val callback = connectivityShadow.networkCallbacks.single()
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            awaitSelectedAddresses(monitor) { it.singleOrNull()?.interfaceType == InterfaceType.Vpn }
            callback.onLinkPropertiesChanged(network, linkProperties("callback0", ip("10.0.0.9")))
            awaitSelectedAddresses(monitor) { it.any { address -> address.interfaceName == "callback0" && address.interfaceType == InterfaceType.Vpn } }
            connectivityShadow.setLinkProperties(network, linkProperties("stale-new0", ip("10.0.0.10")))
            val staleReadCompleted = CountDownLatch(1)
            connectivityShadow.afterLinkPropertiesRead = { captured, _ ->
                if (captured == network) {
                    connectivityShadow.afterLinkPropertiesRead = null
                    callback.onLost(network)
                    connectivityShadow.removeNetwork(network)
                    native.enumeration = {
                        partialNativeEnumeration("witness0", "10.0.0.11")
                    }
                    staleReadCompleted.countDown()
                }
            }
            callback.onAvailable(ShadowNetwork.newInstance(51))
            assertTrue(staleReadCompleted.await(5, TimeUnit.SECONDS))
            val settled = awaitSelectedAddresses(monitor) { it.any { address -> address.interfaceName == "witness0" } }
            assertFalse(settled.any { it.interfaceName == "stale-new0" })
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-09
    @Test
    @Config(sdk = [24, 25])
    fun incompleteGetterRefreshKeepsEarlierMetadataWhileMergingSuccessfulNetworkUpdates() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val failingNetwork = ShadowNetwork.newInstance(60)
        val updatedNetwork = ShadowNetwork.newInstance(61)
        addGetterNetwork(connectivityShadow, failingNetwork, "opaque0", ip("10.0.0.1"))
        connectivityShadow.setNetworkCapabilities(failingNetwork, capabilities(NetworkCapabilities.TRANSPORT_VPN).also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        })
        addGetterNetwork(connectivityShadow, updatedNetwork, "other0", ip("10.0.0.2"))
        val native = NativeInterfaceFixture(enumeration = { throw java.net.SocketException("Unknown native state") })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        try {
            monitor.start()
            awaitSelectedAddresses(monitor) { it.size == 2 }
            connectivityShadow.afterLinkPropertiesRead = { network, _ ->
                if (network == failingNetwork) throw IllegalStateException("One getter failed")
            }
            connectivityShadow.setLinkProperties(updatedNetwork, linkProperties("refreshed0", ip("10.0.0.3")))
            native.enumeration = {
                partialNativeEnumeration("opaque0", "10.0.0.4")
            }
            connectivityShadow.networkCallbacks.single().onAvailable(ShadowNetwork.newInstance(62))
            val merged = awaitSelectedAddresses(monitor) { selection ->
                selection.any { it.ip == ip("10.0.0.4") } && selection.any { it.ip == ip("10.0.0.3") }
            }
            assertEquals(InterfaceType.Vpn, merged.single { it.ip == ip("10.0.0.4") }.interfaceType)
            assertEquals("refreshed0", merged.single { it.ip == ip("10.0.0.3") }.interfaceName)
            assertEquals(InterfaceType.Wifi, merged.single { it.ip == ip("10.0.0.3") }.interfaceType)
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-12
    @Test
    @Config(sdk = [36], shadows = [ObservationTetheringShadow::class])
    fun cleanupWaitsForFirstRegisteredObserverThenAttemptsNextAndAggregatesFailures() = runBlocking {
        val context = tetheringTestContext()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val tether = Shadow.extract<ObservationTetheringShadow>(context.getSystemService(TetheringManager::class.java))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val primary = IllegalStateException("Network cleanup failed")
        val secondary = IllegalArgumentException("Tether cleanup failed")
        val monitor = AndroidNetworkAddressMonitor(context, NativeInterfaceFixture())
        try {
            monitor.start()
            awaitSelectedAddresses(monitor) { true }
            connectivityShadow.beforeUnregistration = {
                order += "network"
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw primary
            }
            tether.beforeUnregistration = {
                check(release.count == 0L) { "Second observer entered before first cleanup was released" }
                order += "tethering"
                secondEntered.countDown()
                throw secondary
            }
            val close = async(Dispatchers.Default) { runCatching { monitor.close() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(1L, secondEntered.count)
            assertFalse(close.isCompleted)
            order += "release"
            release.countDown()
            val failure = withTimeout(5.seconds) { close.await() }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it === primary })
            assertEquals(listOf("network", "release", "tethering"), order)
            assertEquals(listOf(secondary), primary.suppressed.toList())
            assertEquals(NetworkAddressMonitor.State.Failed, monitor.state.value)
            val repeated = runCatching { monitor.close() }.exceptionOrNull()
            assertTrue(generateSequence(repeated) { it.cause }.any { it === primary })
        } finally {
            release.countDown()
            withContext(NonCancellable) { withTimeout(5.seconds) { runCatching { monitor.close() } } }
        }
    }

    // Cases: MJPEG-NET-03, MJPEG-NET-09, MJPEG-NET-11
    @Test
    @Config(sdk = [36, 37], shadows = [ObservationTetheringShadow::class])
    fun tetheringRegistrationRetriesAndWifiAndDefensiveUnknownTypeEventsRespectSpecificNetworks() = runBlocking {
        val context = tetheringTestContext()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val tether = Shadow.extract<ObservationTetheringShadow>(context.getSystemService(TetheringManager::class.java))
        tether.beforeRegistration = { throw IllegalStateException("Tether registration refused") }
        val names = listOf("tether0", "artificial-unknown0", "shared0")
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(names.mapIndexed { index, name -> nativeInterface(name, listOf(ip("10.0.0.${index + 1}"))) })
        })
        val monitor = AndroidNetworkAddressMonitor(context, native)
        try {
            monitor.start()
            val initial = awaitSelectedAddresses(monitor) { it.size == 3 }
            assertNull(tether.registeredCallback)
            val network = ShadowNetwork.newInstance(70)
            val callback = connectivityShadow.networkCallbacks.single()
            tether.beforeRegistration = null
            callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
            callback.onLinkPropertiesChanged(network, linkProperties("shared0"))
            awaitSelectedAddresses(monitor) { it.single { address -> address.interfaceName == "shared0" }.interfaceType == InterfaceType.Vpn }
            val tetherRegistered = CountDownLatch(1)
            tether.afterRegistration = { tetherRegistered.countDown() }
            if (tether.registeredCallback == null) {
                callback.onAvailable(ShadowNetwork.newInstance(71))
                assertTrue(tetherRegistered.await(5, TimeUnit.SECONDS))
            }
            val tetherCallback = checkNotNull(tether.registeredCallback)
            val executor = checkNotNull(tether.registeredExecutor)
            val interfaces = linkedSetOf(
                TetheringInterface(TetheringManager.TETHERING_WIFI, "tether0"),
                TetheringInterface(Int.MAX_VALUE, "artificial-unknown0"),
                TetheringInterface(TetheringManager.TETHERING_WIFI, "shared0"),
            )
            executor.execute { tetherCallback.onTetheredInterfacesChanged(interfaces) }
            interfaces.clear()
            val expected = mapOf("tether0" to InterfaceType.Wifi, "artificial-unknown0" to InterfaceType.Other, "shared0" to InterfaceType.Vpn)
            val typed = awaitSelectedAddresses(monitor) { it.associate { address -> address.interfaceName to address.interfaceType } == expected }
            assertEquals(initial.associate { it.interfaceName to it.id }, typed.associate { it.interfaceName to it.id })
            monitor.close()
            assertNull(tether.registeredCallback)
            assertTrue(connectivityShadow.networkCallbacks.isEmpty())
            executor.execute { tetherCallback.onTetheredInterfacesChanged(emptySet()) }
            monitor.start()
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
        } finally {
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-09, MJPEG-NET-11
    @Test
    @Config(sdk = [36, 37], shadows = [ObservationTetheringShadow::class])
    fun tetheringRegistrationCompletingAfterCloseIsRetiredBeforeCloseSettles() = runBlocking {
        val context = tetheringTestContext()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val tether = Shadow.extract<ObservationTetheringShadow>(context.getSystemService(TetheringManager::class.java))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retirements = AtomicInteger()
        tether.afterRegistration = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        tether.beforeUnregistration = { retirements.incrementAndGet() }
        val monitor = AndroidNetworkAddressMonitor(context, NativeInterfaceFixture())
        try {
            monitor.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val callback = checkNotNull(tether.registeredCallback)
            val closing = async(start = CoroutineStart.UNDISPATCHED) { monitor.close() }
            assertFalse(closing.isCompleted)
            callback.onTetheredInterfacesChanged(emptySet())
            release.countDown()
            withTimeout(5.seconds) { closing.await() }
            assertEquals(1, retirements.get())
            assertNull(tether.registeredCallback)
            assertTrue(connectivityShadow.networkCallbacks.isEmpty())
            assertEquals(NetworkAddressMonitor.State.Closed, monitor.state.value)
        } finally {
            release.countDown()
            withContext(NonCancellable) { withTimeout(5.seconds) { monitor.close() } }
        }
    }

    // Cases: MJPEG-NET-12
    @Test
    @Config(sdk = [36], shadows = [ObservationTetheringShadow::class])
    fun defensiveDiscoveryFailurePublishesFailedBeforeBlockedCleanupAndRemainsPrimary() = runBlocking {
        val context = tetheringTestContext()
        val connectivityShadow = Shadow.extract<ObservationConnectivityShadow>(context.getSystemService(ConnectivityManager::class.java))
        connectivityShadow.clearAllNetworks()
        val tether = Shadow.extract<ObservationTetheringShadow>(context.getSystemService(TetheringManager::class.java))
        val native = NativeInterfaceFixture()
        val monitor = AndroidNetworkAddressMonitor(context, native)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val primary = DiscoveryFailure()
        val cleanup = IllegalStateException("Network cleanup failed")
        val later = IllegalArgumentException("Tether cleanup failed")
        try {
            monitor.start()
            awaitSelectedAddresses(monitor) { true }
            connectivityShadow.beforeUnregistration = {
                cleanupEntered.countDown()
                check(releaseCleanup.await(5, TimeUnit.SECONDS))
                throw cleanup
            }
            tether.beforeUnregistration = { throw later }
            native.enumeration = { throw primary }
            connectivityShadow.networkCallbacks.single().onAvailable(ShadowNetwork.newInstance(80))
            withTimeout(5.seconds) { monitor.state.first { it == NetworkAddressMonitor.State.Failed } }
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            val closing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { monitor.close() } }
            assertFalse(closing.isCompleted)
            assertEquals(NetworkAddressMonitor.State.Failed, monitor.state.value)
            releaseCleanup.countDown()
            val failure = withTimeout(5.seconds) { closing.await() }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it === primary })
            assertEquals(listOf(cleanup), primary.suppressed.toList())
            assertEquals(listOf(later), cleanup.suppressed.toList())
            assertEquals(NetworkAddressMonitor.State.Failed, monitor.state.value)
        } finally {
            releaseCleanup.countDown()
            withContext(NonCancellable) { withTimeout(5.seconds) { runCatching { monitor.close() } } }
        }
    }

    private class DiscoveryFailure : Error("Supplied fatal discovery failure")

    private fun addGetterNetwork(connectivityShadow: ObservationConnectivityShadow, network: Network, name: String, address: InetAddress) {
        @Suppress("DEPRECATION")
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED)
        connectivityShadow.addNetwork(network, info)
        connectivityShadow.setNetworkCapabilities(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI).also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        })
        connectivityShadow.setLinkProperties(network, linkProperties(name, address))
    }

    private suspend fun awaitSelectedAddresses(monitor: AndroidNetworkAddressMonitor, matches: (List<NetworkAddress>) -> Boolean): List<NetworkAddress> =
        withTimeout(5.seconds) {
            val state = monitor.state.first { it is NetworkAddressMonitor.State.Observed && matches(it.inventory.select(allAddressesFilter)) }
            assertIs<NetworkAddressMonitor.State.Observed>(state).inventory.select(allAddressesFilter)
        }

    private fun linkProperties(name: String, address: InetAddress? = null): LinkProperties = LinkProperties().apply {
        interfaceName = name
        if (address != null) {
            val link = Shadow.newInstanceOf(LinkAddress::class.java)
            Shadow.extract<CallbackLinkAddressShadow>(link).callbackAddress = address
            setLinkAddresses(listOf(link))
        }
    }

    private fun capabilities(vararg transports: Int): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        for (transport in transports) shadowOf(it).addTransportType(transport)
    }

    private fun partialNativeEnumeration(
        interfaceName: String,
        ipAddress: String,
    ): java.util.Enumeration<NativeInterfaceAccess.Handle> =
        object : java.util.Enumeration<NativeInterfaceAccess.Handle> {
            private var delivered = false
            override fun hasMoreElements(): Boolean = if (delivered) throw java.net.SocketException("Partial enumeration") else true
            override fun nextElement(): NativeInterfaceAccess.Handle {
                delivered = true
                return nativeInterface(interfaceName, listOf(ip(ipAddress)))
            }
        }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    private val allAddressesFilter = NetworkAddressFilter(AddressFamily.entries.toSet(), InterfaceType.entries.toSet(), AddressCategory.entries.toSet())
}
