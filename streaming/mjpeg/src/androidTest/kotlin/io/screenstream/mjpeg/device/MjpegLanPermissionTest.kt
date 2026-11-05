package io.screenstream.mjpeg.device

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.screenstream.core.permission.RuntimePermission
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.device.MjpegDeviceTestSupport.MultipartViewer
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/** Module grant gating and an external peer path; no independent kernel-blocking or live-capture claim. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
internal class MjpegLanPermissionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun localNetworkGrantAddsTheExternalEndpointWhileLoopbackSurvives() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires tools/android-tests/lan_peer.py and a real external peer", arguments.containsKey("lanRunId"))
        val support = MjpegDeviceTestSupport(compose)
        val context = support.context
        assertEquals(37, context.applicationInfo.targetSdkVersion)
        assertEquals(
            "Prepare a denied LAN grant before starting instrumentation; revocation may kill the process",
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK),
        )
        val interfaceName = checkNotNull(arguments.getString("lanInterface"))
        val nativeInterface = checkNotNull(NetworkInterface.getByName(interfaceName)) { "Requested native interface is absent" }
        assertTrue("Requested native interface must be up", nativeInterface.isUp)
        assertFalse("Requested interface must not be loopback", nativeInterface.isLoopback)
        val address = Collections.list(nativeInterface.inetAddresses).filterIsInstance<Inet4Address>()
            .single { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        val hostAddress = checkNotNull(address.hostAddress)
        val filter = NetworkAddressFilter(
            families = setOf(AddressFamily.Ipv4),
            interfaceTypes = setOf(InterfaceType.Wifi, InterfaceType.Ethernet, InterfaceType.Other),
            categories = setOf(AddressCategory.Private, AddressCategory.Loopback),
        )

        MjpegLanPeerProtocol(support.instrumentation, context, arguments, hostAddress, interfaceName).use { peer ->
            support.openHost(filter).use { fixture ->
                val host = fixture.host
                val logo = context.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
                val endpoint = "http://$hostAddress:${host.port}/"
                val requestPermission = context.getString(R.string.mjpeg_local_network_permission_request)
                val retry = context.getString(R.string.mjpeg_server_retry_description, endpoint)
                MultipartViewer(host.port).use { loopback ->
                    assertFalse(RuntimePermission.LocalNetwork.isGranted(context))
                    support.await(host, "selected native address and permission-gated row") {
                        compose.onAllNodesWithText(endpoint).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() &&
                                compose.onAllNodesWithText(requestPermission).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() &&
                                compose.onAllNodesWithContentDescription(retry).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
                    }
                    assertArrayEquals(logo, support.requestJpeg(host.port))
                    assertArrayEquals(logo, loopback.nextFrame())
                    peer.exchange("denied", host.port, logo)
                    assertArrayEquals("The denied-phase peer probe must not disrupt loopback", logo, support.requestJpeg(host.port))

                    support.instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_LOCAL_NETWORK)
                    support.await(host, "fresh grant and native listener admission") {
                        RuntimePermission.LocalNetwork.isGranted(context) &&
                                compose.onAllNodesWithText(requestPermission).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                                canConnectNative(hostAddress, host.port)
                    }
                    // This same-device connection is readiness only; the peer receipt establishes external traffic.
                    peer.exchange("granted", host.port, logo)
                    assertArrayEquals(logo, support.requestJpeg(host.port))
                    assertArrayEquals(logo, loopback.nextFrame())
                    support.assertExitAndReleased(host, loopback)
                }
            }
        }
        // The external runner restores the original denied grant after instrumentation exits.
    }

    private fun canConnectNative(address: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), 250) }
        true
    } catch (_: java.io.IOException) {
        false
    }
}
