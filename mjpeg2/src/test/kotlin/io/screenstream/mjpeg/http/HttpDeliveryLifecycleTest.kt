package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.http.HttpDelivery.ServerState
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Inet6Address

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class HttpDeliveryLifecycleTest {
    // Cases: MJPEG-SRV-01, MJPEG-OBS-06
    @Test
    fun emptyPlanActivationOpensOneLifetimeAndTerminalStopCannotReopenIt() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        try {
            assertTrue(delivery.start(httpTestProtection(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1, 2, 3)))
            val token = checkNotNull(delivery.appliedAccessToken)
            assertFalse(delivery.start(AccessSettings(), PostStopImage.None, httpTestPage(), byteArrayOf(4)))
            assertEquals(token, delivery.appliedAccessToken)
            assertTrue(checkNotNull(delivery.webState.value).hasImage)
            assertTrue(delivery.info.value.servers.isEmpty())
            delivery.requestStop()
            delivery.requestStop()
            delivery.finishHttpTest()
            assertFalse(delivery.start(AccessSettings(), PostStopImage.None, httpTestPage(), byteArrayOf(5)))
            assertFalse(delivery.updateConfiguration(AccessSettings(), PostStopImage.None, httpTestPage()))
            assertEquals(null, delivery.webState.value)
            assertTrue(delivery.info.value.clients.isEmpty())

            val fresh = KtorHttpDelivery(httpTestContext())
            try {
                assertTrue(fresh.start(AccessSettings(), PostStopImage.None, httpTestPage(), byteArrayOf(6)))
                assertFalse(checkNotNull(fresh.webState.value).hasImage)
                assertEquals(0L, fresh.info.value.completedJpegs)
                assertEquals(0L, fresh.info.value.completedJpegBytes)
                assertEquals(null, fresh.appliedAccessToken)
            } finally {
                fresh.finishHttpTest()
            }
        } finally {
            delivery.finishHttpTest()
        }
    }

    // Cases: MJPEG-SRV-01, MJPEG-SRV-02
    @Test
    fun preactivationPlansValidateDeliveryPortsAndTerminalStopIgnoresLaterPlans() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        try {
            for (port in listOf(1, 65535)) {
                delivery.configureServers(listOf(httpTestAddress()), port)
                assertEquals(port, delivery.info.value.servers.single().port)
                assertEquals(ServerState.Pending, delivery.info.value.servers.single().state)
            }
            expectPlanFailure<IllegalArgumentException> { delivery.configureServers(listOf(httpTestAddress()), 0) }
            expectPlanFailure<IllegalArgumentException> { delivery.configureServers(listOf(httpTestAddress()), 65536) }
            expectPlanFailure<IllegalArgumentException> {
                delivery.configureServers(listOf(httpTestAddress(1), httpTestAddress(1, "127.0.0.2")), 8080)
            }
            expectPlanFailure<IllegalArgumentException> {
                delivery.configureServers(listOf(httpTestAddress(1), httpTestAddress(2)), 8080)
            }
            delivery.configureServers(listOf(httpTestAddress()), 8080)
            expectPlanFailure<IllegalStateException> { delivery.configureServers(listOf(httpTestAddress(host = "127.0.0.2")), 8080) }
            assertEquals("127.0.0.1", delivery.info.value.servers.single().address.ip.hostAddress)
            delivery.finishHttpTest()
            delivery.configureServers(listOf(httpTestAddress()), -1)
            assertTrue(delivery.info.value.servers.isEmpty())
            assertFalse(delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1)))
        } finally {
            delivery.finishHttpTest()
        }
    }

    // Cases: MJPEG-SRV-03
    @Test
    fun plannedIncarnationsPreserveMetadataAndReplaceRemovedPortOrScopedAddressPlans() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        try {
            delivery.configureServers(listOf(httpTestAddress(1), httpTestAddress(2, "127.0.0.2")), 8080)
            val original = delivery.info.value.servers.associate { it.address.id to it.id }
            delivery.configureServers(listOf(httpTestAddress(2, "127.0.0.2", "renamed"), httpTestAddress(1)), 8080)
            assertEquals(listOf(2L, 1L), delivery.info.value.servers.map { it.address.id })
            assertEquals(original, delivery.info.value.servers.associate { it.address.id to it.id })
            assertEquals("renamed", delivery.info.value.servers.first().address.interfaceName)
            delivery.configureServers(listOf(httpTestAddress(1)), 8080)
            delivery.configureServers(listOf(httpTestAddress(1), httpTestAddress(2, "127.0.0.2")), 8080)
            assertEquals(original.getValue(1L), delivery.info.value.servers.first().id)
            assertNotEquals(original.getValue(2L), delivery.info.value.servers.last().id)
            val beforePort = delivery.info.value.servers.map { it.id }
            delivery.configureServers(listOf(httpTestAddress(1), httpTestAddress(2, "127.0.0.2")), 8081)
            assertTrue(delivery.info.value.servers.none { it.id in beforePort })
            val ipv6 = ByteArray(16).apply { this[0] = 0x20; this[1] = 0x01; this[15] = 1 }
            val scoped = listOf(3, 4).map { scope ->
                NetworkAddress(scope.toLong(), Inet6Address.getByAddress(null, ipv6, scope), "scope$scope", InterfaceType.Other)
            }
            delivery.configureServers(scoped, 8080)
            assertEquals(2, delivery.info.value.servers.size)
            assertTrue(delivery.info.value.servers.all { it.state == ServerState.Pending })
        } finally {
            delivery.finishHttpTest()
        }
    }

    private suspend inline fun <reified T : Throwable> expectPlanFailure(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.simpleName}")
        } catch (cause: Throwable) {
            if (cause !is T) throw cause
        }
    }
}
