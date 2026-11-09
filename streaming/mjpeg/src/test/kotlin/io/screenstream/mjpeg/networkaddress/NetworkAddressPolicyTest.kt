package io.screenstream.mjpeg.networkaddress

import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class NetworkAddressPolicyTest {
    // Cases: MJPEG-NET-01
    @Test
    fun filterKeepsOwnedCriteriaAfterCallerMutationAndRejectsMutationThroughItsView() {
        val families = mutableSetOf(AddressFamily.Ipv4)
        val interfaceTypes = mutableSetOf(InterfaceType.Wifi)
        val categories = mutableSetOf(AddressCategory.Private)
        val filter = NetworkAddressFilter(families, interfaceTypes, categories)
        families.clear()
        interfaceTypes += InterfaceType.Vpn
        categories.clear()
        assertEquals(setOf(AddressFamily.Ipv4), filter.families)
        assertEquals(setOf(InterfaceType.Wifi), filter.interfaceTypes)
        assertEquals(setOf(AddressCategory.Private), filter.categories)
        assertFailsWith<UnsupportedOperationException> { (filter.families as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (filter.interfaceTypes as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (filter.categories as MutableSet).clear() }
    }

    // Cases: MJPEG-LAN-02
    @Test
    fun localNetworkPermissionUsesInterfaceTypeAndLoopbackExemption() {
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

    // Cases: MJPEG-NET-01, MJPEG-NET-04
    @Test
    fun inventoryOwnsAlternativesAndSelectionFiltersBeforeChoosingOnePerScopedIp() {
        val sharedIp = InetAddress.getByName("192.168.1.2")
        val mobileAlternative = NetworkAddress(7, sharedIp, "a-mobile", InterfaceType.Mobile)
        val wifiAlternative = NetworkAddress(7, sharedIp, "z-wifi", InterfaceType.Wifi)
        val otherWifiAddress = address("10.0.0.9", InterfaceType.Wifi)
        val alternatives = mutableListOf(mobileAlternative, wifiAlternative)
        val inputAlternatives = linkedMapOf(sharedIp.let(::IpAddressKey) to alternatives, otherWifiAddress.ipKey to mutableListOf(otherWifiAddress))
        val inventory = NetworkAddressInventory(inputAlternatives)
        alternatives.clear()
        inputAlternatives.clear()
        val allAddressesFilter = NetworkAddressFilter(AddressFamily.entries.toSet(), InterfaceType.entries.toSet(), AddressCategory.entries.toSet())
        assertEquals(listOf(mobileAlternative, otherWifiAddress), inventory.select(allAddressesFilter))
        val wifiFilter = NetworkAddressFilter(setOf(AddressFamily.Ipv4), setOf(InterfaceType.Wifi), setOf(AddressCategory.Private))
        val selected = inventory.select(wifiFilter)
        assertEquals(listOf(otherWifiAddress, wifiAlternative), selected)
        assertFailsWith<UnsupportedOperationException> { (selected as MutableList).clear() }
        for (emptyGroupFilter in listOf(
            NetworkAddressFilter(emptySet(), allAddressesFilter.interfaceTypes, allAddressesFilter.categories),
            NetworkAddressFilter(allAddressesFilter.families, emptySet(), allAddressesFilter.categories),
            NetworkAddressFilter(allAddressesFilter.families, allAddressesFilter.interfaceTypes, emptySet()),
        )) assertTrue(inventory.select(emptyGroupFilter).isEmpty())
        assertEquals(listOf(otherWifiAddress, wifiAlternative), inventory.select(wifiFilter))
    }

    // Cases: MJPEG-NET-01, MJPEG-NET-02, MJPEG-NET-04, MJPEG-NET-05
    @Test
    fun familyTypeAndCategoryGroupsCombineWithAndWhileScopesStaySeparateAndOrdered() {
        val scopedBytes = InetAddress.getByName("fd00::1").address
        val scope2 = NetworkAddress(2, Inet6Address.getByAddress(null, scopedBytes, 2), "eth0", InterfaceType.Ethernet)
        val scope1 = NetworkAddress(1, Inet6Address.getByAddress(null, scopedBytes, 1), "eth0", InterfaceType.Ethernet)
        val ipv4 = NetworkAddress(3, InetAddress.getByName("192.168.1.2"), "eth0", InterfaceType.Ethernet)
        val mobile = NetworkAddress(4, InetAddress.getByName("8.8.8.8"), "mobile0", InterfaceType.Mobile)
        val inventory = NetworkAddressInventory(listOf(mobile, scope2, scope1, ipv4).associate { it.ipKey to listOf(it) })
        val allAddressesFilter = NetworkAddressFilter(AddressFamily.entries.toSet(), InterfaceType.entries.toSet(), AddressCategory.entries.toSet())
        assertEquals(listOf(ipv4, scope1, scope2, mobile), inventory.select(allAddressesFilter))
        assertEquals(
            listOf(scope1, scope2), inventory.select(
                NetworkAddressFilter(
                    setOf(AddressFamily.Ipv6), setOf(InterfaceType.Ethernet), setOf(AddressCategory.Private),
                )
            )
        )
        assertTrue(inventory.select(NetworkAddressFilter(setOf(AddressFamily.Ipv4), setOf(InterfaceType.Mobile), setOf(AddressCategory.Private))).isEmpty())
        assertEquals(
            listOf(mobile), inventory.select(
                NetworkAddressFilter(
                    setOf(AddressFamily.Ipv4), setOf(InterfaceType.Mobile, InterfaceType.Vpn), setOf(AddressCategory.Private, AddressCategory.NonPrivate),
                )
            )
        )
        assertNotEquals(scope1.ipKey, scope2.ipKey)
        assertEquals(scope1.ipKey, NetworkAddress(99, scope1.ip, "changed0", InterfaceType.Vpn).ipKey)
    }

    private fun address(ip: String, type: InterfaceType): NetworkAddress =
        NetworkAddress(1, InetAddress.getByName(ip), "fixture", type)
}
