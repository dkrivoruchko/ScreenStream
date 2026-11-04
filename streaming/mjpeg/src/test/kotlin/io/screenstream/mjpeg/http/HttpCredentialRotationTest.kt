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
internal class HttpCredentialRotationTest {
    // Cases: MJPEG-ACC-09, MJPEG-OBS-03, MJPEG-OBS-06, MJPEG-IMG-15, MJPEG-WEB-11
    @Test(timeout = 45_000)
    fun protectedAndOpenRotationsCutOffOldMediaWritersAndWebSocketAdmission() {
        for (openAfterRotation in listOf(false, true)) testApplication {
            engine { connector { host = "127.0.0.1"; port = 8080 } }
            val physical = CompletableDeferred<HttpListener>()
            val delivery = KtorHttpDelivery(httpTestContext()) { parentJob, host, port, _ ->
                ControlledHttpListener(parentJob, host, port).also { physical.complete(it) }
            }
            val acquiredDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val waitingDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val releaseAcquired = CountDownLatch(1)
            val initialEntered = CompletableDeferred<Unit>()
            val releaseInitial = CompletableDeferred<Unit>()
            val acquired = Output(releaseAcquired)
            val waiting = Output()
            val initial = Output()
            val fresh = Output()
            val socketJob = Job()
            val outputs = mapOf("acquired" to acquired, "waiting" to waiting, "initial" to initial, "fresh" to fresh)
            val completed = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            outputs.keys.forEach { completed[it] = CompletableDeferred() }
            completed["socket"] = CompletableDeferred()
            val image = ByteArray(16_384) { (it % 251).toByte() }
            var initialLength: Long? = null
            try {
                delivery.start(httpTestProtection(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1))
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
                                initialLength = content.contentLength
                                initialEntered.complete(Unit)
                                releaseInitial.await()
                            }
                            val dispatcher = if (key == "waiting") waitingDispatcher else acquiredDispatcher
                            withContext(dispatcher) { content.writeTo(output.channel) }
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
                    withTimeout(5.seconds) { acquired.held.await() }
                    val initialCall = async(Dispatchers.IO) {
                        client.get("http://127.0.0.1:8080/jpeg?case=initial&token=$token") { header(HttpHeaders.Host, "127.0.0.1:8080") }
                    }
                    withTimeout(5.seconds) { initialEntered.await() }
                    val waitingCall = async(Dispatchers.IO) {
                        client.get("http://127.0.0.1:8080/mjpeg?case=waiting&token=$token") { header(HttpHeaders.Host, "127.0.0.1:8080") }
                    }
                    withTimeout(5.seconds) { waiting.firstFlushed.await() }
                    withContext(waitingDispatcher) { assertFalse(waitingCall.isCompleted) }
                    assertEquals(image.size.toLong(), initialLength)
                    assertEquals(128, acquired.bytes().size)
                    val settings = if (openAfterRotation) AccessSettings() else httpTestProtection("654321")
                    delivery.updateConfiguration(settings, PostStopImage.Placeholder, httpTestPage("Successor state"))
                    assertTrue(delivery.info.value.clients.isEmpty())
                    withTimeout(5.seconds) { completed.getValue("socket").await() }
                    assertTrue(socketJob.isCancelled)
                    assertFalse(delivery.isWebSocketCurrent(listener, admittedGeneration))
                    assertFalse(acquiredCall.isCompleted)
                    releaseInitial.complete(Unit)
                    releaseAcquired.countDown()
                    withTimeout(5.seconds) {
                        acquiredCall.await()
                        initialCall.await()
                        waitingCall.await()
                        for (key in listOf("acquired", "initial", "waiting")) completed.getValue(key).await()
                    }
                    assertArrayEquals(byteArrayOf(), initial.bytes())
                    assertArrayEquals(multipart(image), acquired.bytes())
                    assertArrayEquals(multipart(image), waiting.bytes())
                    val settled = delivery.awaitHttpInfo { it.completedJpegs == 2L }
                    assertEquals(image.size * 2L, settled.completedJpegBytes)
                    assertTrue(settled.clients.isEmpty())
                    val query = if (openAfterRotation) "" else "&token=${checkNotNull(delivery.appliedToken).value}"
                    client.get("http://127.0.0.1:8080/jpeg?case=fresh$query") { header(HttpHeaders.Host, "127.0.0.1:8080") }
                    withTimeout(5.seconds) { completed.getValue("fresh").await() }
                    assertArrayEquals(image, fresh.bytes())
                    delivery.finishHttpTest()
                    withTimeout(5.seconds) {
                        for (key in listOf("acquired", "initial", "waiting", "fresh")) completed.getValue(key).await()
                    }
                    assertEquals(3L, delivery.info.value.completedJpegs)
                    assertEquals(image.size * 3L, delivery.info.value.completedJpegBytes)
                }
            } finally {
                releaseInitial.complete(Unit)
                releaseAcquired.countDown()
                withContext(NonCancellable) {
                    socketJob.cancel()
                    delivery.finishHttpTest()
                    acquiredDispatcher.close()
                    waitingDispatcher.close()
                }
            }
        }
    }

    private class Output(private val releasePayload: CountDownLatch? = null) {
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

    private fun multipart(image: ByteArray): ByteArray =
        "--screenstream-jpeg\r\nContent-Type: image/jpeg\r\nContent-Length: ${image.size}\r\n\r\n".toByteArray() +
                image + "\r\n--screenstream-jpeg\r\n".toByteArray()
}
