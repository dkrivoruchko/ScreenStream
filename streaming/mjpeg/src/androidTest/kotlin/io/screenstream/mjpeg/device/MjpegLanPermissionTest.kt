package io.screenstream.mjpeg.device

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.screenstream.core.permission.RuntimePermission
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.device.MjpegDeviceTestSupport.MultipartViewer
import io.screenstream.mjpeg.http.HttpDelivery
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
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** Denied/granted UI and external reachability; no independent kernel-blocking or live-capture claim. */
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
        val deviceAddress = checkNotNull(address.hostAddress)
        val addressFilter = NetworkAddressFilter(
            families = setOf(AddressFamily.Ipv4),
            interfaceTypes = setOf(InterfaceType.Wifi, InterfaceType.Ethernet, InterfaceType.Other),
            categories = setOf(AddressCategory.Private, AddressCategory.Loopback),
        )

        MjpegLanPeerProtocol(support.instrumentation, context, arguments, deviceAddress, interfaceName).use { peer ->
            support.openHost(addressFilter).use { fixture ->
                val host = fixture.host
                val logo = context.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
                val nativeEndpointUrl = "http://$deviceAddress:${host.port}/"
                val permissionRequestText = context.getString(R.string.mjpeg_local_network_permission_request)
                val retryDescription = context.getString(R.string.mjpeg_server_retry_description, nativeEndpointUrl)
                MultipartViewer(host.port).use { loopback ->
                    assertFalse(RuntimePermission.LocalNetwork.isGranted(context))
                    support.awaitHostCondition(host, "selected native address and permission-gated row") {
                        compose.onAllNodesWithText(nativeEndpointUrl).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() &&
                                compose.onAllNodesWithText(permissionRequestText).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() &&
                                compose.onAllNodesWithContentDescription(retryDescription).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
                    }
                    assertArrayEquals(logo, loopback.nextFrame())
                    val delivery = host.httpDeliveries.single()
                    var originalLoopbackServer: HttpDelivery.ServerInfo? = null
                    var originalLoopbackClient: HttpDelivery.ClientInfo? = null
                    support.awaitHostCondition(host, "original loopback listener and multipart client") {
                        val info = delivery.info.value
                        val server = info.servers.singleOrNull {
                            it.address.ip.isLoopbackAddress && it.port == host.port && it.state == HttpDelivery.ServerState.Listening
                        }
                        val client = info.clients.singleOrNull {
                            it.serverId == server?.id && it.format == HttpDelivery.MediaFormat.Mjpeg &&
                                    it.state == HttpDelivery.ClientInfo.State.Active
                        }
                        if (server != null && client != null) {
                            originalLoopbackServer = server
                            originalLoopbackClient = client
                            true
                        } else false
                    }
                    val loopbackServerId = checkNotNull(originalLoopbackServer).id
                    val client = checkNotNull(originalLoopbackClient)
                    assertArrayEquals(logo, support.requestJpeg(host.port))
                    peer.exchange("denied", host.port, logo)
                    assertArrayEquals("The denied-phase peer probe must not disrupt loopback", logo, support.requestJpeg(host.port))

                    support.instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_LOCAL_NETWORK)
                    support.awaitHostCondition(host, "fresh grant and native listener admission") {
                        RuntimePermission.LocalNetwork.isGranted(context) &&
                                compose.onAllNodesWithText(permissionRequestText).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                                canConnectToDeviceAddress(deviceAddress, host.port)
                    }
                    val grantObservedAt = SystemClock.elapsedRealtime()
                    var postGrantSample: HttpDelivery.Info? = null
                    support.awaitHostCondition(host, "fresh post-grant client sample") {
                        val info = delivery.info.value
                        if (info.sampledAtElapsedRealtimeMillis > grantObservedAt) {
                            postGrantSample = info
                            true
                        } else false
                    }
                    fun findOriginalLoopbackClient(info: HttpDelivery.Info): HttpDelivery.ClientInfo? {
                        if (info.servers.none { it.id == loopbackServerId && it.state == HttpDelivery.ServerState.Listening }) return null
                        return info.clients.singleOrNull {
                            it.id == client.id && it.serverId == loopbackServerId && it.ip == client.ip &&
                                    it.remotePort == client.remotePort && it.format == HttpDelivery.MediaFormat.Mjpeg &&
                                    it.state == HttpDelivery.ClientInfo.State.Active
                        }
                    }

                    val baselineSample = checkNotNull(postGrantSample)
                    val baselineClientBytes = checkNotNull(findOriginalLoopbackClient(baselineSample)) {
                        "Original loopback response did not survive grant"
                    }.completedJpegBytes
                    assertArrayEquals(logo, loopback.nextFrame())
                    // A retired response can finish at most its one acquired payload. More than
                    // one logo after this fresh sample requires further admission on this client.
                    support.awaitHostCondition(host, "fresh same-client progress after grant") {
                        val info = delivery.info.value
                        info.sampledAtElapsedRealtimeMillis > baselineSample.sampledAtElapsedRealtimeMillis &&
                                (findOriginalLoopbackClient(info)?.completedJpegBytes ?: -1) - baselineClientBytes > logo.size
                    }
                    // This same-device connection is readiness only; the peer receipt establishes external traffic.
                    peer.exchange("granted", host.port, logo)
                    assertArrayEquals(logo, support.requestJpeg(host.port))
                    assertArrayEquals(logo, loopback.nextFrame())
                    support.exitAndAssertResourcesReleased(host, loopback)
                    assertTrue(
                        "Native teardown must be checked while LAN permission remains granted",
                        RuntimePermission.LocalNetwork.isGranted(context),
                    )
                    support.awaitHostCondition(host, "native listener closure after Exit") { !canConnectToDeviceAddress(deviceAddress, host.port) }
                    ServerSocket().use { socket ->
                        socket.reuseAddress = true
                        socket.bind(InetSocketAddress(address, host.port))
                        assertEquals(address, socket.inetAddress)
                        assertEquals(host.port, socket.localPort)
                    }
                }
            }
        }
        // The external runner restores the original denied grant after instrumentation exits.
    }

    private fun canConnectToDeviceAddress(address: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), 250) }
        true
    } catch (_: java.io.IOException) {
        false
    }
}
