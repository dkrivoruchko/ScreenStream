package io.screenstream.mjpeg.ui

import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.SecretValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegUiControllerTest {
    private val serverId = Uuid.parse("00000000-0000-0000-0000-000000000017")

    // Cases: MJPEG-CFG-04 (ready-link mapping only, with supplied server/token inputs)
    @Test
    fun readyIpv4RowKeepsEndpointIdentityAndEncodesTheSuppliedToken() {
        val address = address("192.168.1.10")
        val row = address.mapAddressServer(
            port = 9000,
            server = server(address),
            allowed = true,
            accessApplied = true,
            appliedToken = SecretValue("a b+c/&?=#%"),
        )

        assertEquals(23L, row.addressId)
        assertEquals(serverId, row.serverId)
        assertEquals("wlan0", row.interfaceName)
        assertEquals(InterfaceType.Wifi, row.interfaceType)
        assertEquals(AddressFamily.Ipv4, row.family)
        assertEquals("192.168.1.10", row.address)
        assertEquals(9000, row.port)
        assertEquals(UiController.ServerStatus.Listening, row.status)
        assertTrue(row.isPrivate)
        assertFalse(row.isLoopback)
        assertFalse(row.isLinkLocal)
        assertEquals("http://192.168.1.10:9000/?token=a%20b%2Bc%2F%26%3F%3D%23%25", row.accessUrl?.value)
    }

    // Cases: MJPEG-CFG-04 (tokenless ready link only)
    @Test
    fun readyOpenAccessRowHasAnExactUrlWithoutATokenQuery() {
        val address = address("192.168.1.10")
        val row = address.mapAddressServer(
            port = 9000,
            server = server(address),
            allowed = true,
            accessApplied = true,
            appliedToken = null,
        )

        assertEquals("http://192.168.1.10:9000/", row.accessUrl?.value)
        assertEquals(UiController.ServerStatus.Listening, row.status)
    }

    // Cases: MJPEG-CFG-04 (portable IPv6 links only)
    @Test
    fun ipv6LinksHaveBracketsAndOmitScopeWhileTheRowKeepsItsScopedAddress() {
        val ip = InetAddress.getByName("fd12:3456:789a:1:2:3:4:5")
        for ((input, expectedAddress) in listOf(
            ip to "fd12:3456:789a:1:2:3:4:5",
            Inet6Address.getByAddress(null, ip.address, 7) to "fd12:3456:789a:1:2:3:4:5%7",
        )) {
            val address = address(input)
            val row = address.mapAddressServer(
                port = 9000,
                server = server(address),
                allowed = true,
                accessApplied = true,
                appliedToken = SecretValue("current"),
            )

            assertEquals(expectedAddress, row.address)
            assertEquals(AddressFamily.Ipv6, row.family)
            assertTrue(row.isPrivate)
            assertEquals("http://[fd12:3456:789a:1:2:3:4:5]:9000/?token=current", row.accessUrl?.value)
        }
    }

    // Cases: MJPEG-CFG-04 (readiness gates only)
    @Test
    fun absentPendingFailedAndUnappliedServersDoNotAdvertiseAReadyLink() {
        val address = address("192.168.1.10")
        for ((state, accessApplied, expectedStatus) in listOf(
            Triple(null, true, UiController.ServerStatus.Pending),
            Triple(HttpDelivery.ServerState.Pending, true, UiController.ServerStatus.Pending),
            Triple(
                HttpDelivery.ServerState.Failed(HttpDelivery.ServerFailure.AddressInUse, attempts = 1),
                true,
                UiController.ServerStatus.Failed(UiController.ServerFailureReason.AddressInUse),
            ),
            Triple(
                HttpDelivery.ServerState.Failed(HttpDelivery.ServerFailure.PermissionDenied, attempts = 1),
                true,
                UiController.ServerStatus.Failed(UiController.ServerFailureReason.PermissionDenied),
            ),
            Triple(HttpDelivery.ServerState.Listening, false, UiController.ServerStatus.Listening),
        )) {
            val row = address.mapAddressServer(
                port = 9000,
                server = state?.let { server(address, it) },
                allowed = true,
                accessApplied = accessApplied,
                appliedToken = SecretValue("current"),
            )

            assertNull("state=$state accessApplied=$accessApplied", row.accessUrl)
            assertEquals(expectedStatus, row.status)
            assertEquals(if (state == null) null else serverId, row.serverId)
        }
    }

    // Cases: MJPEG-LAN-02, MJPEG-CFG-04 (supplied permission result only)
    @Test
    fun deniedRowOverridesAListeningSnapshotAndRemovesServerIdentityAndReadyLink() {
        val address = address("192.168.1.10")
        val row = address.mapAddressServer(
            port = 9000,
            server = server(address),
            allowed = false,
            accessApplied = true,
            appliedToken = SecretValue("current"),
        )

        assertEquals(UiController.ServerStatus.Failed(UiController.ServerFailureReason.PermissionDenied), row.status)
        assertNull(row.serverId)
        assertNull(row.accessUrl)
        assertEquals(23L, row.addressId)
        assertEquals("192.168.1.10", row.address)
        assertEquals(9000, row.port)
    }

    // Cases: MJPEG-CFG-04 (defensive nonportable endpoint inputs only)
    @Test
    fun nonportableAddressesAndInvalidPortsNeverAdvertiseACredential() {
        val linkLocal = InetAddress.getByName("fe80::1")
        val addresses = listOf(
            InetAddress.getByName("0.0.0.0"),
            InetAddress.getByName("::"),
            InetAddress.getByName("239.1.2.3"),
            InetAddress.getByName("ff02::1"),
            Inet6Address.getByAddress(null, linkLocal.address, 7),
            InetAddress.getByName("fec0::1"),
        )
        val endpoints = addresses.map { address(it) to 9000 } + listOf(
            address("192.168.1.10") to 0,
            address("192.168.1.10") to 65536,
        )
        for ((address, port) in endpoints) {
            val row = address.mapAddressServer(
                port = port,
                server = server(address, port = port),
                allowed = true,
                accessApplied = true,
                appliedToken = SecretValue("current"),
            )

            assertNull("address=${address.ip.hostAddress} port=$port", row.accessUrl)
            assertEquals(UiController.ServerStatus.Listening, row.status)
            assertEquals(serverId, row.serverId)
        }
    }

    private fun address(ip: String): NetworkAddress = address(InetAddress.getByName(ip))

    private fun address(ip: InetAddress): NetworkAddress = NetworkAddress(
        id = 23,
        ip = ip,
        interfaceName = "wlan0",
        interfaceType = InterfaceType.Wifi,
    )

    private fun server(
        address: NetworkAddress,
        state: HttpDelivery.ServerState = HttpDelivery.ServerState.Listening,
        port: Int = 9000,
    ): HttpDelivery.ServerInfo = HttpDelivery.ServerInfo(serverId, address, port, state)
}
