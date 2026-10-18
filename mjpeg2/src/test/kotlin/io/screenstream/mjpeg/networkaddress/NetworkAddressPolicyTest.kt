package io.screenstream.mjpeg.networkaddress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

internal class NetworkAddressPolicyTest {
    // Cases: MJPEG-NET-01
    @Test
    fun filterKeepsOwnedCriteriaAfterCallerMutationAndRejectsMutationThroughItsView() {
        val families = mutableSetOf(AddressFamily.Ipv4)
        val types = mutableSetOf(InterfaceType.Wifi)
        val categories = mutableSetOf(AddressCategory.Private)
        val filter = NetworkAddressFilter(families, types, categories)
        families.clear()
        types += InterfaceType.Vpn
        categories.clear()
        assertEquals(setOf(AddressFamily.Ipv4), filter.families)
        assertEquals(setOf(InterfaceType.Wifi), filter.interfaceTypes)
        assertEquals(setOf(AddressCategory.Private), filter.categories)
        assertThrows(UnsupportedOperationException::class.java) { (filter.families as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (filter.interfaceTypes as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (filter.categories as MutableSet).clear() }
    }

    // Cases: MJPEG-LAN-02
    @Test
    fun localNetworkPermissionDependsOnInterfaceAndLoopbackRatherThanPrivateAddressLabel() {
        for (type in listOf(InterfaceType.Wifi, InterfaceType.Ethernet, InterfaceType.Other)) {
            assertTrue(address("192.168.1.2", type).requiresLocalNetworkPermission)
            assertTrue(address("8.8.8.8", type).requiresLocalNetworkPermission)
            assertFalse(address("127.0.0.1", type).requiresLocalNetworkPermission)
            assertFalse(address("::1", type).requiresLocalNetworkPermission)
        }
        for (type in listOf(InterfaceType.Mobile, InterfaceType.Vpn)) {
            assertFalse(address("192.168.1.2", type).requiresLocalNetworkPermission)
            assertFalse(address("fd00::1", type).requiresLocalNetworkPermission)
        }
    }

    private fun address(ip: String, type: InterfaceType): NetworkAddress =
        NetworkAddress(1, InetAddress.getByName(ip), "fixture", type)
}
