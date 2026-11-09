package io.screenstream.mjpeg.networkaddress

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.SocketException
import java.util.Collections
import java.util.Enumeration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class AndroidNetworkAddressSourceTest {
    // Cases: MJPEG-NET-06, MJPEG-NET-07
    @Test
    fun nativeEmptyDownAndOmittedInterfacesOverrideCallbacksButFailedReadsUseFallback() {
        val callbackIp = ip("192.168.43.8")
        val names = listOf("empty0", "down0", "failed0", "omitted0")
        val native = NativeInterfaceFixture(
            enumeration = {
                Collections.enumeration(
                    listOf(
                        nativeInterface("empty0"),
                        nativeInterface("down0", readIsUp = { false }, readAddresses = { error("Down addresses must not be read") }),
                        nativeInterface("failed0", readIsUp = { throw SocketException("Status unavailable") }),
                    )
                )
            },
            lookup = { throw SocketException("Retry unavailable") },
        )
        val snapshot = platformSnapshot(names.associateWith { listOf(callbackIp) })
        val source = AndroidNetworkAddressSource(native)
        val result = source.scan(snapshot)
        assertEquals(snapshot, result.platformSnapshot)
        assertEquals(
            mapOf("empty0" to emptyList(), "down0" to emptyList(), "failed0" to listOf(callbackIp)),
            result.interfaces.associate { it.interfaceName to it.addresses },
        )
        assertEquals(setOf("empty0", "down0"), result.successfulInterfaces)
        assertEquals(setOf("failed0"), result.failedInterfaces)
        assertTrue(result.enumerationComplete)
        assertFalse(result.complete)
        for (name in listOf("empty0", "down0", "omitted0")) assertTrue(result.canConfirmAbsence(name))
        assertFalse(result.canConfirmAbsence("failed0"))
        native.enumeration = { Collections.enumeration(listOf(nativeInterface("failed0"))) }
        val recovered = source.scan(snapshot)
        assertEquals(emptyList(), recovered.interfaces.single().addresses)
        assertTrue(recovered.complete)
        assertTrue(recovered.canConfirmAbsence("failed0"))
        assertEquals(listOf(callbackIp), result.interfaces.single { it.interfaceName == "failed0" }.addresses)
        assertFailsWith<UnsupportedOperationException> { (result.interfaces as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.successfulInterfaces as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.failedInterfaces as MutableSet).clear() }
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-07
    @Test
    fun partialEnumerationKeepsPriorSuccessAndRetriesAllKnownInterfacesByName() {
        val callbackIp = ip("10.0.0.8")
        val firstIp = ip("10.0.0.9")
        val retryIp = ip("10.0.0.10")
        val lookups = mutableListOf<String>()
        val native = NativeInterfaceFixture(
            enumeration = {
                object : Enumeration<NativeInterfaceAccess.Handle> {
                    private var delivered = false
                    override fun hasMoreElements(): Boolean = if (delivered) throw SocketException("Enumeration interrupted") else true
                    override fun nextElement(): NativeInterfaceAccess.Handle {
                        delivered = true
                        return nativeInterface("first0", listOf(firstIp))
                    }
                }
            },
            lookup = { name ->
                lookups += name
                when (name) {
                    "retry0" -> nativeInterface(name, listOf(retryIp))
                    "failed0" -> throw SocketException("Lookup failed")
                    "missing0" -> null
                    else -> error("A completed native read must not be replaced by a retry")
                }
            },
        )
        val names = listOf("first0", "retry0", "failed0", "missing0")
        val result = AndroidNetworkAddressSource(native).scan(platformSnapshot(names.associateWith { listOf(callbackIp) }))
        assertEquals(setOf("retry0", "failed0", "missing0"), lookups.toSet())
        assertEquals(
            mapOf("first0" to listOf(firstIp), "retry0" to listOf(retryIp), "failed0" to listOf(callbackIp), "missing0" to listOf(callbackIp)),
            result.interfaces.associate { it.interfaceName to it.addresses },
        )
        assertEquals(setOf("first0", "retry0"), result.successfulInterfaces)
        assertEquals(setOf("failed0"), result.failedInterfaces)
        assertFalse(result.enumerationComplete)
        assertFalse(result.complete)
        for (name in listOf("first0", "retry0")) assertTrue(result.canConfirmAbsence(name))
        for (name in listOf("failed0", "missing0", "unknown0")) assertFalse(result.canConfirmAbsence(name))
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-07
    @Test
    fun partialAddressReadPublishesOnlyCallbackFallbackUntilNamedRetryCompletes() {
        val partialIp = ip("10.0.0.11")
        val callbackIp = ip("10.0.0.12")
        val native = NativeInterfaceFixture(enumeration = {
            Collections.enumeration(listOf(nativeInterface("partial0", readAddresses = {
                object : Enumeration<InetAddress> {
                    private var delivered = false
                    override fun hasMoreElements(): Boolean = true
                    override fun nextElement(): InetAddress {
                        if (delivered) throw SocketException("Address read interrupted")
                        delivered = true
                        return partialIp
                    }
                }
            })))
        })
        val source = AndroidNetworkAddressSource(native)
        val snapshot = platformSnapshot(mapOf("partial0" to listOf(callbackIp)))
        val uncertain = source.scan(snapshot)
        assertEquals(listOf(callbackIp), uncertain.interfaces.single().addresses)
        assertEquals(setOf("partial0"), uncertain.failedInterfaces)
        assertTrue(uncertain.successfulInterfaces.isEmpty())
        assertFalse(uncertain.canConfirmAbsence("partial0"))
        native.lookup = { name -> nativeInterface(name) }
        val recovered = source.scan(snapshot)
        assertEquals(emptyList(), recovered.interfaces.single().addresses)
        assertEquals(setOf("partial0"), recovered.successfulInterfaces)
        assertTrue(recovered.failedInterfaces.isEmpty())
        assertTrue(recovered.complete)
        assertTrue(recovered.canConfirmAbsence("partial0"))
        assertEquals(listOf(callbackIp), uncertain.interfaces.single().addresses)
    }

    // Cases: MJPEG-NET-06, MJPEG-NET-07
    @Test
    fun nullEnumerationIsCompleteAndNameFailurePermitsKnownInterfaceRetry() {
        val snapshot = platformSnapshot(mapOf("known0" to listOf(ip("10.0.0.8"))))
        val emptyScan = AndroidNetworkAddressSource(NativeInterfaceFixture(enumeration = { null })).scan(snapshot)
        assertTrue(emptyScan.complete)
        assertTrue(emptyScan.interfaces.isEmpty())
        assertTrue(emptyScan.canConfirmAbsence("known0"))
        val unnamedInterface = object : NativeInterfaceAccess.Handle {
            override val name: String get() = throw SocketException("Name unavailable")
            override val isUp: Boolean get() = error("Name failed")
            override val inetAddresses: Enumeration<InetAddress> get() = error("Name failed")
        }
        val recovered = AndroidNetworkAddressSource(
            NativeInterfaceFixture(
                enumeration = { Collections.enumeration(listOf(unnamedInterface)) },
                lookup = { nativeInterface(it) },
            )
        ).scan(snapshot)
        assertFalse(recovered.enumerationComplete)
        assertFalse(recovered.complete)
        assertEquals(setOf("known0"), recovered.successfulInterfaces)
        assertEquals(emptyList(), recovered.interfaces.single().addresses)
        assertTrue(recovered.canConfirmAbsence("known0"))
        assertFalse(recovered.canConfirmAbsence("unknown0"))
    }

    private fun platformSnapshot(addressesByInterface: Map<String, List<InetAddress>>) = AndroidNetworkAddressSource.Snapshot(
        revision = 17,
        callbackInterfaces = addressesByInterface.map { (name, ips) -> AndroidNetworkAddressSource.InterfaceAddresses(name, ips) },
        interfaceTypes = addressesByInterface.keys.associateWith { InterfaceType.Wifi },
        ready = true,
    )

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
