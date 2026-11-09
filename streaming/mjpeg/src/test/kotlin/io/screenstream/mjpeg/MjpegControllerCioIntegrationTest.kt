package io.screenstream.mjpeg

import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.http.KtorHttpDelivery
import io.screenstream.mjpeg.http.awaitHttpInfo
import io.screenstream.mjpeg.http.httpTestAddress
import io.screenstream.mjpeg.http.httpTestContext
import io.screenstream.mjpeg.http.requestHttpJpeg
import io.screenstream.mjpeg.http.stopHttpTestDelivery
import io.screenstream.mjpeg.networkaddress.AddressCategory
import io.screenstream.mjpeg.networkaddress.AddressFamily
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddressFilter
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.NetworkSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.UiController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [InertMjpegProjectionShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
internal class MjpegControllerCioIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // Cases: MJPEG-CTL-02, MJPEG-CTL-03, MJPEG-CAP-08, MJPEG-IMG-01
    @Test
    fun controllerCaptureHandoffPublishesThroughRealCioAndStopKeepsTheListener() = runBlocking {
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        val loopback = InetAddress.getByName("127.0.0.1")
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val data = MjpegSettings.Data(
            network = NetworkSettings(
                filter = NetworkAddressFilter(setOf(AddressFamily.Ipv4), setOf(InterfaceType.Other), setOf(AddressCategory.Loopback)),
                httpPort = port,
            ),
            web = WebPageSettings(title = "Controller CIO assembly"),
        )
        seedSettings(context, Json.encodeToString(data))
        val logo = context.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
        val realDelivery = KtorHttpDelivery(httpTestContext())
        val observedDelivery = RecordingHttpDelivery(realDelivery)
        val engine = ControlledMjpegEngineSession()
        var host: MjpegServiceTestHost? = null
        try {
            val activeHost = MjpegServiceTestHost(MjpegSettings(context), engineFactory = { _, _, _ -> engine }, delivery = observedDelivery)
            host = activeHost
            activeHost.monitor.state.value = observedAddressState(listOf(httpTestAddress()))
            val listeningServer = realDelivery.awaitHttpInfo { it.servers.singleOrNull()?.state == HttpDelivery.ServerState.Listening }.servers.single()
            withTimeout(5.seconds) { activeHost.controller.state.first { it.action == UiController.Action.Start(true) } }

            activeHost.controller.requestStreamStart()
            val attempt = withTimeout(5.seconds) { activeHost.access.consentRequests.first { it.size == 1 }.single() }
            val captureRegistered = withTimeout(5.seconds) { observedDelivery.captureRegistered.await() }
            val waitingStatusPublished = withTimeout(5.seconds) { observedDelivery.waitingStatusPublished.await() }
            assertEquals(attempt, captureRegistered.attempt)
            assertEquals(attempt, waitingStatusPublished.attempt)
            assertSame(captureRegistered.generation, waitingStatusPublished.generation)
            assertFalse(engine.startEntered.isCompleted)

            activeHost.access.consentGrant.complete(object : ScreenCaptureAccess.Grant {})
            withTimeout(5.seconds) { activeHost.managerObservation.status.first { it?.isStreaming == true && it.captureAttempt == attempt } }
            withTimeout(5.seconds) { activeHost.controller.state.first { it.action == UiController.Action.Stop(attempt) } }
            val activeStatusPublished = withTimeout(5.seconds) { observedDelivery.activeStatusPublished.await() }
            assertEquals(attempt, activeStatusPublished.attempt)
            assertSame(captureRegistered.generation, activeStatusPublished.generation)

            val expectedJpeg = byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)
            val borrowedJpeg = expectedJpeg.copyOf()
            engine.deliver(borrowedJpeg)
            borrowedJpeg.fill(0)
            val offered = withTimeout(5.seconds) { observedDelivery.jpegOffered.await() }
            assertSame(captureRegistered.generation, offered.generation)
            assertEquals(6, offered.byteCount)
            val jpegResponse = requestHttpJpeg(port)
            assertEquals(200, jpegResponse.status)
            assertEquals("image/jpeg", jpegResponse.headers["content-type"])
            assertEquals("6", jpegResponse.headers["content-length"])
            assertContentEquals(expectedJpeg, jpegResponse.bytes)

            activeHost.controller.requestStreamStop(attempt, fromNotification = false)
            withTimeout(5.seconds) { activeHost.controller.state.first { it.action == UiController.Action.Start(true) } }
            withTimeout(5.seconds) { realDelivery.webState.first { it?.notice == "stopped" } }
            val stopped = realDelivery.info.value
            assertEquals(listOf(listeningServer), stopped.servers)
            assertNull(stopped.fatalCause)
            assertFalse(activeHost.managerObservation.failed.isCompleted)
            val placeholder = requestHttpJpeg(port)
            assertEquals(200, placeholder.status)
            assertEquals("image/jpeg", placeholder.headers["content-type"])
            assertEquals(logo.size.toString(), placeholder.headers["content-length"])
            assertContentEquals(logo, placeholder.bytes)

            val cleanupSucceeded = activeHost.close()
            host = null
            assertTrue(cleanupSucceeded)
            realDelivery.stopHttpTestDelivery()
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(loopback, port))
                assertEquals(port, socket.localPort)
            }
        } finally {
            engine.releaseAllGates()
            withContext(NonCancellable) {
                try {
                    host?.let { assertTrue(it.close()) }
                } finally {
                    realDelivery.stopHttpTestDelivery()
                }
            }
        }
    }

    // Cases: MJPEG-CFG-04, MJPEG-CTL-05, MJPEG-ACC-03, MJPEG-WEB-04, MJPEG-WEB-08
    @Test
    fun advertisedProtectedUrlKeepsAppliedCredentialsUntilCaptureCleanupThenUsesTheirSuccessor() = runBlocking {
        val context = IsolatedSettingsContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())
        val loopback = InetAddress.getByName("127.0.0.1")
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val data = MjpegSettings.Data(
            network = NetworkSettings(
                filter = NetworkAddressFilter(setOf(AddressFamily.Ipv4), setOf(InterfaceType.Other), setOf(AddressCategory.Loopback)),
                httpPort = port,
            ),
            access = AccessSettings(pinEnabled = true, pinPolicy = AccessSettings.PinPolicy.Permanent, pin = SecretValue("123456")),
            web = WebPageSettings(title = "Protected controller CIO assembly"),
        )
        seedSettings(context, Json.encodeToString(data))
        val logo = context.assets.open("mjpeg/screenstream-logo.jpg").use { it.readBytes() }
        val realDelivery = KtorHttpDelivery(httpTestContext())
        val engine = ControlledMjpegEngineSession(holdStop = true)
        var host: MjpegServiceTestHost? = null
        try {
            val activeHost = MjpegServiceTestHost(MjpegSettings(context), engineFactory = { _, _, _ -> engine }, delivery = realDelivery)
            host = activeHost
            activeHost.monitor.state.value = observedAddressState(listOf(httpTestAddress()))
            val initial = withTimeout(5.seconds) {
                activeHost.controller.state.first { it.action == UiController.Action.Start(true) }
            }
            val initialUrl = URI(assertNotNull(initial.addressServers.single().accessUrl).value)
            assertEquals("http://127.0.0.1:$port/?token=${assertNotNull(realDelivery.appliedToken).value}", initialUrl.toString())
            assertEquals("123456", initial.appliedPin?.value)
            val endpoint = URI("http://127.0.0.1:$port/jpeg")
            assertEquals(403, requestViewer(endpoint, logo.size).status)
            assertEquals(403, requestViewer(endpoint.resolve("/jpeg?token=wrong"), logo.size).status)
            val initialExchange = requestViewer(initialUrl, logo.size)
            assertEquals(303, initialExchange.status)
            assertEquals("/", initialExchange.location)
            val initialCookie = assertNotNull(initialExchange.cookie).substringBefore(';')
            assertViewerJpeg(endpoint, logo, initialCookie)
            assertViewerJpeg(initialUrl.resolve("/jpeg?${initialUrl.rawQuery}"), logo)

            activeHost.access.consentGrant.complete(object : ScreenCaptureAccess.Grant {})
            activeHost.controller.requestStreamStart()
            val streaming = withTimeout(5.seconds) { activeHost.controller.state.first { it.action is UiController.Action.Stop } }
            val attempt = assertIs<UiController.Action.Stop>(streaming.action).attempt
            assertTrue(streaming.hidePinOnCapture)
            activeHost.settings.updateData { copy(access = access.copy(pin = SecretValue("654321"), hidePinOnCaptureStart = false)) }
            val deferred = withTimeout(5.seconds) {
                activeHost.controller.state.first { it.action == streaming.action && !it.hidePinOnCapture }
            }
            assertEquals("654321", activeHost.settings.data.first().access.pin?.value)
            assertEquals("123456", deferred.appliedPin?.value)
            assertEquals(initialUrl.toString(), deferred.addressServers.single().accessUrl?.value)
            assertViewerJpeg(endpoint, logo, initialCookie)

            activeHost.controller.requestStreamStop(attempt, fromNotification = false)
            withTimeout(5.seconds) { engine.stopEntered.await() }
            val stopping = withTimeout(5.seconds) { activeHost.controller.state.first { it.action == UiController.Action.Busy } }
            assertEquals("123456", stopping.appliedPin?.value)
            assertEquals(initialUrl.toString(), stopping.addressServers.single().accessUrl?.value)
            assertViewerJpeg(initialUrl.resolve("/jpeg?${initialUrl.rawQuery}"), logo)
            engine.allowStopToReturn.complete(Unit)
            val successor = withTimeout(5.seconds) {
                activeHost.controller.state.first { it.action == UiController.Action.Start(true) && it.appliedPin?.value == "654321" }
            }
            val successorUrl = URI(assertNotNull(successor.addressServers.single().accessUrl).value)
            assertEquals("http://127.0.0.1:$port/?token=${assertNotNull(realDelivery.appliedToken).value}", successorUrl.toString())
            assertEquals(403, requestViewer(endpoint, logo.size).status)
            assertEquals(403, requestViewer(endpoint.resolve("/jpeg?token=wrong"), logo.size).status)
            assertEquals(403, requestViewer(initialUrl.resolve("/jpeg?${initialUrl.rawQuery}"), logo.size).status)
            assertEquals(403, requestViewer(endpoint, logo.size, initialCookie).status)
            assertNull(requestViewer(initialUrl, logo.size).cookie)
            val successorExchange = requestViewer(successorUrl, logo.size)
            assertEquals(303, successorExchange.status)
            assertEquals("/", successorExchange.location)
            val successorCookie = assertNotNull(successorExchange.cookie).substringBefore(';')
            assertViewerJpeg(endpoint, logo, successorCookie)
            assertViewerJpeg(successorUrl.resolve("/jpeg?${successorUrl.rawQuery}"), logo)
            assertFalse(activeHost.managerObservation.failed.isCompleted)
        } finally {
            engine.releaseAllGates()
            withContext(NonCancellable) {
                try {
                    host?.let { assertTrue(it.close()) }
                } finally {
                    realDelivery.stopHttpTestDelivery()
                }
            }
        }
    }

    private fun assertViewerJpeg(url: URI, expected: ByteArray, cookie: String? = null) {
        val response = requestViewer(url, expected.size, cookie)
        assertEquals(200, response.status)
        assertContentEquals(expected, response.bytes)
    }

    private fun requestViewer(url: URI, maximumBodyBytes: Int, cookie: String? = null): ViewerResponse {
        val connection = url.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Connection", "close")
            cookie?.let { connection.setRequestProperty("Cookie", it) }
            val status = connection.responseCode
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = input?.use { it.readNBytes(maximumBodyBytes + 1) } ?: byteArrayOf()
            check(bytes.size <= maximumBodyBytes)
            return ViewerResponse(status, connection.getHeaderField("Set-Cookie"), connection.getHeaderField("Location"), bytes)
        } finally {
            connection.disconnect()
        }
    }

    private class ViewerResponse(val status: Int, val cookie: String?, val location: String?, val bytes: ByteArray)

    private class RecordingHttpDelivery(private val delegate: HttpDelivery) : HttpDelivery by delegate {
        val captureRegistered = CompletableDeferred<CaptureGenerationReceipt>()
        val waitingStatusPublished = CompletableDeferred<CaptureGenerationReceipt>()
        val activeStatusPublished = CompletableDeferred<CaptureGenerationReceipt>()
        val jpegOffered = CompletableDeferred<JpegOfferReceipt>()

        override fun registerCapture(id: StreamingModule.CaptureAttemptId, initialCaptureGeneration: Any): Boolean {
            val accepted = delegate.registerCapture(id, initialCaptureGeneration)
            if (accepted) captureRegistered.complete(CaptureGenerationReceipt(id, initialCaptureGeneration))
            return accepted
        }

        override fun updateCaptureStatus(id: StreamingModule.CaptureAttemptId, state: MjpegCaptureSession.State.Status, captureGeneration: Any?) {
            delegate.updateCaptureStatus(id, state, captureGeneration)
            when (state) {
                MjpegCaptureSession.State.Status.WaitingForPermission -> waitingStatusPublished.complete(CaptureGenerationReceipt(id, captureGeneration))
                MjpegCaptureSession.State.Status.Active -> activeStatusPublished.complete(CaptureGenerationReceipt(id, captureGeneration))
                else -> Unit
            }
        }

        override fun offerJpeg(expectedCaptureGeneration: Any, byteCount: Int, copyTo: (ByteArray) -> Unit) {
            delegate.offerJpeg(expectedCaptureGeneration, byteCount, copyTo)
            jpegOffered.complete(JpegOfferReceipt(expectedCaptureGeneration, byteCount))
        }
    }

    private data class CaptureGenerationReceipt(val attempt: StreamingModule.CaptureAttemptId, val generation: Any?)

    private data class JpegOfferReceipt(val generation: Any, val byteCount: Int)
}
