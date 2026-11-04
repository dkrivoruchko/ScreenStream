package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.http.web.WebRoutes.WebSocketAdmission
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class HttpDeliveryAccessBoundaryTest {
    // Cases: MJPEG-ACC-04, MJPEG-ACC-05, MJPEG-ACC-10, MJPEG-WEB-09
    @Test(timeout = 30_000)
    fun allListenersSharePeerBudgetAndFullTableDeniesPinAndSocketButKeepsViewingCredentials() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.start(twoListeners = true)
            val first = fixture.listeners.getValue("127.0.0.1")
            val second = fixture.listeners.getValue("127.0.0.2")
            val token = checkNotNull(fixture.delivery.appliedToken).value
            val cookie = checkNotNull(fixture.delivery.exchangeToken(first, token)).value
            repeat(1_024) { peer -> assertEquals(HttpAccess.PinResult.Wrong, fixture.delivery.verifyPin(first, "wrong", "peer$peer")) }
            assertEquals(HttpAccess.PinResult.Blocked(900_000), fixture.delivery.verifyPin(second, "123456", "unknown"))
            val denied = fixture.delivery.admitWebSocket(second, null, "unknown", fixture.job())
            assertEquals(WebSocketAdmission.Denied(900_000), denied)
            repeat(3) { assertEquals(HttpAccess.PinResult.Wrong, fixture.delivery.verifyPin(second, "wrong", "peer1")) }
            assertEquals(HttpAccess.PinResult.Blocked(60_000), fixture.delivery.verifyPin(first, "wrong", "peer1"))
            assertEquals(HttpAccess.PinResult.Blocked(60_000), fixture.delivery.verifyPin(second, "123456", "peer1"))
            assertEquals(WebSocketAdmission.Denied(60_000), fixture.delivery.admitWebSocket(second, null, "unknown", fixture.job()))
            val authorized = fixture.delivery.admitWebSocket(second, cookie, "peer1", fixture.job())
            assertTrue(authorized is WebSocketAdmission.Accepted)
            assertTrue(fixture.delivery.info.value.clients.isEmpty())
            val known = fixture.delivery.verifyPin(second, "123456", "peer0")
            assertTrue(known is HttpAccess.PinResult.Authorized)
            assertTrue(fixture.delivery.verifyPin(first, "123456", "unknown") is HttpAccess.PinResult.Authorized)
            fixture.delivery.updateConfiguration(httpTestProtection("654321"), PostStopImage.Placeholder, httpTestPage())
            assertFalse(fixture.delivery.isWebSocketCurrent(second, (authorized as WebSocketAdmission.Accepted).credentialGeneration))
            assertEquals(HttpAccess.PinResult.Blocked(60_000), fixture.delivery.verifyPin(second, "654321", "peer1"))
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-WEB-07, MJPEG-WEB-09, MJPEG-ACC-04
    @Test(timeout = 30_000)
    fun retiredListenersAndCompletedSocketCallsCannotSpendPinBudgetOrGainAdmission() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.start()
            val old = fixture.listeners.getValue("127.0.0.1")
            fixture.delivery.configureServers(emptyList(), 8080)
            repeat(5) { assertNull(fixture.delivery.verifyPin(old, "wrong", "peer")) }
            assertNull(fixture.delivery.exchangeToken(old, checkNotNull(fixture.delivery.appliedToken).value))
            assertEquals(WebSocketAdmission.Denied(null), fixture.delivery.admitWebSocket(old, null, "peer", fixture.job()))
            fixture.delivery.configureServers(listOf(httpTestAddress(2)), 8080)
            val current = withTimeout(5.seconds) { fixture.created.receive() }
            fixture.delivery.awaitHttpInfo { it.servers.single().state == HttpDelivery.ServerState.Listening }
            repeat(4) { assertEquals(HttpAccess.PinResult.Wrong, fixture.delivery.verifyPin(current, "wrong", "peer")) }
            val cookie = checkNotNull(fixture.delivery.exchangeToken(current, checkNotNull(fixture.delivery.appliedToken).value)).value
            val ended = fixture.job()
            ended.cancelAndJoin()
            assertEquals(WebSocketAdmission.Denied(null), fixture.delivery.admitWebSocket(current, cookie, "peer", ended))
            assertEquals(HttpAccess.PinResult.Blocked(60_000), fixture.delivery.verifyPin(current, "wrong", "peer"))
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        val created = Channel<HttpListener>(Channel.UNLIMITED)
        val listeners = mutableMapOf<String, HttpListener>()
        val delivery = KtorHttpDelivery(httpTestContext()) { parentJob, host, port, _ ->
            ControlledHttpListener(parentJob, host, port).also { created.trySend(it) }
        }
        private val jobs = mutableListOf<Job>()

        suspend fun start(twoListeners: Boolean = false) {
            delivery.start(httpTestProtection(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1))
            val addresses = if (twoListeners) listOf(httpTestAddress(), httpTestAddress(2, "127.0.0.2")) else listOf(httpTestAddress())
            delivery.configureServers(addresses, 8080)
            repeat(addresses.size) {
                val listener = withTimeout(5.seconds) { created.receive() }
                listeners[listener.host] = listener
            }
            delivery.awaitHttpInfo { it.servers.all { server -> server.state == HttpDelivery.ServerState.Listening } }
        }

        fun job(): Job = Job().also(jobs::add)

        suspend fun close() {
            try {
                delivery.finishHttpTest()
            } finally {
                jobs.forEach { it.cancelAndJoin() }
            }
        }
    }
}
