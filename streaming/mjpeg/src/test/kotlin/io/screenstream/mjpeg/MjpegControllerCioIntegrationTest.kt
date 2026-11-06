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
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.NetworkSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.UiController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
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
            assertArrayEquals(expectedJpeg, jpegResponse.bytes)

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
            assertArrayEquals(logo, placeholder.bytes)

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
