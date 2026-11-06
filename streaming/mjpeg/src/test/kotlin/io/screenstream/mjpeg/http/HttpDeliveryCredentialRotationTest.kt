package io.screenstream.mjpeg.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.call
import io.ktor.server.engine.connector
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.asByteWriteChannel
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.http.web.WebRoutes
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class HttpDeliveryCredentialRotationTest {
    // Cases: MJPEG-ACC-09, MJPEG-OBS-03, MJPEG-OBS-06, MJPEG-IMG-15, MJPEG-WEB-11
    @Test(timeout = 45_000)
    fun protectedAndOpenRotationsCutOffOldMediaWritersAndWebSocketAdmission() {
        for (openAfterRotation in listOf(false, true)) testApplication {
            engine {
                connector {
                    host = "127.0.0.1"
                    port = 8080
                }
            }
            val physical = CompletableDeferred<HttpListener>()
            val delivery = KtorHttpDelivery(httpTestContext()) { parentJob, host, port, _ ->
                ControlledHttpListener(parentJob, host, port).also { physical.complete(it) }
            }
            val acquiredDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val waitingDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val releaseAcquiredPayload = CountDownLatch(1)
            val initialHandoffEntered = CompletableDeferred<Unit>()
            val releaseInitialHandoff = CompletableDeferred<Unit>()
            val initialWriteFailure = CompletableDeferred<CancellationException>()
            val acquiredOutput = RecordingMediaOutput(releaseAcquiredPayload)
            val waitingOutput = RecordingMediaOutput()
            val initialOutput = RecordingMediaOutput()
            val freshOutput = RecordingMediaOutput()
            val socketJob = Job()
            val outputs = mapOf(
                "acquired" to acquiredOutput,
                "waiting" to waitingOutput,
                "initial" to initialOutput,
                "fresh" to freshOutput,
            )
            val completed = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            outputs.keys.forEach { completed[it] = CompletableDeferred() }
            completed["socket"] = CompletableDeferred()
            val image = ByteArray(16_384) { (it % 251).toByte() }
            var initialContentLength: Long? = null
            try {
                delivery.start(httpTestProtectedAccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), byteArrayOf(1))
                delivery.configureServers(listOf(httpTestAddress()), 8080)
                val listener = withTimeout(5.seconds) { physical.await() }
                delivery.awaitHttpInfo { it.servers.single().state == HttpDelivery.ServerState.Listening }
                val attempt = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
                val generation = Any()
                delivery.registerCapture(attempt, generation)
                delivery.updateCaptureStatus(attempt, Status.Active, generation)
                delivery.offerJpeg(generation, image.size) { image.copyInto(it) }
                val token = checkNotNull(delivery.appliedToken).value
                val cookie = checkNotNull(delivery.exchangeToken(listener, token)).value
                application {
                    monitor.subscribe(RoutingRoot.RoutingCallStarted) { routingCall: io.ktor.server.routing.RoutingCall ->
                        val key = routingCall.request.queryParameters["case"]
                        if (key != null) checkNotNull(routingCall.coroutineContext[Job]).invokeOnCompletion {
                            completed.getValue(key).complete(Unit)
                        }
                    }
                    sendPipeline.intercept(ApplicationSendPipeline.After) {
                        val content = subject
                        val key = call.request.queryParameters["case"]
                        val output = key?.let(outputs::get)
                        if (content is OutgoingContent.WriteChannelContent && output != null) {
                            if (key == "initial") {
                                initialContentLength = content.contentLength
                                initialHandoffEntered.complete(Unit)
                                releaseInitialHandoff.await()
                            }
                            val dispatcher = if (key == "waiting") waitingDispatcher else acquiredDispatcher
                            try {
                                withContext(dispatcher) { content.writeTo(output.channel) }
                            } catch (cause: CancellationException) {
                                if (key != "initial") throw cause
                                initialWriteFailure.complete(cause)
                            }
                            proceedWith(object : OutgoingContent.NoContent() {
                                override val status = HttpStatusCode.NoContent
                            })
                        }
                    }
                    WebRoutes(httpTestContext(), delivery).installRoutes(this, listener)
                }
                socketJob.invokeOnCompletion { completed.getValue("socket").complete(Unit) }
                val socketAdmission = delivery.admitWebSocket(listener, cookie, "127.0.0.1", socketJob)
                assertTrue(socketAdmission is WebRoutes.WebSocketAdmission.Accepted)
                val admittedGeneration = (socketAdmission as WebRoutes.WebSocketAdmission.Accepted).credentialGeneration
                assertTrue(delivery.isWebSocketCurrent(listener, admittedGeneration))
                coroutineScope {
                    val acquiredCall = async(Dispatchers.IO) {
                        client.get("http://127.0.0.1:8080/mjpeg?case=acquired&token=$token") { header(HttpHeaders.Host, "127.0.0.1:8080") }
                    }
                    withTimeout(5.seconds) { acquiredOutput.held.await() }
                    val initialCall = async(Dispatchers.IO) {
                        client.get("http://127.0.0.1:8080/jpeg?case=initial&token=$token") { header(HttpHeaders.Host, "127.0.0.1:8080") }
                    }
                    withTimeout(5.seconds) { initialHandoffEntered.await() }
                    val waitingCall = async(Dispatchers.IO) {
                        client.get("http://127.0.0.1:8080/mjpeg?case=waiting&token=$token") { header(HttpHeaders.Host, "127.0.0.1:8080") }
                    }
                    withTimeout(5.seconds) { waitingOutput.firstFlushed.await() }
                    withContext(waitingDispatcher) { assertFalse(waitingCall.isCompleted) }
                    assertEquals(image.size.toLong(), initialContentLength)
                    assertEquals(128, acquiredOutput.bytes().size)
                    val settings = if (openAfterRotation) AccessSettings() else httpTestProtectedAccessSettings("654321")
                    delivery.updateConfiguration(settings, PostStopImage.Placeholder, httpTestPageSettings("Successor state"))
                    assertTrue(delivery.info.value.clients.isEmpty())
                    withTimeout(5.seconds) { completed.getValue("socket").await() }
                    assertTrue(socketJob.isCancelled)
                    assertFalse(delivery.isWebSocketCurrent(listener, admittedGeneration))
                    assertFalse(acquiredCall.isCompleted)
                    releaseInitialHandoff.complete(Unit)
                    releaseAcquiredPayload.countDown()
                    withTimeout(5.seconds) {
                        acquiredCall.await()
                        initialCall.await()
                        waitingCall.await()
                        for (key in listOf("acquired", "initial", "waiting")) completed.getValue(key).await()
                    }
                    assertArrayEquals(byteArrayOf(), initialOutput.bytes())
                    assertEquals("JPEG response revoked", withTimeout(5.seconds) { initialWriteFailure.await() }.message)
                    assertArrayEquals(multipartBytes(image), acquiredOutput.bytes())
                    assertArrayEquals(multipartBytes(image), waitingOutput.bytes())
                    val settled = delivery.awaitHttpInfo { it.completedJpegs == 2L }
                    assertEquals(image.size * 2L, settled.completedJpegBytes)
                    assertTrue(settled.clients.isEmpty())
                    val query = if (openAfterRotation) "" else "&token=${checkNotNull(delivery.appliedToken).value}"
                    client.get("http://127.0.0.1:8080/jpeg?case=fresh$query") { header(HttpHeaders.Host, "127.0.0.1:8080") }
                    withTimeout(5.seconds) { completed.getValue("fresh").await() }
                    assertArrayEquals(image, freshOutput.bytes())
                    delivery.stopHttpTestDelivery()
                    withTimeout(5.seconds) {
                        for (key in listOf("acquired", "initial", "waiting", "fresh")) completed.getValue(key).await()
                    }
                    assertEquals(3L, delivery.info.value.completedJpegs)
                    assertEquals(image.size * 3L, delivery.info.value.completedJpegBytes)
                }
            } finally {
                releaseInitialHandoff.complete(Unit)
                releaseAcquiredPayload.countDown()
                withContext(NonCancellable) {
                    socketJob.cancel()
                    delivery.stopHttpTestDelivery()
                    acquiredDispatcher.close()
                    waitingDispatcher.close()
                }
            }
        }
    }

    private class RecordingMediaOutput(private val releasePayload: CountDownLatch? = null) {
        val held = CompletableDeferred<Unit>()
        val firstFlushed = CompletableDeferred<Unit>()
        private val recorded = ByteArrayOutputStream()
        private var heldOnce = false
        private val sink = object : RawSink {
            override fun write(source: Buffer, byteCount: Long) {
                if (releasePayload != null && !heldOnce) {
                    heldOnce = true
                    val prefix = minOf(128L, byteCount).toInt()
                    recorded.write(source.readByteArray(prefix))
                    held.complete(Unit)
                    check(releasePayload.await(10, TimeUnit.SECONDS))
                    recorded.write(source.readByteArray((byteCount - prefix).toInt()))
                } else recorded.write(source.readByteArray(byteCount.toInt()))
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        private val buffered = sink.asByteWriteChannel()
        val channel = object : ByteWriteChannel by buffered {
            override suspend fun flush() {
                buffered.flush()
                firstFlushed.complete(Unit)
            }
        }

        fun bytes(): ByteArray = recorded.toByteArray()
    }

    private fun multipartBytes(image: ByteArray): ByteArray =
        "--screenstream-jpeg\r\nContent-Type: image/jpeg\r\nContent-Length: ${image.size}\r\n\r\n".toByteArray() +
                image + "\r\n--screenstream-jpeg\r\n".toByteArray()
}
