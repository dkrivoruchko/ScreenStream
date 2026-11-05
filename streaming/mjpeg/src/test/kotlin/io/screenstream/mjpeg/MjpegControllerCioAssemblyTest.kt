package io.screenstream.mjpeg

import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.http.KtorHttpDelivery
import io.screenstream.mjpeg.http.awaitHttpInfo
import io.screenstream.mjpeg.http.finishHttpTest
import io.screenstream.mjpeg.http.httpTestAddress
import io.screenstream.mjpeg.http.httpTestContext
import io.screenstream.mjpeg.http.requestHttpJpeg
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
internal class MjpegControllerCioAssemblyTest {
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
        val observedDelivery = ObservedDelivery(realDelivery)
        val engine = ControlledMjpegEngine()
        var host: MjpegServiceHost? = null
        try {
            val activeHost = MjpegServiceHost(MjpegSettings(context), engineFactory = { _, _, _ -> engine }, delivery = observedDelivery)
            host = activeHost
            activeHost.monitor.state.value = observedAddresses(listOf(httpTestAddress()))
            val listening = realDelivery.awaitHttpInfo { it.servers.singleOrNull()?.state == HttpDelivery.ServerState.Listening }.servers.single()
            withTimeout(5.seconds) { activeHost.controller.state.first { it.action == UiController.Action.Start(true) } }

            activeHost.controller.requestStreamStart()
            val attempt = withTimeout(5.seconds) { activeHost.access.requested.first { it.size == 1 }.single() }
            val registration = withTimeout(5.seconds) { observedDelivery.registration.await() }
            val waiting = withTimeout(5.seconds) { observedDelivery.waiting.await() }
            assertEquals(attempt, registration.attempt)
            assertEquals(attempt, waiting.attempt)
            assertSame(registration.generation, waiting.generation)
            assertFalse(engine.startEntered.isCompleted)

            activeHost.access.consent.complete(object : ScreenCaptureAccess.Grant {})
            withTimeout(5.seconds) { activeHost.managerObservation.status.first { it?.isStreaming == true && it.captureAttempt == attempt } }
            withTimeout(5.seconds) { activeHost.controller.state.first { it.action == UiController.Action.Stop(attempt) } }
            val active = withTimeout(5.seconds) { observedDelivery.active.await() }
            assertEquals(attempt, active.attempt)
            assertSame(registration.generation, active.generation)

            val expected = byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)
            val borrowed = expected.copyOf()
            engine.deliver(borrowed)
            borrowed.fill(0)
            val offered = withTimeout(5.seconds) { observedDelivery.offer.await() }
            assertSame(registration.generation, offered.generation)
            assertEquals(6, offered.byteCount)
            val image = requestHttpJpeg(port)
            assertEquals(200, image.status)
            assertEquals("image/jpeg", image.headers["content-type"])
            assertEquals("6", image.headers["content-length"])
            assertArrayEquals(expected, image.bytes)

            activeHost.controller.requestStreamStop(attempt, fromNotification = false)
            withTimeout(5.seconds) { activeHost.controller.state.first { it.action == UiController.Action.Start(true) } }
            withTimeout(5.seconds) { realDelivery.webState.first { it?.notice == "stopped" } }
            val stopped = realDelivery.info.value
            assertEquals(listOf(listening), stopped.servers)
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
            realDelivery.finishHttpTest()
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(loopback, port))
                assertEquals(port, socket.localPort)
            }
        } finally {
            engine.release()
            withContext(NonCancellable) {
                try {
                    host?.let { assertTrue(it.close()) }
                } finally {
                    realDelivery.finishHttpTest()
                }
            }
        }
    }

    private class ObservedDelivery(private val delegate: HttpDelivery) : HttpDelivery by delegate {
        val registration = CompletableDeferred<CaptureHandoff>()
        val waiting = CompletableDeferred<CaptureHandoff>()
        val active = CompletableDeferred<CaptureHandoff>()
        val offer = CompletableDeferred<JpegOffer>()

        override fun registerCapture(id: StreamingModule.CaptureAttemptId, initialCaptureGeneration: Any): Boolean {
            val accepted = delegate.registerCapture(id, initialCaptureGeneration)
            if (accepted) registration.complete(CaptureHandoff(id, initialCaptureGeneration))
            return accepted
        }

        override fun updateCaptureStatus(id: StreamingModule.CaptureAttemptId, state: MjpegCaptureSession.State.Status, captureGeneration: Any?) {
            delegate.updateCaptureStatus(id, state, captureGeneration)
            when (state) {
                MjpegCaptureSession.State.Status.WaitingForPermission -> waiting.complete(CaptureHandoff(id, captureGeneration))
                MjpegCaptureSession.State.Status.Active -> active.complete(CaptureHandoff(id, captureGeneration))
                else -> Unit
            }
        }

        override fun offerJpeg(expectedCaptureGeneration: Any, byteCount: Int, copyTo: (ByteArray) -> Unit) {
            delegate.offerJpeg(expectedCaptureGeneration, byteCount, copyTo)
            offer.complete(JpegOffer(expectedCaptureGeneration, byteCount))
        }
    }

    private data class CaptureHandoff(val attempt: StreamingModule.CaptureAttemptId, val generation: Any?)

    private data class JpegOffer(val generation: Any, val byteCount: Int)
}
